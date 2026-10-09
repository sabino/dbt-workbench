(ns dbt-workbench.query-processor
  (:require [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dbt-workbench.query-processor.tokens :as tokens]
            [dbt-workbench.jdbc :as jdbc]
            [dbt-workbench.query-processor.compile :as compiler]
            [dbt-workbench.query-processor.driver :as driver]
            [dbt-workbench.query-processor.honey :as honey-query]
            [dbt-workbench.query-processor.parse :as parse]
            [dbt-workbench.query-processor.store :as store]
            [dbt-workbench.storage :as storage]))

(defn parse-input [options]
  (case (:format options "sql") "sql" {:query/version 1 :sql (:content options)} "edn" (parse/parse-edn (:content options))
        (storage/fail! "Format must be SQL or EDN.")))
(defn read-catalog! [directory project-id target]
  (let [metadata (store/create-store directory project-id {:target target})]
    (with-open [connection (driver/connect-duckdb! metadata)]
      (let [columns (jdbc/query-maps! connection "SELECT table_schema,table_name,column_name,data_type FROM information_schema.columns WHERE table_catalog=current_database() ORDER BY table_schema,table_name,ordinal_position")]
        {:relations (->> columns (group-by (juxt :table_schema :table_name))
                         (map (fn [[[schema table] fields]] {:name (if (= schema "main") table (str schema "." table)) :label table :schema schema
                                                            :columns (mapv #(hash-map :name (:column_name %) :type (:data_type %)) fields)}))
                         (sort-by :name) vec)}))))
(defn parameter-names [content format]
  (let [query (parse-input {:content content :format format})]
    (if (:sql query)
      (into #{} (map #(keyword (.getName ^net.sf.jsqlparser.expression.JdbcNamedParameter %)))
            (mapcat #(-> % parse/collect-native-metadata :parameters) (parse/parse-sql (:sql query))))
      (into #{} (keep #(when (and (vector? %) (= :param (first %))) (keyword (second %))))
            (tree-seq coll? seq query)))))
(defn execute! [directory project-id options cancel register-statement!]
  (let [metadata (store/create-store directory project-id options)
        query (parse-input options)
        compiled (compiler/compile query metadata options false)
        result (driver/execute! compiled metadata options cancel register-statement!)]
    (merge result {:dialect (:dialect options "duckdb")
                   :provenance {:target (:target metadata) :format (:format options "sql") :editor_content (:editor_content options (:content options))
                                :original_sql (when (= "sql" (:format options "sql")) (:content options))
                                :original_edn (when (= "edn" (:format options)) (:content options))
                                :parameters (:parameters options {}) :manifest_revision (storage/digest (pr-str (:manifest metadata)))
                                :baseline_id (get-in metadata [:baseline :id]) :baseline_revision (get-in metadata [:baseline :manifest_revision])
                                :native_sql (:sql compiled) :translated_sql (:sql result)
                                :bindings (:bindings result) :captured_relations (:captured_relations result) :warnings (:warnings result)
                                :truncated (:truncated result) :row_limit (:max_rows options 10000) :processor "clojure/honeysql+jdbc"}
                   :summary {:row_count (:row_count result) :truncated (:truncated result)}})))
(defn convert-query [directory project-id {:keys [content format parameters] :as options}]
  (let [metadata (store/create-store directory project-id options)]
    (case format
      "sql" (let [queries (mapv honey-query/convert-select (parse/parse-sql content))]
              {:format "edn" :content (with-out-str (pprint/pprint (if (= 1 (count queries)) (first queries) queries)))
               :note "SQL parsed into editable HoneySQL data."})
      "edn" (let [query (parse/parse-edn content)]
              (if (:sql query)
                (do (parse/parse-sql (:sql query)) {:format "sql" :content (:sql query) :note "Native SQL restored from EDN."})
                (let [queries (if (vector? query) query [query])
                      compiled (mapv #(compiler/compile % metadata (assoc options :parameters (or parameters {})) true) queries)
                      values (vec (mapcat :params compiled))
                      sql (tokens/name-parameters (str/join ";\n\n" (map :sql compiled)))]
                  {:format "sql" :content sql :parameters (into {} (map-indexed (fn [index value] [(keyword (str "p" index)) value]) values))
                   :note "HoneySQL data compiled into SQL."})))
      (storage/fail! "Format must be SQL or EDN."))))
