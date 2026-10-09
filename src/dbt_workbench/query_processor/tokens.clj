(ns dbt-workbench.query-processor.tokens
  (:require [clojure.string :as str]))

(defn word-start? [character] (and character (or (Character/isLetter ^char character) (= character \_))))
(defn word-part? [character] (and character (or (word-start? character) (Character/isDigit ^char character))))
(defn scan-while [text start predicate]
  (loop [index start] (if (and (< index (count text)) (predicate (.charAt ^String text index))) (recur (inc index)) index)))
(defn scan-quoted [text start quote escaped?]
  (loop [index (inc start)]
    (cond
      (>= index (count text)) index
      (and escaped? (= \\ (.charAt ^String text index))) (recur (min (count text) (+ index 2)))
      (= quote (.charAt ^String text index)) (if (and (< (inc index) (count text)) (= quote (.charAt ^String text (inc index)))) (recur (+ index 2)) (inc index))
      :else (recur (inc index)))))
(defn scan-block [text start]
  (loop [index (+ start 2) depth 1]
    (cond (>= index (count text)) index
          (.startsWith ^String text "/*" index) (recur (+ index 2) (inc depth))
          (.startsWith ^String text "*/" index) (if (= depth 1) (+ index 2) (recur (+ index 2) (dec depth)))
          :else (recur (inc index) depth))))
(defn dollar-delimiter [text start]
  (let [end (scan-while text (inc start) word-part?)]
    (when (and (< end (count text)) (= \$ (.charAt ^String text end))
               (or (= end (inc start)) (word-start? (.charAt ^String text (inc start)))))
      (subs text start (inc end)))))
(defn tokenize [text]
  (loop [start 0 tokens []]
    (if (>= start (count text)) tokens
        (let [character (.charAt ^String text start) next-char (when (< (inc start) (count text)) (.charAt ^String text (inc start)))
              dollar (when (= character \$) (dollar-delimiter text start))
              [kind end] (cond
                           (Character/isWhitespace character) [:whitespace (scan-while text start #(Character/isWhitespace ^char %))]
                           (.startsWith ^String text "--" start) [:comment (scan-while text start #(not (#{\newline \return} %)))]
                           (.startsWith ^String text "/*" start) [:comment (scan-block text start)]
                           (#{\' \" \`} character) [(if (= character \') :string :identifier)
                                                    (scan-quoted text start character (and (= character \') (pos? start) (#{\e \E} (.charAt ^String text (dec start))) (or (= start 1) (not (word-part? (.charAt ^String text (- start 2)))))))]
                           dollar [:string (let [end (.indexOf ^String text ^String dollar (int (+ start (count dollar))))] (if (neg? end) (count text) (+ end (count dollar))))]
                           (and (= character \:) (not= next-char \:) (or (zero? start) (not= \: (.charAt ^String text (dec start)))) (word-start? next-char)) [:named-parameter (scan-while text (inc start) word-part?)]
                           (= character \?) [:parameter (inc start)]
                           (word-start? character) [:word (scan-while text start word-part?)]
                           :else [:symbol (inc start)])]
          (recur end (conj tokens {:kind kind :text (subs text start end) :from start :to end}))))))
(defn normalize-for-parser [sql]
  (apply str (map (fn [{:keys [kind text]}]
                    (cond (and (= kind :word) (= "final" (str/lower-case text))) "\"final\""
                          (= kind :whitespace) (if (or (str/includes? text "\n") (str/includes? text "\r")) "\n" " ")
                          :else text)) (tokenize sql))))
(defn bind-parameters [sql parameters]
  (let [tokens (tokenize sql) names (mapv #(keyword (subs (:text %) 1)) (filter #(= :named-parameter (:kind %)) tokens))]
    {:sql (apply str (map #(if (= :named-parameter (:kind %)) "?" (:text %)) tokens)) :params (mapv parameters names)}))
(defn name-parameters [sql]
  (let [tokens (tokenize sql)]
    (first (reduce (fn [[text index] token]
                     (if (= :parameter (:kind token)) [(str text ":p" index) (inc index)] [(str text (:text token)) index])) ["" 0] tokens))))
(defn unquote-identifier [value]
  (apply str (map (fn [{:keys [kind text]}]
                    (if (= kind :identifier) (str/replace (subs text 1 (dec (count text))) "\"\"" "\"") text)) (tokenize value))))
