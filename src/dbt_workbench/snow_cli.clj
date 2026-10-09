(ns dbt-workbench.snow-cli
  (:require [cheshire.core :as json]
            [cheshire.parse :as json-parse]
            [clojure.string :as str]
            [dbt-workbench.jdbc :as jdbc]
            [dbt-workbench.query-processor.parse :as parse]
            [dbt-workbench.query-processor.tokens :as tokens]
            [dbt-workbench.storage :as storage])
  (:import (com.fasterxml.jackson.core JsonFactory JsonToken)
           (java.io ByteArrayOutputStream InputStream)
           (java.math BigDecimal)
           (java.util.concurrent Callable Executors Future TimeUnit)
           (net.sf.jsqlparser.expression LongValue)
           (net.sf.jsqlparser.statement.select Limit PlainSelect Select)))

(def max-output-bytes (* 32 1024 1024))
(defn read-output! [^InputStream stream too-large]
  (with-open [stream stream
              output (ByteArrayOutputStream.)]
    (let [buffer (byte-array 8192)]
      (loop [size 0]
        (let [read (.read stream buffer)]
          (when (pos? read)
            (if (<= (+ size read) max-output-bytes)
              (.write output buffer 0 read)
              (reset! too-large true))
            (recur (+ size read)))))
      (.toString output "UTF-8"))))
(defn stop-process! [^Process process]
  (let [descendants (.toList (.descendants (.toHandle process)))]
    (doseq [^java.lang.ProcessHandle child (reverse descendants)] (.destroyForcibly child))
    (.destroyForcibly process)
    (.waitFor process 2 TimeUnit/SECONDS)))
(defn run-process! [args input timeout cancel]
  (when @cancel (storage/fail! "Query cancelled."))
  (let [builder (ProcessBuilder. ^java.util.List args)
        _ (.put (.environment builder) "BROWSER" "/usr/bin/true")
        process (try (.start builder) (catch java.io.IOException _ (storage/fail! "Snowflake CLI is unavailable. Install snow and configure its connection locally.")))
        executor (Executors/newFixedThreadPool 3)
        too-large (atom false)
        output (.submit executor ^Callable (fn [] (read-output! (.getInputStream process) too-large)))
        ;; stderr is captured and discarded, never returned: authentication errors may contain URLs or credentials.
        errors (.submit executor ^Callable (fn [] (read-output! (.getErrorStream process) too-large)))
        input-task (.submit executor ^Callable
                            (fn [] (with-open [writer (.getOutputStream process)]
                                     (when input (.write writer (.getBytes ^String input "UTF-8"))))))
        deadline (+ (System/nanoTime) (long (* timeout 1e9)))]
    (try
      (loop []
        (cond
          @cancel (do (stop-process! process) (storage/fail! "Query cancelled. The CLI process stopped; server-side cancellation is unconfirmed."))
          @too-large (do (stop-process! process) (storage/fail! "Snowflake CLI output exceeded the local 32 MiB limit."))
          (> (System/nanoTime) deadline) (do (stop-process! process) (storage/fail! "Snowflake CLI exceeded its time limit. Server-side cancellation is unconfirmed."))
          (.waitFor process 25 TimeUnit/MILLISECONDS)
          (let [stdout (.get ^Future output 2 TimeUnit/SECONDS)]
            (.get ^Future errors 2 TimeUnit/SECONDS)
            (.get ^Future input-task 2 TimeUnit/SECONDS)
            (when @too-large (storage/fail! "Snowflake CLI output exceeded the local 32 MiB limit."))
            {:exit (.exitValue process) :out stdout})
          :else (recur)))
      (catch clojure.lang.ExceptionInfo error (throw error))
      (catch Exception _ (storage/fail! "Snowflake CLI could not complete the request. Check its local configuration and cached authentication."))
      (finally
        (when (.isAlive process) (stop-process! process))
        (.shutdownNow executor)))))
(defn parse-json [content]
  (try
    (binding [json-parse/*use-bigdecimals?* true]
      (json/parse-string-strict content))
    (catch Exception _ (storage/fail! "Snowflake CLI returned an unsupported JSON result. Use a configured CLI with JSON output support."))))
(defn read-configured [content]
  (let [entries (parse-json content)]
    (when-not (and (vector? entries) (every? map? entries))
      (storage/fail! "Snowflake CLI connection metadata has an unsupported format."))
    (->> entries
         (map (fn [entry]
                (let [connection (get entry "connection_name")
                      parameters (get entry "parameters")]
                  (when-not (and (string? connection) (not (str/blank? connection)) (map? parameters))
                    (storage/fail! "Snowflake CLI connection metadata has an unsupported format."))
                  (merge {:id connection :name connection :type "snowflake" :transport "snow_cli" :cli_connection connection}
                         (into {} (for [field [:account :user :database :schema :warehouse :role]
                                        :let [value (get parameters (name field))]
                                        :when (string? value)] [field value]))))))
         (sort-by :id) vec)))
(defn list-configured! []
  (let [result (run-process! ["snow" "connection" "list" "--format" "JSON" "--silent"] nil 15 (atom false))]
    (when-not (zero? (:exit result)) (storage/fail! "Snowflake CLI could not list its local connection configuration."))
    (read-configured (:out result))))
(defn render-literal [value]
  (cond
    (nil? value) "NULL"
    (string? value) (do
                      (when (str/includes? value (str (char 0))) (storage/fail! "SQL text parameters cannot contain a NUL character."))
                      (str "'" (-> value (str/replace "\\" "\\\\") (str/replace "'" "''")) "'"))
    (boolean? value) (if value "TRUE" "FALSE")
    (instance? BigDecimal value) (.toPlainString ^BigDecimal value)
    (integer? value) (str value)
    (or (float? value) (double? value)) (if (Double/isFinite (double value)) (str value) (storage/fail! "SQL numeric parameters must be finite."))
    :else (storage/fail! "Snowflake CLI parameters support text, finite numbers, booleans, and null only.")))
(defn render-parameters [sql parameters]
  (let [lexemes (tokens/tokenize sql)
        named (filter #(= :named-parameter (:kind %)) lexemes)
        positional (filter #(= :parameter (:kind %)) lexemes)]
    (when (and (seq named) (seq positional)) (storage/fail! "Use named or positional SQL parameters, not both."))
    (if (map? parameters)
      (do
        (when-not (and (empty? positional) (= (set (map #(keyword (subs (:text %) 1)) named)) (set (keys parameters))))
          (storage/fail! "Parameters must match exactly the named SQL parameters."))
        (apply str (map (fn [token]
                          (if (= :named-parameter (:kind token))
                            (render-literal (get parameters (keyword (subs (:text token) 1)))) (:text token))) lexemes)))
      (do
        (when-not (and (sequential? parameters) (empty? named) (= (count positional) (count parameters)))
          (storage/fail! "Parameter values must match the SQL placeholders."))
        (first (reduce (fn [[text values] token]
                         (if (= :parameter (:kind token))
                           [(str text (render-literal (first values))) (next values)]
                           [(str text (:text token)) values])) ["" (seq parameters)] lexemes))))))
(defn bound-select [sql parameters max-rows]
  (when-not (and (integer? max-rows) (<= 1 max-rows 100000)) (storage/fail! "Invalid Snowflake CLI row limit."))
  (let [rendered (render-parameters sql parameters)
        statements (parse/parse-sql rendered)]
    (when-not (= 1 (count statements)) (storage/fail! "Snowflake CLI accepts one SELECT or WITH statement."))
    (let [^Select statement (first statements)
          limit (.getLimit statement)
          row-count (when limit (.getRowCount ^Limit limit))]
      (when (or (.getFetch statement) (and (instance? PlainSelect statement) (.getTop ^PlainSelect statement))
                (.getLimitBy statement) (and limit (.getOffset ^Limit limit)))
        (storage/fail! "Snowflake CLI bounded queries support LIMIT and OFFSET. Remove TOP, FETCH, and LIMIT BY."))
      (when (and row-count (not (instance? LongValue row-count)))
        (storage/fail! "Snowflake CLI LIMIT requires a nonnegative integer."))
      (when (and row-count (neg? (.getValue ^LongValue row-count))) (storage/fail! "SQL LIMIT must be nonnegative."))
      (.setLimit statement (doto (Limit.) (.setRowCount (LongValue. (long (min (inc max-rows) (if row-count (.getValue ^LongValue row-count) (inc max-rows))))))))
      (str statement))))
(defn read-column-order [content]
  (with-open [parser (.createParser (JsonFactory.) ^String content)]
    (when-not (= JsonToken/START_ARRAY (.nextToken parser)) (storage/fail! "Snowflake CLI SELECT results must be a JSON row array."))
    (let [token (.nextToken parser)]
      (cond
        (= JsonToken/END_ARRAY token) []
        (= JsonToken/START_OBJECT token)
        (loop [names []]
          (let [token (.nextToken parser)]
            (if (= JsonToken/END_OBJECT token) names
              (do
                (when-not (= JsonToken/FIELD_NAME token) (storage/fail! "Snowflake CLI returned unsupported row data."))
                (let [field (.currentName parser)]
                  (when (some #{field} names) (storage/fail! "Snowflake CLI cannot preserve duplicate column names. Give projections unique aliases."))
                  (.nextToken parser) (.skipChildren parser)
                  (recur (conj names field)))))))
        :else (storage/fail! "Snowflake CLI SELECT results must contain JSON objects.")))))
(defn infer-type [values]
  (let [types (into #{} (keep (fn [value]
                              (cond (nil? value) nil (boolean? value) "BOOLEAN" (integer? value) "INTEGER"
                                    (instance? BigDecimal value) "DECIMAL" (number? value) "FLOAT"
                                    (string? value) "TEXT" :else "JSON"))) values)]
    (cond (empty? types) "UNKNOWN" (= 1 (count types)) (first types)
          (every? #{"INTEGER" "DECIMAL"} types) "DECIMAL" :else "UNKNOWN")))
(defn render-result-value [value]
  (if (and (integer? value) (not (<= -9007199254740991 value 9007199254740991)))
    (str value)
    (jdbc/json-value value)))
(defn read-result [content max-rows]
  (let [data (parse-json content)
        columns (read-column-order content)]
    (when-not (and (vector? data) (every? map? data)
                   (every? #(= (set columns) (set (keys %))) data))
      (storage/fail! "Snowflake CLI returned inconsistent or unsupported row data."))
    (let [rows (vec (take max-rows data))]
      {:columns (mapv (fn [column] {:name column :type (infer-type (map #(get % column) rows)) :type_source "inferred"}) columns)
       :rows (mapv (fn [row] (mapv #(render-result-value (get row %)) columns)) rows)
       :row_count (count rows) :truncated (> (count data) max-rows)
       :warnings ["Snowflake CLI column types are inferred from returned JSON values; declared types, precision, and scale are unavailable."
                  "The CLI may reduce numeric precision before JSON serialization; received decimal and large integer digits are preserved as strings."
                  "Empty CLI results contain no column metadata. Duplicate output names cannot be recovered after CLI JSON serialization."
                  "Stopping the CLI process does not confirm server-side query cancellation."]})))
(defn execute-select! [output sql parameters options cancel]
  (let [connection (:cli_connection output)
        max-rows (or (:max_rows options) 10000)
        timeout (or (:timeout_seconds options) 30)]
    (when-not (and (= "snowflake" (:type output)) (= "snow_cli" (:transport output))
                   (string? connection) (not (str/blank? connection)) (not (str/includes? connection (str (char 0)))))
      (storage/fail! "Choose a registered Snowflake CLI connection."))
    (when-not (and (number? timeout) (< 0 timeout 121)) (storage/fail! "Invalid Snowflake CLI timeout."))
    (let [sql (bound-select sql (or parameters {}) max-rows)
          args ["snow" "sql" (str "--connection=" connection) "--stdin" "--format" "JSON" "--silent"
                "--enable-templating" "NONE" "--local-only" "--decimal-precision" "38"]
          result (run-process! args sql timeout cancel)]
      (when-not (zero? (:exit result))
        (storage/fail! "Snowflake CLI SELECT failed. Check its cached authentication, connection, and role grants. Interactive login is disabled."))
      (assoc (read-result (:out result) max-rows) :sql sql :transport "snow_cli"))))
