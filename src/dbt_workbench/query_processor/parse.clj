(ns dbt-workbench.query-processor.parse
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.query-processor.tokens :as tokens])
  (:import (java.io PushbackReader StringReader)
           (net.sf.jsqlparser.parser CCJSqlParserUtil)
           (net.sf.jsqlparser.statement.select Select PlainSelect)
           (net.sf.jsqlparser.expression Function JdbcNamedParameter)
           (net.sf.jsqlparser.schema Table)
           (net.sf.jsqlparser.util TablesNamesFinder)))

(def safe-functions #{"abs" "avg" "ceil" "ceiling" "coalesce" "concat" "count" "floor" "greatest" "least" "length" "lower" "max" "min" "nullif" "round" "sum" "upper" "substring" "substr" "trim" "date" "date_trunc" "strftime" "row_number" "rank" "dense_rank" "lag" "lead" "first_value" "last_value" "if" "any_value"})
(defn ast-children [node]
  (->> (.getMethods (class node))
       (filter #(and (zero? (.getParameterCount ^java.lang.reflect.Method %))
                     (str/starts-with? (.getName ^java.lang.reflect.Method %) "get")
                     (not (#{"getASTNode" "getClass"} (.getName ^java.lang.reflect.Method %)))))
       (mapcat (fn [method]
                 (let [value (try (.invoke ^java.lang.reflect.Method method node (object-array 0)) (catch Exception _ nil))]
                   (cond (instance? java.util.Collection value) (seq value)
                         (and value (str/starts-with? (.getName (class value)) "net.sf.jsqlparser.")) [value]
                         :else []))))
       (filter #(and % (str/starts-with? (.getName (class %)) "net.sf.jsqlparser."))) vec))
(defn ast-nodes [root]
  (let [seen (java.util.IdentityHashMap.)]
    (loop [todo [root] nodes []]
      (if-let [node (peek todo)]
        (if (.containsKey seen node)
          (recur (pop todo) nodes)
          (do (.put seen node true) (recur (into (pop todo) (ast-children node)) (conj nodes node))))
        nodes))))
(defn parse-sql [sql]
  (when (or (not (string? sql)) (str/blank? sql)) (storage/fail! "SQL content is empty."))
  (when (some #(str/includes? sql %) ["{{" "{%" "{#"]) (storage/fail! "Compile dbt Jinja before running a query."))
  (let [statements (try (.getStatements (CCJSqlParserUtil/parseStatements (tokens/normalize-for-parser sql)))
                        (catch Exception e (storage/fail! (str "Cannot parse SQL: " (.getMessage e)))))]
    (when-not (<= 1 (count statements) 100) (storage/fail! "Run between 1 and 100 statements."))
    (doseq [statement statements]
      (when-not (instance? Select statement) (storage/fail! "Only read-only SELECT or WITH queries are allowed."))
      (doseq [node (ast-nodes statement)]
        (when (and (instance? Function node) (not (safe-functions (str/lower-case (or (.getName ^Function node) "unsupported")))))
          (storage/fail! (str "Function " (or (.getName ^Function node) "unsupported") " is outside the read-only vocabulary.")))
        (when (and (instance? Table node) (some #(str/includes? (.getFullyQualifiedName ^Table node) %) ["/" "\\" "'" (str (char 0))]))
          (storage/fail! "Queries can read database relations, not filesystem paths."))
        (when (and (instance? PlainSelect node) (seq (.getIntoTables ^PlainSelect node)))
          (storage/fail! "SELECT INTO is not read-only."))))
    (vec statements)))
(defn parse-edn [content]
  (try
    (with-open [reader (PushbackReader. (StringReader. content))]
      (let [value (edn/read {:eof ::eof} reader)]
        (when-not (= ::eof (edn/read {:eof ::eof} reader)) (storage/fail! "Enter one EDN query."))
        (when-not (or (map? value) (and (vector? value) (<= 1 (count value) 100) (every? map? value)))
          (storage/fail! "Enter a HoneySQL query map, or a vector of query maps."))
        value))
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Exception e (storage/fail! (str "Cannot parse EDN: " (.getMessage e))))))
(defn collect-native-metadata [statement]
  (let [nodes (ast-nodes statement)
        names (.getTables (TablesNamesFinder.) statement)
        sources (mapcat (fn [node]
                          (for [method (.getMethods (class node))
                                :when (and (zero? (.getParameterCount ^java.lang.reflect.Method method))
                                           (#{"getFromItem" "getRightItem"} (.getName ^java.lang.reflect.Method method)))]
                            (.invoke ^java.lang.reflect.Method method node (object-array 0)))) nodes)]
    {:tables (filterv #(and (instance? Table %) (contains? names (.getFullyQualifiedName ^Table %))) sources)
     :parameters (filterv #(instance? JdbcNamedParameter %) nodes)}))
