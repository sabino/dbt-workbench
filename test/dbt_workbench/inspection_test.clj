(ns dbt-workbench.inspection-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dbt-workbench.inspection :as inspection]
            [dbt-workbench.runtime :as runtime]
            [dbt-workbench.storage :as storage])
  (:import (java.nio.file Files)))

(def manifest
  {:nodes {:model.shop.orders {:unique_id "model.shop.orders" :name "orders" :resource_type "model"
                              :description "Customer orders" :tags ["finance"] :config {:materialized "table" :meta {:owner "Data team"}}
                              :columns {:order_id {:name "order_id" :description "Primary key" :data_type "INTEGER"}}
                              :depends_on {:nodes ["source.shop.raw.orders"]}}
           :test.shop.orders_unique {:unique_id "test.shop.orders_unique" :name "orders_unique" :resource_type "test"
                                     :column_name "order_id" :test_metadata {:name "unique"}
                                     :depends_on {:nodes ["model.shop.orders"]}}}
   :sources {:source.shop.raw.orders {:unique_id "source.shop.raw.orders" :name "orders" :resource_type "source"}}})
(def catalog {:nodes {:model.shop.orders {:columns {:order_id {:name "order_id" :type "INTEGER" :index 1}
                                                        :amount {:name "amount" :type "DECIMAL(10,2)" :index 2}}}}})
(def run-results {:metadata {:invocation_id "real-fixture-invocation"} :elapsed_time 0.2
                  :results [{:unique_id "test.shop.orders_unique" :status "fail" :failures 2 :message "Found 2 results"}]})

(deftest manifest-lineage
  (let [graph (inspection/build-lineage manifest run-results)]
    (is (= 3 (count (:nodes graph))))
    (is (= [{:source "source.shop.raw.orders" :target "model.shop.orders"}
            {:source "model.shop.orders" :target "test.shop.orders_unique"}] (:edges graph)))
    (is (= "fail" (:status (last (:nodes graph)))))
    (is (nil? (:empty_reason graph))))
  (is (string? (:empty_reason (inspection/build-lineage {} {})))))

(deftest documented-and-observed-columns
  (let [docs (inspection/build-model-docs manifest catalog run-results "model.shop.orders")]
    (is (= "Data team" (:owner docs)))
    (is (= ["finance"] (:tags docs)))
    (is (= ["source.shop.raw.orders"] (:depends_on docs)))
    (is (= ["test.shop.orders_unique"] (:dependents docs)))
    (is (= ["amount" "order_id"] (mapv :name (:columns docs))))
    (is (= "DECIMAL(10,2)" (:data_type (first (:columns docs)))))
    (is (= "Primary key" (:description (second (:columns docs)))))
    (is (= {:total 1 :statuses {"fail" 1}} (:test_summary docs)))
    (is (= 2 (get-in docs [:tests 0 :result :failures]))))
  (is (= "not_run" (get-in (inspection/build-model-docs manifest {} {} "model.shop.orders") [:tests 0 :status])))
  (is (thrown? clojure.lang.ExceptionInfo (inspection/build-model-docs {} {} {} "model.shop.orders"))))

(deftest inspect-stored-and-failed-run-artifacts
  (let [directory (.toFile (Files/createTempDirectory "inspection-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
        project-root (io/file directory "project")
        data (io/file directory "projects" "shop")
        artifacts (io/file data "jobs" "failed-build" "build-dev" "artifacts")
        job {:id "failed-build" :project_id "shop" :kind "build" :status "failed" :summary {}}]
    (try
      (storage/save-json! (io/file directory "projects.json") {:projects [{:id "shop" :path (str project-root)}]})
      (storage/save-text! (io/file project-root "dbt_project.yml") "name: shop\n")
      (is (= [] (:nodes (inspection/read-lineage directory "shop"))))
      (is (not (.exists data)))
      (storage/save-json! (io/file data "manifest.json") manifest)
      (storage/save-json! (io/file artifacts "manifest.json") manifest)
      (storage/save-json! (io/file artifacts "catalog.json") catalog)
      (storage/save-json! (io/file artifacts "run_results.json") run-results)
      (storage/save-json! (io/file artifacts "sources.json") {:results [{:unique_id "source.shop.raw.orders" :status "pass"}]})
      (is (= "Data team" (:owner (inspection/read-model directory "shop" "model.shop.orders"))))
      (is (= 3 (count (:resources (inspection/read-catalog directory "shop")))))
      (is (= "pass" (get-in (inspection/read-resource directory "shop" "source.shop.raw.orders") [:freshness :status])))
      (with-redefs [runtime/find-job (fn [_ _] job)]
        (let [details (inspection/read-job directory "shop" {} "failed-build")]
          (is (= 4 (count (get-in details [:runs 0 :artifacts]))))
          (is (= "fail" (get-in details [:tests 0 :status])))
          (is (= "unique" (get-in details [:tests 0 :test_metadata :name])))
          (is (= "failed" (get-in details [:job :status])))))
      (with-redefs [runtime/find-job (fn [_ _] (assoc job :project_id "other"))]
        (is (thrown? clojure.lang.ExceptionInfo (inspection/read-job directory "shop" {} "failed-build"))))
      (finally (doseq [file (reverse (file-seq directory))] (.delete file))))))

(def inventory-manifest
  (-> manifest
      (assoc-in [:nodes :model.shop.orders :depends_on :macros] ["macro.shop.clean_name"])
      (assoc-in [:nodes :seed.shop.country_codes]
                {:unique_id "seed.shop.country_codes" :name "country_codes" :resource_type "seed" :original_file_path "seeds/country_codes.csv"})
      (assoc-in [:sources :source.shop.raw.orders :columns]
                {:order_id {:name "order_id" :description "Order identifier"}})
      (assoc-in [:sources :source.shop.raw.orders :config :freshness] {:warn_after {:count 12 :period "hour"}})
      (assoc :macros {:macro.shop.clean_name {:unique_id "macro.shop.clean_name" :name "clean_name" :resource_type "macro"
                                            :original_file_path "macros/clean_name.sql" :macro_sql "{% macro clean_name(value) %}trim({{ value }}){% endmacro %}"
                                            :arguments [{:name "value" :type "string"}]}}
             :exposures {:exposure.shop.orders_dashboard {:unique_id "exposure.shop.orders_dashboard" :name "orders_dashboard" :resource_type "exposure"
                                                         :owner {:name "Analytics" :email "analytics@example.test"} :type "dashboard"
                                                         :depends_on {:nodes ["model.shop.orders"]}}}
             :metrics {:metric.shop.revenue {:unique_id "metric.shop.revenue" :name "revenue" :resource_type "metric" :type "simple"
                                            :depends_on {:nodes ["model.shop.orders"]}}}
             :semantic_models {:semantic_model.shop.orders {:unique_id "semantic_model.shop.orders" :name "orders" :resource_type "semantic_model"}}
             :saved_queries {:saved_query.shop.revenue {:unique_id "saved_query.shop.revenue" :name "revenue" :resource_type "saved_query"}}
             :docs {:doc.shop.overview {:unique_id "doc.shop.overview" :name "overview" :block_contents "Order project documentation"}})))

(def freshness {:results [{:unique_id "source.shop.raw.orders" :status "warn" :max_loaded_at "2026-10-08T10:00:00Z"
                          :snapshotted_at "2026-10-09T10:00:00Z" :max_loaded_at_time_ago_in_s 86400}]})

(deftest catalog-includes-resource-types-without-changing-lineage
  (let [resources (:resources (inspection/build-catalog inventory-manifest run-results freshness))]
    (is (= 10 (count resources)))
    (is (= #{"model" "test" "source" "macro" "seed" "exposure" "metric" "semantic_model" "saved_query" "doc"}
           (set (map :resource_type resources))))
    (is (= "warn" (:freshness_status (first (filter #(= "source" (:resource_type %)) resources)))))
    (is (not-any? #(= "macro" (:resource_type %)) (:nodes (inspection/build-lineage inventory-manifest run-results)))))
  (is (string? (:empty_reason (inspection/build-catalog {} {} {})))))

(deftest resource-documentation-uses-artifact-fields
  (let [docs #(inspection/build-resource-docs inventory-manifest catalog run-results freshness %)
        source (docs "source.shop.raw.orders")
        macro (docs "macro.shop.clean_name")
        exposure (docs "exposure.shop.orders_dashboard")
        model (docs "model.shop.orders")]
    (is (= "warn" (get-in source [:freshness :status])))
    (is (= 12 (get-in source [:freshness_config :warn_after :count])))
    (is (= "Order identifier" (get-in source [:columns 0 :description])))
    (is (= ["model.shop.orders"] (:dependents source)))
    (is (= "macros/clean_name.sql" (:path macro)))
    (is (= (get-in inventory-manifest [:macros :macro.shop.clean_name :macro_sql]) (:code macro)))
    (is (= ["model.shop.orders"] (:dependents macro)))
    (is (= {:name "Analytics" :email "analytics@example.test"} (:owner exposure)))
    (is (= ["model.shop.orders"] (:dependencies exposure)))
    (is (= ["source.shop.raw.orders" "macro.shop.clean_name"] (:dependencies model)))
    (is (= "Data team" (:owner model)))
    (is (= "fail" (:status (docs "test.shop.orders_unique"))))
    (is (= "Order project documentation" (:code (docs "doc.shop.overview"))))
    (is (= [] (:columns (docs "seed.shop.country_codes"))))
    (is (nil? (:status (docs "seed.shop.country_codes"))))
    (is (nil? (:freshness (inspection/build-resource-docs inventory-manifest {} {} {} "source.shop.raw.orders")))))
  (is (thrown? clojure.lang.ExceptionInfo (inspection/build-resource-docs inventory-manifest {} {} {} "missing.shop.resource"))))
