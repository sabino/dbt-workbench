(ns dbt-workbench.query-conversion-test
  (:require [clojure.test :refer [deftest is]]
            [dbt-workbench.jdbc :as jdbc]
            [dbt-workbench.query-processor :as processor]
            [dbt-workbench.query-processor.compile :as compiler]
            [dbt-workbench.query-processor.parse :as parse]
            [dbt-workbench.query-processor.store :as store]))

(defn convert [options]
  (with-redefs [store/create-store (fn [& _] {})]
    (processor/convert-query nil nil options)))

(defn execute-saved-sql [sql]
  (let [compiled (compiler/compile-native sql {})]
    (with-open [connection (jdbc/connect! "jdbc:duckdb:")]
      (:rows (jdbc/query! connection (:sql compiled) (:params compiled))))))

(deftest saved-sql-literals-survive-reopening
  (let [source "{:select [[8 :answer] [\"O'Reilly :p0 ?\" :label] [nil :missing] [true :enabled]]}"
        ordinary (convert {:format "edn" :content source})
        saved (convert {:format "edn" :content source :save_format "sql"})]
    (is (seq (:parameters ordinary)))
    (is (= {} (:parameters saved)))
    (is (= [[8 "O'Reilly :p0 ?" nil true]] (execute-saved-sql (:content saved))))
    (is (not (.contains (:content saved) "SELECT :p0")))))

(deftest generated-sql-bindings-survive-native-sql-save
  (let [generated (convert {:format "edn" :content "{:select [[8 :answer]]}"})
        saved (convert {:format "sql" :content (:content generated)
                        :parameters (:parameters generated) :save_format "sql"})]
    (is (= {} (:parameters saved)))
    (is (= [[8]] (execute-saved-sql (:content saved))))))

(deftest file-save-preserves-exact-unbound-sql
  (let [sql "-- Keep this comment and spacing\nselect  7 AS answer;\n"
        saved (convert {:format "sql" :content sql :save_format "sql"})]
    (is (= sql (:content saved)))
    (is (= {} (:parameters saved)))
    (is (= [[7]] (execute-saved-sql (:content saved))))))

(deftest native-edn-save-preserves-values-in-the-file
  (let [generated (convert {:format "edn" :content "{:select [[9 :answer]]}"})
        saved (convert {:format "sql" :content (:content generated)
                        :parameters {:p0 10} :save_format "edn"})
        reopened (parse/parse-edn (:content saved))
        sql (convert {:format "edn" :content (:content saved) :save_format "sql"})]
    (is (= {:select [[10 :answer]]} reopened))
    (is (= {} (:parameters saved)))
    (is (= [[10]] (execute-saved-sql (:content sql)))))
  (let [edn "{:select [[42 :answer]]}\n"
        saved (convert {:format "edn" :content edn :save_format "edn"})]
    (is (= edn (:content saved)))))

(deftest named-bindings-inline-safely-and-validate-exactly
  (let [sql "SELECT :name AS label, ':name' AS literal, :missing AS missing, :enabled AS enabled"
        saved (convert {:format "sql" :content sql :parameters {:name "O'Reilly" :missing nil :enabled false}
                        :save_format "sql"})]
    (is (= [["O'Reilly" ":name" nil false]] (execute-saved-sql (:content saved)))))
  (is (thrown? Exception (convert {:format "sql" :content "SELECT :required" :save_format "sql"})))
  (is (thrown? Exception (convert {:format "sql" :content "SELECT 1" :parameters {:extra 2} :save_format "sql"})))
  (is (thrown? Exception (convert {:format "sql" :content "SELECT :value" :parameters {:value {:nested true}}
                                 :save_format "sql"}))))

(deftest conversion-refuses-ambiguous-quoted-identifiers
  (let [sql "SELECT \"a.b\" FROM \"x.y\""]
    (is (thrown? clojure.lang.ExceptionInfo (convert {:format "sql" :content sql})))
    (is (= sql (:content (convert {:format "sql" :content sql :save_format "sql"}))))))

(deftest decimal-conversion-preserves-exact-values
  (doseq [literal ["1.1" "1.5" "123456789012.123456" "0.000000000000000001"]]
    (let [sql (str "SELECT " literal " AS amount")
          converted (convert {:format "sql" :content sql})
          query (parse/parse-edn (:content converted))
          value (first (first (:select query)))
          compiled (compiler/compile query {} {} false)
          saved (convert {:format "edn" :content (:content converted) :save_format "sql"})
          native-rows (execute-saved-sql sql)
          edn-rows (with-open [connection (jdbc/connect! "jdbc:duckdb:")]
                     (:rows (jdbc/query! connection (:sql compiled) (:params compiled))))]
      (is (instance? java.math.BigDecimal value))
      (is (= (bigdec literal) value))
      (is (.contains (:content converted) "M"))
      (is (= native-rows edn-rows))
      (is (= native-rows (execute-saved-sql (:content saved)))))))

(deftest comma-limit-offset-is-preserved
  (let [converted (convert {:format "sql" :content "SELECT * FROM orders LIMIT 10, 20"})
        query (parse/parse-edn (:content converted))
        saved (convert {:format "edn" :content (:content converted) :save_format "sql"})]
    (is (= 20 (:limit query)))
    (is (= 10 (:offset query)))
    (is (.contains (:content saved) "LIMIT 20"))
    (is (.contains (:content saved) "OFFSET 10"))))

(deftest multi-statement-parameters-are-scoped-to-their-statement
  (let [converted (convert {:format "sql" :content "SELECT :x AS x; SELECT :y AS y"})
        ordinary (convert {:format "edn" :content (:content converted) :parameters {:x 1 :y 2}})
        saved (convert {:format "edn" :content (:content converted) :parameters {:x 1 :y 2} :save_format "sql"})]
    (is (= {:p0 1 :p1 2} (:parameters ordinary)))
    (is (= {} (:parameters saved)))
    (is (= 2 (count (parse/parse-sql (:content saved)))))
    (is (thrown? Exception (convert {:format "edn" :content (:content converted)
                                   :parameters {:x 1 :y 2 :extra 3} :save_format "sql"})))))

(deftest quoted-sql-strings-preserve-their-value
  (let [edn (convert {:format "sql" :content "SELECT 'O''Reilly' AS label"})
        saved (convert {:format "edn" :content (:content edn) :save_format "sql"})]
    (is (= {:select [["O'Reilly" :label]]} (parse/parse-edn (:content edn))))
    (is (= [["O'Reilly"]] (execute-saved-sql (:content saved))))))

(deftest decimal-conversion-refuses-unsupported-numeric-semantics
  (doseq [sql ["SELECT 1E-18 AS amount" "SELECT 0.000000000000000000000000000000000000001 AS amount"]]
    (is (thrown? clojure.lang.ExceptionInfo (convert {:format "sql" :content sql})))
    (is (= sql (:content (convert {:format "sql" :content sql :save_format "sql"}))))))

(deftest saved-floating-point-literals-preserve-jdbc-types
  (doseq [value [0.1 123456789012.12346]]
    (let [query {:select [[value :amount]]}
          compiled (compiler/compile query {} {} false)
          saved (convert {:format "edn" :content (pr-str query) :save_format "sql"})]
      (with-open [connection (jdbc/connect! "jdbc:duckdb:")]
        (let [native (jdbc/query! connection (:sql compiled) (:params compiled))
              reopened (jdbc/query! connection (:content saved))]
          (is (= (:rows native) (:rows reopened)))
          (is (= "DOUBLE" (:type (first (:columns reopened)))))))))
  (doseq [[value expected-type] [[0.1 "DOUBLE"] [(float 0.1) "FLOAT"]]]
    (let [query {:select [[[:param :amount] :amount]]}
          compiled (compiler/compile query {} {:parameters {:amount value}} false)
          saved (convert {:format "edn" :content (pr-str query) :parameters {:amount value} :save_format "sql"})]
      (with-open [connection (jdbc/connect! "jdbc:duckdb:")]
        (let [native (jdbc/query! connection (:sql compiled) (:params compiled))
              reopened (jdbc/query! connection (:content saved))]
          (is (= (:rows native) (:rows reopened)))
          (is (= expected-type (:type (first (:columns native)))))
          (is (= expected-type (:type (first (:columns reopened))))))))))

(deftest dollar-quoted-sql-requires-sql-editing
  (doseq [sql ["SELECT $$O''Reilly$$ AS label" "SELECT $tag$literal$tag$ AS label"]]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dollar-quoted strings"
                          (convert {:format "sql" :content sql})))
    (is (= sql (:content (convert {:format "sql" :content sql :save_format "sql"}))))))

(deftest saved-sql-floating-bindings-preserve-jdbc-types
  (doseq [[value expected-type] [[0.1 "DOUBLE"] [(float 0.1) "FLOAT"]]]
    (let [saved (convert {:format "sql" :content "SELECT :amount AS amount"
                          :parameters {:amount value} :save_format "sql"})]
      (with-open [connection (jdbc/connect! "jdbc:duckdb:")]
        (let [native (jdbc/query! connection "SELECT ? AS amount" [value])
              reopened (jdbc/query! connection (:content saved))]
          (is (= (:rows native) (:rows reopened)))
          (is (= expected-type (:type (first (:columns native)))))
          (is (= expected-type (:type (first (:columns reopened))))))))))
