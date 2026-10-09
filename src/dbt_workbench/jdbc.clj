(ns dbt-workbench.jdbc
  (:require [clojure.string :as str])
  (:import (java.sql DriverManager PreparedStatement ResultSet)
           (java.util Properties)))

(defn connect! [url & [properties]]
  (DriverManager/getConnection url (doto (Properties.) (.putAll (or properties {})))))
(defn json-value [value]
  (cond
    (nil? value) nil
    (instance? java.math.BigDecimal value) (str value)
    (instance? java.sql.Date value) (str value)
    (instance? java.sql.Timestamp value) (str (.toInstant ^java.sql.Timestamp value))
    (or (string? value) (number? value) (boolean? value)) value
    :else (str value)))
(defn bind! [^PreparedStatement statement parameters]
  (doseq [[index value] (map-indexed vector parameters)] (.setObject statement (inc index) value)))
(defn execute! [connection sql & [parameters]]
  (with-open [statement (.prepareStatement ^java.sql.Connection connection sql)]
    (bind! statement parameters)
    (.execute statement)
    (.getUpdateCount statement)))
(defn read-result! [^ResultSet result max-rows & [value-fn]]
  (let [metadata (.getMetaData result) count (.getColumnCount metadata)
        columns (mapv (fn [index] {:name (.getColumnLabel metadata index) :type (.getColumnTypeName metadata index) :precision (.getPrecision metadata index) :scale (.getScale metadata index)}) (range 1 (inc count)))]
    (loop [rows []]
      (if (and (< (clojure.core/count rows) max-rows) (.next result))
        (recur (conj rows (mapv #((or value-fn json-value) (.getObject result (int %))) (range 1 (inc count)))))
        {:columns columns :rows rows :row_count (clojure.core/count rows) :truncated (.next result)}))))
(defn query! [connection sql & [parameters max-rows]]
  (with-open [statement (.prepareStatement ^java.sql.Connection connection sql)]
    (bind! statement parameters)
    (with-open [result (.executeQuery statement)] (read-result! result (or max-rows 100000)))))
(defn query-maps! [connection sql & [parameters]]
  (let [{:keys [columns rows]} (query! connection sql parameters)]
    (mapv #(zipmap (map (comp keyword :name) columns) %) rows)))
(defn quote-identifier [value]
  (str "\"" (str/replace (str value) "\"" "\"\"") "\""))
(defn quote-literal [value] (str "'" (str/replace (str value) "'" "''") "'"))
