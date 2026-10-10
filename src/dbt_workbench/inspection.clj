(ns dbt-workbench.inspection
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

(defn read-project-artifacts
  ([directory project-id]
   (read-project-artifacts directory project-id #{:manifest :catalog :freshness :run_results}))
  ([directory project-id kinds]
   (let [directories (list-artifact-directories directory project-id)
         saved (io/file directory "projects" project-id "manifest.json")]
     (cond-> {}
       (kinds :manifest) (assoc :manifest (if (.isFile saved)
                                           {:path (str saved) :data (storage/read-json saved {})}
                                           (read-latest-artifact directories "manifest.json")))
       (kinds :catalog) (assoc :catalog (read-latest-artifact directories "catalog.json"))
       (kinds :freshness) (assoc :freshness (read-latest-artifact directories "sources.json"))
       (kinds :run_results) (assoc :run_results (read-latest-artifact directories "run_results.json"))))))

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

(def editor-resource-types #{"model" "seed" "snapshot" "source" "macro"})
(def editor-resource-fields
  [:unique_id :name :resource_type :package_name :description :source_name :identifier :alias :relation_name])

(defn select-editor-text [data fields]
  (into {} (for [field fields :let [value (get data field)] :when (string? value)] [field value])))

(defn describe-editor-columns [node catalog-node]
  (mapv (fn [column]
          (let [key (keyword (:name column))
                declared (get-in node [:columns key]) observed (get-in catalog-node [:columns key])]
            (assoc (select-editor-text column [:name :data_type :description])
                   :metadata_source (cond (and declared observed) "declared+catalog"
                                          observed "catalog" :else "declared"))))
        (list-columns node catalog-node)))

(defn resolve-editor-path [node package files]
  (when (or (= package (:package_name node))
            (and (nil? (:package_name node)) (= package (second (str/split (:unique_id node) #"\.")))))
    (let [patch (:patch_path node)
          patch (when (and (string? patch) (str/starts-with? patch (str package "://")))
                  (subs patch (+ (count package) 3)))]
      (first (filter files [(:original_file_path node) patch (:path node)])))))

(defn build-editor-resource [node catalog-node package files metadata-source]
  (let [kind (or (:resource_type node) (first (str/split (:unique_id node) #"\.")))
        node (assoc node :resource_type kind)]
    (assoc (select-editor-text node editor-resource-fields)
           :package_name (or (:package_name node) (second (str/split (:unique_id node) #"\.")))
           :path (resolve-editor-path node package files)
           :columns (describe-editor-columns node catalog-node)
           :arguments (mapv #(select-editor-text % [:name :type :description]) (:arguments node))
           :metadata_source metadata-source)))

(defn index-declared-columns [columns]
  (into {} (for [column columns :when (and (map? column) (string? (:name column)))]
             [(keyword (:name column)) (select-editor-text column [:name :data_type :description])])))

(defn build-declared-editor-resources [data path package]
  (vec
    (concat
      (for [kind ["model" "seed" "snapshot" "macro"]
            declaration (let [entries (get data (keyword (str kind "s")))] (when (sequential? entries) entries))
            :when (and (map? declaration) (string? (:name declaration)))]
        (assoc (select-editor-text declaration [:name :description :alias :identifier])
               :unique_id (str kind "." package "." (:name declaration)) :resource_type kind :package_name package
               :original_file_path path :columns (index-declared-columns (:columns declaration))
               :arguments (mapv #(select-editor-text % [:name :type :description]) (:arguments declaration))))
      (for [source (when (sequential? (:sources data)) (:sources data))
            table (:tables source)
            :when (and (string? (:name source)) (string? (:name table)))]
        (assoc (select-editor-text table [:name :description :identifier])
               :unique_id (str "source." package "." (:name source) "." (:name table)) :resource_type "source"
               :package_name package :source_name (:name source) :original_file_path path
               :columns (index-declared-columns (:columns table)))))))

(defn split-macro-arguments [signature]
  (loop [characters (seq signature) depth 0 quote nil escaped? false part "" parts []]
    (if-let [character (first characters)]
      (cond
        escaped? (recur (next characters) depth quote false (str part character) parts)
        (and quote (= character \\)) (recur (next characters) depth quote true (str part character) parts)
        quote (recur (next characters) depth (when-not (= quote character) quote) false (str part character) parts)
        (#{\' \"} character) (recur (next characters) depth character false (str part character) parts)
        (#{\( \[ \{} character) (recur (next characters) (inc depth) nil false (str part character) parts)
        (#{\) \] \}} character) (recur (next characters) (dec depth) nil false (str part character) parts)
        (and (= character \,) (zero? depth)) (recur (next characters) depth nil false "" (conj parts part))
        :else (recur (next characters) depth nil false (str part character) parts))
      (->> (conj parts part)
           (keep #(some-> (re-find #"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*(?:=|$)" %) second))
           (mapv #(hash-map :name %))))))

(defn discover-editor-blocks [content path package kind]
  (let [pattern (if (= "macro" kind)
                  #"(?s)\{%-?\s*macro\s+([A-Za-z_][A-Za-z0-9_]*)\s*\((.*?)\)\s*-?%\}"
                  #"\{%-?\s*snapshot\s+([A-Za-z_][A-Za-z0-9_]*)\s*-?%\}")]
    (mapv (fn [[_ name signature]]
            {:unique_id (str kind "." package "." name) :name name :resource_type kind :package_name package
             :original_file_path path :arguments (if signature (split-macro-arguments signature) [])})
          (re-seq pattern (-> content
                              (str/replace #"(?s)\{#.*?#\}" "")
                              (str/replace #"(?s)\{%-?\s*raw\s*-?%\}.*?\{%-?\s*endraw\s*-?%\}" ""))))))

(defn read-editor-declarations [directory project-id files package]
  (reduce (fn [result file]
            (try
              (let [preview (workspace/read-file directory project-id (:path file))
                    data (when-not (:read_only preview) (yaml/parse-string (:content preview)))]
                (update result :resources into (if (map? data) (build-declared-editor-resources data (:path file) package) [])))
              (catch Exception _ (assoc result :warnings ["Some local property files could not be read. Their metadata is omitted."]))))
          {:resources [] :warnings []} (filter #(contains? #{"yml" "yaml"} (:kind %)) files)))

(defn matches-editor-folder? [path folders]
  (some #(str/starts-with? path (str (str/replace % #"/$" "") "/")) folders))

(defn read-editor-file-resources [directory project-id files config package]
  (vec
    (mapcat
      (fn [file]
        (let [path (:path file) kind (:kind file)
              resource-type (cond (and (contains? #{"sql" "py"} kind) (matches-editor-folder? path (:model-paths config ["models"]))) "model"
                                  (and (= "csv" kind) (matches-editor-folder? path (:seed-paths config ["seeds"]))) "seed"
                                  (and (= "sql" kind) (matches-editor-folder? path (:snapshot-paths config ["snapshots"]))) "snapshot"
                                  (and (= "sql" kind) (matches-editor-folder? path (:macro-paths config ["macros"]))) "macro")]
          (case resource-type
            ("macro" "snapshot") (let [preview (try (workspace/read-file directory project-id path) (catch Exception _ nil))]
                                     (if (or (nil? preview) (:read_only preview)) []
                                       (discover-editor-blocks (:content preview) path package resource-type)))
            ("model" "seed") (let [name (str/replace (:name file) #"\.[^.]+$" "")]
                               [{:unique_id (str resource-type "." package "." name) :name name :resource_type resource-type
                                 :package_name package :original_file_path path}])
            []))) files)))

(defn read-editor-context [directory project-id]
  (let [project (workspace/find-project directory project-id)
        config (workspace/read-yaml (io/file (:path project) "dbt_project.yml"))
        package (str (:name config (:name project)))
        files (workspace/list-files directory project-id)
        paths (set (map :path files))
        declarations (read-editor-declarations directory project-id files package)
        declared (into {} (map (juxt :unique_id identity)) (:resources declarations))
        local (read-editor-file-resources directory project-id files config package)
        local (mapv (fn [node]
                      (let [docs (declared (:unique_id node))]
                        (cond-> (merge docs node)
                          (seq (:arguments docs))
                          (assoc :arguments (let [documented (into {} (map (juxt :name identity)) (:arguments docs))]
                                              (mapv #(merge % (documented (:name %))) (:arguments node))))))) local)
        extra (filter #(or (= "source" (:resource_type %)) (= "snapshot" (:resource_type %))) (:resources declarations))
        artifacts (read-project-artifacts directory project-id #{:manifest :catalog})
        manifest (get-in artifacts [:manifest :data]) catalog (get-in artifacts [:catalog :data])
        manifest-available (boolean (seq manifest))
        nodes (filter #(editor-resource-types (or (:resource_type %) (first (str/split (:unique_id %) #"\."))))
                      (list-catalog-resources manifest))
        local-resources (into {} (map (fn [node] [(:unique_id node) (build-editor-resource node nil package paths "project_files")]))
                              (concat extra local))
        resources (->> nodes
                       (reduce (fn [resources node]
                                 (assoc resources (:unique_id node)
                                        (build-editor-resource node (or (get-in catalog [:nodes (keyword (:unique_id node))])
                                                                        (get-in catalog [:sources (keyword (:unique_id node))]))
                                                               package paths "manifest"))) local-resources)
                       vals (sort-by (juxt :resource_type :package_name :source_name :name :unique_id)) vec)]
    {:resources resources :manifest_available manifest-available
     :metadata_source (if manifest-available "local_artifacts+project_files" "project_files")
     :warnings (:warnings declarations)
     :empty_reason (when (empty? resources) "No local dbt resources are available. Add project models or declarations to enable editor suggestions.")}))

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
