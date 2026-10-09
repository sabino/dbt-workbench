(ns dbt-workbench.snow-cli-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dbt-workbench.snow-cli :as snow-cli]))

(def cli-output {:type "snowflake" :transport "snow_cli" :cli_connection "analytics"})
(deftest cli-metadata-redaction
  (let [listed (snow-cli/read-configured
                "[{\"connection_name\":\"analytics\",\"parameters\":{\"account\":\"organization-account\",\"user\":\"analyst\",\"database\":\"EXAMPLE_DEV\",\"warehouse\":\"WH\",\"role\":\"READER\",\"authenticator\":\"OAUTH_AUTHORIZATION_CODE\",\"password\":\"hidden-password\",\"token\":\"hidden-token\",\"oauth_client_secret\":\"hidden-secret\",\"private_key_file\":\"hidden-path\"},\"is_default\":true}]")]
    (is (= [{:id "analytics" :name "analytics" :type "snowflake" :transport "snow_cli" :cli_connection "analytics"
             :account "organization-account" :user "analyst" :database "EXAMPLE_DEV" :warehouse "WH" :role "READER"}] listed))
    (is (not (str/includes? (pr-str listed) "hidden"))))
  (let [called (atom nil)]
    (with-redefs [snow-cli/run-process! (fn [args input timeout cancel]
                                        (reset! called {:args args :input input :timeout timeout :cancel @cancel})
                                        {:exit 0 :out "[]"})]
      (is (= [] (snow-cli/list-configured!)))
      (is (= ["snow" "connection" "list" "--format" "JSON" "--silent"] (:args @called)))
      (is (nil? (:input @called)))
      (is (= 15 (:timeout @called))))))

(deftest cli-safe-parameter-literals
  (is (= "SELECT 'Robert''); DROP TABLE users; --' AS name, TRUE, NULL, 123456789012345678901234567890"
         (snow-cli/render-parameters "SELECT :name AS name, :enabled, :nothing, :number"
                                     {:name "Robert'); DROP TABLE users; --" :enabled true :nothing nil :number 123456789012345678901234567890N})))
  (is (thrown? Exception (snow-cli/render-parameters "SELECT ?, :name" {:name "safe"})))
  (is (= "SELECT ':name', 1::INTEGER, 'safe'" (snow-cli/render-parameters "SELECT ':name', 1::INTEGER, :name" {:name "safe"})))
  (is (= "SELECT 1, 'two'" (snow-cli/render-parameters "SELECT ?, ?" [1 "two"])))
  (is (= "SELECT 1.12345678901234567890123456789012345678" (snow-cli/render-parameters "SELECT ?" [1.12345678901234567890123456789012345678M])))
  (is (thrown? Exception (snow-cli/render-parameters "SELECT :name" {})))
  (is (thrown? Exception (snow-cli/render-parameters "SELECT ?" [##NaN])))
  (is (thrown? Exception (snow-cli/render-parameters "SELECT ?" [(str "x" (char 0))]))))

(deftest cli-select-bounds
  (is (str/ends-with? (snow-cli/bound-select "SELECT 1" {} 2) "LIMIT 3"))
  (is (str/ends-with? (snow-cli/bound-select "SELECT 1 LIMIT 1" {} 2) "LIMIT 1"))
  (is (str/includes? (snow-cli/bound-select "SELECT 1 ORDER BY 1 LIMIT 200 OFFSET 2" {} 2) "ORDER BY 1 LIMIT 3 OFFSET 2"))
  (is (str/starts-with? (snow-cli/bound-select "WITH x AS (SELECT 1 AS n) SELECT n FROM x" {} 2) "WITH x AS"))
  (doseq [sql ["DELETE FROM orders" "CREATE TABLE x AS SELECT 1" "PUT file:///tmp/data @stage" "GET @stage file:///tmp/data"
               "SELECT 1; SELECT 2" "SELECT read_csv('/tmp/data')" "SELECT nextval('sequence')" "SELECT 1 !source /tmp/data" "SELECT 1 LIMIT -1"]]
    (is (thrown? Exception (snow-cli/bound-select sql {} 2)) sql)))

(deftest cli-json-column-order-and-numeric-digits
  (let [json "[{\"Z\":123456789012345678901234567890,\"Y\":1.12345678901234567890123456789012345678,\"X\":true,\"W\":null,\"V\":\"text\",\"U\":2,\"T\":3,\"S\":4,\"R\":5,\"Q\":6},{\"Q\":7,\"R\":8,\"S\":9,\"T\":10,\"U\":11,\"V\":\"next\",\"W\":null,\"X\":false,\"Y\":2.5,\"Z\":12}]"
        result (snow-cli/read-result json 1)]
    (is (= ["Z" "Y" "X" "W" "V" "U" "T" "S" "R" "Q"] (mapv :name (:columns result))))
    (is (= "1.12345678901234567890123456789012345678" (second (first (:rows result)))))
    (is (= "123456789012345678901234567890" (ffirst (:rows result))))
    (is (= "DECIMAL" (-> result :columns second :type)))
    (is (= "UNKNOWN" (get-in result [:columns 3 :type])))
    (is (every? #(= "inferred" (:type_source %)) (:columns result)))
    (is (:truncated result))
    (is (= 1 (:row_count result))))
  (is (= [] (:columns (snow-cli/read-result "[]" 10))))
  (is (thrown? Exception (snow-cli/read-result "[{\"X\":1,\"X\":2}]" 10)))
  (is (thrown? Exception (snow-cli/read-result "[{\"X\":1},{\"Y\":2}]" 10))))

(deftest cli-execution-boundary-no-network
  (let [call (atom nil)]
    (with-redefs [snow-cli/run-process! (fn [args input timeout cancel]
                                        (reset! call {:args args :input input :timeout timeout :cancel @cancel})
                                        {:exit 0 :out "[{\"value\":7},{\"value\":8}]"})]
      (let [result (snow-cli/execute-select! cli-output "SELECT :value AS value" {:value 7} {:max_rows 1 :timeout_seconds 5} (atom false))]
        (is (= [[7]] (:rows result)))
        (is (:truncated result))
        (is (= "snow_cli" (:transport result)))
        (is (str/includes? (:input @call) "LIMIT 2"))
        (is (= 5 (:timeout @call)))
        (is (= "--connection=analytics" (nth (:args @call) 2)))
        (is (some #{"--stdin"} (:args @call)))
        (is (some #{"NONE"} (:args @call)))
        (is (not-any? #{"--password" "--token" "--debug" "--single-transaction"} (:args @call)))))
    (with-redefs [snow-cli/run-process! (fn [& _] {:exit 1 :out "https://login.example?token=hidden"})]
      (let [message (try (snow-cli/execute-select! cli-output "SELECT 1" {} {} (atom false))
                         (catch Exception error (.getMessage error)))]
        (is (not (str/includes? message "hidden")))
        (is (not (str/includes? message "https://")))))))

(deftest cli-local-process-timeout-and-cancellation
  (let [cancel (atom false)
        result (future (try (snow-cli/run-process! ["/bin/sleep" "3"] nil 5 cancel)
                            (catch Exception error (.getMessage error))))]
    (Thread/sleep 50)
    (reset! cancel true)
    (is (str/includes? (deref result 3000 "Cancellation timed out") "cancelled")))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"time limit" (snow-cli/run-process! ["/bin/sleep" "3"] nil 0.05 (atom false)))))
