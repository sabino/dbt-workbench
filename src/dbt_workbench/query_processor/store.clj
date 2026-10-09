(ns dbt-workbench.query-processor.store
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.connections :as connections]
            [dbt-workbench.snowflake :as snowflake]
            [dbt-workbench.workspace :as workspace]))

(defn create-store [directory project-id options]
  (let [project (workspace/find-project directory project-id)
        config (workspace/read-yaml (io/file (:path project) "dbt_project.yml"))
        profiles (workspace/read-yaml (io/file (:profiles_dir project) "profiles.yml"))
        profile (get profiles (keyword (or (:profile config) (:name config))))
        target (or (:target options) (:target profile))
        output (connections/find-output directory project-id target)
        baseline (workspace/read-baseline directory project-id (:baseline_id options))
        manifest (workspace/read-manifest directory project-id)
        baseline-manifest (when baseline (storage/read-json (:manifest_path baseline) {}))]
    (when-not (map? output) (storage/fail! "The selected target has no profile."))
    {:directory directory :project-id project-id :project project :target target :output output :profiles profile :manifest manifest
     :federated? (= "federated" (:dialect options))
     :routes (:routes (connections/read-routing directory project-id))
     :connection-outputs (connections/read-outputs directory project-id)
     :models (workspace/list-models directory project-id) :baseline baseline :baseline-manifest baseline-manifest}))
(defn resolve-model [store model-id binding]
  (let [manifest (if (= :baseline binding) (:baseline-manifest store) (:manifest store))
        nodes (filter #(= "model" (:resource_type %)) (vals (:nodes manifest)))
        models (if (seq nodes) nodes (:models store))
        matches (filter #(or (= model-id (:unique_id %)) (= (str/lower-case model-id) (str/lower-case (:name %)))) models)]
    (when-not (= 1 (count matches)) (storage/fail! "Model is missing or ambiguous. Use the full model ID."))
    (first matches)))
(defn resolve-database-path [store output]
  (connections/resolve-path (:project store) output))
(defn resolve-route-database [store path]
  (when-not (= path (resolve-database-path store (:output store)))
    (or (some (fn [attachment]
                (let [entry (if (string? attachment) {:path attachment} attachment)
                      attached-path (resolve-database-path store {:type "duckdb" :path (:path entry)})]
                  (when (= path attached-path)
                    (or (:alias entry) (str/replace (.getName (io/file attached-path)) #"\.duckdb$" "")))))
              (get-in store [:output :attach]))
        (when-let [baseline (:baseline store)]
          (when (= path (resolve-database-path store (get-in store [:profiles :outputs (keyword (:target baseline))])))
            "baseline"))
        (connections/database-alias path))))
(defn resolve-source [store source logical?]
  (let [binding (or (:binding source) :dev)
        model (resolve-model store (:model source) binding)
        baseline (:baseline store)
        route (when (= :dev binding) (first (filter #(= (:unique_id model) (:model_id %)) (:routes store))))
        target (if (= :baseline binding) (:target baseline) (or (:target route) (:target store)))
        output (if (or route (= :baseline binding)) (get (:connection-outputs store) (keyword target)) (:output store))
        remote? (= "snowflake" (:type output))
        federated? (:federated? store)
        _ (when (and federated? (not (contains? #{"duckdb" "snowflake"} (:type output))))
            (storage/fail! "Federated model sources support DuckDB and Snowflake connections only."))
        _ (when (and (not federated?) (or route remote?) (not= (:type output) (get-in store [:output :type])))
            (storage/fail! "Cross-engine model routing is unsupported. Select connections using the same engine."))
        _ (when (and (not federated?) remote?
                     (not= (snowflake/account-host (snowflake/expand-value (:account output)))
                           (snowflake/account-host (snowflake/expand-value (get-in store [:output :account])))))
            (storage/fail! "Snowflake routes must use the selected connection's account. Cross-account routing is unsupported."))
        path (when (and route (not remote?)) (resolve-database-path store output))
        database (cond federated? target
                       remote? (snowflake/resolve-identifier (snowflake/expand-value (:database output)))
                       route (resolve-route-database store path))
        relation (or (:relation route) (:alias model) (:name model))
        relation (if remote? (snowflake/resolve-identifier relation) relation)
        schema (or (:schema route) (when (or route remote?) (:schema output)) (:schema model) (if remote? "PUBLIC" "main"))
        schema (if remote? (snowflake/resolve-identifier (snowflake/expand-value schema)) schema)]
    (when (= "ephemeral" (get-in model [:config :materialized])) (storage/fail! "Preview compiled SQL for ephemeral models."))
    (when (and (= :baseline binding) (nil? baseline)) (storage/fail! "Save a baseline before using baseline bindings."))
    (cond-> {:relation (if logical? relation (str (cond (and (= :baseline binding) (not remote?) (not federated?)) "baseline." database (str database ".") :else "") schema "." relation))
             :binding (name binding) :model_id (:unique_id model) :target target}
      (or route remote? federated?) (assoc :database database :schema schema :relation_name relation :routed (boolean route))
      remote? (assoc :connection_type "snowflake")
      path (assoc :database_path path))))
