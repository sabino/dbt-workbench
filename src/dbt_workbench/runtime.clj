(ns dbt-workbench.runtime
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dbt-workbench.text :as text]
            [dbt-workbench.jdbc :as jdbc]
            [dbt-workbench.query-processor :as processor]
            [dbt-workbench.query-processor.parse :as parse]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.workspace :as workspace])
  (:import (java.lang ProcessHandle)
           (java.io RandomAccessFile)
           (java.sql Connection PreparedStatement)
           (java.util.concurrent Executors ExecutorService TimeUnit)))

(def terminal #{"succeeded" "failed" "cancelled" "interrupted"})
(def kinds #{"parse" "build" "test" "baseline" "preview" "query" "compare"})
(defn read-record [record json-fields]
  (reduce (fn [data field] (if (string? (field data)) (update data field #(json/parse-string % true)) data)) record json-fields))
(defn list-jobs [runtime project-id]
  (locking runtime
    (mapv #(read-record % [:options :summary]) (jdbc/query-maps! (:connection runtime) "SELECT * FROM jobs WHERE project_id=? ORDER BY created_at DESC" [project-id]))))
(defn find-job [runtime id]
  (locking runtime
    (or (some-> (first (jdbc/query-maps! (:connection runtime) "SELECT * FROM jobs WHERE id=?" [id])) (read-record [:options :summary]))
        (storage/fail! "Job not found." 404))))
(defn save-event! [runtime id message & [level data]]
  (locking runtime
    (jdbc/execute! (:connection runtime) "INSERT INTO events(job_id,timestamp,level,message,data) VALUES(?,?,?,?,?)"
                   [id (storage/timestamp) (or level "info") message (when data (json/generate-string data))])))
(defn list-events [runtime id cursor]
  (find-job runtime id)
  (locking runtime
    (mapv #(read-record % [:data]) (jdbc/query-maps! (:connection runtime) "SELECT * FROM events WHERE job_id=? AND seq>? ORDER BY seq" [id cursor]))))
(defn find-result [runtime id]
  (locking runtime
    (or (some-> (first (jdbc/query-maps! (:connection runtime) "SELECT * FROM results WHERE id=?" [id])) (read-record [:columns :provenance :summary]))
        (storage/fail! "Result not found." 404))))
(defn list-results [runtime project-id]
  (locking runtime
    (mapv #(dissoc (read-record % [:columns :provenance :summary]) :data_path)
          (jdbc/query-maps! (:connection runtime) "SELECT * FROM results WHERE project_id=? ORDER BY created_at DESC" [project-id]))))
(defn read-result-rows [runtime id]
  (let [result (find-result runtime id)]
    (when (str/ends-with? (:data_path result) ".arrow")
      (storage/fail! "This legacy Arrow result remains in history. Load its original query and rerun it with the Clojure engine."))
    (storage/read-json (:data_path result) [])))
(defn result-page [runtime id offset limit]
  (let [result (find-result runtime id) rows (read-result-rows runtime id) offset (min offset (count rows))]
    {:columns (:columns result) :row_count (:row_count result) :offset offset :limit limit
     :rows (subvec (vec rows) offset (min (count rows) (+ offset limit)))}))
(defn save-result! [runtime job result]
  (let [id (storage/create-id) file (io/file (workspace/project-data (:directory runtime) (:project_id job)) "results" (str id ".json"))]
    (storage/save-json! file (:rows result))
    (locking runtime
      (jdbc/execute! (:connection runtime) "INSERT INTO results(id,project_id,job_id,kind,columns,row_count,sql,dialect,provenance,created_at,data_path,summary) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)"
                     [id (:project_id job) (:id job) (:kind job) (json/generate-string (:columns result)) (:row_count result)
                      (:sql result "") (:dialect result "") (json/generate-string (:provenance result {})) (storage/timestamp) (str file) (json/generate-string (:summary result {}))]))
    id))
(defn active? [runtime project-id] (some #(not (terminal (:status %))) (list-jobs runtime project-id)))
(defn require-idle! [runtime project-id]
  (when (active? runtime project-id) (storage/fail! "Wait for this project's active jobs to finish or cancel them before changing files or reference state." 409)))
(defn cancel-job! [runtime id]
  (locking runtime
    (let [job (find-job runtime id)]
      (when-not (terminal (:status job))
        (when-let [cancel (get-in @(:workers runtime) [id :cancel])] (reset! cancel true))
        (when-let [^Process process (get-in @(:workers runtime) [id :process])]
          (doseq [handle (iterator-seq (.iterator (.descendants process)))] (.destroyForcibly ^ProcessHandle handle))
          (.destroyForcibly process))
        (when-let [^PreparedStatement statement (get-in @(:workers runtime) [id :statement])] (try (.cancel statement) (catch Exception _ nil)))
        (locking runtime (jdbc/execute! (:connection runtime) "UPDATE jobs SET status='cancelling' WHERE id=?" [id])))
      (find-job runtime id))))
(defn dbt-binary []
  (or (System/getenv "DBT_WORKBENCH_DBT")
      (let [file (first (filter #(.isFile %) (map #(io/file % "dbt") (or (seq (.listFiles (io/file ".tools/dbt"))) []))))]
        (when file (storage/canonical-path file))) "dbt"))
(defn check-cancel! [cancel] (when @cancel (storage/fail! "Job cancelled.")))
(defn resolve-dbt-selection [item manifest models]
  (if-not (str/starts-with? item "model.") item
    (let [node (get-in manifest [:nodes (keyword item)])
          model (first (filter #(= item (:unique_id %)) models))]
      (cond
        (seq (:fqn node)) (str "fqn:" (str/join "." (:fqn node)))
        (:path model) (str "path:" (:path model))
        :else (storage/fail! (str "Selected model is missing: " item ". Refresh the model graph."))))))
(defn run-dbt! [runtime job cancel command]
  (check-cancel! cancel)
  (let [directory (:directory runtime) project-id (:project_id job) project (workspace/find-project directory project-id)
        options (:options job) run-dir (io/file (workspace/project-data directory project-id) "jobs" (:id job) (str command "-" (or (:target options) "default")))
        artifact-dir (io/file run-dir "artifacts") log-dir (io/file run-dir "logs")
        baseline (workspace/read-baseline directory project-id (:baseline_id options))
        selection (or (:select options) (when (= command "compile") (:model_id options)))
        selections (if (string? selection) (text/split-words selection) selection)
        selections (when (seq selections)
                     (let [manifest (workspace/read-manifest directory project-id)
                           models (workspace/list-models directory project-id)]
                       (mapv #(resolve-dbt-selection % manifest models) selections)))
        _ (when (and (:defer options) (not baseline)) (storage/fail! "Save or import a baseline before enabling reference deferral."))
        args (cond-> [(dbt-binary) command "--project-dir" (:path project) "--profiles-dir" (:profiles_dir project)
                      "--target-path" (str artifact-dir) "--log-path" (str log-dir) "--log-format" "json"]
               (:target options) (into ["--target" (:target options)])
               (and (seq selections) (not= command "parse") (not= "baseline" (:kind job))) (into (cons "--select" selections))
               (and (:defer options) (not= "baseline" (:kind job)) (not= command "parse")) (into ["--defer" "--state" (:artifact_dir baseline)])
               (and (:defer options) (:favor_state options) (not= "baseline" (:kind job))) (conj "--favor-state"))
        builder (doto (ProcessBuilder. ^java.util.List args) (.directory (io/file (:path project))) (.redirectErrorStream true))]
    (.mkdirs artifact-dir) (.mkdirs log-dir)
    (.put (.environment builder) "DBT_SEND_ANONYMOUS_USAGE_STATS" "false")
    (save-event! runtime (:id job) (str "Running dbt " command " against target " (:target options) ".") "info" {:command args})
    (let [process (.start builder)]
      (swap! (:workers runtime) assoc-in [(:id job) :process] process)
      (try
        (with-open [reader (io/reader (.getInputStream process))]
          (doseq [line (line-seq reader)]
            (let [event (try (json/parse-string line true) (catch Exception _ nil))]
              (save-event! runtime (:id job) (or (get-in event [:info :msg]) line) (get-in event [:info :level] "info")))))
        (let [code (.waitFor process)]
          (check-cancel! cancel)
          (when-not (zero? code) (storage/fail! (str "dbt " command " failed with exit code " code ". Inspect the run logs.")))
          (let [manifest (storage/read-json (io/file artifact-dir "manifest.json") nil)]
            (when manifest (storage/save-json! (io/file (workspace/project-data directory project-id) "manifest.json") manifest)))
          (when (= "baseline" (:kind job)) (workspace/register-baseline! directory project-id artifact-dir (:target options)))
          {:artifact_dir (str artifact-dir) :command args :exit_code code :run_results (storage/read-json (io/file artifact-dir "run_results.json") {})})
        (finally (swap! (:workers runtime) update (:id job) dissoc :process))))))
(defn canonical-row [columns row]
  (mapv (fn [column value] (if (and value (text/numeric-type? (:type column)))
                           (try (.stripTrailingZeros (bigdec value)) (catch Exception _ value)) value)) columns row))
(defn compare-results [runtime options]
  (let [left (find-result runtime (:left_result_id options)) right (find-result runtime (:right_result_id options))
        _ (when (or (get-in left [:provenance :truncated]) (get-in right [:provenance :truncated])) (storage/fail! "Comparisons require complete saved inputs."))
        left-rows (read-result-rows runtime (:id left)) right-rows (read-result-rows runtime (:id right))
        columns (:columns left) keys (:key_columns options)
        names (mapv :name columns) right-names (mapv :name (:columns right))
        _ (when-not (= names right-names) (storage/fail! "Comparison columns must have the same names and order."))
        indices (mapv (fn [key] (let [index (.indexOf ^java.util.List names key)] (when (neg? index) (storage/fail! "A key column is missing.")) index)) keys)
        _ (when (seq indices)
            (doseq [rows [left-rows right-rows]]
              (when-not (= (count rows) (count (set (map #(mapv % indices) rows)))) (storage/fail! "Comparison keys must be unique."))))
        a (frequencies (map #(canonical-row columns %) left-rows)) b (frequencies (map #(canonical-row (:columns right) %) right-rows))
        differences (vec (concat (for [[row n] a :when (> n (get b row 0))] ["left" (str row) (- n (get b row 0))])
                                (for [[row n] b :when (> n (get a row 0))] ["right" (str row) (- n (get a row 0))])))
        summary {:equal (= a b) :schema_equal (= columns (:columns right)) :differences (count differences) :left_rows (count left-rows) :right_rows (count right-rows)
                 :comparison_scope "Captured rows, with duplicates preserved and row order ignored."}]
    {:columns [{:name "side" :type "TEXT"} {:name "row" :type "TEXT"} {:name "count" :type "INTEGER"}]
     :rows differences :row_count (count differences) :provenance options :summary summary}))
(defn execute-job! [runtime job]
  (let [id (:id job) directory (:directory runtime) project-id (:project_id job)
        cancel (get-in @(:workers runtime) [id :cancel])
        summary (atom {}) result-id (atom nil)]
    (locking runtime (jdbc/execute! (:connection runtime) "UPDATE jobs SET status='running',started_at=? WHERE id=?" [(storage/timestamp) id]))
    (save-event! runtime id "Job started.")
    (try
      (check-cancel! cancel)
      (case (:kind job)
        ("parse" "build" "test" "baseline")
        (do (when (and (= "build" (:kind job)) (:is_demo (workspace/find-project directory project-id)) (nil? (workspace/read-baseline directory project-id)))
              (save-event! runtime id "Preparing demo reference data before the development build.")
              (run-dbt! runtime (assoc job :kind "baseline" :options {:target "baseline"}) cancel "build"))
            (reset! summary (run-dbt! runtime job cancel (if (= "baseline" (:kind job)) "build" (:kind job)))))
        "preview"
        (let [options (:options job) baseline (workspace/read-baseline directory project-id (:baseline_id options))
              artifacts (if (:baseline options) (:artifact_dir baseline) (:artifact_dir (run-dbt! runtime job cancel "compile")))
              manifest (storage/read-json (io/file artifacts "manifest.json") {})
              model (or (get-in manifest [:nodes (keyword (:model_id options))]) (storage/fail! "Compiled model is missing."))
              content (or (:compiled_code model) (:compiled_sql model))
              _ (when (str/blank? content) (storage/fail! (str "dbt did not compile " (:model_id options) ". Inspect selection warnings in the run logs.")))
              result (processor/execute! directory project-id (merge options {:content content :format "sql"} (when (:baseline options) {:target (:target baseline)})) cancel
                                         #(swap! (:workers runtime) assoc-in [id :statement] %))]
          (reset! result-id (save-result! runtime job result)) (reset! summary (:summary result)))
        "query"
        (let [options (:options job) input (processor/parse-input options)
              statements (cond
                           (vector? input) (mapv pr-str input)
                           (and (= "edn" (:format options)) (:sql input)) (mapv str (parse/parse-sql (:sql input)))
                           :else (or (:statements options) (when (:sql input) (mapv str (parse/parse-sql (:sql input)))) [(:content options)]))
              native? (boolean (:sql input))
              statement-format (if native? "sql" (:format options "sql"))
              parameter-sets (mapv #(processor/parameter-names % statement-format) statements)
              names (reduce into #{} parameter-sets)]
          (when-not (and (vector? statements) (<= 1 (count statements) 100) (every? #(and (string? %) (not (str/blank? %))) statements)) (storage/fail! "Run between 1 and 100 nonempty query statements."))
          (when-not (= names (set (keys (:parameters options {})))) (storage/fail! "Parameters must match the executed statements' declared names."))
          (reset! summary {:statement_count (count statements) :statement_results []})
          (doseq [[index content] (map-indexed vector statements)]
            (check-cancel! cancel)
            (swap! summary assoc :statement_index (inc index))
            (locking runtime
              (jdbc/execute! (:connection runtime) "UPDATE jobs SET summary=? WHERE id=?" [(json/generate-string @summary) id]))
            (save-event! runtime id (str "Running statement " (inc index) " of " (count statements) "."))
            (let [result (processor/execute! directory project-id (assoc options :content content :format statement-format :parameters (select-keys (:parameters options {}) (parameter-sets index))) cancel #(swap! (:workers runtime) assoc-in [id :statement] %))
                  result (update result :provenance merge {:editor_format (:format options "sql") :editor_content (:editor_content options (:content options))
                                                          :execution_scope (:execution_scope options "statement") :statement_index (inc index) :statement_count (count statements)})
                  saved (save-result! runtime job result)]
              (swap! summary update :statement_results conj (assoc (:summary result) :result_id saved :statement_index (inc index)))
              (when (= index (dec (count statements))) (reset! result-id saved) (swap! summary merge (:summary result))))))
        "compare" (let [result (compare-results runtime (:options job))] (reset! result-id (save-result! runtime job result)) (reset! summary (:summary result))))
      (check-cancel! cancel)
      (locking runtime
        (jdbc/execute! (:connection runtime) "UPDATE jobs SET status='succeeded',finished_at=?,summary=?,result_id=? WHERE id=?"
                       [(storage/timestamp) (json/generate-string @summary) @result-id id]))
      (save-event! runtime id "Job succeeded.")
      (catch Exception e
        (let [status (if @cancel "cancelled" "failed") message (if @cancel "Job cancelled." (ex-message e))]
          (locking runtime
            (jdbc/execute! (:connection runtime) "UPDATE jobs SET status=?,finished_at=?,summary=?,error=? WHERE id=?"
                           [status (storage/timestamp) (json/generate-string @summary) message id]))
          (save-event! runtime id (str "Job " status ". " message) (if @cancel "info" "error"))))
      (finally (swap! (:workers runtime) dissoc id)))))
(defn drain-project! [runtime project-id]
  (loop []
    (let [job (locking runtime
                (let [job (first (filter #(not (terminal (:status %))) (reverse (list-jobs runtime project-id))))]
                  (when-not job (swap! (:scheduled runtime) disj project-id)) job))]
      (when job (execute-job! runtime job) (recur)))))
(defn submit! [runtime project-id kind options]
  (workspace/find-project (:directory runtime) project-id)
  (when-not (and (kinds kind) (map? options)) (storage/fail! "Invalid job kind or options."))
  (locking runtime
    (when @(:closed runtime) (storage/fail! "The workbench is shutting down." 503))
    (let [id (storage/create-id)
          options (cond-> options (and (:defer options) (nil? (:baseline_id options))) (assoc :baseline_id (:id (workspace/read-baseline (:directory runtime) project-id))))]
      (locking runtime
        (jdbc/execute! (:connection runtime) "INSERT INTO jobs(id,project_id,kind,status,created_at,options) VALUES(?,?,?,'queued',?,?)"
                       [id project-id kind (storage/timestamp) (json/generate-string options)]))
      (swap! (:workers runtime) assoc id {:cancel (atom false)})
      (save-event! runtime id "Job queued.")
      (when-not (@(:scheduled runtime) project-id)
        (swap! (:scheduled runtime) conj project-id)
        (.submit ^ExecutorService (:pool runtime) ^Runnable #(drain-project! runtime project-id)))
      (find-job runtime id))))
(defn start-runtime! [directory]
  (.mkdirs (io/file directory))
  (let [lock-file (RandomAccessFile. (io/file directory ".jvm-runtime.lock") "rw") channel (.getChannel lock-file)
        lease (.tryLock channel)]
    (when-not lease (.close lock-file) (storage/fail! "This data directory is already open."))
    (let [connection (jdbc/connect! (str "jdbc:sqlite:" (io/file directory "runtime.sqlite3")))
          runtime {:directory directory :connection connection :pool (Executors/newFixedThreadPool 4)
                   :workers (atom {}) :scheduled (atom #{}) :closed (atom false) :lock-file lock-file :lease lease}]
      (jdbc/execute! connection "PRAGMA journal_mode=WAL")
      (jdbc/execute! connection "PRAGMA foreign_keys=ON")
      (doseq [sql ["CREATE TABLE IF NOT EXISTS jobs(id TEXT PRIMARY KEY,project_id TEXT,kind TEXT,status TEXT,created_at TEXT,started_at TEXT,finished_at TEXT,options TEXT,summary TEXT,error TEXT,result_id TEXT)"
                   "CREATE TABLE IF NOT EXISTS events(seq INTEGER PRIMARY KEY AUTOINCREMENT,job_id TEXT,timestamp TEXT,level TEXT,message TEXT,data TEXT)"
                   "CREATE TABLE IF NOT EXISTS results(id TEXT PRIMARY KEY,project_id TEXT,job_id TEXT,kind TEXT,columns TEXT,row_count INTEGER,sql TEXT,dialect TEXT,provenance TEXT,created_at TEXT,data_path TEXT,summary TEXT)"]]
        (jdbc/execute! connection sql))
      (when (some #(= "arrow_path" (:name %)) (jdbc/query-maps! connection "PRAGMA table_info(results)"))
        (jdbc/execute! connection "ALTER TABLE results RENAME COLUMN arrow_path TO data_path"))
      (jdbc/execute! connection "UPDATE jobs SET status='interrupted',finished_at=?,error='Application stopped before completion. Review before rerunning.' WHERE status NOT IN ('succeeded','failed','cancelled','interrupted')" [(storage/timestamp)])
      (doseq [project (workspace/list-projects directory)] (workspace/upgrade-demo! directory project))
      runtime)))
(defn stop-runtime! [runtime]
  (reset! (:closed runtime) true)
  (doseq [id (keys @(:workers runtime))] (cancel-job! runtime id))
  (.shutdown ^ExecutorService (:pool runtime))
  (.awaitTermination ^ExecutorService (:pool runtime) 10 TimeUnit/SECONDS)
  (.close ^Connection (:connection runtime))
  (.release ^java.nio.channels.FileLock (:lease runtime))
  (.close ^RandomAccessFile (:lock-file runtime)))
