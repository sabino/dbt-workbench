(ns dbt-workbench.inspection
  (:require [clojure.java.io :as io]
            [dbt-workbench.runtime :as runtime]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.workspace :as workspace]))

(def artifact-names ["manifest.json" "catalog.json" "run_results.json" "sources.json"])

(defn list-artifact-directories [directory project-id]
  (let [project (workspace/find-project directory project-id)
        config (workspace/read-yaml (io/file (:path project) "dbt_project.yml"))
        jobs (io/file directory "projects" project-id "jobs")]
    (into [(io/file (:path project) (or (:target-path config) "target"))]
          (for [job (.listFiles jobs) :when (.isDirectory job)
                run (.listFiles job) :when (.isDirectory run)]
            (io/file run "artifacts")))))

(defn read-latest-artifact [directories artifact-name]
  (when-let [file (last (sort-by (juxt #(.lastModified %) str)
                               (filter #(.isFile %) (map #(io/file % artifact-name) directories))))]
    {:path (str file) :data (storage/read-json file {})}))

(defn read-project-artifacts [directory project-id]
  (let [directories (list-artifact-directories directory project-id)
        saved (io/file directory "projects" project-id "manifest.json")]
    {:manifest (if (.isFile saved)
                 {:path (str saved) :data (storage/read-json saved {})}
                 (read-latest-artifact directories "manifest.json"))
     :catalog (read-latest-artifact directories "catalog.json")
     :freshness (read-latest-artifact directories "sources.json")
     :run_results (read-latest-artifact directories "run_results.json")}))

(defn list-resources [manifest]
  (->> (concat (vals (:nodes manifest)) (vals (:sources manifest)) (vals (:exposures manifest)) (vals (:metrics manifest)))
       (sort-by :unique_id) vec))

(def resource-collections
  [:nodes :sources :macros :exposures :metrics :semantic_models :saved_queries :unit_tests :groups :docs])

(defn list-catalog-resources [manifest]
  (->> resource-collections
       (mapcat #(vals (get manifest %)))
       (filter :unique_id)
       (sort-by :unique_id) vec))

(defn list-dependencies [node]
  (vec (distinct (concat (get-in node [:depends_on :nodes]) (get-in node [:depends_on :macros])))))

(defn find-owner [node]
  (or (:owner node) (get-in node [:meta :owner]) (get-in node [:config :meta :owner]) (get-in node [:config :owner])))

(defn build-resource-metadata [node]
  (merge (select-keys node [:unique_id :name :description :original_file_path :package_name :database :schema :alias :relation_name])
         {:resource_type (or (:resource_type node) (first (.split ^String (:unique_id node) "\\.")))
          :description (:description node)
          :path (:original_file_path node)
          :tags (vec (or (:tags node) (get-in node [:config :tags])))
          :owner (find-owner node)}))

(defn index-results [run-results]
  (into {} (map (juxt :unique_id identity) (:results run-results))))

(defn summarize-tests [tests]
  {:total (count tests)
   :statuses (frequencies (map :status tests))})

(defn list-tests [manifest run-results model-id]
  (let [results (index-results run-results)]
    (->> (vals (:nodes manifest))
         (filter #(and (= "test" (:resource_type %)) (some #{model-id} (get-in % [:depends_on :nodes]))))
         (sort-by :unique_id)
         (mapv (fn [node]
                 (merge (select-keys node [:unique_id :name :description :column_name :test_metadata :original_file_path])
                        {:status (get-in results [(:unique_id node) :status] "not_run")
                         :result (results (:unique_id node))}))))))

(defn build-lineage [manifest run-results]
  (let [resources (list-resources manifest)
        ids (set (map :unique_id resources))
        results (index-results run-results)]
    {:nodes (mapv (fn [node]
                    (merge (select-keys node [:unique_id :name :resource_type :description :tags :original_file_path :database :schema :alias])
                           {:materialized (get-in node [:config :materialized])
                            :status (get-in results [(:unique_id node) :status])})) resources)
     :edges (vec (for [node resources dependency (get-in node [:depends_on :nodes]) :when (ids dependency)]
                   {:source dependency :target (:unique_id node)}))
     :empty_reason (when (empty? resources) "No manifest resources are available. Parse or build the project to create a manifest.")}))

(defn read-lineage
  ([directory project-id]
   (let [artifacts (read-project-artifacts directory project-id)]
     (assoc (build-lineage (get-in artifacts [:manifest :data]) (get-in artifacts [:run_results :data]))
            :manifest_path (get-in artifacts [:manifest :path])
            :run_results_path (get-in artifacts [:run_results :path]))))
  ([directory project-id _runtime] (read-lineage directory project-id)))

(defn list-columns [node catalog-node]
  (let [documented (:columns node) observed (:columns catalog-node)
        names (sort (set (concat (map name (keys documented)) (map name (keys observed)))))]
    (mapv (fn [column-name]
            (let [docs (get documented (keyword column-name)) actual (get observed (keyword column-name))]
              {:name column-name :description (:description docs)
               :data_type (or (:data_type docs) (:type actual))
               :tags (vec (:tags docs)) :meta (:meta docs)
               :index (:index actual) :stats (:stats actual)})) names)))

(defn build-model-docs [manifest catalog run-results model-id]
  (let [node (get-in manifest [:nodes (keyword model-id)])]
    (when-not (= "model" (:resource_type node)) (storage/fail! "Model documentation is unavailable in the manifest. Parse or build the project." 404))
    (let [catalog-node (get-in catalog [:nodes (keyword model-id)])
          tests (list-tests manifest run-results model-id)]
      (merge (select-keys node [:unique_id :name :description :original_file_path :database :schema :alias :relation_name :package_name :compiled_code :raw_code])
             {:tags (vec (:tags node)) :config (:config node)
              :owner (find-owner node)
              :meta (or (:meta node) (get-in node [:config :meta]))
              :columns (list-columns node catalog-node)
              :depends_on (vec (get-in node [:depends_on :nodes]))
              :dependents (->> (list-resources manifest)
                               (filter #(some #{model-id} (get-in % [:depends_on :nodes])))
                               (mapv :unique_id))
              :tests tests :test_summary (summarize-tests tests)
              :catalog_available (boolean catalog-node)}))))

(defn read-model
  ([directory project-id model-id]
   (let [artifacts (read-project-artifacts directory project-id)]
     (assoc (build-model-docs (get-in artifacts [:manifest :data]) (get-in artifacts [:catalog :data])
                              (get-in artifacts [:run_results :data]) model-id)
            :manifest_path (get-in artifacts [:manifest :path])
            :catalog_path (get-in artifacts [:catalog :path])
            :run_results_path (get-in artifacts [:run_results :path]))))
  ([directory project-id model-id _runtime] (read-model directory project-id model-id)))

(defn build-catalog [manifest run-results freshness]
  (let [results (index-results run-results) freshness-results (index-results freshness)
        resources (mapv (fn [node]
                          (assoc (build-resource-metadata node)
                                 :status (get-in results [(:unique_id node) :status])
                                 :freshness_status (get-in freshness-results [(:unique_id node) :status])))
                        (list-catalog-resources manifest))]
    {:resources resources :counts (frequencies (map :resource_type resources))
     :empty_reason (when (empty? resources) "No manifest resources are available. Parse or build the project to create a manifest.")}))

(defn build-resource-docs [manifest catalog run-results freshness resource-id]
  (let [resources (list-catalog-resources manifest)
        node (first (filter #(= resource-id (:unique_id %)) resources))]
    (when-not node (storage/fail! "Resource documentation is unavailable in the manifest. Parse or build the project." 404))
    (let [model? (= "model" (:resource_type node))
          catalog-node (or (get-in catalog [:nodes (keyword resource-id)]) (get-in catalog [:sources (keyword resource-id)]))
          tests (list-tests manifest run-results resource-id)
          result (get (index-results run-results) resource-id)
          freshness-result (get (index-results freshness) resource-id)]
      (merge (when model? (build-model-docs manifest catalog run-results resource-id))
             (build-resource-metadata node)
             (select-keys node [:source_name :source_description :identifier :type :url :maturity :label :arguments
                                :test_metadata :column_name :raw_code :compiled_code :macro_sql :loaded_at_field])
             {:columns (list-columns node catalog-node)
              :config (:config node) :meta (or (:meta node) (get-in node [:config :meta]))
              :dependencies (list-dependencies node)
              :depends_on (vec (get-in node [:depends_on :nodes]))
              :dependents (->> resources (filter #(some #{resource-id} (list-dependencies %))) (mapv :unique_id))
              :tests tests :test_summary (summarize-tests tests)
              :status (:status result) :result result
              :code (or (:raw_code node) (:raw_sql node) (:macro_sql node) (:block_contents node) (:compiled_code node))
              :freshness freshness-result :freshness_config (or (:freshness node) (get-in node [:config :freshness]))
              :catalog_available (boolean catalog-node)}))))

(defn read-catalog [directory project-id]
  (let [artifacts (read-project-artifacts directory project-id)]
    (assoc (build-catalog (get-in artifacts [:manifest :data]) (get-in artifacts [:run_results :data]) (get-in artifacts [:freshness :data]))
           :manifest_path (get-in artifacts [:manifest :path])
           :run_results_path (get-in artifacts [:run_results :path])
           :freshness_path (get-in artifacts [:freshness :path]))))

(defn read-resource [directory project-id resource-id]
  (let [artifacts (read-project-artifacts directory project-id)]
    (assoc (build-resource-docs (get-in artifacts [:manifest :data]) (get-in artifacts [:catalog :data])
                                (get-in artifacts [:run_results :data]) (get-in artifacts [:freshness :data]) resource-id)
           :manifest_path (get-in artifacts [:manifest :path])
           :catalog_path (get-in artifacts [:catalog :path])
           :run_results_path (get-in artifacts [:run_results :path])
           :freshness_path (get-in artifacts [:freshness :path]))))

(defn read-run-artifacts [run-directory]
  (let [directory (io/file run-directory "artifacts")
        artifacts (vec (for [filename artifact-names :let [file (io/file directory filename)] :when (.isFile file)]
                         {:name filename :path (str file) :size_bytes (.length file)}))
        manifest (storage/read-json (io/file directory "manifest.json") {})
        results (storage/read-json (io/file directory "run_results.json") {})]
    {:name (.getName (io/file run-directory)) :artifacts artifacts :metadata (:metadata results)
     :elapsed_time (:elapsed_time results)
     :results (mapv (fn [result]
                      (let [node (get-in manifest [:nodes (keyword (:unique_id result))])]
                        (merge result (select-keys node [:name :resource_type :original_file_path :test_metadata :column_name]))))
                    (:results results))
     :empty_reason (when (empty? (:results results)) "This run has no run_results entries.")}))

(defn read-job [directory project-id engine job-id]
  (workspace/find-project directory project-id)
  (let [job (runtime/find-job engine job-id)]
    (when-not (= project-id (:project_id job)) (storage/fail! "Job not found in this project." 404))
    (let [runs (->> (.listFiles (io/file directory "projects" project-id "jobs" job-id))
                    (filter #(.isDirectory %)) (sort-by #(.getName %)) (mapv read-run-artifacts))
          results (vec (mapcat :results runs))
          tests (filterv #(= "test" (:resource_type %)) results)]
      {:job job :runs runs :tests tests :test_summary (summarize-tests tests)
       :empty_reason (when (empty? runs) "This job has no dbt artifacts. Query and comparison jobs expose results separately.")})))
