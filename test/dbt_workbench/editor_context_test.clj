(ns dbt-workbench.editor-context-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dbt-workbench.inspection :as inspection]
            [dbt-workbench.query-processor :as processor]
            [dbt-workbench.server :as server]
            [dbt-workbench.storage :as storage])
  (:import (java.nio.file Files)))

(defn with-project! [run!]
  (let [directory (.toFile (Files/createTempDirectory "editor-context-" (make-array java.nio.file.attribute.FileAttribute 0)))
        root (io/file directory "project")]
    (try
      (storage/save-json! (io/file directory "projects.json") {:projects [{:id "example" :name "Example" :path (str root)}]})
      (storage/save-text! (io/file root "dbt_project.yml") "name: example\n")
      (storage/save-text! (io/file root "models/orders.sql") "select 1 as order_id")
      (storage/save-text! (io/file root "macros/clean_name.sql")
                          "{% macro clean_name(value, fallback='a,b') %}{{ value }}{% endmacro %}")
      (storage/save-text! (io/file root "seeds/countries.csv") "country_code\nAA\n")
      (storage/save-text! (io/file root "snapshots/orders_history.sql") "{% snapshot orders_history %}select 1{% endsnapshot %}")
      (storage/save-text! (io/file root "models/schema.yml")
                          "version: 2\nmodels:\n  - name: orders\n    description: Customer orders\n    columns:\n      - name: order_id\n        data_type: INTEGER\n        description: Order key\nseeds:\n  - name: countries\n    columns:\n      - name: country_code\n        data_type: VARCHAR\nmacros:\n  - name: clean_name\n    description: Cleans display names\n    arguments:\n      - name: value\n        type: string\n        description: Input name\nsources:\n  - name: raw\n    tables:\n      - name: orders\n        identifier: upstream_orders\n        description: Raw orders\n        columns:\n          - name: order_id\n            data_type: BIGINT\n")
      (run! directory root)
      (finally (doseq [file (reverse (file-seq directory))] (.delete file))))))

(defn find-resource [context unique-id]
  (first (filter #(= unique-id (:unique_id %)) (:resources context))))

(deftest missing-manifest-uses-static-project-metadata-without-writes
  (with-project!
    (fn [directory _]
      (let [context (inspection/read-editor-context directory "example")
            model (find-resource context "model.example.orders")
            macro (find-resource context "macro.example.clean_name")
            source (find-resource context "source.example.raw.orders")]
        (is (false? (:manifest_available context)))
        (is (= #{"model" "seed" "snapshot" "macro" "source"} (set (map :resource_type (:resources context)))))
        (is (= "Customer orders" (:description model)))
        (is (= [{:name "order_id" :data_type "INTEGER" :description "Order key" :metadata_source "declared"}] (:columns model)))
        (is (= "macros/clean_name.sql" (:path macro)))
        (is (= "Cleans display names" (:description macro)))
        (is (= [{:name "value" :type "string" :description "Input name"} {:name "fallback"}] (:arguments macro)))
        (is (= "models/schema.yml" (:path source)))
        (is (= "raw" (:source_name source)))
        (is (= "upstream_orders" (:identifier source)))
        (is (= "project_files" (:metadata_source context)))
        (is (not (.exists (io/file directory "projects" "example"))))
        (is (nil? (:empty_reason context)))))))

(deftest manifest-and-catalog-provide-whitelisted-editor-fields
  (with-project!
    (fn [directory root]
      (let [model {:unique_id "model.example.orders" :name "orders" :resource_type "model" :package_name "example"
                   :original_file_path "models/orders.sql" :alias "orders_view" :relation_name "example.main.orders_view"
                   :description "Manifest orders" :raw_code "secret code" :compiled_code "secret compiled"
                   :config {:password "secret password"} :columns {:order_id {:name "order_id" :description "Documented key"}}}
            source {:unique_id "source.example.raw.orders" :name "orders" :source_name "raw" :resource_type "source"
                    :package_name "example" :original_file_path "models/schema.yml" :identifier "upstream_orders"
                    :columns {:order_id {:name "order_id" :description "Source key"}}}
            macro {:unique_id "macro.example.clean_name" :name "clean_name" :resource_type "macro" :package_name "example"
                   :original_file_path "macros/clean_name.sql" :description "Manifest macro"
                   :macro_sql "secret macro" :arguments [{:name "value" :type "string" :description "Value" :default "secret default"}]}
            dependency (assoc model :unique_id "model.package_a.orders" :package_name "package_a")]
        (storage/save-json! (io/file directory "projects/example/manifest.json")
                            {:nodes {:model.example.orders model :model.package_a.orders dependency}
                             :sources {:source.example.raw.orders source} :macros {:macro.example.clean_name macro}})
        (storage/save-json! (io/file root "target/catalog.json")
                            {:nodes {:model.example.orders {:columns {:order_id {:name "order_id" :type "BIGINT"}
                                                                     :amount {:name "amount" :type "DECIMAL(10,2)"}}}}
                             :sources {:source.example.raw.orders {:columns {:order_id {:name "order_id" :type "BIGINT"}}}}})
        (let [context (inspection/read-editor-context directory "example")
              resource (find-resource context "model.example.orders")
              remote-package (find-resource context "model.package_a.orders")
              source (find-resource context "source.example.raw.orders")
              macro (find-resource context "macro.example.clean_name")]
          (is (:manifest_available context))
          (is (= "manifest" (:metadata_source resource)))
          (is (= "models/orders.sql" (:path resource)))
          (is (= "orders_view" (:alias resource)))
          (is (= [{:name "amount" :data_type "DECIMAL(10,2)" :metadata_source "catalog"}
                  {:name "order_id" :data_type "BIGINT" :description "Documented key" :metadata_source "declared+catalog"}]
                 (:columns resource)))
          (is (= "package_a" (:package_name remote-package)))
          (is (nil? (:path remote-package)))
          (is (= "BIGINT" (get-in source [:columns 0 :data_type])))
          (is (= [{:name "value" :type "string" :description "Value"}] (:arguments macro)))
          (is (not (.contains (pr-str context) "secret"))))))))

(deftest editor-navigation-rejects-unsafe-missing-and-generated-paths
  (with-project!
    (fn [directory root]
      (storage/save-text! (io/file root "target/generated.sql") "select 1")
      (storage/save-text! (io/file root "dbt_packages/package_a/models/orders.sql") "select 1")
      (storage/save-text! (io/file root "profiles.yml") "secret: hidden")
      (storage/save-text! (io/file directory "outside.sql") "select 1")
      (Files/createSymbolicLink (.toPath (io/file root "models/link.sql")) (.toPath (io/file directory "outside.sql"))
                                (make-array java.nio.file.attribute.FileAttribute 0))
      (let [paths ["../outside.sql" (str (io/file directory "outside.sql")) "target/generated.sql"
                   "dbt_packages/package_a/models/orders.sql" "models/missing.sql" "models/link.sql" "profiles.yml"
                   "models/../models/orders.sql"]
            nodes (into {} (map-indexed (fn [index path]
                                         (let [id (str "model.example.unsafe_" index)]
                                           [(keyword id) {:unique_id id :name (str "unsafe_" index) :resource_type "model"
                                                          :package_name "example" :original_file_path path}])) paths))]
        (storage/save-json! (io/file directory "projects/example/manifest.json") {:nodes nodes})
        (let [context (inspection/read-editor-context directory "example")
              resources (filter #(.startsWith ^String (:name %) "unsafe_") (:resources context))]
          (is (= (count paths) (count resources)))
          (is (every? #(nil? (:path %)) resources))
          (is (not-any? #(= "link" (:name %)) (:resources context))))))))

(deftest editor-context-endpoint-is-local-and-get-only
  (with-project!
    (fn [directory _]
      (with-redefs [processor/read-catalog! (fn [& _] (throw (Exception. "Warehouse access is forbidden.")))
                    processor/convert-query (fn [& _] (throw (Exception. "Compilation is forbidden.")))]
        (let [response (server/handle-api! {:directory directory} "GET" "/api/projects/example/editor-context" {} {})
              body (json/parse-string (:body response) true)]
          (is (= 200 (:status response)))
          (is (seq (:resources body)))
          (is (false? (:manifest_available body))))
        (is (thrown? clojure.lang.ExceptionInfo
                      (server/handle-api! {:directory directory} "POST" "/api/projects/example/editor-context" {} {})))))))

(deftest static-macro-signatures-do-not-split-nested-defaults
  (is (= [{:name "value"} {:name "fallback"} {:name "options"}]
         (inspection/split-macro-arguments "value, fallback='a,b', options=[one, two]"))))

(deftest commented-macro-examples-are-not-editor-resources
  (let [content "{# {% macro commented(value) %}{% endmacro %} #}\n{% raw %}{% macro example(value) %}{% endmacro %}{% endraw %}\n{% macro actual(value) %}{% endmacro %}"]
    (is (= ["actual"] (mapv :name (inspection/discover-editor-blocks content "macros/example.sql" "example" "macro"))))))

(deftest empty-project-context-explains-missing-suggestions
  (with-project!
    (fn [directory root]
      (doseq [path ["models/orders.sql" "models/schema.yml" "macros/clean_name.sql" "seeds/countries.csv"
                    "snapshots/orders_history.sql"]]
        (.delete (io/file root path)))
      (let [context (inspection/read-editor-context directory "example")]
        (is (= [] (:resources context)))
        (is (false? (:manifest_available context)))
        (is (string? (:empty_reason context)))
        (is (not (.exists (io/file directory "projects/example"))))))))

(deftest editor-context-does-not-read-run-or-freshness-artifacts
  (with-project!
    (fn [directory root]
      (storage/save-json! (io/file root "target/run_results.json") {:results []})
      (storage/save-json! (io/file root "target/sources.json") {:results []})
      (let [read-json storage/read-json]
        (with-redefs [storage/read-json (fn [path fallback]
                                         (when (contains? #{"run_results.json" "sources.json"} (.getName (io/file path)))
                                           (throw (Exception. "Unrelated run artifacts are forbidden.")))
                                         (read-json path fallback))]
          (is (seq (:resources (inspection/read-editor-context directory "example")))))))))
