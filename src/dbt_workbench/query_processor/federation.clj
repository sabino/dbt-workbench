(ns dbt-workbench.query-processor.federation
  (:require [clojure.string :as str]
            [dbt-workbench.connections :as connections]
            [dbt-workbench.jdbc :as jdbc]
            [dbt-workbench.query-processor.parse :as parse]
            [dbt-workbench.query-processor.tokens :as tokens]
            [dbt-workbench.storage :as storage])
  (:import (java.sql Connection PreparedStatement)
           (net.sf.jsqlparser.expression Alias)
           (net.sf.jsqlparser.schema Database Table)))

(def capture-row-limit 50000)
(def capture-relation-limit 16)

(defn normalize-identifier [identifier remote?]
  (let [quoted? (str/starts-with? identifier "\"")
        value (tokens/unquote-identifier identifier)]
    (if (and remote? (not quoted?)) (str/upper-case value) value)))

(defn describe-source [^Table table compiled metadata]
  (when (> (count (.getNameParts table)) 3)
    (storage/fail! "Federated tables use connection.schema.table. Use aliases when qualifying columns."))
  (let [relation (.getUnquotedName table)
        matching-binding (first (filter #(or (= (.getFullyQualifiedName table) (:relation %))
                                             (= relation (:relation_name %))) (:bindings compiled)))
        models (filter #(= (str/lower-case relation) (str/lower-case (:name %))) (:models metadata))
        route (when (and (= 1 (count models)) (str/blank? (.getDatabaseName table))
                         (str/blank? (.getSchemaName table)))
                (first (filter #(= (:unique_id (first models)) (:model_id %)) (:routes metadata))))
        connection-id (or (some-> (.getDatabase table) .getDatabaseName not-empty tokens/unquote-identifier)
                          (:target matching-binding) (:target route) (:target metadata))
        project-id (or (:project-id metadata) (get-in metadata [:project :id]))
        output (connections/find-output (:directory metadata) project-id connection-id)
        _ (when (= "snow_cli" (:transport output))
            (storage/fail! "Snow CLI results lack declared column types. Use a Snowflake JDBC connection for federation."))
        remote? (= "snowflake" (:type output))
        schema (or (not-empty (.getSchemaName table)) (:schema matching-binding) (:schema route) (:schema output)
                   (if remote? "PUBLIC" "main"))
        schema (normalize-identifier schema remote?)
        relation (normalize-identifier (or (:relation route) (.getName table)) remote?)]
    (when-not (contains? #{"duckdb" "snowflake"} (:type output))
      (storage/fail! "Federation supports registered DuckDB and Snowflake connections."))
    {:connection_id connection-id :source_type (:type output) :schema schema :relation relation
     :source_sql (str (jdbc/quote-identifier schema) "." (jdbc/quote-identifier relation))
     :key [connection-id schema relation]}))

(defn resolve-column-type [{:keys [type precision scale]}]
  (let [type (str/upper-case (or type ""))
        decimal (re-matches #"(?:DECIMAL|NUMERIC)\((\d+),\s*(\d+)\)" type)
        precision (if decimal (Long/parseLong (second decimal)) precision)
        scale (if decimal (Long/parseLong (nth decimal 2)) scale)]
    (cond
      (or decimal (contains? #{"DECIMAL" "NUMERIC" "NUMBER" "FIXED"} type))
      (if (and (integer? precision) (integer? scale) (<= 1 precision 38) (<= 0 scale precision))
        (str "DECIMAL(" precision "," scale ")")
        (storage/fail! "Federated decimals need precision up to 38 and a known scale. Cast the source column explicitly."))

      (contains? #{"BOOLEAN" "BOOL"} type) "BOOLEAN"
      (contains? #{"TINYINT" "SMALLINT" "INTEGER" "INT" "BIGINT" "HUGEINT" "UTINYINT" "USMALLINT" "UINTEGER" "UBIGINT" "UHUGEINT"} type) type
      (contains? #{"DOUBLE" "DOUBLE PRECISION" "FLOAT" "FLOAT8"} type) "DOUBLE"
      (contains? #{"REAL" "FLOAT4"} type) "REAL"
      (or (contains? #{"VARCHAR" "TEXT" "STRING" "CHAR" "CHARACTER" "CHARACTER VARYING"} type)
          (re-matches #"(?:VARCHAR|CHAR)\(\d+\)" type)) "VARCHAR"
      (= "DATE" type) "DATE"
      (contains? #{"TIME" "TIME WITHOUT TIME ZONE"} type) "TIME"
      (contains? #{"TIMESTAMP" "TIMESTAMP_NTZ" "TIMESTAMP WITHOUT TIME ZONE" "DATETIME"} type) "TIMESTAMP"
      (contains? #{"TIMESTAMP WITH TIME ZONE" "TIMESTAMPTZ" "TIMESTAMP_LTZ" "TIMESTAMP_TZ"} type) "TIMESTAMPTZ"
      (contains? #{"BLOB" "BINARY" "VARBINARY"} type) "BLOB"
      :else (storage/fail! (str "Federation cannot preserve source type " type ". Expose a view with supported scalar types.")))))

(defn check-budget! [cancel deadline]
  (when @cancel (storage/fail! "Query cancelled."))
  (when (>= (System/nanoTime) deadline) (storage/fail! "Federated query exceeded its time limit.")))

(defn remaining-time [cancel deadline]
  (check-budget! cancel deadline)
  (/ (- deadline (System/nanoTime)) 1e9))

(defn import-rows! [^Connection connection name result cancel deadline register-statement!]
  (let [columns (:columns result)
        names (mapv :name columns)]
    (when (or (empty? names) (not= (count names) (count (set (map str/lower-case names)))))
      (storage/fail! "Federated inputs require unique column names."))
    (jdbc/execute! connection
                   (str "CREATE TABLE " (jdbc/quote-identifier name) " ("
                        (str/join ", " (map #(str (jdbc/quote-identifier (:name %)) " " (resolve-column-type %)) columns)) ")"))
    (with-open [statement (.prepareStatement connection
                                            (str "INSERT INTO " (jdbc/quote-identifier name) " VALUES ("
                                                 (str/join "," (repeat (count columns) "?")) ")"))]
      (register-statement! statement)
      (try
        (doseq [batch (partition-all 1000 (:rows result))]
          (check-budget! cancel deadline)
          (doseq [row batch]
            (jdbc/bind! statement row)
            (.addBatch ^PreparedStatement statement))
          (.executeBatch ^PreparedStatement statement)
          (.clearBatch ^PreparedStatement statement))
        (check-budget! cancel deadline)
        (finally (register-statement! nil))))))

(defn capture-source! [connection source metadata cancel deadline register-statement! run-query!]
  (with-open [source-connection (connections/open-connection! (:directory metadata)
                                                             (or (:project-id metadata) (get-in metadata [:project :id]))
                                                             (:connection_id source))]
    (let [result (run-query! source-connection
                             (str "SELECT * FROM " (:source_sql source) " LIMIT " (inc capture-row-limit))
                             [] capture-row-limit (remaining-time cancel deadline) cancel register-statement! identity)]
      (when (:truncated result)
        (storage/fail! (str "Federated input " (:connection_id source) "." (:schema source) "." (:relation source)
                            " exceeds 50,000 rows. Filter it in a source view before joining.")))
      (import-rows! connection (:local_relation source) result cancel deadline register-statement!)
      (assoc (dissoc source :key :source_sql) :row_count (:row_count result) :row_limit capture-row-limit
             :columns (:columns result)))))

(defn rewrite-table! [^Table table local-name]
  (when-not (.getAlias table)
    (.setAlias table (Alias. (.getName table))))
  (.setName table (jdbc/quote-identifier local-name))
  (.setSchemaName table nil)
  (.setDatabase table (Database. "")))

(defn execute! [compiled metadata options cancel register-statement! run-query!]
  (let [statements (parse/parse-sql (:sql compiled))
        max-rows (or (:max_rows options) 10000)
        timeout (or (:timeout_seconds options) 30)]
    (when-not (= 1 (count statements)) (storage/fail! "Federation runs one SELECT at a time."))
    (when-not (and (integer? max-rows) (<= 1 max-rows 100000) (number? timeout) (< 0 timeout 121))
      (storage/fail! "Invalid federated row limit or timeout."))
    (let [statement (first statements)
          tables (:tables (parse/collect-native-metadata statement))
          descriptions (mapv #(describe-source % compiled metadata) tables)
          sources (vec (distinct descriptions))
          duplicate-names (->> tables (group-by #(str/lower-case (.getUnquotedName ^Table %)))
                               (filter #(> (count (val %)) 1)) (mapcat val))
          deadline (+ (System/nanoTime) (long (* timeout 1e9)))]
      (when (some #(nil? (.getAlias ^Table %)) duplicate-names)
        (storage/fail! "Give each table an explicit alias when federated relation names repeat."))
      (when (> (count sources) capture-relation-limit)
        (storage/fail! "Federation supports at most 16 physical input relations per query."))
      (with-open [connection (jdbc/connect! "jdbc:duckdb:")]
        (let [sources (mapv #(assoc %1 :local_relation (str "wb_federated_" %2)) sources (range))
              local-names (into {} (map (juxt :key :local_relation)) sources)
              captures (mapv #(capture-source! connection % metadata cancel deadline register-statement! run-query!) sources)]
          (doseq [[table source] (map vector tables descriptions)]
            (rewrite-table! table (local-names (:key source))))
          (let [bound (if (:tree compiled)
                        (tokens/bind-parameters (str statement) (or (:parameters options) {}))
                        {:sql (str statement) :params (:params compiled)})
                output (run-query! connection (:sql bound) (:params bound) max-rows
                                   (remaining-time cancel deadline) cancel register-statement!)]
            (assoc output :sql (:sql bound)
                   :bindings (mapv #(hash-map :target (:connection_id %) :schema (:schema %)
                                             :relation (:relation %) :source_type (:source_type %)) sources)
                   :captured_relations captures
                   :warnings ["Federation copies at most 50,000 rows per input, across at most 16 inputs, into isolated DuckDB."
                              "Sources are captured sequentially without a shared transaction or snapshot. Results use DuckDB SQL semantics."])))))))
