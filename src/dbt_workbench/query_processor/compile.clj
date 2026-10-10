(ns dbt-workbench.query-processor.compile
  (:refer-clojure :exclude [compile])
  (:require [honey.sql :as honey]
            [malli.core :as malli]
            [dbt-workbench.query-processor.honey :as honey-query]
            [dbt-workbench.query-processor.parse :as parse]
            [dbt-workbench.query-processor.store :as store]
            [dbt-workbench.storage :as storage])
  (:import (net.sf.jsqlparser.expression JdbcNamedParameter)))

(def structured-schema
  [:map {:closed true} [:query/version [:= 1]]
   [:from [:map {:closed true} [:model :string] [:as {:optional true} :string] [:binding {:optional true} [:enum :dev :baseline]]]]
   [:select [:vector {:min 1} [:map {:closed true} [:as :string] [:expr :any]]]]
   [:where {:optional true} :any] [:group-by {:optional true} [:vector :any]]
   [:order-by {:optional true} [:vector :any]] [:limit {:optional true} [:int {:min 0 :max 100000}]]])
(def binary-operators {:eq := :ne :<> :gt :> :gte :>= :lt :< :lte :<= :add :+ :+ :+ :sub :- :- :- :mul :* :* :* :and :and :or :or})
(defn valid-date? [value]
  (and (string? value) (try (java.time.LocalDate/parse value) true (catch Exception _ false))))
(defn compile-expression [alias parameters value]
  (if-not (vector? value)
    (if (or (nil? value) (string? value) (number? value) (boolean? value)) value (storage/fail! "Expressions require literals or operator vectors."))
    (let [[operator & args] value]
      (cond
        (= :field operator) (do (when-not (and (= 2 (count args)) (= alias (first args)) (string? (second args))) (storage/fail! "Fields require the declared alias and column name."))
                               (keyword (str alias "." (second args))))
        (= :param operator) (let [[param kind] args key (keyword param) value (get parameters key ::missing)]
                              (when (or (not= 2 (count args)) (= ::missing value) (not (#{:text :integer :float :boolean :date} kind)))
                                (storage/fail! (str "Missing or invalid parameter " param ".")))
                              (when-not (or (nil? value) (case kind :text (string? value) :integer (integer? value) :float (number? value) :boolean (boolean? value) :date (valid-date? value)))
                                (storage/fail! (str "Parameter " param " has the wrong type.")))
                              (keyword (str "?" param)))
        (and (binary-operators operator) (= 2 (count args))) (into [(binary-operators operator)] (map #(compile-expression alias parameters %) args))
        (and (#{:div :/} operator) (= 2 (count args))) [:/ [:cast (compile-expression alias parameters (first args)) :double] [:nullif [:cast (compile-expression alias parameters (second args)) :double] 0]]
        (and (= :not operator) (= 1 (count args))) [:not (compile-expression alias parameters (first args))]
        (and (= :is-null operator) (= 1 (count args))) [:= (compile-expression alias parameters (first args)) nil]
        (and (#{:sum :count :min :max :avg} operator) (or (= 1 (count args)) (and (= :count operator) (empty? args)))) [operator (if (seq args) (compile-expression alias parameters (first args)) :*)]
        (and (= :coalesce operator) (<= 2 (count args))) (into [:coalesce] (map #(compile-expression alias parameters %) args))
        :else (storage/fail! (str "Unsupported operator or arity: " operator))))))
(defn compile-structured [query store parameters dialect logical? & [{:keys [inline?]}]]
  (when-not (malli/validate structured-schema query) (storage/fail! "Invalid structured EDN query. Check its fields and operator values."))
  (let [alias (or (get-in query [:from :as]) "m") source (store/resolve-source store (:from query) logical?)
        expr #(compile-expression alias parameters %)
        form (cond-> {:select (mapv (fn [projection] [(expr (:expr projection)) (keyword (:as projection))]) (:select query))
                      :from [[(keyword (:relation source)) (keyword alias)]]}
               (contains? query :where) (assoc :where (expr (:where query)))
               (contains? query :group-by) (assoc :group-by (mapv expr (:group-by query)))
               (contains? query :order-by) (assoc :order-by (mapv (fn [item] (if (map? item)
                                                                           (do (when-not (#{:asc :desc} (or (:direction item) :asc)) (storage/fail! "Order direction must be :asc or :desc."))
                                                                               [(expr (:expr item)) (or (:direction item) :asc) :nulls-last]) [(expr item) :asc :nulls-last])) (:order-by query)))
               (contains? query :limit) (assoc :limit (:limit query)))
        names (into #{} (keep #(when (and (vector? %) (= :param (first %))) (keyword (second %)))) (tree-seq coll? seq query))]
    (when-not (= names (set (keys parameters))) (storage/fail! "Parameters must match exactly the query's declared parameters."))
    (let [[sql & params] (honey/format form {:dialect (if (= "sqlite" dialect) :ansi :ansi) :quoted true :params parameters :inline (boolean inline?)})]
      {:sql sql :params (vec params) :form form :bindings [source]})))
(defn compile-native [sql parameters]
  (let [statements (parse/parse-sql sql)]
    (when-not (= 1 (count statements)) (storage/fail! "Select one statement or use Run all."))
    (let [statement (first statements) metadata (parse/collect-native-metadata statement)
          names (mapv #(keyword (.getName ^JdbcNamedParameter %)) (:parameters metadata))]
      (when-not (= (set names) (set (keys parameters))) (storage/fail! "Parameters must match exactly the named SQL parameters."))
      {:sql sql :params (mapv parameters names) :tree statement :tables (:tables metadata) :bindings []})))
(defn compile [query store options logical?]
  (cond
    (:sql query) (compile-native (:sql query) (or (:parameters options) {}))
    (:query/version query) (compile-structured query store (or (:parameters options) {}) (:dialect options) logical? (select-keys options [:inline?]))
    :else (honey-query/compile-query query (or (:parameters options) {}) (select-keys options [:inline?]))))
