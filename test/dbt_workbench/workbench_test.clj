(ns dbt-workbench.workbench-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :as test :refer [deftest is use-fixtures]]
            [dbt-workbench.jdbc :as jdbc]
            [dbt-workbench.query-processor :as processor]
            [dbt-workbench.query-processor.parse :as parse]
            [dbt-workbench.query-processor.tokens :as tokens]
            [dbt-workbench.query-processor.driver :as driver]
            [dbt-workbench.query-processor.store :as query-store]
            [dbt-workbench.runtime :as runtime]
            [dbt-workbench.server :as server]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.workspace :as workspace])
  (:import (java.nio.file Files)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)))

(def context (atom nil))
(defn wait-job [engine job]
  (loop [attempt 0]
    (let [record (runtime/find-job engine (:id job))]
      (cond (runtime/terminal (:status record)) record
            (> attempt 1200) (throw (ex-info "Job timeout" record))
            :else (do (Thread/sleep 50) (recur (inc attempt)))))))
(defn run-job [kind options] (wait-job (:engine @context) (runtime/submit! (:engine @context) (:id @context) kind options)))
(defn setup! [run-tests]
  (let [directory (str (Files/createTempDirectory "workbench-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
        engine (runtime/start-runtime! directory) project (workspace/create-demo! directory)]
    (reset! context {:directory directory :engine engine :id (:id project)})
    (try
      (let [job (run-job "build" {:target "dev"})] (is (= "succeeded" (:status job)) (str (:error job))) (is (= 28 (count (get-in job [:summary :run_results :results])))))
      (run-tests)
      (finally (runtime/stop-runtime! engine) (doseq [file (reverse (file-seq (io/file directory)))] (.setWritable file true) (.delete file))))))
(use-fixtures :once setup!)

(deftest workspace-history
  (let [{:keys [directory id]} @context path "models/orders.sql" original (workspace/read-file directory id path)
        changed (workspace/save-file! directory id {:path path :content (str (:content original) "\n-- test\n") :expected_revision (:revision original)})]
    (is (not= (:revision original) (:revision changed)))
    (is (thrown? clojure.lang.ExceptionInfo (workspace/save-file! directory id {:path path :content "SELECT 1" :expected_revision (:revision original)})))
    (workspace/save-file! directory id {:path path :content (:content changed) :expected_revision (:revision changed)})
    (is (= 1 (count (workspace/list-changes directory id))))
    (is (= (:content original) (:before (first (workspace/list-changes directory id)))))
    (workspace/save-file! directory id {:path path :content (:content original) :expected_revision (:revision changed)})
    (is (= 2 (count (workspace/list-changes directory id))))))

(deftest file-boundaries
  (doseq [path ["../profiles.yml" "profiles.yml" "/etc/passwd" "target/manifest.json"]]
    (is (thrown? clojure.lang.ExceptionInfo (workspace/read-file (:directory @context) (:id @context) path)))))

(deftest project-file-previews
  (let [directory (str (Files/createTempDirectory "workbench-files-" (make-array java.nio.file.attribute.FileAttribute 0)))
        project (workspace/create-demo! directory) id (:id project) root (:path project)
        text-files {"notes.txt" "Café\n" "helper.py" "print('hello')\n" "table.csv" "id,name\n1,River\n"
                    "LICENSE" "License example\n" ".gitignore" "target/\n"}
        binary-files {"binary-preview.bin" (byte-array [65 0 66]) "invalid-utf8.txt" (byte-array [-1 -2])}
        large-content (apply str (repeat (* 3 1024 1024) "x"))]
    (try
      (doseq [[path content] text-files] (storage/save-text! (io/file root path) content))
      (doseq [[path content] binary-files]
        (with-open [stream (io/output-stream (io/file root path))] (.write stream content)))
      (storage/save-text! (io/file root "large-preview.txt") large-content)
      (let [paths (set (map :path (workspace/list-files directory id)))]
        (is (every? paths (concat (keys text-files) (keys binary-files) ["large-preview.txt"]))))
      (doseq [[path content] text-files]
        (let [preview (workspace/read-file directory id path)]
          (is (= content (:content preview)))
          (is (false? (:read_only preview)))))
      (doseq [[path bytes] binary-files]
        (let [preview (workspace/read-file directory id path)]
          (is (true? (:read_only preview)))
          (is (= "" (:content preview)))
          (is (thrown? clojure.lang.ExceptionInfo (workspace/save-file! directory id {:path path :content "replace" :expected_revision (:revision preview)})))
          (is (= (seq bytes) (seq (Files/readAllBytes (.toPath (io/file root path))))))))
      (let [preview (workspace/read-file directory id "large-preview.txt")]
        (is (true? (:read_only preview)))
        (is (= (count large-content) (:size_bytes preview)))
        (is (re-find #"2 MiB" (:preview_reason preview)))
        (is (thrown? clojure.lang.ExceptionInfo (workspace/save-file! directory id {:path "large-preview.txt" :content "replace"})))
        (is (= large-content (slurp (io/file root "large-preview.txt")))))
      (let [original (workspace/read-file directory id "notes.txt")]
        (doseq [content [large-content "text\u0000bytes"]]
          (is (thrown? clojure.lang.ExceptionInfo (workspace/save-file! directory id {:path "notes.txt" :content content :expected_revision (:revision original)}))))
        (is (= (:content original) (:content (workspace/read-file directory id "notes.txt"))))
        (is (= "Edited text\n" (:content (workspace/save-file! directory id {:path "notes.txt" :content "Edited text\n" :expected_revision (:revision original)})))))
      (finally (doseq [file (reverse (file-seq (io/file directory)))] (.delete file))))))

(deftest native-query-boundaries
  (doseq [content ["DELETE FROM orders" "SELECT * INTO backup FROM orders" "SELECT * FROM read_csv('/etc/passwd')" "SELECT FROM" "SELECT read_text('/etc/passwd')"]]
    (is (thrown? Exception (parse/parse-sql content)) content))
  (is (= 2 (count (parse/parse-sql "SELECT ';'; SELECT 2")))))

(deftest lexical-parameters
  (let [sql "SELECT :value, ':ignored', \"colon:name\", $$:dollar$$, $tag$? :tagged$tag$, 1::INTEGER /* :outer /* :inner */ ? */ -- :line\n, :value"
        bound (tokens/bind-parameters sql {:value 42})]
    (is (= [42 42] (:params bound)))
    (is (= [42] (:params (tokens/bind-parameters (str "SELECT E'escaped" (char 92) "':still_string', :value") {:value 42}))))
    (is (.contains ^String (:sql bound) "':ignored'"))
    (is (.contains ^String (:sql bound) "1::INTEGER"))
    (is (= "SELECT :p0, '?', $$?$$, :p1" (tokens/name-parameters "SELECT ?, '?', $$?$$, ?")))))

(deftest demo-compatibility-and-catalog
  (is (= {:models {:demo {:+materialized "view"}} :require-dbt-version [">=2.0.5" "<3.0.0"]}
         (workspace/upgrade-demo-config {:models {:demo {:materialized "view"}} :require-dbt-version [">=1.0.0" "<2.0.0"]})))
  (let [legacy {:tests [{:accepted_values {:values ["a"]}} {:relationships {:to "ref('orders')" :field "id"}}]}
        upgraded (workspace/upgrade-demo-tests legacy)]
    (is (= ["a"] (get-in upgraded [:tests 0 :accepted_values :arguments :values])))
    (is (= upgraded (workspace/upgrade-demo-tests upgraded))))
  (let [{:keys [directory id]} @context
        relations (:relations (processor/read-catalog! directory id "dev"))
        orders (first (filter #(= "orders" (:name %)) relations))]
    (is (some #(= "customer_id" (:name %)) (:columns orders)))
    (is (some #(= "amount" (:name %)) (:columns orders)))))

(deftest shared-database-attachments
  (let [{:keys [directory id]} @context
        metadata (query-store/create-store directory id {:target "dev"})]
    (with-open [first-connection (driver/connect-duckdb! metadata)
                second-connection (driver/connect-duckdb! metadata)]
      (is (= 100 (-> (jdbc/query! first-connection "SELECT COUNT(*) FROM baseline.main.customers") :rows first first)))
      (is (= 100 (-> (jdbc/query! second-connection "SELECT COUNT(*) FROM baseline.main.customers") :rows first first)))
      (is (seq (:relations (processor/read-catalog! directory id "dev")))))))

(def structured-query
  "{:query/version 1 :from {:model \"orders\" :as \"o\"}
    :select [{:as \"customer_id\" :expr [:field \"o\" \"customer_id\"]}
             {:as \"revenue\" :expr [:sum [:field \"o\" \"amount\"]]}]
    :where [:eq [:field \"o\" \"status\"] [:param \"status\" :text]]
    :group-by [[:field \"o\" \"customer_id\"]] :order-by [[:field \"o\" \"customer_id\"]]}")
(deftest structured-honeysql-and-jdbc
  (let [{:keys [directory id]} @context options {:target "dev" :content structured-query :format "edn" :parameters {:status "completed"} :dialect "duckdb"}
        native (processor/execute! directory id options (atom false) (fn [_]))
        portable (processor/execute! directory id (assoc options :dialect "sqlite") (atom false) (fn [_]))]
    (is (= (mapv :name (:columns native)) ["customer_id" "revenue"]))
    (is (= (mapv #(runtime/canonical-row (:columns native) %) (:rows native)) (mapv #(runtime/canonical-row (:columns portable) %) (:rows portable))))
    (is (= "clojure/honeysql+jdbc" (get-in native [:provenance :processor])))
    (is (seq (:captured_relations portable)))
    (is (thrown? clojure.lang.ExceptionInfo (processor/execute! directory id (assoc options :parameters {:status 3}) (atom false) (fn [_]))))))

(deftest conversion-roundtrip
  (let [{:keys [directory id]} @context sql "WITH x AS (SELECT * FROM orders) SELECT COUNT(*) AS count FROM x"
        edn (processor/convert-query directory id {:content sql :format "sql" :parameters {}})
        restored (processor/convert-query directory id {:content (:content edn) :format "edn" :parameters {}})]
    (is (not (.contains (:content edn) ":sql")))
    (is (.contains (:content edn) ":with"))
    (is (= (:rows (processor/execute! directory id {:content sql :format "sql" :target "dev"} (atom false) (fn [_])))
           (:rows (processor/execute! directory id {:content (:content restored) :parameters (:parameters restored) :format "sql" :target "dev"} (atom false) (fn [_])))))
    (is (thrown? Exception (processor/convert-query directory id {:content "DELETE FROM orders" :format "sql"}))))
  (let [{:keys [directory id]} @context options {:content structured-query :format "edn" :parameters {:status "completed"} :target "dev"}
        converted (processor/convert-query directory id options)
        output (processor/execute! directory id (assoc options :content (:content converted) :format "sql" :parameters (:parameters converted)) (atom false) (fn [_]))]
    (is (pos? (:row_count output)))))

(deftest run-all-and-partial-failure
  (let [options {:content "SELECT 1 AS n; SELECT 2 AS n;" :editor_content "SELECT 1 AS n; SELECT 2 AS n;" :format "sql" :target "dev" :dialect "duckdb"}
        job (run-job "query" options)]
    (is (= "succeeded" (:status job)) (:error job))
    (is (= 2 (count (get-in job [:summary :statement_results]))))
    (is (= [[2]] (:rows (runtime/result-page (:engine @context) (:result_id job) 0 100))))
    (is (= options (:options job))))
  (let [job (run-job "query" {:content "SELECT 1 AS n; SELECT missing FROM orders; SELECT 3 AS n;" :format "sql" :target "dev"})]
    (is (= "failed" (:status job)))
    (is (= 2 (get-in job [:summary :statement_index])))
    (is (= 1 (count (get-in job [:summary :statement_results]))))
    (is (= [[1]] (:rows (runtime/result-page (:engine @context) (get-in job [:summary :statement_results 0 :result_id]) 0 100))))))

(deftest query-cancellation
  (let [engine (:engine @context) job (runtime/submit! engine (:id @context) "query" {:target "dev" :format "sql" :content "WITH RECURSIVE t(n) AS (SELECT 1 UNION ALL SELECT n+1 FROM t WHERE n < 1000000000) SELECT SUM(n) FROM t"})]
    (Thread/sleep 100)
    (runtime/cancel-job! engine (:id job))
    (is (= "cancelled" (:status (wait-job engine job))))
    (is (= "succeeded" (:status (run-job "query" {:target "dev" :format "sql" :content "SELECT 42 AS value"}))))))

(deftest dbt-preview-and-reference-deferral
  (let [job (run-job "preview" {:target "dev" :model_id "model.jaffle_shop.orders" :dialect "duckdb"})]
    (is (= "succeeded" (:status job)) (:error job))
    (is (= 99 (:row_count (runtime/find-result (:engine @context) (:result_id job))))))
  (let [job (run-job "build" {:target "dev" :select "model.jaffle_shop.orders" :defer true :favor_state true})]
    (is (= "succeeded" (:status job)) (:error job))))

(deftest staging-model-selection
  (is (= "fqn:jaffle_shop.staging.stg_customers"
         (runtime/resolve-dbt-selection "model.jaffle_shop.stg_customers"
           {:nodes {:model.jaffle_shop.stg_customers {:fqn ["jaffle_shop" "staging" "stg_customers"]}}} [])))
  (is (= "path:models/staging/stg_customers.sql"
         (runtime/resolve-dbt-selection "model.jaffle_shop.stg_customers" {}
           [{:unique_id "model.jaffle_shop.stg_customers" :path "models/staging/stg_customers.sql"}])))
  (is (= "tag:staging+" (runtime/resolve-dbt-selection "tag:staging+" {} [])))
  (doseq [[model rows] [["stg_customers" 100] ["stg_orders" 99] ["stg_payments" 113]]]
    (let [job (run-job "preview" {:target "dev" :model_id (str "model.jaffle_shop." model) :dialect "duckdb"})]
      (is (= "succeeded" (:status job)) (:error job))
      (when (= "succeeded" (:status job))
        (is (= rows (:row_count (runtime/find-result (:engine @context) (:result_id job))))))))
  (let [job (run-job "build" {:target "dev" :select "model.jaffle_shop.stg_customers"})]
    (is (= "succeeded" (:status job)) (:error job))
    (is (some #(= "model.jaffle_shop.stg_customers" (:unique_id %)) (get-in job [:summary :run_results :results])))))

(deftest timeout-and-row-limit
  (let [{:keys [directory id]} @context options {:target "dev" :content "SELECT * FROM orders" :format "sql" :max_rows 2}
        output (processor/execute! directory id options (atom false) (fn [_]))]
    (is (= 2 (:row_count output)))
    (is (:truncated output))
    (is (thrown? clojure.lang.ExceptionInfo (processor/execute! directory id (assoc options :max_rows 0) (atom false) (fn [_]))))))

(defn request! [base method path body & [origin]]
  (let [builder (HttpRequest/newBuilder (URI. (str base path)))
        publisher (if body (HttpRequest$BodyPublishers/ofString (json/generate-string body)) (HttpRequest$BodyPublishers/noBody))]
    (.header builder "Content-Type" "application/json")
    (when origin (.header builder "Origin" origin))
    (.method builder method publisher)
    (let [response (.send (HttpClient/newHttpClient) (.build builder) (HttpResponse$BodyHandlers/ofString))]
      {:status (.statusCode response) :body (json/parse-string (.body response) true)})))

(deftest http-validation-and-restart
  (let [directory (str (Files/createTempDirectory "workbench-http-" (make-array java.nio.file.attribute.FileAttribute 0)))
        app (server/start! {:port 0 :data-dir directory})
        base (str "http://127.0.0.1:" (.getPort (.getAddress ^com.sun.net.httpserver.HttpServer (:server app))))]
    (try
      (is (= "clojure" (get-in (request! base "GET" "/api/health" nil) [:body :backend])))
      (is (= 403 (:status (request! base "POST" "/api/projects/demo" {} "https://example.com"))))
      (is (= 422 (:status (request! base "POST" "/api/projects/open" {:path 4}))))
      (let [project (:body (request! base "POST" "/api/projects/demo" {})) id (:id project)
            route (str "/api/projects/" id) original (:body (request! base "GET" (str route "/file?path=models/orders.sql") nil))
            save (request! base "PUT" (str route "/file") {:path "models/orders.sql" :content (str (:content original) "\n-- history\n") :expected_revision (:revision original)})]
        (is (= 200 (:status save)))
        (is (= 409 (:status (request! base "PUT" (str route "/file") {:path "models/orders.sql" :content "SELECT 1" :expected_revision (:revision original)}))))
        (is (= 422 (:status (request! base "POST" (str route "/jobs") {:kind "query" :options {:parameters {:x []}}}))))
        (is (= 400 (:status (request! base "POST" (str route "/query/convert") {:format "sql" :content "DELETE FROM orders"}))))
        (let [edn (:body (request! base "POST" (str route "/query/convert") {:format "sql" :content "SELECT 1"}))
              restored (:body (request! base "POST" (str route "/query/convert") {:format "edn" :content (:content edn)}))]
            (is (= {:p0 1} (:parameters restored)))
            (is (.contains (:content restored) ":p0")))
        (jdbc/execute! (:connection (:runtime app)) "INSERT INTO jobs(id,project_id,kind,status,options,summary) VALUES(?,?,?,'running','{}','{}')" ["interrupted-probe" id "query"])
        (server/stop! app)
        (let [restored (server/start! {:port 0 :data-dir directory})]
          (try
            (is (= 1 (count (workspace/list-changes directory id))))
            (is (= "interrupted" (:status (runtime/find-job (:runtime restored) "interrupted-probe"))))
            (is (= (:revision (:body save)) (:revision (workspace/read-file directory id "models/orders.sql"))))
            (finally (server/stop! restored)))))
      (finally
        (when-not @(:closed (:runtime app)) (server/stop! app))
        (doseq [file (reverse (file-seq (io/file directory)))] (.setWritable file true) (.delete file))))))

(deftest captured-duplicate-comparison
  (let [engine (:engine @context) job {:id (storage/create-id) :project_id (:id @context) :kind "query"}
        result {:columns [{:name "n" :type "INTEGER"}] :rows [[1] [1] [2]] :row_count 3}
        left (runtime/save-result! engine job result)
        same (runtime/save-result! engine job (assoc result :rows [[2] [1] [1]]))
        other (runtime/save-result! engine job (assoc result :rows [[1] [2]] :row_count 2))]
    (is (get-in (runtime/compare-results engine {:left_result_id left :right_result_id same}) [:summary :equal]))
    (is (false? (get-in (runtime/compare-results engine {:left_result_id left :right_result_id other}) [:summary :equal])))
    (is (thrown? clojure.lang.ExceptionInfo (runtime/compare-results engine {:left_result_id left :right_result_id same :key_columns ["n"]})))))

(deftest honeysql-conversion-execution-roundtrips
  (let [{:keys [directory id]} @context]
    (doseq [sql ["SELECT DISTINCT status FROM orders ORDER BY status"
                 "SELECT customer_id, COUNT(DISTINCT status) AS statuses FROM orders GROUP BY customer_id HAVING COUNT(*) > 1 ORDER BY customer_id LIMIT 10 OFFSET 1"
                 "SELECT o.customer_id, c.first_name FROM orders o LEFT JOIN customers c ON o.customer_id = c.customer_id JOIN customers d ON d.customer_id=c.customer_id WHERE o.status IN ('completed','placed') ORDER BY o.order_id LIMIT 10"
                 "SELECT CASE WHEN amount > 10 THEN 'large' ELSE 'small' END AS size FROM orders ORDER BY order_id LIMIT 10"
                 "WITH final AS (SELECT customer_id FROM orders WHERE amount > 10) SELECT COUNT(*) AS n FROM final"]]
      (let [options {:target "dev" :dialect "duckdb" :format "sql" :content sql}
            converted (processor/convert-query directory id options)
            native (processor/execute! directory id options (atom false) (fn [_]))
            edn (processor/execute! directory id (assoc options :format "edn" :content (:content converted)) (atom false) (fn [_]))]
        (is (= (:rows native) (:rows edn)) sql)
        (is (not (.contains (:content converted) ":sql")) sql)))
    (is (thrown? clojure.lang.ExceptionInfo (processor/convert-query directory id {:target "dev" :format "sql" :content "SELECT row_number() OVER (ORDER BY order_id) AS n FROM orders"})))
    (let [options {:format "sql" :target "dev" :content "SELECT :value AS n; SELECT 2 AS n;" :parameters {:value 41}}
          converted (processor/convert-query directory id options)
          job (run-job "query" (assoc options :format "edn" :content (:content converted)))]
      (is (= "succeeded" (:status job)) (:error job))
      (is (= 2 (count (get-in job [:summary :statement_results]))))
      (is (= [[41]] (:rows (runtime/result-page (:engine @context) (get-in job [:summary :statement_results 0 :result_id]) 0 10)))))))
