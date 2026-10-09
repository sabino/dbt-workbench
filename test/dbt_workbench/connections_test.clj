(ns dbt-workbench.connections-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dbt-workbench.connections :as connections]
            [dbt-workbench.jdbc :as jdbc]
            [dbt-workbench.snow-cli :as snow-cli]
            [dbt-workbench.snowflake :as snowflake]
            [dbt-workbench.query-processor.compile :as compiler]
            [dbt-workbench.query-processor.driver :as driver]
            [dbt-workbench.query-processor.store :as store]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.workspace :as workspace])
  (:import (java.nio.file Files)))

(defn with-project! [run!]
  (let [directory (str (Files/createTempDirectory "connections-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
        root (io/file directory "workspace") dev (io/file root "dev.duckdb") baseline (io/file root "baseline.duckdb")]
    (try
      (storage/save-text! (io/file root "dbt_project.yml") "name: example\nprofile: example\n")
      (storage/save-text! (io/file root "profiles.yml")
                          "example:\n  target: dev\n  outputs:\n    dev:\n      type: duckdb\n      path: dev.duckdb\n      password: do-not-expose\n      token: hidden\n    baseline:\n      type: duckdb\n      path: baseline.duckdb\n    remote:\n      type: postgres\n      password: secret\n    absent:\n      type: duckdb\n      path: absent.duckdb\n")
      (doseq [model ["orders" "customers"]]
        (storage/save-text! (io/file root "models" (str model ".sql")) "select 1"))
      (doseq [[file orders customers] [[dev 10 1] [baseline 100 20]]]
        (with-open [connection (jdbc/connect! (str "jdbc:duckdb:" file))]
          (jdbc/execute! connection (str "CREATE TABLE orders AS SELECT " orders " AS amount"))
          (jdbc/execute! connection (str "CREATE TABLE customers AS SELECT " customers " AS amount"))))
      (let [project (workspace/open-project! directory (str root) (str root))]
        (run! directory (:id project) baseline))
      (finally (doseq [file (reverse (file-seq (io/file directory)))] (.delete file))))))
(defn execute-query! [directory id query]
  (let [metadata (store/create-store directory id {})
        compiled (compiler/compile query metadata {} false)]
    (driver/execute! compiled metadata {} (atom false) (fn [_]))))

(deftest redacted-real-connections
  (with-project!
    (fn [directory id baseline]
      (let [listed (connections/list-connections directory id)]
        (is (= ["absent" "baseline" "dev" "remote"] (mapv :id listed)))
        (is (not (re-find #"do-not-expose|hidden|secret|password|token" (pr-str listed)))))
      (is (:healthy (connections/test-connection! directory id "dev")))
      (is (false? (:healthy (connections/test-connection! directory id "absent"))))
      (is (false? (:healthy (connections/test-connection! directory id "remote"))))
      (is (= ["customers" "orders"] (mapv :name (:relations (connections/read-schema! directory id "dev")))))
      (is (= "extra" (:id (connections/save-connection! directory id {:id "extra" :type "duckdb" :path (str baseline)}))))
      (connections/save-connection! directory id {:id "extra" :type "duckdb" :path (str baseline) :name "Extra"})
      (is (= 1 (count (filter #(= "extra" (:id %)) (connections/list-connections directory id)))))
      (is (thrown? Exception (connections/save-connection! directory id {:id "dev" :type "duckdb" :path (str baseline)})))
      (is (thrown? Exception (connections/save-connection! directory id {:id "missing" :type "duckdb" :path (str baseline "missing")})))
      (with-open [connection (connections/open-connection! directory id "extra")]
        (is (thrown? Exception (jdbc/execute! connection "INSERT INTO orders VALUES (200)"))))
      (is (thrown? Exception (connections/remove-connection! directory id "dev")))
      (connections/save-routing! directory id {:version 1 :routes [{:model_id "model.example.orders" :target "extra"}]})
      (is (thrown? Exception (connections/remove-connection! directory id "extra")))
      (connections/save-routing! directory id {:version 1 :routes []})
      (is (:removed (connections/remove-connection! directory id "extra")))
      (is (:removed (connections/remove-connection! directory id "extra")))
      (is (.isFile baseline)))))

(deftest mixed-environment-routing
  (with-project!
    (fn [directory id _]
      (let [sql {:sql "SELECT orders.amount + customers.amount AS total FROM orders CROSS JOIN customers"}
            route {:version 1 :routes [{:model_id "model.example.customers" :target "baseline"}]}]
        (is (= [[11]] (:rows (execute-query! directory id sql))))
        (is (:valid (:validation (connections/save-routing! directory id route))))
        (is (= route (connections/read-routing directory id)))
        (let [result (execute-query! directory id sql)
              binding (first (filter :routed (:bindings result)))]
          (is (= [[30]] (:rows result)))
          (is (= [[30]] (:rows (execute-query! directory id {:sql "SELECT o.amount + c.amount AS total FROM orders AS o CROSS JOIN customers AS c"}))))
          (is (= [[20]] (:rows (execute-query! directory id {:select [:amount] :from [:customers]}))))
          (is (= [[20]] (:rows (execute-query! directory id {:select [:amount] :from [:customers] :where [:= :amount 20]}))))
          (is (= "baseline" (:target binding)))
          (is (= "main" (:schema binding)))
          (is (string? (:database binding)))
          (is (str/includes? (:sql result) "wb_route_")))
        (let [query {:query/version 1 :from {:model "customers" :as "c"}
                     :select [{:as "amount" :expr [:field "c" "amount"]}]}
              result (execute-query! directory id query)]
          (is (= [[20]] (:rows result)))
          (is (= "baseline" (:target (first (:bindings result))))))
        (connections/save-routing! directory id {:version 1 :routes [{:model_id "model.example.customers" :target "dev"}]})
        (is (= [[11]] (:rows (execute-query! directory id sql))))
        (let [project (workspace/find-project directory id)
              profile (io/file (:profiles_dir project) "profiles.yml")]
          (storage/save-text! profile (str/replace (slurp profile) "path: dev.duckdb"
                                                  "path: dev.duckdb\n      attach:\n        - path: baseline.duckdb\n          alias: baseline\n          read_only: true"))
          (connections/save-routing! directory id route)
          (let [result (execute-query! directory id sql)]
            (is (= [[30]] (:rows result)))
            (is (= "baseline" (:database (first (filter :routed (:bindings result))))))))))))

(deftest routing-validation-and-missing-relation
  (with-project!
    (fn [directory id _]
      (doseq [routes [[{:model_id "model.example.unknown" :target "dev"}]
                      [{:model_id "model.example.orders" :target "missing"}]
                      [{:model_id "model.example.orders" :target "remote"}]
                      [{:model_id "model.example.orders" :target "dev"} {:model_id "model.example.orders" :target "baseline"}]]]
        (is (thrown? Exception (connections/save-routing! directory id {:version 1 :routes routes}))))
      (doseq [route [{:model_id "model.example.orders" :target "absent"}
                     {:model_id "model.example.orders" :target "baseline" :relation "missing"}]]
        (let [validation (connections/validate-routing! directory id {:version 1 :routes [route]})]
          (is (false? (:valid validation)))
          (is (string? (:diagnostic (first (:routes validation))))))))))

(deftest imported-cli-references-are-local-and-idempotent
  (with-project!
    (fn [directory id _]
      (let [configured {:id "analytics" :name "Analytics" :type "snowflake" :transport "snow_cli" :cli_connection "analytics"
                        :account "organization-account" :user "analyst" :database "EXAMPLE_DEV" :schema "PUBLIC" :warehouse "WH" :role "READER"}
            reference {:id "cli-analytics" :type "snowflake" :transport "snow_cli" :cli_connection "analytics"}
            file (io/file (workspace/project-data directory id) "connections.json")]
        (with-redefs [snow-cli/list-configured! (fn [] [configured])
                      snow-cli/execute-select! (fn [& _] (throw (ex-info "Import must not execute cloud SQL." {})))
                      jdbc/connect! (fn [& _] (throw (ex-info "Import must not open JDBC." {})))]
          (is (= reference (connections/import-cli! directory id {:id "cli-analytics" :cli_connection "analytics"})))
          (is (= reference (connections/import-cli! directory id {:id "cli-analytics" :cli_connection "analytics"})))
          (is (= [reference] (storage/read-json file [])))
          (is (= #{:id :type :transport :cli_connection} (set (keys (first (storage/read-json file []))))))
          (is (not (re-find #"organization-account|analyst|EXAMPLE_DEV|PUBLIC|READER|warehouse" (slurp file))))
          (is (= "EXAMPLE_DEV" (:database (connections/find-output directory id "cli-analytics"))))
          (is (thrown? Exception (connections/import-cli! directory id {:id "dev" :cli_connection "analytics"})))
          (is (thrown? Exception (connections/import-cli! directory id {:id "invalid id" :cli_connection "analytics"})))
          (is (thrown? Exception (connections/import-cli! directory id {:id "missing" :cli_connection "missing"})))
          (is (thrown? Exception (connections/import-cli! directory id {:id "extra" :cli_connection "analytics" :account "organization-account"})))
          (is (= [reference] (storage/read-json file []))))
        (with-redefs [snow-cli/list-configured! (fn [] (throw (ex-info "JDBC refusal must not enumerate CLI configuration." {})))
                      snowflake/connect! (fn [& _] (throw (ex-info "JDBC must not receive cached CLI auth." {})))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Snow CLI connections execute through the CLI"
                               (connections/open-connection! directory id "cli-analytics"))))))))

(deftest cli-routed-relation-validation-uses-bounded-cli-select
  (with-project!
    (fn [directory id _]
      (let [configured {:id "analytics" :name "Analytics" :type "snowflake" :transport "snow_cli" :cli_connection "analytics"
                        :account "organization-account" :database "EXAMPLE_DEV" :schema "PUBLIC"}
            routing {:version 1 :routes [{:model_id "model.example.orders" :target "cli-analytics" :schema "history" :relation "order_archive"}]}
            captured (atom [])]
        (with-redefs [snow-cli/list-configured! (fn [] [configured])
                      jdbc/connect! (fn [& _] (throw (ex-info "CLI route validation must not use JDBC." {})))
                      snowflake/connect! (fn [& _] (throw (ex-info "CLI route validation must not use Snowflake JDBC." {})))
                      snow-cli/execute-select! (fn [output sql parameters options cancel]
                                                (swap! captured conj {:output output :sql sql :parameters parameters :options options :cancel @cancel})
                                                {:columns [] :rows [] :row_count 0 :truncated false})]
          (connections/import-cli! directory id {:id "cli-analytics" :cli_connection "analytics"})
          (let [result (connections/validate-routing! directory id routing)
                route (first (:routes result))
                call (first @captured)]
            (is (:valid result))
            (is (= "EXAMPLE_DEV" (:database route)))
            (is (= "HISTORY" (:schema route)))
            (is (= "ORDER_ARCHIVE" (:relation route)))
            (is (= "snow_cli" (:transport route)))
            (is (= "SELECT * FROM \"EXAMPLE_DEV\".\"HISTORY\".\"ORDER_ARCHIVE\" LIMIT 0" (:sql call)))
            (is (= {:max_rows 1 :timeout_seconds 15} (:options call)))
            (is (= {} (:parameters call)))
            (is (false? (:cancel call)))
            (is (= "analytics" (get-in call [:output :cli_connection])))))
        (with-redefs [snow-cli/list-configured! (fn [] [configured])
                      snow-cli/execute-select! (fn [& _] (throw (ex-info "Failure contained token=hidden." {})))]
          (let [result (connections/validate-routing! directory id routing)]
            (is (false? (:valid result)))
            (is (not (str/includes? (:diagnostic (first (:routes result))) "hidden")))))))))
