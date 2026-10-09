(ns dbt-workbench.connections
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dbt-workbench.jdbc :as jdbc]
            [dbt-workbench.snowflake :as snowflake]
            [dbt-workbench.snow-cli :as snow-cli]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.workspace :as workspace]))

(defn read-profile [directory project-id]
  (let [project (workspace/find-project directory project-id)
        config (workspace/read-yaml (io/file (:path project) "dbt_project.yml"))
        profiles (workspace/read-yaml (io/file (:profiles_dir project) "profiles.yml"))]
    (get profiles (keyword (or (:profile config) (:name config))))))
(defn read-outputs [directory project-id]
  (merge (get (read-profile directory project-id) :outputs)
         (into {} (map (fn [connection] [(keyword (:id connection)) connection]))
               (storage/read-json (io/file (workspace/project-data directory project-id) "connections.json") []))))
(defn resolve-path [project output]
  (let [raw (:path output)]
    (when-not (and (= "duckdb" (:type output)) (string? raw) (not (str/blank? raw)))
      (storage/fail! "Interactive connections require a local DuckDB file."))
    (let [trimmed (str/trim raw)
          variable (second (re-matches #"\{\{\s*env_var\(['\"]([^'\"]+)['\"]\)\s*\}\}" trimmed))
          expanded (if variable (or (System/getenv variable) (storage/fail! "A target path environment variable is missing.")) raw)
          file (io/file expanded)]
      (when (or (some #(str/includes? expanded %) ["{{" "{%" "://"]) (= ":memory:" expanded))
        (storage/fail! "Connections require literal local paths or a single env_var expression."))
      (storage/canonical-path (if (.isAbsolute file) file (io/file (:path project) file))))))
(defn require-file! [project output]
  (let [path (resolve-path project output)]
    (when-not (.isFile (io/file path)) (storage/fail! "The DuckDB connection file does not exist."))
    path))
(defn find-output [directory project-id connection-id]
  (let [output (or (get (read-outputs directory project-id) (keyword connection-id)) (storage/fail! "Connection not found." 404))]
    (if (= "snow_cli" (:transport output))
      (merge (or (first (filter #(= (:cli_connection output) (:cli_connection %)) (snow-cli/list-configured!)))
                 (storage/fail! "Registered Snow CLI connection is missing from the local CLI configuration.")) output)
      output)))
(defn import-cli! [directory project-id {:keys [id cli_connection] :as request}]
  (when-not (and (every? #{:id :cli_connection} (keys request))
                 (string? id) (re-matches #"[A-Za-z][A-Za-z0-9_-]{0,63}" id)
                 (string? cli_connection) (not (str/blank? cli_connection))
                 (some #(= cli_connection (:cli_connection %)) (snow-cli/list-configured!)))
    (storage/fail! "Choose a configured Snow CLI connection and a valid local ID."))
  (when (contains? (:outputs (read-profile directory project-id)) (keyword id))
    (storage/fail! "A saved connection cannot replace a profile target."))
  (let [file (io/file (workspace/project-data directory project-id) "connections.json")
        saved {:id id :type "snowflake" :transport "snow_cli" :cli_connection cli_connection}]
    (locking directory
      (storage/save-json! file (conj (filterv #(not= id (:id %)) (storage/read-json file [])) saved)))
    saved))
(defn list-connections [directory project-id]
  (let [profile-ids (set (keys (:outputs (read-profile directory project-id))))]
    (->> (read-outputs directory project-id)
         (map (fn [[id output]]
                (cond-> {:id (name id) :name (or (:name output) (name id)) :type (:type output)
                         :schema (or (:schema output) "main") :source (if (profile-ids id) "profile" "local")
                         :supported (contains? #{"duckdb" "snowflake"} (:type output)) :transport (:transport output) :cli_connection (:cli_connection output)}
                  (and (= "duckdb" (:type output)) (string? (:path output))
                       (not (str/includes? (:path output) "{{"))) (assoc :path (:path output))
                  (= "snowflake" (:type output))
                  (merge (into {} (filter (fn [[_ value]] (and (string? value) (not (str/includes? value "{{")))))
                               (select-keys output [:account :user :database :warehouse :role :authenticator]))))))
         (sort-by :id) vec)))
(defn open-connection! [directory project-id connection-id]
  (when (= "snow_cli" (:transport (get (read-outputs directory project-id) (keyword connection-id))))
    (storage/fail! "Snow CLI connections execute through the CLI. JDBC access and typed federation are unsupported for this transport."))
  (let [output (find-output directory project-id connection-id)]
    (case (:type output)
      "duckdb" (let [path (require-file! (workspace/find-project directory project-id) output)]
                 (jdbc/connect! (str "jdbc:duckdb:" path) {"duckdb.read_only" "true"}))
      "snowflake" (snowflake/connect! output)
      (storage/fail! "Interactive connections support DuckDB and Snowflake only."))))
(defn test-connection! [directory project-id connection-id]
  (try
    (let [output (find-output directory project-id connection-id)
          local? (= "duckdb" (:type output))]
      (if (= "snow_cli" (:transport output))
        (snow-cli/execute-select! output "SELECT 1 AS connection_check" {} {:max_rows 1 :timeout_seconds 15} (atom false))
        (with-open [connection (open-connection! directory project-id connection-id)] (jdbc/query! connection "SELECT 1")))
      {:id connection-id :healthy true :read_only local? :select_only true})
    (catch Exception _
      {:id connection-id :healthy false :select_only true
       :diagnostic (if (= "snowflake" (:type (get (read-outputs directory project-id) (keyword connection-id))))
                     "Snowflake connection failed. Check its local configuration, cached authentication, or role grants."
                     "Connection failed. Check its local file and access.")})))
(defn read-schema! [directory project-id connection-id]
  (let [output (find-output directory project-id connection-id)
        columns (if (= "snow_cli" (:transport output))
                  (let [result (snow-cli/execute-select! output
                                 "SELECT table_catalog,table_schema,table_name,column_name,data_type FROM information_schema.columns ORDER BY table_schema,table_name,ordinal_position"
                                 {} {:max_rows 10000 :timeout_seconds 30} (atom false))]
                    (when (:truncated result) (storage/fail! "Schema inventory exceeds 10,000 columns. Use a narrower JDBC schema connection."))
                    (mapv #(zipmap (map (comp keyword str/lower-case :name) (:columns result)) %) (:rows result)))
                  (with-open [connection (open-connection! directory project-id connection-id)]
                    (if (= "snowflake" (:type output)) (snowflake/read-columns! connection)
                      (jdbc/query-maps! connection "SELECT table_catalog,table_schema,table_name,column_name,data_type FROM information_schema.columns WHERE table_catalog=current_database() ORDER BY table_schema,table_name,ordinal_position"))))]
    {:id connection-id :relations
     (->> columns (group-by (juxt :table_catalog :table_schema :table_name))
          (map (fn [[[database schema relation] fields]]
                 {:database database :schema schema :name relation
                  :columns (mapv #(hash-map :name (:column_name %) :type (:data_type %)) fields)}))
          (sort-by (juxt :schema :name)) vec)}))
(defn save-connection! [directory project-id connection]
  (let [{:keys [id type path schema]} connection
        file (io/file (workspace/project-data directory project-id) "connections.json")]
    (when-not (and (string? id) (re-matches #"[A-Za-z][A-Za-z0-9_-]{0,63}" id)
                   (or (nil? (:name connection)) (string? (:name connection))))
      (storage/fail! "Use a connection ID and optional name."))
    (when (contains? (:outputs (read-profile directory project-id)) (keyword id))
      (storage/fail! "A saved connection cannot replace a profile target."))
    (let [saved (case type
                  "duckdb" (do
                             (when-not (and (string? path) (not (str/includes? path "{{"))
                                            (or (nil? schema) (and (string? schema) (not (str/blank? schema))))
                                            (every? #{:id :name :type :path :schema} (keys connection)))
                               (storage/fail! "DuckDB connections require an existing local path and optional schema."))
                             (let [saved (assoc connection :path (require-file! (workspace/find-project directory project-id) connection))]
                               (with-open [connection (jdbc/connect! (str "jdbc:duckdb:" (:path saved)) {"duckdb.read_only" "true"})]
                                 (jdbc/query! connection "SELECT 1"))
                               saved))
                  "snowflake" (snowflake/validate-saved! connection)
                  (storage/fail! "Interactive connections support DuckDB and Snowflake only."))]
      (locking directory
        (storage/save-json! file (conj (vec (remove #(= id (:id %)) (storage/read-json file []))) saved)))
      saved)))
(defn read-routing [directory project-id]
  (storage/read-json (io/file (workspace/project-data directory project-id) "routing.json") {:version 1 :routes []}))
(defn remove-connection! [directory project-id connection-id]
  (when (contains? (:outputs (read-profile directory project-id)) (keyword connection-id))
    (storage/fail! "Profile targets must be managed in profiles.yml."))
  (when (some #(= connection-id (:target %)) (:routes (read-routing directory project-id)))
    (storage/fail! "Remove model routes using this connection before removing it."))
  (let [file (io/file (workspace/project-data directory project-id) "connections.json")]
    (locking directory
      (storage/save-json! file (vec (remove #(= connection-id (:id %)) (storage/read-json file [])))))
    {:id connection-id :removed true}))
(defn resolve-model [directory project-id model-id]
  (or (get-in (workspace/read-manifest directory project-id) [:nodes (keyword model-id)])
      (first (filter #(= model-id (:unique_id %)) (workspace/list-models directory project-id)))))
(defn check-routing! [directory project-id routing]
  (let [routes (:routes routing)]
    (when-not (and (= 1 (:version routing)) (vector? routes)
                   (every? #{:version :routes} (keys routing))
                   (= (count routes) (count (set (map :model_id routes)))))
      (storage/fail! "Routing requires version 1 and unique model IDs."))
    (doseq [{:keys [model_id target schema relation] :as route} routes]
      (when-not (and (map? route) (string? model_id) (string? target)
                     (every? #{:model_id :target :schema :relation} (keys route))
                     (every? #(or (nil? %) (and (string? %) (re-matches #"[A-Za-z_][A-Za-z0-9_]*" %))) [schema relation]))
        (storage/fail! "Routes require a full model ID, target, and optional schema/relation identifiers."))
      (let [model (resolve-model directory project-id model_id)
            output (find-output directory project-id target)]
        (when-not (and model (or (nil? (:resource_type model)) (= "model" (:resource_type model))))
          (storage/fail! "Route model ID does not exist."))
        (when (= "ephemeral" (or (:materialized model) (get-in model [:config :materialized])))
          (storage/fail! "Ephemeral models have no routable relation."))
        (when-not (contains? #{"duckdb" "snowflake"} (:type output))
          (storage/fail! "Model routing supports DuckDB and Snowflake connections only."))))
    routing))
(defn validate-routing! [directory project-id routing]
  (check-routing! directory project-id routing)
  (let [results (mapv (fn [{:keys [model_id target schema relation]}]
                        (let [model (resolve-model directory project-id model_id)
                              output (find-output directory project-id target)
                              schema (or schema (:schema output) (:schema model) (if (= "snowflake" (:type output)) "PUBLIC" "main"))
                              relation (or relation (:alias model) (:name model))
                              result {:model_id model_id :target target :schema schema :relation relation}]
                          (try
                            (if (= "snow_cli" (:transport output))
                              (let [database (snowflake/resolve-identifier (:database output))
                                    schema (snowflake/resolve-identifier (snowflake/expand-value schema))
                                    relation (snowflake/resolve-identifier relation)
                                    sql (str "SELECT * FROM " (str/join "." (map jdbc/quote-identifier (remove nil? [database schema relation]))) " LIMIT 0")]
                                (snow-cli/execute-select! output sql {} {:max_rows 1 :timeout_seconds 15} (atom false))
                                (assoc result :valid true :database database :schema schema :relation relation :transport "snow_cli"))
                              (with-open [connection (open-connection! directory project-id target)]
                                (let [remote? (= "snowflake" (:type output))
                                      database (-> (jdbc/query! connection "SELECT current_database()") :rows first first)
                                      schema (if remote? (snowflake/resolve-identifier (snowflake/expand-value schema)) schema)
                                      relation (if remote? (snowflake/resolve-identifier relation) relation)]
                                  (jdbc/query! connection (str "SELECT * FROM " (jdbc/quote-identifier schema) "." (jdbc/quote-identifier relation) " LIMIT 0"))
                                  (assoc result :valid true :database database :schema schema :relation relation))))
                            (catch Exception _ (assoc result :valid false :diagnostic "The connection or routed relation could not be read. Check the target, schema, relation, and access.")))))
                      (:routes routing))]
    {:valid (every? :valid results) :routes results}))
(defn save-routing! [directory project-id routing]
  (check-routing! directory project-id routing)
  (storage/save-json! (io/file (workspace/project-data directory project-id) "routing.json") routing)
  (assoc routing :validation (validate-routing! directory project-id routing)))
(defn database-alias [path]
  (str "wb_route_" (subs (storage/digest path) 0 16)))
