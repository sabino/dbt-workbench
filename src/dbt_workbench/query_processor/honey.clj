(ns dbt-workbench.query-processor.honey
  (:require [clojure.string :as str]
            [honey.sql :as sql]
            [malli.core :as malli]
            [dbt-workbench.query-processor.parse :as parse]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.query-processor.tokens :as tokens])
  (:import (net.sf.jsqlparser.schema Table Column)
           (net.sf.jsqlparser.expression Function JdbcNamedParameter StringValue LongValue DoubleValue)
           (net.sf.jsqlparser.statement.select PlainSelect ParenthesedSelect Select)))

(def binary-operators
  {"EqualsTo" := "NotEqualsTo" :<> "GreaterThan" :> "GreaterThanEquals" :>= "MinorThan" :< "MinorThanEquals" :<=
   "AndExpression" :and "OrExpression" :or "Addition" :+ "Subtraction" :- "Multiplication" :* "Division" :/ "Modulo" :%})
(defn identifier [value]
  (keyword (tokens/unquote-identifier (str value))))
(defn unsupported! [node]
  (storage/fail! (str "SQL-to-EDN does not support " (.getSimpleName (class node)) ". Keep editing SQL or simplify this construct.")))
(declare convert-select)
(defn convert-expression [node]
  (let [kind (when node (.getSimpleName (class node)))]
    (cond
      (nil? node) nil
      (instance? Column node) (identifier (.getFullyQualifiedName ^Column node))
      (instance? StringValue node) (.getValue ^StringValue node)
      (instance? LongValue node) (.getValue ^LongValue node)
      (instance? DoubleValue node) (.getValue ^DoubleValue node)
      (instance? JdbcNamedParameter node) [:param (keyword (.getName ^JdbcNamedParameter node))]
      (instance? Function node) (let [operator (keyword (str/lower-case (.getName ^Function node)))
                                     args (mapv convert-expression (.getParameters ^Function node))]
                                 (if (.isDistinct ^Function node) [operator (into [:distinct] args)] (into [operator] args)))
      (instance? ParenthesedSelect node) (convert-select (.getSelect ^ParenthesedSelect node))
      (instance? java.util.Collection node) (mapv convert-expression node)
      (binary-operators kind) [(binary-operators kind) (convert-expression (.getLeftExpression node)) (convert-expression (.getRightExpression node))]
      (= kind "AllColumns") :*
      (= kind "AllTableColumns") (identifier (str (.getTable node) ".*"))
      (= kind "NullValue") nil
      (= kind "BooleanValue") (.getValue node)
      (= kind "Parenthesis") (convert-expression (.getExpression node))
      (= kind "SignedExpression") (if (= \- (.getSign node)) [:- (convert-expression (.getExpression node))] (convert-expression (.getExpression node)))
      (= kind "NotExpression") [:not (convert-expression (.getExpression node))]
      (= kind "IsNullExpression") [(if (.isNot node) :is-not :is) (convert-expression (.getLeftExpression node)) nil]
      (= kind "InExpression") [(if (.isNot node) :not-in :in) (convert-expression (.getLeftExpression node)) (convert-expression (.getRightExpression node))]
      (= kind "LikeExpression") (do (when (.getEscape node) (unsupported! node))
                                    [(if (.isNot node) :not-like :like) (convert-expression (.getLeftExpression node)) (convert-expression (.getRightExpression node))])
      (= kind "Between") [(if (.isNot node) :not-between :between) (convert-expression (.getLeftExpression node))
                           (convert-expression (.getBetweenExpressionStart node)) (convert-expression (.getBetweenExpressionEnd node))]
      (= kind "CaseExpression") (let [clauses (mapcat (fn [clause] [(convert-expression (.getWhenExpression clause)) (convert-expression (.getThenExpression clause))]) (.getWhenClauses node))
                                     expression (.getSwitchExpression node)]
                                 (cond-> (into (if expression [:case-expr (convert-expression expression)] [:case]) clauses)
                                   (.getElseExpression node) (into [:else (convert-expression (.getElseExpression node))])))
      :else (unsupported! node))))
(defn convert-source [node]
  (let [source (cond (instance? Table node) (identifier (.getFullyQualifiedName ^Table node))
                     (instance? ParenthesedSelect node) (convert-select (.getSelect ^ParenthesedSelect node))
                     :else (unsupported! node))
        alias (.getAlias node)]
    (if alias [source (identifier (.getName alias))] source)))
(defn convert-projection [item]
  (let [expression (convert-expression (.getExpression item)) alias (.getAlias item)]
    (if alias [expression (identifier (.getName alias))]
        (if (vector? expression) [expression] expression))))
(defn convert-join [form join]
  (when (or (.isNatural join) (.isSimple join) (seq (.getUsingColumns join))) (unsupported! join))
  (let [source (convert-source (.getRightItem join)) conditions (mapv convert-expression (.getOnExpressions join))
        kind (cond (.isLeft join) :left-join (.isRight join) :right-join (.isFull join) :full-join (.isCross join) :cross-join :else :join)]
    (if (= kind :cross-join) (update form :join-by (fnil into []) [:cross [source]])
        (do (when-not (seq conditions) (unsupported! join))
            (update form :join-by (fnil into []) [(case kind :left-join :left :right-join :right :full-join :full :join)
                                                 [source (if (= 1 (count conditions)) (first conditions) (into [:and] conditions))]])))))
(defn convert-order [item]
  (let [nulls (.getNullOrdering item)]
    (cond-> [(convert-expression (.getExpression item)) (if (and (.isAscDescPresent item) (not (.isAsc item))) :desc :asc)]
      nulls (conj (if (= "NULLS_FIRST" (str nulls)) :nulls-first :nulls-last)))))
(defn convert-select [^Select statement]
  (when-not (instance? PlainSelect statement) (unsupported! statement))
  (let [^PlainSelect query statement distinct (.getDistinct query)
        _ (when (and distinct (seq (.getOnSelectItems distinct))) (unsupported! distinct))
        _ (doseq [value [(.getTop query) (.getQualify query) (.getFetch query) (.getForClause query)
                         (.getOracleHierarchical query) (.getSampleClause query)] :when value] (unsupported! value))
        form (cond-> {(if distinct :select-distinct :select) (mapv convert-projection (.getSelectItems query))}
               (.getFromItem query) (assoc :from [(convert-source (.getFromItem query))])
               (.getWhere query) (assoc :where (convert-expression (.getWhere query)))
               (.getGroupBy query) (assoc :group-by (mapv convert-expression (.getGroupByExpressionList (.getGroupBy query))))
               (.getHaving query) (assoc :having (convert-expression (.getHaving query)))
               (seq (.getOrderByElements query)) (assoc :order-by (mapv convert-order (.getOrderByElements query)))
               (.getLimit query) (assoc :limit (convert-expression (.getRowCount (.getLimit query))))
               (.getOffset query) (assoc :offset (convert-expression (.getOffset (.getOffset query))))
               (seq (.getWithItemsList query)) (assoc (if (some #(.isRecursive %) (.getWithItemsList query)) :with-recursive :with)
                                                     (mapv (fn [item]
                                                             (when (seq (.getWithItemList item)) (unsupported! item))
                                                             [(identifier (.getAliasName item)) (convert-select (.getSelect (.getParenthesedStatement item)))]) (.getWithItemsList query))))]
    (reduce convert-join form (.getJoins query))))
(def query-schema
  [:map {:closed true}
   [:select {:optional true} [:vector {:min 1} :any]] [:select-distinct {:optional true} [:vector {:min 1} :any]]
   [:from {:optional true} [:vector :any]] [:where {:optional true} :any] [:having {:optional true} :any]
   [:group-by {:optional true} [:vector :any]] [:order-by {:optional true} [:vector :any]]
   [:join {:optional true} [:vector :any]] [:left-join {:optional true} [:vector :any]] [:right-join {:optional true} [:vector :any]]
   [:full-join {:optional true} [:vector :any]] [:cross-join {:optional true} [:vector :any]]
   [:join-by {:optional true} [:vector :any]]
   [:with {:optional true} [:vector :any]] [:with-recursive {:optional true} [:vector :any]]
   [:limit {:optional true} [:int {:min 0 :max 100000}]] [:offset {:optional true} [:int {:min 0}]]])
(defn compile-query [query parameters]
  (when-not (and (malli/validate query-schema query) (or (seq (:select query)) (seq (:select-distinct query))))
    (storage/fail! "Enter a HoneySQL SELECT map with supported clauses."))
  (let [nodes (tree-seq coll? seq query)
        names (into #{} (keep #(when (and (vector? %) (= :param (first %))) (second %))) nodes)]
    (when (some #(and (vector? %) (#{:raw :inline :lift} (first %))) nodes)
      (storage/fail! "Use HoneySQL data expressions and bound parameters, not raw SQL fragments."))
    (when-not (= names (set (keys parameters))) (storage/fail! "Parameters must match the query's :param names."))
    (let [[statement & values] (try (sql/format query {:quoted true :params parameters})
                                  (catch Exception e (storage/fail! (str "Cannot compile HoneySQL: " (ex-message e)))))
          tree (first (parse/parse-sql statement))]
      {:sql statement :params (vec values) :form query :tables (:tables (parse/collect-native-metadata tree)) :bindings []})))
