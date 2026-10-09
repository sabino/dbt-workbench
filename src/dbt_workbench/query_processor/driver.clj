(ns dbt-workbench.query-processor.driver
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dbt-workbench.jdbc :as jdbc]
            [dbt-workbench.snowflake :as snowflake]
            [dbt-workbench.snow-cli :as snow-cli]
            [dbt-workbench.query-processor.federation :as federation]
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
  (let [tree (or (:tree compiled)
                 (when (and (or (seq (:routes store)) (= "snowflake" (get-in store [:output :type])))
                            (empty? (:bindings compiled)))
                   (first (parse/parse-sql (:sql compiled)))))
        tables (if tree (:tables (parse/collect-native-metadata tree)) (:tables compiled))
        bindings (atom (vec (:bindings compiled)))
        routed? (atom false)]
    (doseq [^Table table tables]
      (let [name (.getUnquotedName table)
            matches (filter #(= (str/lower-case name) (str/lower-case (:name %))) (:models store))]
        (when (and (= 1 (count matches)) (str/blank? (str (.getDatabase table))) (str/blank? (.getSchemaName table)))
          (let [source (store/resolve-source store {:model (:unique_id (first matches))} false)]
            (when (or (:routed source) (= "snowflake" (:connection_type source)))
              (when-not (.getAlias table) (.setAlias table (Alias. (.getName table))))
              (.setName table (jdbc/quote-identifier (:relation_name source)))
              (.setSchemaName table (jdbc/quote-identifier (:schema source)))
              (when (:database source)
                (.setDatabase table (net.sf.jsqlparser.schema.Database. (jdbc/quote-identifier (:database source)))))
              (reset! routed? true))
            (swap! bindings conj source)))))
    (cond-> (assoc compiled :bindings @bindings)
      @routed? (assoc :sql (str tree)))))
(defn attach-routes! [connection compiled _store]
  (doseq [source (distinct (filter :routed (:bindings compiled)))]
    (let [path (:database_path source)]
      (when-not (.isFile (io/file path)) (storage/fail! "A routed DuckDB connection file does not exist."))
      (when (:database source)
        (attach-database! connection path (:database source))))))
(defn read-bindings! [connection compiled]
  (let [database (-> (jdbc/query! connection "SELECT current_database()") :rows first first)]
    (mapv (fn [binding]
            (cond-> (dissoc binding :database_path)
              (and (or (:routed binding) (= "snowflake" (:connection_type binding))) (nil? (:database binding))) (assoc :database database)))
          (:bindings compiled))))
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
(defn run-query! [connection sql parameters max-rows timeout cancel register-statement! & [value-fn]]
  (with-open [statement (.prepareStatement ^Connection connection sql)]
    (jdbc/bind! statement parameters)
    (.setQueryTimeout statement (int (Math/ceil (double timeout))))
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
          (let [rows (jdbc/read-result! result max-rows (or value-fn jdbc/json-value))]
            (when @expired (storage/fail! "Query exceeded its time limit."))
            (when @cancel (storage/fail! "Query cancelled."))
            rows))
        (finally (.shutdownNow scheduler) (register-statement! nil))))))
(defn bind-query [compiled options]
  (if (:tree compiled)
    (tokens/bind-parameters (:sql compiled) (or (:parameters options) {}))
    (select-keys compiled [:sql :params])))
(defn execute-snowflake! [compiled store options rows timeout cancel register-statement!]
  (let [compiled (resolve-native-bindings! compiled store)
        bound (bind-query compiled options)]
    ;; Parse again at the remote boundary so direct driver calls retain the SELECT-only contract.
    (when-not (= 1 (count (parse/parse-sql (:sql bound)))) (storage/fail! "Select one statement per Snowflake query."))
    (when @cancel (storage/fail! "Query cancelled."))
    (with-open [connection (snowflake/connect! (:output store))]
      (try
        (let [bindings (read-bindings! connection compiled)
              output (run-query! connection (:sql bound) (:params bound) rows timeout cancel register-statement!)]
          (assoc output :sql (:sql bound) :bindings bindings :captured_relations []
                 :warnings ["Snowflake queries execute remotely. Access depends on the selected connection's role grants."]))
        (catch Exception _
          (storage/fail! (if @cancel "Query cancelled." "Snowflake SELECT failed. Check the relation, parameters, role grants, and query timeout.")))))))
(defn execute-local! [compiled store options dialect rows timeout cancel register-statement!]
  (with-open [duckdb (connect-duckdb! store)]
    (attach-baseline! duckdb store)
    (let [compiled (resolve-native-bindings! compiled store)
          _ (attach-routes! duckdb compiled store)
          bindings (read-bindings! duckdb compiled)
          bound (bind-query compiled options)]
      (when (and (= "sqlite" dialect) (:tree compiled)
                 (some #(= "Division" (.getSimpleName (class %))) (parse/ast-nodes (:tree compiled))))
        (storage/fail! "Native division can change semantics in SQLite. Use DuckDB or structured EDN division."))
      (if (= "sqlite" dialect)
        (with-open [sqlite (jdbc/connect! "jdbc:sqlite::memory:")]
          (let [captured (capture-sqlite-inputs! duckdb sqlite (assoc compiled :sql (:sql bound)))
                _ (jdbc/execute! sqlite "PRAGMA query_only = ON")
                output (run-query! sqlite (:sql captured) (:params bound) rows timeout cancel register-statement!)]
            (assoc output :sql (:sql captured) :bindings bindings :captured_relations (:captured_relations captured)
                   :warnings ["SQLite runs bounded copies of DuckDB inputs. Numeric and date semantics can differ."])))
        (assoc (run-query! duckdb (:sql bound) (:params bound) rows timeout cancel register-statement!)
               :sql (:sql bound) :bindings bindings :captured_relations [] :warnings [])))))
(defn execute! [compiled store options cancel register-statement!]
  (let [target-type (get-in store [:output :type])
        dialect (or (:dialect options) (if (= "snowflake" target-type) "snowflake" "duckdb"))
        rows (or (:max_rows options) 10000) timeout (or (:timeout_seconds options) 30)]
    (when-not (and (#{"duckdb" "sqlite" "snowflake" "federated"} dialect) (integer? rows) (<= 1 rows 100000) (number? timeout) (< 0 timeout 121))
      (storage/fail! "Invalid engine, row limit, or timeout."))
    (when-not (or (= "federated" dialect) (and (= "snowflake" dialect) (= "snowflake" target-type))
                  (and (#{"duckdb" "sqlite"} dialect) (= "duckdb" target-type)))
      (storage/fail! "Choose a matching Snowflake or DuckDB target, or select Federation for mixed connections."))
    (case dialect
      "federated" (federation/execute! compiled store options cancel register-statement! run-query!)
      "snowflake" (if (= "snow_cli" (get-in store [:output :transport]))
                    (let [compiled (resolve-native-bindings! compiled store) bound (bind-query compiled options)
                          output (snow-cli/execute-select! (:output store) (:sql bound) (:params bound) options cancel)]
                      (assoc output :bindings (mapv #(dissoc % :database_path) (:bindings compiled)) :captured_relations []))
                    (execute-snowflake! compiled store options rows timeout cancel register-statement!))
      (execute-local! compiled store options dialect rows timeout cancel register-statement!))))
