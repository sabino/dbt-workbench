(ns dbt-workbench.test-runner
  (:require [clojure.test :as test]
            [dbt-workbench.workbench-test]))
(defn -main [& _]
  (let [result (test/run-tests 'dbt-workbench.workbench-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
