(ns dbt-workbench.query-processor.store
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.workspace :as workspace]))

(defn create-store [directory project-id options]
  (let [project (workspace/find-project directory project-id)
        config (workspace/read-yaml (io/file (:path project) "dbt_project.yml"))
        profiles (workspace/read-yaml (io/file (:profiles_dir project) "profiles.yml"))
        profile (get profiles (keyword (or (:profile config) (:name config))))
        target (or (:target options) (:target profile))
        output (get-in profile [:outputs (keyword target)])
        baseline (workspace/read-baseline directory project-id (:baseline_id options))
        manifest (workspace/read-manifest directory project-id)
        baseline-manifest (when baseline (storage/read-json (:manifest_path baseline) {}))]
    (when-not (map? output) (storage/fail! "The selected target has no profile."))
    {:project project :target target :output output :profiles profile :manifest manifest
     :models (workspace/list-models directory project-id) :baseline baseline :baseline-manifest baseline-manifest}))
(defn resolve-model [store model-id binding]
  (let [manifest (if (= :baseline binding) (:baseline-manifest store) (:manifest store))
        nodes (filter #(= "model" (:resource_type %)) (vals (:nodes manifest)))
        models (if (seq nodes) nodes (:models store))
        matches (filter #(or (= model-id (:unique_id %)) (= (str/lower-case model-id) (str/lower-case (:name %)))) models)]
    (when-not (= 1 (count matches)) (storage/fail! "Model is missing or ambiguous. Use the full model ID."))
    (first matches)))
(defn resolve-database-path [store output]
  (let [raw (:path output)]
    (when-not (and (= "duckdb" (:type output)) (string? raw)) (storage/fail! "Interactive queries require a local DuckDB target."))
    (let [trimmed (str/trim raw)
          expanded (if (and (str/starts-with? trimmed "{{") (str/ends-with? trimmed "}}"))
                     (let [expression (str/trim (subs trimmed 2 (- (count trimmed) 2)))]
                       (when-not (and (str/starts-with? expression "env_var(") (str/ends-with? expression ")")) (storage/fail! "Preview paths support literal paths and env_var only."))
                       (let [argument (str/trim (subs expression 8 (dec (count expression))))]
                         (when-not (and (> (count argument) 2) (#{\' \"} (first argument)) (= (first argument) (last argument))) (storage/fail! "env_var requires a quoted variable name."))
                         (or (System/getenv (subs argument 1 (dec (count argument)))) (storage/fail! "A target path environment variable is missing.")))) raw)
          file (io/file expanded)]
      (when (some #(str/includes? expanded %) ["{{" "{%"] ) (storage/fail! "Preview paths support literal paths and env_var only."))
      (storage/canonical-path (if (.isAbsolute file) file (io/file (get-in store [:project :path]) file))))))
(defn resolve-source [store source logical?]
  (let [binding (or (:binding source) :dev)
        model (resolve-model store (:model source) binding)
        baseline (:baseline store)
        relation (or (:alias model) (:name model)) schema (or (:schema model) "main")]
    (when (= "ephemeral" (get-in model [:config :materialized])) (storage/fail! "Preview compiled SQL for ephemeral models."))
    (when (and (= :baseline binding) (nil? baseline)) (storage/fail! "Save a baseline before using baseline bindings."))
    {:relation (if logical? relation (str (if (= :baseline binding) "baseline." "") schema "." relation))
     :binding (name binding) :model_id (:unique_id model) :target (if (= :baseline binding) (:target baseline) (:target store))}))
