(ns dbt-workbench.snowflake
  (:require [clojure.string :as str]
            [dbt-workbench.jdbc :as jdbc]
            [dbt-workbench.storage :as storage])
  (:import (java.util.logging Level Logger)))

(def fields [:account :user :database :schema :warehouse :role :authenticator
             :password :token :private_key_file :private_key_file_pwd])
(def stored-fields #{:id :name :type :account :user :database :schema :warehouse :role :authenticator
                     :password_env :token_env :private_key_file_env :private_key_file_pwd_env})
(defn read-environment [variable] (System/getenv variable))
(defn expand-value [value]
  (when (some? value)
    (when-not (string? value) (storage/fail! "Snowflake connection values must be strings."))
    (let [trimmed (str/trim value)
          [_ quote variable] (re-matches #"\{\{\s*env_var\(([\"'])([A-Za-z_][A-Za-z0-9_]*)\1\)\s*\}\}" trimmed)]
      (if quote
        (or (read-environment variable) (storage/fail! "A Snowflake connection environment variable is missing."))
        (do (when (some #(str/includes? value %) ["{{" "{%"])
              (storage/fail! "Snowflake profiles support literal values or a single env_var expression."))
            value)))))
(defn resolve-output [output]
  (reduce (fn [resolved field]
            (let [reference (get output (keyword (str (name field) "_env")))
                  value (if reference
                          (do (when-not (and (string? reference) (re-matches #"[A-Za-z_][A-Za-z0-9_]*" reference))
                                (storage/fail! "Secret references must name an environment variable."))
                              (or (read-environment reference) (storage/fail! "A Snowflake credential environment variable is missing.")))
                          (expand-value (get output field)))]
              (cond-> resolved value (assoc field value))))
          {:type "snowflake"} fields))
(defn account-host [account]
  (when-not (and (string? account) (not (str/blank? account)))
    (storage/fail! "Snowflake connections require an account identifier."))
  (let [value (-> account str/lower-case (str/replace #"^https://" "")
                  (str/replace #"\.snowflakecomputing\.com/?$" "") (str/replace "_" "-"))]
    (when-not (and (re-matches #"[a-z0-9][a-z0-9.-]*" value)
                   (not (str/includes? value ".."))
                   (not (str/ends-with? value ".")))
      (storage/fail! "Use a Snowflake account identifier or its HTTPS snowflakecomputing.com host."))
    (str value ".snowflakecomputing.com")))
(defn resolve-identifier [value]
  (when value
    (when-not (and (string? value) (not (str/blank? value)))
      (storage/fail! "Snowflake database and schema identifiers must be nonempty strings."))
    (if (and (str/starts-with? value "\"") (str/ends-with? value "\""))
      (str/replace (subs value 1 (dec (count value))) "\"\"" "\"")
      (str/upper-case value))))
(defn require-auth! [output]
  (let [authenticator (str/lower-case (or (:authenticator output)
                                         (when (:token output) "oauth")
                                         (when (:private_key_file output) "snowflake_jwt") "snowflake"))]
    (when-not (and (string? (:user output)) (not (str/blank? (:user output))))
      (storage/fail! "Snowflake connections require a user."))
    (case authenticator
      "oauth" (when (str/blank? (:token output)) (storage/fail! "Snowflake OAuth requires a token environment variable."))
      "snowflake_jwt" (when (str/blank? (:private_key_file output)) (storage/fail! "Snowflake key authentication requires a private key file environment variable."))
      "snowflake" (when (str/blank? (:password output)) (storage/fail! "Snowflake password authentication requires a password environment variable."))
      (storage/fail! "Use Snowflake password, OAuth, or private key file authentication. Browser authentication is unsupported."))
    authenticator))
(defn connection-options [output]
  (let [resolved (resolve-output output)
        host (account-host (:account resolved))
        authenticator (require-auth! resolved)
        base {"user" (:user resolved) "authenticator" authenticator
              "enablePutGet" "false" "CLIENT_TELEMETRY_ENABLED" "false"
              "CLIENT_OUT_OF_BAND_TELEMETRY_ENABLED" "false" "JDBC_QUERY_RESULT_FORMAT" "JSON"
              "loginTimeout" "15" "networkTimeout" "30000" "queryTimeout" "30"
              "IMPLICIT_SERVER_SIDE_QUERY_TIMEOUT" "true"}
        options (reduce (fn [properties field]
                          (if-let [value (get resolved field)]
                            (assoc properties (if (= :database field) "db" (name field))
                                   (if (#{:database :schema :warehouse :role} field) (resolve-identifier value) value))
                            properties)) base (concat [:database :schema :warehouse :role]
                                                     (case authenticator
                                                       "oauth" [:token]
                                                       "snowflake_jwt" [:private_key_file :private_key_file_pwd]
                                                       "snowflake" [:password])))]
    {:url (str "jdbc:snowflake://" host "/") :properties options}))
(defn validate-saved! [connection]
  (when-not (and (= "snowflake" (:type connection)) (every? stored-fields (keys connection))
                 (every? #(and (string? %) (not (str/blank? %))) (vals connection))
                 (every? (fn [[field value]]
                           (or (not (str/ends-with? (name field) "_env"))
                               (re-matches #"[A-Za-z_][A-Za-z0-9_]*" value))) connection))
    (storage/fail! "Snowflake connections accept account fields and credential environment variable names only."))
  (account-host (:account connection))
  (let [auth (str/lower-case (or (:authenticator connection)
                                (when (:token_env connection) "oauth")
                                (when (:private_key_file_env connection) "snowflake_jwt") "snowflake"))]
    (when-not (and (string? (:user connection))
                   (case auth "oauth" (:token_env connection)
                         "snowflake_jwt" (:private_key_file_env connection)
                         "snowflake" (:password_env connection) false))
      (storage/fail! "Select password, OAuth, or private key authentication and provide its environment variable name.")))
  connection)
(defn initialize-driver! []
  ;; Disable the driver's JUL logger before class loading so credentials never reach application logs.
  (doto (Logger/getLogger "net.snowflake")
    (.setLevel Level/OFF)
    (.setUseParentHandlers false))
  (Class/forName "net.snowflake.client.jdbc.SnowflakeDriver"))
(defn connect! [output]
  (let [{:keys [url properties]} (connection-options output)]
    (initialize-driver!)
    (try
      (jdbc/connect! url properties)
      (catch Exception _ (storage/fail! "Snowflake connection failed. Check its account, authentication, and network access.")))))
(defn read-columns! [connection]
  (try
    (jdbc/query-maps! connection
                     "SELECT table_catalog AS \"table_catalog\",table_schema AS \"table_schema\",table_name AS \"table_name\",column_name AS \"column_name\",data_type AS \"data_type\" FROM information_schema.columns ORDER BY table_schema,table_name,ordinal_position")
    (catch Exception _ (storage/fail! "Snowflake schema could not be read. Check the database and role grants."))))
