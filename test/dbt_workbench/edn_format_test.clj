(ns dbt-workbench.edn-format-test
  (:require [clojure.edn :as edn]
            [clojure.test :refer [deftest is]]
            [dbt-workbench.query-processor :as processor]
            [dbt-workbench.validation :as validation]))

(deftest formatting-preserves-standard-edn-values
  (let [content "{:select [[12345678901234567890.123456789012345678M :amount] [123456789012345678901234567890N :identifier]] :where [:= :created #inst \"2026-10-09T00:00:00Z\"] :id #uuid \"00000000-0000-0000-0000-000000000001\"}"
        formatted (processor/format-edn content)]
    (is (= "edn" (:format formatted)))
    (is (= (edn/read-string content) (edn/read-string (:content formatted))))
    (is (.contains (:content formatted) "12345678901234567890.123456789012345678M"))
    (is (.contains (:content formatted) "123456789012345678901234567890N"))
    (is (.contains (:content formatted) "\n"))))

(deftest formatting-rejects-incomplete-or-multiple-values
  (doseq [content ["" "; Only a comment" "{:select [" "{:select [1]} {:select [2]}"
                   "#unknown/tag 1" "#=(System/exit 0)"]]
    (is (thrown? clojure.lang.ExceptionInfo (processor/format-edn content)) content))
  (is (thrown? clojure.lang.ExceptionInfo (validation/validate! :format {:content "{}" :format "sql"})))
  (is (= {:content "{}" :format "edn"} (validation/validate! :format {:content "{}" :format "edn"}))))
