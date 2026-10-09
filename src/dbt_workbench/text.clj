(ns dbt-workbench.text
  (:require [clojure.string :as str]))

(defn split-on [text separator]
  (loop [start 0 parts []]
    (let [index (.indexOf ^String text ^String separator (int start))]
      (if (neg? index) (conj parts (subs text start))
          (recur (+ index (count separator)) (conj parts (subs text start index)))))))
(defn split-words [text] (vec (enumeration-seq (java.util.StringTokenizer. text))))
(defn remove-suffix [text suffix]
  (if (str/ends-with? text suffix) (subs text 0 (- (count text) (count suffix))) text))
(defn numeric-type? [type]
  (let [type (str/upper-case type)] (some #(str/includes? type %) ["INT" "DECIMAL" "DOUBLE" "FLOAT" "REAL" "NUMERIC"])))
