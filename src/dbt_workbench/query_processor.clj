(ns dbt-workbench.query-processor
  (:require [clojure.edn :as edn]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [honey.sql :as honey]
            [dbt-workbench.query-processor.tokens :as tokens]
            [dbt-workbench.connections :as connections]
            [dbt-workbench.query-processor.compile :as compiler]
            [dbt-workbench.query-processor.driver :as driver]
            [dbt-workbench.query-processor.honey :as honey-query]
            [dbt-workbench.query-processor.parse :as parse]
            [dbt-workbench.query-processor.store :as store]
            [dbt-workbench.storage :as storage])
  (:import (java.io PushbackReader StringReader)))

(defn format-edn [content]
  (try
    (with-open [input (PushbackReader. (StringReader. content))]
      (let [eof (Object.) form (edn/read {:eof eof} input)]
        (when (or (identical? eof form) (not (identical? eof (edn/read {:eof eof} input))))
          (storage/fail! "Format requires one complete EDN value."))
        {:content (with-out-str (pprint/pprint form)) :format "edn"}))
    (catch Exception _ (storage/fail! "Format requires one complete EDN value with standard EDN tags."))))

(defn parse-input [options]
  (case (:format options "sql") "sql" {:query/version 1 :sql (:content options)} "edn" (parse/parse-edn (:content options))
        (storage/fail! "Format must be SQL or EDN.")))
(defn read-catalog! [directory project-id target]
  (let [metadata (store/create-store directory project-id {:target target})
        schema (connections/read-schema! directory project-id (:target metadata))]
    {:relations (mapv (fn [relation]
                       (assoc relation :name (if (= "main" (:schema relation)) (:name relation)
                                              (str (:schema relation) "." (:name relation)))
                                       :label (:name relation)))
                     (:relations schema))}))
(defn read-query-parameter-names [query]
  (if (:sql query)
    (into #{} (map #(keyword (.getName ^net.sf.jsqlparser.expression.JdbcNamedParameter %)))
          (mapcat #(-> % parse/collect-native-metadata :parameters) (parse/parse-sql (:sql query))))
    (into #{} (keep #(when (and (vector? %) (= :param (first %))) (keyword (second %))))
          (tree-seq coll? seq query))))

(defn parameter-names [content format]
  (read-query-parameter-names (parse-input {:content content :format format})))
(defn execute! [directory project-id options cancel register-statement!]
  (let [metadata (store/create-store directory project-id options)
        query (parse-input options)
        compiled (compiler/compile query metadata options false)
        result (driver/execute! compiled metadata options cancel register-statement!)]
    (merge result {:dialect (or (:dialect options) (if (= "snowflake" (get-in metadata [:output :type])) "snowflake" "duckdb"))
                   :provenance {:target (:target metadata) :format (:format options "sql") :editor_content (:editor_content options (:content options))
                                :original_sql (when (= "sql" (:format options "sql")) (:content options))
                                :original_edn (when (= "edn" (:format options)) (:content options))
                                :parameters (:parameters options {}) :manifest_revision (storage/digest (pr-str (:manifest metadata)))
                                :baseline_id (get-in metadata [:baseline :id]) :baseline_revision (get-in metadata [:baseline :manifest_revision])
                                :native_sql (:sql compiled) :translated_sql (:sql result)
                                :bindings (:bindings result) :captured_relations (:captured_relations result) :warnings (:warnings result)
                                :truncated (:truncated result) :row_limit (:max_rows options 10000) :processor "clojure/honeysql+jdbc"}
                   :summary {:row_count (:row_count result) :truncated (:truncated result)}})))
(defn validate-conversion-parameters! [queries parameters]
  (let [names (into #{} (mapcat read-query-parameter-names) queries)]
    (when-not (= names (set (keys parameters)))
      (storage/fail! "Parameters must match exactly the document's declared parameters."))))

(defn format-parameter-literal [value]
  (when-not (or (nil? value) (string? value) (number? value) (boolean? value))
    (storage/fail! "File saves support scalar parameter values only."))
  (when (and (or (instance? Double value) (instance? Float value))
             (not (Double/isFinite (double value))))
    (storage/fail! "File saves require finite numeric parameters."))
  (first (honey/format-expr [:inline value])))

(defn inline-sql-parameters [sql parameters]
  (validate-conversion-parameters! [{:sql sql}] parameters)
  (let [content (apply str (map (fn [{:keys [kind text]}]
                                 (if (= :named-parameter kind)
                                   (format-parameter-literal (parameters (keyword (subs text 1))))
                                   text)) (tokens/tokenize sql)))]
    (parse/parse-sql content)
    content))

(defn inline-query-parameters [query parameters]
  (walk/postwalk (fn [node]
                   (if (and (vector? node) (= :param (first node)))
                     (let [value (parameters (keyword (second node)))]
                       (format-parameter-literal value)
                       value)
                     node)) query))

(defn compile-conversion-queries [queries metadata options]
  (let [parameters (or (:parameters options) {})]
    (validate-conversion-parameters! queries parameters)
    (mapv #(compiler/compile % metadata
                            (assoc options :parameters (select-keys parameters (read-query-parameter-names %)))
                            true) queries)))

(defn convert-file-query [content format save-format parameters metadata options]
  (when-not (contains? #{"sql" "edn"} save-format)
    (storage/fail! "File saves require SQL or EDN as the destination format."))
  (let [parameters (or parameters {})
        query (parse-input {:content content :format format})
        queries (if (vector? query) query [query])]
    (case save-format
      "sql" (if (:sql query)
              {:format "sql" :content (inline-sql-parameters (:sql query) parameters) :parameters {}}
              (let [compiled (compile-conversion-queries queries metadata (assoc options :inline? true))]
                (when (some #(seq (:params %)) compiled)
                  (storage/fail! "The saved SQL still requires generated parameters."))
                {:format "sql" :content (str/join ";\n\n" (map :sql compiled)) :parameters {}}))
      "edn" (do
              (validate-conversion-parameters! queries parameters)
              (let [form (if (= "sql" format)
                           (let [forms (mapv honey-query/convert-select (parse/parse-sql content))]
                             (if (= 1 (count forms)) (first forms) forms))
                           query)
                    form (if (:sql form)
                           (assoc form :sql (inline-sql-parameters (:sql form) parameters))
                           (inline-query-parameters form parameters))]
                ;; Validate typed parameters before replacing their declarations with values.
                (when (= "edn" format) (compile-conversion-queries queries metadata options))
                {:format "edn" :content (if (and (= "edn" format) (empty? parameters))
                                         content (with-out-str (pprint/pprint form)))
                 :parameters {}})))))

(defn convert-query [directory project-id {:keys [content format parameters save_format] :as options}]
  (let [metadata (store/create-store directory project-id options)]
    (if save_format
      (convert-file-query content format save_format parameters metadata options)
      (case format
        "sql" (let [queries (mapv honey-query/convert-select (parse/parse-sql content))]
                {:format "edn" :content (with-out-str (pprint/pprint (if (= 1 (count queries)) (first queries) queries)))
                 :note "SQL parsed into editable HoneySQL data."})
        "edn" (let [query (parse/parse-edn content)]
                (if (:sql query)
                  (do (parse/parse-sql (:sql query)) {:format "sql" :content (:sql query) :note "Native SQL restored from EDN."})
                  (let [queries (if (vector? query) query [query])
                        compiled (compile-conversion-queries queries metadata options)
                        values (vec (mapcat :params compiled))
                        sql (tokens/name-parameters (str/join ";\n\n" (map :sql compiled)))]
                    {:format "sql" :content sql :parameters (into {} (map-indexed (fn [index value] [(keyword (str "p" index)) value]) values))
                     :note "HoneySQL data compiled into SQL."})))
        (storage/fail! "Format must be SQL or EDN.")))))
