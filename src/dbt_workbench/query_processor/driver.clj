(ns dbt-workbench.query-processor.driver
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dbt-workbench.jdbc :as jdbc]
            [dbt-workbench.query-processor.parse :as parse]
            [dbt-workbench.query-processor.store :as store]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.text :as text]
            [dbt-workbench.query-processor.tokens :as tokens])
  (:import (java.sql Connection PreparedStatement)
           (java.util.concurrent Executors TimeUnit)
           (net.sf.jsqlparser.schema Table)
           (net.sf.jsqlparser.expression Alias)))

(defn attach-database! [connection path alias]
  (let [path (storage/canonical-path path)]
    ;; DuckDB shares attachments across connections to the same file.
    (jdbc/execute! connection (str "ATTACH IF NOT EXISTS " (jdbc/quote-literal path) " AS " (jdbc/quote-identifier alias) " (READ_ONLY)"))
    (let [attached (first (filter #(= alias (:name %)) (jdbc/query-maps! connection "PRAGMA database_list")))]
      (when-not (= path (storage/canonical-path (:file attached)))
        (storage/fail! (str "Database alias " alias " refers to another file."))))))
(defn connect-duckdb! [store]
  (let [path (store/resolve-database-path store (:output store))]
    (when-not (.isFile (io/file path)) (storage/fail! "This target has no database. Run Build first."))
    (let [connection (jdbc/connect! (str "jdbc:duckdb:" path) {"duckdb.read_only" "true"})
          attachments (get-in store [:output :attach])]
      (try
        (jdbc/execute! connection (str "SET schema = " (jdbc/quote-literal (or (get-in store [:output :schema]) "main"))))
        (doseq [entry attachments]
          (let [entry (if (string? entry) {:path entry} entry)
                file (io/file (:path entry)) file (if (.isAbsolute file) file (io/file (get-in store [:project :path]) file))
                alias (or (:alias entry) (text/remove-suffix (.getName file) ".duckdb"))]
            (when (.isFile file)
              (attach-database! connection file alias))))
        connection
        (catch Exception e (.close connection) (throw e))))))
(defn attach-baseline! [connection store]
  (when-let [baseline (:baseline store)]
    (let [output (get-in store [:profiles :outputs (keyword (:target baseline))]) path (store/resolve-database-path store output)
          databases (jdbc/query-maps! connection "PRAGMA database_list")]
      (when-not (some #(= "baseline" (:name %)) databases)
        (let [current (store/resolve-database-path store (:output store))]
          (when-not (= path current)
            (attach-database! connection path "baseline")))))))
(defn resolve-native-bindings! [compiled store]
  (let [bindings (atom [])]
    (doseq [^Table table (:tables compiled)]
      (let [name (.getUnquotedName table)
            matches (filter #(= (str/lower-case name) (str/lower-case (:name %))) (:models store))]
        (when (and (= 1 (count matches)) (nil? (.getDatabase table)) (nil? (.getSchemaName table)))
          (let [source (store/resolve-source store {:model (:unique_id (first matches))} false)]
            (swap! bindings conj source)))))
    (assoc compiled :bindings @bindings)))
(defn capture-sqlite-inputs! [duckdb sqlite compiled]
  (let [statement (first (parse/parse-sql (:sql compiled)))
        tables (:tables (parse/collect-native-metadata statement))
        captures (atom {})]
    (doseq [^Table table tables]
      (let [source (.getFullyQualifiedName table)]
        (when-not (@captures source)
          (let [name (str "wb_input_" (count @captures)) result (jdbc/query! duckdb (str "SELECT * FROM " source) [] 50000)]
            (when (:truncated result) (storage/fail! "SQLite input exceeds 50,000 rows. Use DuckDB for this query."))
            (jdbc/execute! sqlite (str "CREATE TABLE " (jdbc/quote-identifier name) " (" (str/join ", " (map (fn [column] (str (jdbc/quote-identifier (:name column)) " " (if (text/numeric-type? (:type column)) "NUMERIC" "TEXT"))) (:columns result))) ")"))
            (with-open [insert (.prepareStatement ^Connection sqlite (str "INSERT INTO " (jdbc/quote-identifier name) " VALUES (" (str/join "," (repeat (count (:columns result)) "?")) ")"))]
              (doseq [row (:rows result)] (jdbc/bind! insert row) (.addBatch insert))
              (.executeBatch insert))
            (swap! captures assoc source {:name name :relation source :rows (:row_count result)})))
        (when-not (.getAlias table) (.setAlias table (Alias. (.getName table))))
        (.setName table (:name (@captures source)))
        (.setSchemaName table nil)
        (.setDatabase table (net.sf.jsqlparser.schema.Database. ""))))
    {:sql (str statement) :captured_relations (vec (vals @captures))}))
(defn run-query! [connection sql parameters max-rows timeout cancel register-statement!]
  (with-open [statement (.prepareStatement ^Connection connection sql)]
    (jdbc/bind! statement parameters)
    (register-statement! statement)
    (let [scheduler (Executors/newSingleThreadScheduledExecutor)
          deadline (+ (System/nanoTime) (long (* timeout 1e9)))
          expired (atom false)]
      (.scheduleAtFixedRate scheduler
                            ^Runnable (fn [] (when (or @cancel (> (System/nanoTime) deadline))
                                               (reset! expired (> (System/nanoTime) deadline))
                                               (try (.cancel ^PreparedStatement statement) (catch Exception _ nil))))
                            25 25 TimeUnit/MILLISECONDS)
      (try
        (when @cancel (storage/fail! "Query cancelled."))
        (with-open [result (.executeQuery statement)]
          (let [rows (jdbc/read-result! result max-rows)]
            (when @expired (storage/fail! "Query exceeded its time limit."))
            (when @cancel (storage/fail! "Query cancelled."))
            rows))
        (finally (.shutdownNow scheduler) (register-statement! nil))))))
(defn execute! [compiled store options cancel register-statement!]
  (let [dialect (or (:dialect options) "duckdb") rows (or (:max_rows options) 10000) timeout (or (:timeout_seconds options) 30)]
    (when-not (and (#{"duckdb" "sqlite"} dialect) (integer? rows) (<= 1 rows 100000) (number? timeout) (< 0 timeout 121)) (storage/fail! "Invalid engine, row limit, or timeout."))
    (with-open [duckdb (connect-duckdb! store)]
      (attach-baseline! duckdb store)
      (let [compiled (resolve-native-bindings! compiled store)
            bound (if (:tree compiled) (tokens/bind-parameters (:sql compiled) (or (:parameters options) {})) (select-keys compiled [:sql :params]))]
        (when (and (= "sqlite" dialect) (:tree compiled)
                   (some #(= "Division" (.getSimpleName (class %))) (parse/ast-nodes (:tree compiled))))
          (storage/fail! "Native division can change semantics in SQLite. Use DuckDB or structured EDN division."))
        (if (= "sqlite" dialect)
          (with-open [sqlite (jdbc/connect! "jdbc:sqlite::memory:")]
            (let [captured (capture-sqlite-inputs! duckdb sqlite (assoc compiled :sql (:sql bound)))
                  _ (jdbc/execute! sqlite "PRAGMA query_only = ON")
                  output (run-query! sqlite (:sql captured) (:params bound) rows timeout cancel register-statement!)]
              (assoc output :sql (:sql captured) :bindings (:bindings compiled) :captured_relations (:captured_relations captured)
                     :warnings ["SQLite runs bounded copies of DuckDB inputs. Numeric and date semantics can differ."])))
          (assoc (run-query! duckdb (:sql bound) (:params bound) rows timeout cancel register-statement!) :sql (:sql bound) :bindings (:bindings compiled) :captured_relations [] :warnings []))))))
