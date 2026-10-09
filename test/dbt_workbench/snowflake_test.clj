(ns dbt-workbench.snowflake-test
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dbt-workbench.connections :as connections]
            [dbt-workbench.connections-test :as connection-fixture]
            [dbt-workbench.jdbc :as jdbc]
            [dbt-workbench.query-processor.compile :as compiler]
            [dbt-workbench.query-processor.driver :as driver]
            [dbt-workbench.query-processor.store :as store]
            [dbt-workbench.snowflake :as snowflake]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.workspace :as workspace]))

(def snowflake-connection
  {:id "snow" :type "snowflake" :account "organization-account" :user "analyst"
   :database "sales" :schema "public" :warehouse "analytics" :role "reader" :password_env "SF_PASSWORD"})
(defn closeable [] (reify java.io.Closeable (close [_])))
(deftest snowflake-authentication-boundary
  (with-redefs [snowflake/read-environment {"SF_PASSWORD" "hidden-password" "SF_TOKEN" "hidden-token"
                                          "SF_KEY" "/private/secret/key.p8" "SF_KEY_PASSWORD" "hidden-key-password"
                                          "SF_ACCOUNT" "organization-account"}]
    (let [{:keys [url properties]} (snowflake/connection-options snowflake-connection)]
      (is (= "jdbc:snowflake://organization-account.snowflakecomputing.com/" url))
      (is (not (str/includes? url "hidden")))
      (is (= "hidden-password" (get properties "password")))
      (is (= "SALES" (get properties "db")))
      (is (= "PUBLIC" (get properties "schema")))
      (is (= "false" (get properties "enablePutGet")))
      (is (= "false" (get properties "CLIENT_TELEMETRY_ENABLED")))
      (is (= "false" (get properties "CLIENT_OUT_OF_BAND_TELEMETRY_ENABLED")))
      (is (= "JSON" (get properties "JDBC_QUERY_RESULT_FORMAT")))
      (is (= "true" (get properties "IMPLICIT_SERVER_SIDE_QUERY_TIMEOUT"))))
    (let [oauth (snowflake/connection-options (-> snowflake-connection (dissoc :password_env) (assoc :token_env "SF_TOKEN")))]
      (is (= "oauth" (get-in oauth [:properties "authenticator"])))
      (is (= "hidden-token" (get-in oauth [:properties "token"]))))
    (let [key (snowflake/connection-options (-> snowflake-connection (dissoc :password_env)
                                                               (assoc :private_key_file_env "SF_KEY" :private_key_file_pwd_env "SF_KEY_PASSWORD")))]
      (is (= "snowflake_jwt" (get-in key [:properties "authenticator"])))
      (is (= "/private/secret/key.p8" (get-in key [:properties "private_key_file"])))
      (is (= "hidden-key-password" (get-in key [:properties "private_key_file_pwd"]))))
    (let [profile {:type "snowflake" :account "{{ env_var('SF_ACCOUNT') }}" :user "analyst"
                   :password "{{ env_var(\"SF_PASSWORD\") }}"}]
      (is (= "hidden-password" (get-in (snowflake/connection-options profile) [:properties "password"]))))
    (doseq [account ["organization-account?password=hidden" "https://attacker.example/path" "localhost:9000" "org..account"]]
      (is (thrown? Exception (snowflake/account-host account))))
    (is (thrown? Exception (snowflake/expand-value "{{ env_var('SF_PASSWORD', 'fallback') }}")))
    (is (thrown? Exception (snowflake/connection-options (assoc snowflake-connection :authenticator "externalbrowser"))))
    (is (thrown? Exception (snowflake/connection-options (assoc snowflake-connection :password_env "ABSENT"))))))

(deftest snowflake-driver-loads-without-network
  (is (= "net.snowflake.client.jdbc.SnowflakeDriver" (.getName ^Class (snowflake/initialize-driver!)))))

(deftest saved-snowflake-connections-never-store-credentials
  (connection-fixture/with-project!
    (fn [directory id _]
      (with-redefs [snowflake/read-environment (constantly "hidden")
                    jdbc/connect! (fn [& _] (throw (ex-info "Network must not be called when saving." {})))]
        (is (= snowflake-connection (connections/save-connection! directory id snowflake-connection)))
        (let [config (slurp (io/file (workspace/project-data directory id) "connections.json"))
              listed (first (filter #(= "snow" (:id %)) (connections/list-connections directory id)))]
          (is (not (str/includes? config "hidden")))
          (is (not (str/includes? (pr-str listed) "hidden")))
          (is (not (contains? listed :password_env)))
          (is (:supported listed)))
        (doseq [field [:password :token :private_key_file :private_key_file_pwd]]
          (is (thrown? Exception (connections/save-connection! directory id (assoc snowflake-connection field "hidden"))))))
      (with-redefs [snowflake/connect! (fn [_] (throw (ex-info "Error contained hidden-password." {})))]
        (let [health (connections/test-connection! directory id "snow")]
          (is (false? (:healthy health)))
          (is (not (str/includes? (pr-str health) "hidden-password")))))
      (with-redefs [snowflake/connect! (fn [_] (closeable))
                    jdbc/query! (fn [& _] {:rows [[1]]})
                    snowflake/read-columns! (fn [_] [{:table_catalog "SALES" :table_schema "PUBLIC" :table_name "ORDERS" :column_name "AMOUNT" :data_type "NUMBER"}])]
        (let [health (connections/test-connection! directory id "snow")]
          (is (:healthy health))
          (is (false? (:read_only health)))
          (is (:select_only health)))
        (is (= "SALES" (-> (connections/read-schema! directory id "snow") :relations first :database)))))))

(deftest snowflake-query-and-routing-boundary
  (connection-fixture/with-project!
    (fn [directory id _]
      (connections/save-connection! directory id snowflake-connection)
      (connections/save-connection! directory id (assoc snowflake-connection :id "snow-baseline" :database "historical"))
      (let [captured (atom [])
            run! (fn [query]
                   (let [metadata (store/create-store directory id {:target "snow"})
                         compiled (compiler/compile query metadata {:parameters {:amount 7}} false)]
                     (driver/execute! compiled metadata {:dialect "snowflake" :parameters {:amount 7}} (atom false) (fn [_]))))]
        (with-redefs [snowflake/connect! (fn [_] (closeable))
                      jdbc/query! (fn [& _] {:rows [["SALES"]]})
                      driver/run-query! (fn [_ sql parameters rows timeout _ _ & _]
                                          (swap! captured conj {:sql sql :parameters parameters :rows rows :timeout timeout})
                                          {:rows [[7]] :columns [{:name "AMOUNT" :type "NUMBER"}] :row_count 1 :truncated false})]
          (let [native (run! {:sql "SELECT orders.amount FROM orders WHERE orders.amount = :amount"})]
            (is (= [7] (:parameters (last @captured))))
            (is (str/includes? (:sql native) "\"SALES\".\"PUBLIC\".\"ORDERS\""))
            (is (= "snow" (:target (first (:bindings native)))))
            (is (= "SALES" (:database (first (:bindings native))))))
          (let [honey (run! {:select [:amount] :from [:orders] :where [:= :amount [:param :amount]]})]
            (is (= [7] (:parameters (last @captured))))
            (is (str/includes? (:sql honey) "\"ORDERS\"")))
          (connections/save-routing! directory id {:version 1 :routes [{:model_id "model.example.orders" :target "snow-baseline"}]})
          (let [routed (run! {:sql "SELECT amount FROM orders WHERE amount = :amount"})]
            (is (str/includes? (:sql routed) "\"HISTORICAL\".\"PUBLIC\".\"ORDERS\""))
            (is (= "snow-baseline" (:target (first (:bindings routed))))))
          (let [metadata (store/create-store directory id {:target "snow"})]
            (is (thrown? Exception (driver/execute! {:sql "DELETE FROM orders"} metadata {:dialect "snowflake"} (atom false) (fn [_]))))
            (is (thrown? Exception (driver/execute! {:sql "SELECT 1"} metadata {:dialect "duckdb"} (atom false) (fn [_]))))
            (is (thrown? Exception (driver/execute! {:sql "SELECT 1"} metadata {:dialect "snowflake"} (atom true) (fn [_]))))))
        (connections/save-connection! directory id (assoc snowflake-connection :id "other-account" :account "other-account"))
        (storage/save-json! (io/file (workspace/project-data directory id) "routing.json")
                            {:version 1 :routes [{:model_id "model.example.orders" :target "other-account"}]})
        (is (thrown? Exception (store/resolve-source (store/create-store directory id {:target "snow"}) {:model "orders"} false)))))))
