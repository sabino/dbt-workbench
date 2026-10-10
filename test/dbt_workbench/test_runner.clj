(ns dbt-workbench.test-runner
  (:require [clojure.test :as test]
            [dbt-workbench.workbench-test]
            [dbt-workbench.file-creation-test]
            [dbt-workbench.query-conversion-test]
            [dbt-workbench.edn-format-test]
            [dbt-workbench.inspection-test]
            [dbt-workbench.git-test]
            [dbt-workbench.connections-test]
            [dbt-workbench.github-test]
            [dbt-workbench.snowflake-test]
            [dbt-workbench.snow-cli-test]
            [dbt-workbench.federation-test]))
(defn -main [& _]
  (let [result (test/run-tests 'dbt-workbench.workbench-test 'dbt-workbench.file-creation-test 'dbt-workbench.query-conversion-test 'dbt-workbench.edn-format-test 'dbt-workbench.inspection-test 'dbt-workbench.git-test 'dbt-workbench.connections-test 'dbt-workbench.github-test 'dbt-workbench.snowflake-test 'dbt-workbench.snow-cli-test 'dbt-workbench.federation-test)]
    (shutdown-agents)
    (System/exit (if (zero? (+ (:fail result) (:error result))) 0 1))))
