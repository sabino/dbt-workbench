(ns workbench.builder
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.dom :as dom]))

(def clauses #{:select :select-distinct :from :where :group-by :order-by :limit :offset :join-by})
(def operations [:count :sum :avg :min :max])
(def comparisons [:= :<> :> :>= :< :<= :like :is :is-not])
(defn label [identifier] (if (keyword? identifier) (name identifier) (str identifier)))
(defn source-name [source] (if (vector? source) (first source) source))
(defn source-alias [source] (when (vector? source) (second source)))
(defn projections [query] (or (:select query) (:select-distinct query)))
(defn aggregate? [projection]
  (and (vector? projection) (vector? (first projection)) (some #{(ffirst projection)} operations)))
(defn columns [query] (vec (remove aggregate? (projections query))))
(defn aggregates [query] (vec (filter aggregate? (projections query))))
(defn filters [query]
  (let [condition (:where query)]
    (cond (nil? condition) [] (#{:and :or} (first condition)) (vec (rest condition)) :else [condition])))
(defn supported? [query]
  (and (map? query) (every? clauses (keys query)) (= 1 (count (:from query)))
       (keyword? (source-name (first (:from query))))
       (every? #(or (keyword? %) (and (aggregate? %) (= 2 (count %)) (= 2 (count (first %))) (keyword? (second (first %))) (keyword? (second %)))) (projections query))
       (every? #(and (vector? %) (= 3 (count %)) (some #{(first %)} comparisons) (keyword? (second %))
                      (or (nil? (nth % 2)) (string? (nth % 2)) (number? (nth % 2)) (boolean? (nth % 2))
                          (and (vector? (nth % 2)) (= :param (first (nth % 2)))))) (filters query))
       (every? keyword? (:group-by query))
       (every? #(and (vector? %) (keyword? (first %)) (#{:asc :desc} (second %))) (:order-by query))
       (every? (fn [[kind [source condition]]] (and (#{:join :left :right :full} kind) (keyword? (source-name source))
                                                   (= := (first condition)) (every? keyword? (rest condition)))) (partition 2 (:join-by query)))))
(defn set-projections [query values]
  (assoc query (if (:select-distinct query) :select-distinct :select) (if (seq values) (vec values) [:*])))
(defn set-filter-list [query conditions]
  (if (seq conditions) (assoc query :where (if (= 1 (count conditions)) (first conditions) (into [(if (= :or (first (:where query))) :or :and)] conditions))) (dissoc query :where)))
(defn replace-item [items index value] (assoc (vec items) index value))
(defn remove-item [items index] (into [] (keep-indexed #(when (not= %1 index) %2)) items))
(defn set-list [query key values] (if (seq values) (assoc query key (vec values)) (dissoc query key)))
(defn relation-fields [catalog source]
  (let [relation (first (filter #(= (:name %) (label (source-name source))) catalog)) qualifier (or (source-alias source) (source-name source))]
    (mapv #(keyword (str (label qualifier) "." (:name %))) (:columns relation))))
(defn qualify-source-columns [query catalog]
  (let [source (first (:from query)) relation (first (filter #(= (:name %) (label (source-name source))) catalog))
        names (set (map #(keyword (:name %)) (:columns relation)))
        qualifier (label (or (source-alias source) (source-name source)))
        qualify #(if (names %) (keyword (str qualifier "." (label %))) %)
        qualify-projection #(if (aggregate? %) (update-in % [0 1] qualify) (qualify %))]
    (cond-> (set-projections query (mapv qualify-projection (projections query)))
      (:where query) (set-filter-list (mapv #(update % 1 qualify) (filters query)))
      (:group-by query) (update :group-by #(mapv qualify %))
      (:order-by query) (update :order-by #(mapv (fn [item] (update item 0 qualify)) %)))))
(defn available-fields [catalog query]
  (vec (distinct (mapcat #(relation-fields catalog %) (cons (first (:from query)) (map (comp first second) (partition 2 (:join-by query))))))))
(defn select-control [title value choices on-change]
  (dom/select #js {:aria-label title :value (label value) :onChange #(on-change (.. % -target -value))}
    (map (fn [[key text]] (dom/option #js {:key (label key) :value (label key)} text)) choices)))
(defn field-control [title value fields on-change]
  (select-control title value (mapv #(vector % (label %)) (distinct (cons value fields))) #(on-change (keyword %))))
(defn action [title on-click]
  (dom/button #js {:type "button" :className "button small" :onClick on-click :onMouseDown #(.preventDefault %)} title))
(defn section [title content]
  (dom/section #js {:className "builder-step"} (dom/h3 {} title) content))
(defn value-input [title value on-change]
  (let [parameter? (and (vector? value) (= :param (first value))) kind (cond parameter? "parameter" (number? value) "number" (boolean? value) "boolean" :else "text")]
    (dom/div #js {:className "builder-value"}
      (select-control (str title " type") kind [["text" "Text"] ["number" "Number"] ["boolean" "Boolean"] ["parameter" "Parameter"]]
        #(on-change (case % "number" 0 "boolean" true "parameter" [:param :value] "")))
      (if (= kind "boolean")
        (select-control title (str value) [["true" "True"] ["false" "False"]] #(on-change (= % "true")))
        (dom/input #js {:aria-label title :type (if (= kind "number") "number" "text") :value (if parameter? (label (second value)) (or value ""))
                        :onChange #(let [text (.. % -target -value)] (on-change (case kind "number" (if (str/blank? text) 0 (js/Number text)) "parameter" [:param (keyword text)] text)))})))))
(defn query-builder [query catalog on-change]
  (let [fields (available-fields catalog query) source (first (:from query)) all-columns (columns query) metrics (aggregates query)
        conditions (filters query) groups (:group-by query) sorts (:order-by query) joins (vec (partition 2 (:join-by query)))
        update-columns #(on-change (set-projections query (concat % metrics)))
        update-metrics #(on-change (set-projections query (concat (remove #{:*} all-columns) %)))
        update-filters #(on-change (set-filter-list query %))]
    (dom/div #js {:className "visual-query-builder" :aria-label "Visual query builder"}
      (section "Data"
        (dom/div #js {:className "builder-row"}
          (select-control "Source table" (source-name source) (mapv #(vector (:name %) (:name %)) catalog)
            #(on-change {:select [:*] :from [(keyword %)]}))
          (dom/span #js {:className "muted"} "Choose a table, then shape the query below.")))
      (section "Columns"
        (dom/div {}
          (dom/div #js {:className "builder-chips"}
            (map-indexed (fn [index column]
                           (dom/div #js {:className "builder-chip" :key index}
                             (field-control (str "Column " (inc index)) column (cons :* fields) #(update-columns (replace-item all-columns index %)))
                             (action "Remove column" #(update-columns (remove-item all-columns index))))) all-columns))
          (action "+ Column" #(update-columns (conj (vec (remove #{:*} all-columns)) (or (first fields) :*))))
          (dom/label #js {:className "check-control"}
            (dom/input #js {:type "checkbox" :checked (boolean (:select-distinct query))
                            :onChange #(on-change (if (.. % -target -checked) (-> query (dissoc :select) (assoc :select-distinct (projections query))) (-> query (dissoc :select-distinct) (assoc :select (projections query)))))}) "Distinct rows")))
      (section "Filter"
        (dom/div {}
          (when (> (count conditions) 1)
            (select-control "Filter combination" (if (= :or (first (:where query))) :or :and) [[:and "Match all conditions"] [:or "Match any condition"]]
              #(on-change (assoc query :where (into [(keyword %)] conditions)))))
          (map-indexed (fn [index [operator field value]]
                         (let [update-condition #(update-filters (replace-item conditions index %))]
                           (dom/div #js {:className "builder-row" :key index}
                             (field-control (str "Filter " (inc index) " field") field fields #(update-condition [operator % value]))
                             (select-control (str "Filter " (inc index) " operator") operator (mapv #(vector % (label %)) comparisons) #(update-condition [(keyword %) field (if (#{"is" "is-not"} %) nil value)]))
                             (when-not (#{:is :is-not} operator) (value-input (str "Filter " (inc index) " value") value #(update-condition [operator field %])))
                             (action "Remove filter" #(update-filters (remove-item conditions index)))))) conditions)
          (action "+ Filter" #(update-filters (conj conditions [:= (or (first fields) :id) ""])))))
      (section "Summarize"
        (dom/div {}
          (map-indexed (fn [index [[operator field] alias]]
                         (let [update-metric #(update-metrics (replace-item metrics index %))]
                           (dom/div #js {:className "builder-row" :key index}
                             (select-control (str "Aggregation " (inc index)) operator (mapv #(vector % (str/upper-case (label %))) operations) #(update-metric [[(keyword %) field] alias]))
                             (field-control (str "Aggregation " (inc index) " field") field (cons :* fields) #(update-metric [[operator %] alias]))
                             (dom/input #js {:aria-label (str "Aggregation " (inc index) " name") :value (label alias) :onChange #(update-metric [[operator field] (keyword (.. % -target -value))])})
                             (action "Remove aggregation" #(update-metrics (remove-item metrics index)))))) metrics)
          (action "+ Aggregation" #(update-metrics (conj metrics [[:count :*] (keyword (str "count_" (inc (count metrics))))])))
          (dom/div #js {:className "builder-chips"}
            (map-indexed (fn [index field] (dom/div #js {:className "builder-chip" :key index}
                                            (field-control (str "Group " (inc index)) field fields #(on-change (assoc query :group-by (replace-item groups index %))))
                                            (action "Remove group" #(on-change (set-list query :group-by (remove-item groups index)))))) groups))
          (action "+ Group by" #(on-change (let [field (or (first fields) :id)] (-> query (assoc :group-by (conj (vec groups) field)) (set-projections (concat (distinct (conj (vec (remove #{:*} all-columns)) field)) metrics))))))))
      (section "Join"
        (dom/div {}
          (map-indexed (fn [index [kind [table [_ left right]]]]
                         (let [update-join #(on-change (assoc query :join-by (vec (mapcat identity (replace-item joins index %)))))]
                           (dom/div #js {:className "builder-row" :key index}
                             (select-control (str "Join " (inc index) " type") kind [[:left "Left join"] [:join "Inner join"] [:right "Right join"] [:full "Full join"]] #(update-join [(keyword %) [table [:= left right]]]))
                             (select-control (str "Join " (inc index) " table") (source-name table) (mapv #(vector (:name %) (:name %)) catalog)
                               #(let [source [(keyword %) (keyword (str "j" (inc index)))]] (update-join [kind [source [:= left (or (first (relation-fields catalog source)) :id)]]])))
                             (field-control (str "Join " (inc index) " left field") left fields #(update-join [kind [table [:= % right]]]))
                             (field-control (str "Join " (inc index) " right field") right (relation-fields catalog table) #(update-join [kind [table [:= left %]]]))
                             (action "Remove join" #(on-change (set-list query :join-by (mapcat identity (remove-item joins index)))))))) joins)
          (action "+ Join" #(let [table [(keyword (:name (first catalog))) (keyword (str "j" (inc (count joins))))]]
                              (on-change (update (qualify-source-columns query catalog) :join-by (fnil into []) [:left [table [:= (or (first fields) :id) (or (first (relation-fields catalog table)) :id)]]]))))))
      (section "Sort and limit"
        (dom/div {}
          (map-indexed (fn [index [field direction & nulls]]
                         (dom/div #js {:className "builder-row" :key index}
                           (field-control (str "Sort " (inc index) " field") field (concat fields (map second metrics)) #(on-change (assoc query :order-by (replace-item sorts index (into [% direction] nulls)))))
                           (select-control (str "Sort " (inc index) " direction") direction [[:asc "Ascending"] [:desc "Descending"]] #(on-change (assoc query :order-by (replace-item sorts index (into [field (keyword %)] nulls)))))
                           (action "Remove sort" #(on-change (set-list query :order-by (remove-item sorts index)))))) sorts)
          (action "+ Sort" #(on-change (assoc query :order-by (conj (vec sorts) [(or (first fields) :id) :asc]))))
          (dom/div #js {:className "builder-row"}
            (dom/label {} "Row limit" (dom/input #js {:aria-label "Visual row limit" :type "number" :min 0 :max 100000 :placeholder "No SQL limit" :value (or (:limit query) "")
                                                     :onChange #(let [value (.. % -target -value)] (on-change (if (str/blank? value) (dissoc query :limit) (assoc query :limit (js/Number value)))))}))
            (dom/label {} "Offset" (dom/input #js {:aria-label "Visual offset" :type "number" :min 0 :value (or (:offset query) "")
                                                  :onChange #(let [value (.. % -target -value)] (on-change (if (str/blank? value) (dissoc query :offset) (assoc query :offset (js/Number value)))))}))))))))
