(ns dbt-workbench.federation-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [dbt-workbench.connections :as connections]
            [dbt-workbench.jdbc :as jdbc]
            [dbt-workbench.query-processor.compile :as compiler]
            [dbt-workbench.query-processor.driver :as driver]
            [dbt-workbench.query-processor.federation :as federation]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.workspace :as workspace])
  (:import (java.nio.file Files)))

(defn with-sources! [run!]
  (let [directory (str (Files/createTempDirectory "federation-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
        root (io/file directory "project")
        left-file (io/file root "dev.duckdb")
        right-file (io/file root "extra.duckdb")]
    (try
      (storage/save-text! (io/file root "dbt_project.yml") "name: example\nprofile: example\n")
      (storage/save-text! (io/file root "profiles.yml")
                          "example:\n  target: dev\n  outputs:\n    dev:\n      type: duckdb\n      path: dev.duckdb\n    extra:\n      type: duckdb\n      path: extra.duckdb\n    remote:\n      type: snowflake\n      database: EXAMPLE\n      schema: PUBLIC\n")
      (with-open [connection (jdbc/connect! (str "jdbc:duckdb:" left-file))]
        (jdbc/execute! connection "CREATE TABLE orders (id INTEGER, amount DECIMAL(20,6), memo VARCHAR, placed_at TIMESTAMP)")
        (jdbc/execute! connection "INSERT INTO orders VALUES (1,123456789012.123456,'00123',TIMESTAMP '2026-01-02 03:04:05.123456'),(2,NULL,NULL,NULL)")
        (jdbc/execute! connection "CREATE TABLE too_large AS SELECT range AS id FROM range(50001)")
        (jdbc/execute! connection "CREATE TABLE unsupported AS SELECT [1,2] AS numbers"))
      (with-open [connection (jdbc/connect! (str "jdbc:duckdb:" right-file))]
        (jdbc/execute! connection "CREATE TABLE customers (id INTEGER, name VARCHAR)")
        (jdbc/execute! connection "INSERT INTO customers VALUES (1,'Alice'),(1,'Alice'),(NULL,'Unknown')")
        (jdbc/execute! connection "CREATE SCHEMA PUBLIC")
        (jdbc/execute! connection "CREATE TABLE PUBLIC.CUSTOMERS AS SELECT * FROM customers"))
      (let [project (workspace/open-project! directory (str root) (str root))]
        (run! {:directory directory :project-id (:id project) :project project :target "dev"} right-file))
      (finally
        (doseq [file (reverse (file-seq (io/file directory)))] (.delete file))))))

(defn execute-query! [metadata sql & [options cancel]]
  (let [options (or options {})]
    (federation/execute! (compiler/compile-native sql (or (:parameters options) {})) metadata options
                         (or cancel (atom false)) (fn [_]) driver/run-query!)))

(deftest bounded-cross-source-join-preserves-values
  (with-sources!
    (fn [metadata _]
      (let [result (execute-query! metadata
                                   "SELECT o.id,o.amount,o.memo,o.placed_at,c.name FROM dev.main.orders AS o LEFT JOIN extra.main.customers AS c ON o.id=c.id ORDER BY o.id,c.name")
            source-rows (with-open [connection (connections/open-connection! (:directory metadata) (:project-id metadata) "dev")]
                          (:rows (jdbc/query! connection "SELECT id,amount,memo,placed_at FROM orders ORDER BY id")))]
        (is (= 3 (:row_count result)))
        (is (= [(conj (first source-rows) "Alice") (conj (first source-rows) "Alice") (conj (second source-rows) nil)] (:rows result)))
        (is (= {"dev" 2 "extra" 3} (into {} (map (juxt :connection_id :row_count)) (:captured_relations result))))
        (is (= [50000 50000] (mapv :row_limit (:captured_relations result))))
        (is (= #{"dev" "extra"} (set (map :target (:bindings result)))))
        (is (false? (:truncated result)))
        (is (= [["246913578024.246912"]]
               (:rows (execute-query! metadata "SELECT sum(o.amount) AS total FROM dev.main.orders AS o JOIN extra.main.customers AS c ON o.id=c.id"))))
        (is (= [[1 "Alice"] [1 "Alice"]]
               (:rows (execute-query! metadata
                                      "SELECT o.id,c.name FROM dev.main.orders AS o JOIN extra.main.customers AS c ON o.id=c.id WHERE o.id=:id"
                                      {:parameters {:id 1}}))))
        (let [limited (execute-query! metadata "SELECT * FROM dev.main.orders" {:max_rows 1})]
          (is (= 1 (:row_count limited)))
          (is (:truncated limited)))))))

(deftest snowflake-orchestration-uses-registered-connection
  (with-sources!
    (fn [metadata right-file]
      (let [open! connections/open-connection!
            opened (atom [])]
        ;; A local JDBC stand-in verifies orchestration without claiming a live Snowflake result.
        (with-redefs [connections/open-connection!
                      (fn [directory project-id connection-id]
                        (swap! opened conj connection-id)
                        (if (= "remote" connection-id)
                          (jdbc/connect! (str "jdbc:duckdb:" right-file) {"duckdb.read_only" "true"})
                          (open! directory project-id connection-id)))]
          (let [result (execute-query! metadata "SELECT o.id,c.name FROM dev.main.orders AS o JOIN remote.public.customers AS c ON o.id=c.id")
                remote (first (filter #(= "remote" (:connection_id %)) (:captured_relations result)))]
            (is (= [[1 "Alice"] [1 "Alice"]] (:rows result)))
            (is (= #{"dev" "remote"} (set @opened)))
            (is (= "snowflake" (:source_type remote)))
            (is (= "PUBLIC" (:schema remote)))
            (is (= "CUSTOMERS" (:relation remote)))))))))

(deftest captures-physical-relation-once
  (with-sources!
    (fn [metadata _]
      (let [result (execute-query! metadata "SELECT a.id FROM dev.main.orders AS a JOIN dev.main.orders AS b ON a.id=b.id")]
        (is (= [[1] [2]] (:rows result)))
        (is (= 1 (count (:captured_relations result))))))))

(deftest federation-fails-before-unsafe-or-incomplete-results
  (with-sources!
    (fn [metadata _]
      (testing "oversized input is rejected even when final query is small"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds 50,000"
                              (execute-query! metadata "SELECT count(*) FROM dev.main.too_large"))))
      (testing "unsupported types and ambiguous names require an explicit choice"
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cannot preserve source type"
                              (execute-query! metadata "SELECT * FROM dev.main.unsupported"))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"explicit alias"
                            (execute-query! metadata "SELECT * FROM dev.main.orders JOIN dev.main.orders ON orders.id=orders.id")))
      (is (thrown? Exception (execute-query! metadata "SELECT * FROM unknown.main.orders")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"connection.schema.table"
                            (execute-query! metadata "SELECT * FROM server.dev.main.orders")))
      (is (thrown? Exception (execute-query! metadata "SELECT * FROM read_csv('/tmp/data.csv')")))
      (is (thrown? Exception (execute-query! metadata "DELETE FROM dev.main.orders")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"cancelled"
                            (execute-query! metadata "SELECT * FROM dev.main.orders" {} (atom true))))
      (with-open [connection (connections/open-connection! (:directory metadata) (:project-id metadata) "dev")]
        (is (= [[2]] (:rows (jdbc/query! connection "SELECT count(*) FROM orders"))))))))

(deftest decimal-type-contract
  (is (= "DECIMAL(20,6)" (federation/resolve-column-type {:type "NUMBER" :precision 20 :scale 6})))
  (is (= "DECIMAL(38,12)" (federation/resolve-column-type {:type "DECIMAL(38,12)"})))
  (is (thrown? Exception (federation/resolve-column-type {:type "NUMBER"})))
  (is (thrown? Exception (federation/resolve-column-type {:type "NUMBER" :precision 39 :scale 2}))))
