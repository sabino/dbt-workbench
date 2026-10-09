(ns workbench.core
  (:require [clojure.string :as str]
            [com.fulcrologic.fulcro.application :as app]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.dom :as dom]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]))

(declare application load-project! load-file! select-model! select-job! load-result!
         submit! refresh-project! save-file! create-demo! open-project! cancel-job! import-baseline!)

(def default-edn "{:query/version 1\n :from {:model \"model.jaffle_shop.orders\" :as \"o\"}\n :select [{:as \"customer_id\" :expr [:field \"o\" \"customer_id\"]}\n          {:as \"revenue\" :expr [:sum [:field \"o\" \"amount\"]]}]\n :where [:eq [:field \"o\" \"status\"] [:param \"status\" :text]]\n :group-by [[:field \"o\" \"customer_id\"]]\n :order-by [[:field \"o\" \"customer_id\"]]}")
(def default-sql "SELECT customer_id, SUM(amount) AS revenue\nFROM orders\nGROUP BY customer_id\nORDER BY customer_id")
(def initial
  {:ui/projects [] :ui/project nil :ui/models [] :ui/jobs [] :ui/files []
   :ui/results [] :ui/target "dev" :ui/defer true :ui/favor-state false
   :ui/tab "model" :ui/model-id nil :ui/file nil :ui/content "" :ui/saved-content ""
   :ui/selected-job nil :ui/events [] :ui/event-cursor 0 :ui/result nil :ui/page nil
   :ui/error nil :ui/notice nil :ui/submitting false :ui/open-path "" :ui/profiles ""
   :ui/query-format "edn" :ui/query-edn default-edn :ui/query-sql default-sql
   :ui/parameters "{\"status\": \"completed\"}" :ui/sql-parameters "{}" :ui/dialect "duckdb" :ui/baseline-input false
   :ui/left-result "" :ui/right-result "" :ui/compare-keys "" :ui/conflict false :ui/artifact-dir "" :ui/reference-target "prod"})

(defmutation change [{:keys [updates]}]
  (action [{:keys [state]}] (swap! state merge updates)))

(defmutation replace-entities [{:keys [table root records id-key]}]
  (action [{:keys [state]}]
    (swap! state
      (fn [s]
        (let [entities (mapv #(assoc % table (get % id-key)) records)]
          (-> s
            (assoc table (into {} (map (juxt table identity) entities)))
            (assoc root (mapv #(vector table (get % table)) entities))))))))

(defn current [] (app/current-state application))
(defn patch! [updates] (comp/transact! application [(change {:updates updates})]))
(defn value! [key event] (patch! {key (.. event -target -value)}))
(defn checked! [key event] (patch! {key (.. event -target -checked)}))
(defn project-id [] (get-in (current) [:ui/project :id]))
(defn dirty? [] (not= (:ui/content (current)) (:ui/saved-content (current))))
(defn may-discard? [] (or (not (dirty?)) (js/confirm "Discard the unsaved changes in this editor?")))
(defn api-path [suffix] (str "/api/projects/" (project-id) suffix))
(defn human-error [x]
  (cond (string? x) x (map? x) (or (:message x) (pr-str x)) :else (str x)))
(defn report-error! [error]
  (patch! {:ui/error (or (.-message error) (str error)) :ui/submitting false}))
(defn request! [method url & [body]]
  (-> (js/fetch url
        (clj->js (cond-> {:method method :headers {"Content-Type" "application/json"}}
                   body (assoc :body (js/JSON.stringify (clj->js body))))))
    (.then (fn [response]
             (-> (.json response)
               (.then (fn [data]
                        (let [data (js->clj data :keywordize-keys true)]
                          (if (.-ok response)
                            data
                            (let [error (js/Error. (human-error (or (:detail data) (:error data) data)))]
                              (aset error "status" (.-status response))
                              (throw error)))))))))))

(defn entities! [table root id-key records]
  (comp/transact! application [(replace-entities {:table table :root root :id-key id-key :records records})]))
(defn load-projects! []
  (-> (request! "GET" "/api/projects")
    (.then #(patch! {:ui/projects (:projects %)})) (.catch report-error!)))
(defn load-models! [id]
  (-> (request! "GET" (str "/api/projects/" id "/models"))
    (.then (fn [data]
             (when (= id (project-id))
               (entities! :model/id :ui/models :unique_id (:models data)))))
    (.catch report-error!)))
(defn load-files! [id]
  (-> (request! "GET" (str "/api/projects/" id "/files"))
    (.then #(when (= id (project-id)) (patch! {:ui/files (:files %)}))) (.catch report-error!)))
(defn load-results! [id]
  (-> (request! "GET" (str "/api/projects/" id "/results"))
    (.then #(when (= id (project-id)) (patch! {:ui/results (:results %)}))) (.catch report-error!)))
(defn refresh-project! []
  (when-let [id (project-id)]
    (-> (request! "GET" (str "/api/projects/" id))
      (.then #(when (= id (project-id)) (patch! {:ui/project %}))) (.catch report-error!))
    (load-models! id) (load-files! id) (load-results! id)))
(defn load-project! [id]
  (when (may-discard?)
    (patch! {:ui/error nil :ui/notice nil :ui/file nil :ui/content "" :ui/saved-content ""
           :ui/models [] :ui/jobs [] :ui/events [] :ui/event-cursor 0 :ui/selected-job nil
           :ui/result nil :ui/page nil :ui/results [] :ui/model-id nil :ui/conflict false})
  (-> (request! "GET" (str "/api/projects/" id))
    (.then (fn [project]
             (let [targets (:targets project)
                   target (if (some #{"dev"} targets) "dev" (or (first targets) "dev"))]
               (patch! {:ui/project project :ui/target target :ui/tab "model"})
               (load-projects!) (load-models! id) (load-files! id) (load-results! id))))
    (.catch report-error!))))
(defn create-demo! []
  (patch! {:ui/submitting true :ui/error nil})
  (-> (request! "POST" "/api/projects/demo" {})
    (.then #(do (patch! {:ui/submitting false}) (load-project! (:id %)))) (.catch report-error!)))
(defn open-project! []
  (let [s (current) path (str/trim (:ui/open-path s))]
    (if (str/blank? path)
      (patch! {:ui/error "Enter the local directory containing dbt_project.yml."})
      (do (patch! {:ui/submitting true :ui/error nil})
        (-> (request! "POST" "/api/projects/open"
              (cond-> {:path path} (not (str/blank? (:ui/profiles s))) (assoc :profiles_dir (:ui/profiles s))))
          (.then #(do (patch! {:ui/submitting false}) (load-project! (:id %)))) (.catch report-error!))))))
(defn load-file! [path]
  (when (may-discard?)
    (let [id (project-id)]
    (-> (request! "GET" (str "/api/projects/" id "/file?path=" (js/encodeURIComponent path)))
      (.then (fn [file]
               (when (= id (project-id))
                 (patch! {:ui/file file :ui/content (:content file) :ui/saved-content (:content file)
                          :ui/model-id (:unique_id (first (filter #(= path (:path %)) (vals (:model/id (current))))))
                          :ui/tab "model" :ui/conflict false :ui/error nil}))))
      (.catch report-error!)))))
(defn select-model! [model]
  (when (:path model) (load-file! (:path model))))
(defn save-file! []
  (let [s (current) file (:ui/file s)]
    (when file
      (patch! {:ui/submitting true :ui/error nil})
      (-> (request! "PUT" (api-path "/file")
            {:path (:path file) :content (:ui/content s) :expected_revision (:revision file)})
        (.then #(do (patch! {:ui/file % :ui/saved-content (:content %) :ui/content (:content %)
                            :ui/submitting false :ui/conflict false :ui/notice "File saved to your project."})
                     (load-models! (project-id))))
        (.catch (fn [e]
                  (if (and (= 409 (.-status e)) (not (str/starts-with? (.-message e) "Wait for")))
                    (patch! {:ui/conflict true :ui/submitting false
                             :ui/error "This file changed on disk. Your draft is preserved. Copy it before reloading, or keep editing."})
                    (report-error! e))))))))
(defn job-options []
  (let [s (current)] {:target (:ui/target s) :defer (:ui/defer s) :favor_state (:ui/favor-state s)}))
(defn submit! [kind options]
  (patch! {:ui/submitting true :ui/error nil :ui/notice nil})
  (-> (request! "POST" (api-path "/jobs") {:kind kind :options (merge (job-options) options)})
    (.then (fn [job]
             (patch! {:ui/submitting false})
             (select-job! (:id job))
             (when (#{"query" "preview" "compare"} kind) (patch! {:ui/notice "Query queued. Results will open when it finishes."}))))
    (.catch report-error!)))
(defn select-job! [id]
  (patch! {:ui/selected-job id :ui/events [] :ui/event-cursor 0}))
(defn cancel-job! [id]
  (-> (request! "POST" (str "/api/jobs/" id "/cancel") {})
    (.then #(patch! {:ui/notice (str "Cancellation requested for " (:kind %) ".")}))
    (.catch report-error!)))
(defn load-result! [id & [offset]]
  (let [offset (or offset 0)]
    (-> (js/Promise.all #js [(request! "GET" (str "/api/results/" id))
                             (request! "GET" (str "/api/results/" id "/rows?offset=" offset "&limit=100"))])
      (.then (fn [data]
               (patch! {:ui/result (aget data 0) :ui/page (aget data 1) :ui/tab "results" :ui/error nil})))
      (.catch report-error!))))
(defn run-query! []
  (let [s (current) input (if (= "edn" (:ui/query-format s)) (:ui/parameters s) (:ui/sql-parameters s))]
    (try
      (let [parameters (js->clj (js/JSON.parse (if (str/blank? input) "{}" input)) :keywordize-keys true)]
        (when-not (map? parameters) (throw (js/Error. "Parameters must be a JSON object.")))
        (submit! "query" {:content (if (= "edn" (:ui/query-format s)) (:ui/query-edn s) (:ui/query-sql s))
                          :format (:ui/query-format s) :dialect (:ui/dialect s) :parameters parameters}))
      (catch :default e (report-error! e)))))
(defn run-preview! []
  (let [s (current)]
    (submit! "preview" {:model_id (:ui/model-id s) :dialect (:ui/dialect s) :baseline (:ui/baseline-input s)})))
(defn run-compare! []
  (let [s (current)]
    (submit! "compare" {:left_result_id (:ui/left-result s) :right_result_id (:ui/right-result s)
                        :key_columns (into [] (remove str/blank?) (map str/trim (str/split (:ui/compare-keys s) #",")))})))
(defn import-baseline! []
  (let [s (current)]
    (patch! {:ui/submitting true :ui/error nil})
    (-> (request! "POST" (api-path "/baseline/import")
          {:artifact_dir (:ui/artifact-dir s) :target (:ui/reference-target s)})
      (.then #(do (patch! {:ui/submitting false :ui/notice "Reference state imported. Your next build can defer to it."})
                  (refresh-project!)))
      (.catch report-error!))))

(defonce polling? (atom false))
(defonce timer (atom nil))
(defonce tick (atom 0))
(defonce completed-jobs (atom #{}))
(defonce presented-results (atom #{}))
(defn poll! []
  (when (and (project-id) (compare-and-set! polling? false true))
    (let [id (project-id) selected (:ui/selected-job (current)) cursor (:ui/event-cursor (current))]
      (-> (request! "GET" (str "/api/projects/" id "/jobs"))
        (.then (fn [data]
                 (when (= id (project-id))
                   (let [jobs (:jobs data)
                         ;; A job may finish before its first status poll. Inspect the
                         ;; current selection here, not the selection at request start.
                         selected-now (:ui/selected-job (current))
                         job (first (filter #(= selected-now (:id %)) jobs))
                         finished (filterv #(and (= "succeeded" (:status %))
                                                 (not (contains? @completed-jobs (:id %)))) jobs)]
                     (entities! :job/id :ui/jobs :id jobs)
                     (when (seq finished)
                       (swap! completed-jobs into (map :id finished))
                       (refresh-project!))
                     (when (and (#{"succeeded" "failed" "cancelled" "interrupted"} (:status job))
                                (:ui/notice (current)))
                       (patch! {:ui/notice nil}))
                     ;; Each result opens once automatically. Later navigation is
                     ;; left alone even while this successful job remains selected.
                     (when (and (= "succeeded" (:status job)) (:result_id job)
                                (not (contains? @presented-results (:result_id job))))
                       (swap! presented-results conj (:result_id job))
                       (load-result! (:result_id job)))))))
        (.catch report-error!)
        (.finally #(reset! polling? false)))
      (when selected
        (-> (request! "GET" (str "/api/jobs/" selected "/events?after=" cursor))
          (.then (fn [data]
                   (when (and (= id (project-id)) (= selected (:ui/selected-job (current))))
                     (let [latest-cursor (:ui/event-cursor (current))
                           events (filterv #(> (:seq %) latest-cursor) (:events data))]
                       (when (seq events)
                         (patch! {:ui/events (into (vec (:ui/events (current))) events)
                                  :ui/event-cursor (apply max cursor (map :seq events))}))))))
          (.catch report-error!)))
      (when (zero? (mod (swap! tick inc) 6)) (load-results! id)))))

(defn icon [name]
  (dom/span #js {:className (str "icon icon-" name) :aria-hidden true}
    (case name "play" "▷" "file" "≡" "model" "◇" "check" "✓" "arrow" "↗" "folder" "▱" "clock" "◷" "close" "×" "plus" "+" "command" "⌘" "compare" "⇄" "dot" "●" "·")))
(defn button [label action & [class disabled]]
  (dom/button #js {:className (str "button " class) :onClick (fn [_] (action)) :disabled (boolean disabled)} label))
(defn pill [label & [class]] (dom/span #js {:className (str "pill " class)} label))
(defn field-label [label child] (dom/label #js {:className "field"} (dom/span #js {:className "field-label"} label) child))
(defn checkbox [label key checked]
  (dom/label #js {:className "check-control"}
    (dom/input #js {:type "checkbox" :checked (boolean checked) :onChange #(checked! key %)}) label))
(defn date-label [value]
  (if value (try (.toLocaleTimeString (js/Date. value) #js [] #js {:hour "2-digit" :minute "2-digit"}) (catch :default _ "")) ""))
(defn json-display [v] (if (string? v) v (js/JSON.stringify (clj->js v) nil 2)))

(defsc ModelRow [this {:model/keys [id] :keys [name materialized]}]
  {:query [:model/id :unique_id :name :path :materialized :depends_on :columns]
   :ident :model/id}
  (let [model (get-in (current) [:model/id id])]
    (dom/button #js {:className (str "model-row " (when (:active (comp/get-computed this)) "active"))
                     :onClick #(select-model! model) :title (:path model)}
      (icon "model") (dom/span #js {:className "model-name"} name)
      (when materialized (dom/span #js {:className "model-kind"} materialized)))))
(def model-row (comp/factory ModelRow {:keyfn :model/id}))

(defsc JobRow [this {:job/keys [id] :keys [kind status created_at result_id]}]
  {:query [:job/id :kind :status :created_at :started_at :finished_at :summary :error :result_id :options]
   :ident :job/id}
  (dom/button #js {:className (str "job-row " (when (:active (comp/get-computed this)) "active"))
                   :onClick #(do (select-job! id) (when result_id (load-result! result_id)))}
    (dom/span #js {:className (str "status-dot " status)})
    (dom/span #js {:className "job-copy"} (dom/strong {} (str/capitalize (or kind "job")))
      (dom/span {} (str status " · " (date-label created_at))))
    (when result_id (icon "arrow"))))
(def job-row (comp/factory JobRow {:keyfn :job/id}))

(defn welcome [props]
  (dom/div #js {:className "welcome"}
    (dom/div #js {:className "welcome-hero"}
      (pill "YOUR DATA. YOUR MACHINE." "accent")
      (dom/h1 {} "A little room to" (dom/br) "work things out.")
      (dom/p #js {:className "welcome-intro"} "Explore, change, and test your dbt project locally. Keep your baseline close and your next idea closer.")
      (dom/div #js {:className "welcome-demo"}
        (dom/div {} (dom/h3 {} "Start with the Jaffle Shop") (dom/p {} "A real dbt project with local data, ready to experiment."))
        (button (str (if (:ui/submitting props) "Opening…" "Create Jaffle demo") " ↗") create-demo! "primary" (:ui/submitting props))))
    (dom/div #js {:className "welcome-open card"}
      (icon "folder") (dom/h2 {} "Bring your own project")
      (dom/p {} "Open a directory on this machine. Your SQL stays in your project.")
      (field-label "Project directory"
        (dom/input #js {:placeholder "/path/to/dbt-project" :value (:ui/open-path props)
                        :onChange #(value! :ui/open-path %) :onKeyDown #(when (= "Enter" (.-key %)) (open-project!))}))
      (field-label "Profiles directory · optional"
        (dom/input #js {:placeholder "Uses project profiles.yml or ~/.dbt" :value (:ui/profiles props) :onChange #(value! :ui/profiles %)}))
      (button "Open project →" open-project! "dark full" (:ui/submitting props))
      (when (seq (:ui/projects props))
        (dom/div #js {:className "recent-projects"} (dom/h4 {} "RECENT PROJECTS")
          (map #(dom/button #js {:key (:id %) :onClick (fn [_] (load-project! (:id %)))}
                  (icon "folder") (dom/span {} (:name %)) (icon "arrow")) (:ui/projects props)))))))

(defn sidebar [props]
  (dom/aside #js {:className "sidebar"}
    (dom/div #js {:className "sidebar-heading"} (dom/span {} "PROJECT EXPLORER")
      (dom/button #js {:className "icon-button" :title "Refresh project" :onClick refresh-project! :aria-label "Refresh project"} "↻"))
    (dom/div #js {:className "sidebar-section-title"} "Models" (pill (count (:ui/models props))))
    (dom/div #js {:className "model-list"}
      (if (seq (:ui/models props)) (map #(model-row (comp/computed % {:active (= (:unique_id %) (:ui/model-id props))})) (:ui/models props))
        (dom/p #js {:className "sidebar-hint"} "Parse the project to discover models.")))
    (dom/details #js {:className "files-list"}
      (dom/summary {} "Project files" (pill (count (:ui/files props))))
      (map #(dom/button #js {:key (:path %) :className (str "file-row " (when (= (:path %) (get-in props [:ui/file :path])) "active"))
                             :onClick (fn [_] (load-file! (:path %))) :title (:path %)}
              (icon "file") (dom/span {} (:path %))) (:ui/files props)))
    (dom/div #js {:className "sidebar-bottom"}
      (dom/span #js {:className "local-light"}) "Connected to your machine"
      (dom/span #js {:className "muted mono project-path" :title (get-in props [:ui/project :path])} (get-in props [:ui/project :path])))))

(defn dialect-control [props]
  (dom/select #js {:value (:ui/dialect props) :aria-label "Execution dialect" :onChange #(value! :ui/dialect %)}
    (dom/option #js {:value "duckdb"} "DuckDB · native")
    (dom/option #js {:value "sqlite"} "SQLite · translated")))
(defn model-editor [props]
  (let [file (:ui/file props) dirty (not= (:ui/content props) (:ui/saved-content props))
        model (first (filter #(= (:unique_id %) (:ui/model-id props)) (:ui/models props)))]
    (dom/div #js {:className "editor-panel"}
      (dom/div #js {:className "panel-title"}
        (dom/div {} (dom/span #js {:className "eyebrow"} "MODEL WORKSPACE")
          (dom/h2 {} (or (:name model) "Your next change starts here")))
        (when model (pill (or (:materialized model) "model") "neutral")))
      (if file
        (dom/div #js {:className "code-card"}
          (dom/div #js {:className "code-toolbar"}
            (dom/div #js {:className "file-title"} (icon "file") (dom/span {} (:path file)) (when dirty (dom/span #js {:className "unsaved"} "Unsaved")))
            (button "Save file" save-file! "small" (or (not dirty) (:ui/submitting props))))
          (when (:ui/conflict props)
            (dom/div #js {:className "conflict-banner"} "A newer version exists on disk. "
              (button "Reload disk version" #(load-file! (:path file)) "small" false)))
          (dom/textarea #js {:className "code-editor" :aria-label "Model file editor" :spellCheck false
                             :value (:ui/content props) :onChange #(value! :ui/content %)
                             :onKeyDown #(when (and (or (.-ctrlKey %) (.-metaKey %)) (= "s" (.-key %))) (.preventDefault %) (save-file!))})
          (dom/div #js {:className "editor-footer"}
            (dom/span {} (str (count (str/split-lines (:ui/content props))) " lines"))
            (dom/span {} (str "Revision " (subs (or (:revision file) "") 0 (min 10 (count (or (:revision file) ""))))))
            (dom/span {} "⌘ / Ctrl S to save")))
        (dom/div #js {:className "empty-editor"} (icon "model")
          (dom/h3 {} "Pick a model to explore")
          (dom/p {} "Choose a model or project file in the explorer. Parse refreshes your project graph.")
          (button "Parse project" #(submit! "parse" {}) "small" (:ui/submitting props))))
      (when model
        (dom/div #js {:className "preview-strip"}
          (dom/div {} (dom/strong {} "Try this model") (dom/p {} "Preview the current model or its saved baseline version."))
          (dom/div #js {:className "preview-controls"}
            (checkbox "Saved baseline version" :ui/baseline-input (:ui/baseline-input props))
            (dialect-control props)
            (button "▷ Preview" run-preview! "primary" (or dirty (:ui/submitting props)))))
        )
      (when dirty (dom/p #js {:className "hint"} "Save your file before building or previewing the change."))
      (when model
        (dom/details #js {:className "lineage card"}
          (dom/summary {} "Upstream dependencies" (pill (count (:depends_on model))))
          (if (seq (:depends_on model))
            (map #(dom/div #js {:className "dependency mono" :key %} (icon "model") (str %)) (:depends_on model))
            (dom/p #js {:className "muted"} "No upstream dependencies reported."))))
      (dom/details #js {:className "reference-import card"}
        (dom/summary {} "Import an existing reference manifest")
        (dom/p #js {:className "hint"} "Use existing production or target artifacts without rebuilding them. Give the reference target a label; your development target stays selected above.")
        (dom/div #js {:className "import-controls"}
          (field-label "Artifact directory containing manifest.json"
            (dom/input #js {:value (:ui/artifact-dir props) :placeholder "/path/to/production-artifacts" :onChange #(value! :ui/artifact-dir %)}))
          (field-label "Reference target"
            (dom/input #js {:aria-label "Reference target" :value (:ui/reference-target props) :placeholder "prod" :onChange #(value! :ui/reference-target %)}))
          (button "Import state" import-baseline! "small" (or (str/blank? (:ui/artifact-dir props)) (:ui/submitting props))))))))

(defn query-editor [props]
  (let [edn? (= "edn" (:ui/query-format props))]
    (dom/div #js {:className "editor-panel"}
      (dom/div #js {:className "panel-title"}
        (dom/div {} (dom/span #js {:className "eyebrow"} "QUERY LAB") (dom/h2 {} "Follow a question."))
        (pill "READ-ONLY QUERIES"))
      (dom/p #js {:className "intro"} "Use a structured EDN query or native SQL. Every result keeps its SQL and input provenance.")
      (dom/div #js {:className "code-card"}
        (dom/div #js {:className "code-toolbar"}
          (dom/div #js {:className "segmented"}
            (button "EDN" #(patch! {:ui/query-format "edn"}) (when edn? "selected"))
            (button "SQL" #(patch! {:ui/query-format "sql"}) (when-not edn? "selected")))
          (dialect-control props))
        (dom/textarea #js {:className "code-editor query-editor" :aria-label (if edn? "EDN query editor" "SQL query editor") :spellCheck false
                           :value (if edn? (:ui/query-edn props) (:ui/query-sql props))
                           :onChange #(value! (if edn? :ui/query-edn :ui/query-sql) %)
                           :onKeyDown #(when (and (or (.-ctrlKey %) (.-metaKey %)) (= "Enter" (.-key %))) (.preventDefault %) (run-query!))})
        (dom/div #js {:className "query-parameters"}
          (field-label "Parameters · JSON object"
            (dom/input #js {:aria-label "Parameters" :className "mono" :value (if edn? (:ui/parameters props) (:ui/sql-parameters props)) :onChange #(value! (if edn? :ui/parameters :ui/sql-parameters) %)}))
          (button "▷ Run query" run-query! "primary" (:ui/submitting props))))
      (dom/p #js {:className "hint"} (if edn? "EDN v1 resolves model references through your dbt manifest. ⌘ / Ctrl Enter to run." "SQL runs as a read-only query. SQLite translation covers supported SQL; inspect errors and provenance for limitations.")))))

(defn result-option [r] (dom/option #js {:key (:id r) :value (:id r)} (str (or (:kind r) "result") " · " (subs (:id r) 0 (min 8 (count (:id r)))) " · " (:row_count r) " rows")))
(defn comparison [props]
  (dom/details #js {:className "comparison card"}
    (dom/summary {} (icon "compare") "Compare saved results")
    (dom/div #js {:className "comparison-fields"}
      (field-label "Left result" (dom/select #js {:aria-label "Left result" :value (:ui/left-result props) :onChange #(value! :ui/left-result %)}
                                  (dom/option #js {:value ""} "Choose a result…") (map result-option (:ui/results props))))
      (field-label "Right result" (dom/select #js {:aria-label "Right result" :value (:ui/right-result props) :onChange #(value! :ui/right-result %)}
                                   (dom/option #js {:value ""} "Choose a result…") (map result-option (:ui/results props))))
      (field-label "Key columns · comma separated"
        (dom/input #js {:aria-label "Key columns" :value (:ui/compare-keys props) :placeholder "customer_id" :onChange #(value! :ui/compare-keys %)}))
      (button "Compare →" run-compare! "small"
        (or (str/blank? (:ui/left-result props)) (str/blank? (:ui/right-result props)) (:ui/submitting props))))))
(defn results-panel [props]
  (let [result (:ui/result props) page (:ui/page props) columns (:columns page)
        comparison? (= "compare" (:kind result)) summary (:summary result)
        offset (or (:offset page) 0) limit (or (:limit page) 100) total (or (:row_count page) (:row_count result) 0)]
    (dom/div #js {:className "results-panel"}
      (dom/div #js {:className "panel-title"}
        (dom/div {} (dom/span #js {:className "eyebrow"} "RESULT EXPLORER") (dom/h2 {} "See what changed."))
        (when result (pill (str (:row_count result) " rows") "accent")))
      (dom/div #js {:className "result-picker"}
        (dom/select #js {:aria-label "Saved result" :value (or (:id result) "") :onChange #(when-not (str/blank? (.. % -target -value)) (load-result! (.. % -target -value)))}
          (dom/option #js {:value ""} "Choose a saved result…") (map result-option (:ui/results props)))
        (when result (dom/a #js {:className "button small" :href (str "/api/results/" (:id result) "/download?format=csv")} "Download CSV ↓")))
      (if result
        (dom/div {}
          (when comparison?
            (dom/div #js {:className (str "comparison-outcome " (if (:equal summary) "matches" "differs")) :role "status"}
              (dom/strong {} (if (:equal summary) "Rows match" (str (or (:differences summary) total) " differences")))
              (dom/span {} (str (:left_rows summary) " left rows · " (:right_rows summary) " right rows"))
              (when (false? (:schema_equal summary)) (dom/p #js {:className "schema-difference"} "Column types/names differ."))
              (dom/p #js {:className "hint"} (or (:comparison_scope summary) "Comparison applies to these saved result rows."))))
          (dom/div #js {:className "result-meta"} (pill (or (:dialect result) (:kind result)) "neutral")
            (dom/span #js {:className "muted mono"} (str "result / " (subs (:id result) 0 (min 12 (count (:id result)))))))
          (dom/div #js {:className "grid-wrap"}
            (dom/table #js {:className "result-grid"}
              (dom/thead {} (dom/tr {} (dom/th #js {:className "row-number"} "#")
                             (map-indexed (fn [i c] (dom/th #js {:key i} (dom/span {} (if (string? c) c (:name c)))
                                                     (when (:type c) (dom/small {} (:type c))))) columns)))
              (dom/tbody {}
                (map-indexed
                  (fn [i row]
                    (dom/tr #js {:key (+ offset i)} (dom/td #js {:className "row-number"} (+ offset i 1))
                      (map-indexed (fn [j value] (dom/td #js {:key j :title (when (some? value) (str value)) :className (when (nil? value) "null-value")}
                                                  (if (nil? value) "null" (if (or (map? value) (vector? value)) (json-display value) (str value))))) row))) (:rows page)))))
          (when (empty? (:rows page)) (dom/p #js {:className "empty-rows"} (if comparison? "No row differences were found." "The query returned no rows.")))
          (dom/div #js {:className "pagination"}
            (dom/span {} (str (if (zero? total) 0 (inc offset)) "–" (min total (+ offset (count (:rows page)))) " of " total))
            (dom/div {} (button "← Previous" #(load-result! (:id result) (max 0 (- offset limit))) "small" (zero? offset))
              (button "Next →" #(load-result! (:id result) (+ offset limit)) "small" (>= (+ offset limit) total))))
          (dom/details #js {:className "result-sql card"}
            (dom/summary {} "SQL & provenance")
            (dom/h4 {} "EXECUTED SQL") (dom/pre {} (json-display (:sql result)))
            (dom/h4 {} "INPUT PROVENANCE") (dom/pre {} (json-display (:provenance result)))))
        (dom/div #js {:className "empty-editor"} (icon "play") (dom/h3 {} "An idea, then a result.")
          (dom/p {} "Preview a model or run a query. Its result will appear here with a record of SQL and input bindings.")))
      (comparison props))))

(defn activity [props]
  (let [selected (first (filter #(= (:job/id %) (:ui/selected-job props)) (:ui/jobs props)))
        active? (#{"queued" "running" "cancelling"} (:status selected))]
    (dom/aside #js {:className "activity"}
      (dom/div #js {:className "sidebar-heading"} "ACTIVITY" (pill (count (:ui/jobs props))))
      (dom/div #js {:className "job-list"}
        (if (seq (:ui/jobs props)) (map #(job-row (comp/computed % {:active (= (:job/id %) (:ui/selected-job props))})) (:ui/jobs props))
          (dom/div #js {:className "activity-empty"} (icon "clock") (dom/p {} "Your runs land here.") (dom/span {} "Build, test, or preview to get started."))))
      (when selected
        (dom/div #js {:className "job-details"}
          (dom/div #js {:className "log-heading"} (dom/strong {} (str/capitalize (:kind selected)))
            (when active? (button "Cancel" #(cancel-job! (:job/id selected)) "small danger" (= "cancelling" (:status selected)))))
          (when (:error selected) (dom/div #js {:className "job-error"} (human-error (:error selected))))
          (when (:summary selected) (dom/details #js {:className "job-summary"} (dom/summary {} "Run summary") (dom/pre {} (json-display (:summary selected)))))
          (dom/div #js {:className "log-view" :role "log" :aria-label "Job logs"}
            (if (seq (:ui/events props))
              (map #(dom/div #js {:className (str "log-line " (:level %)) :key (:seq %)}
                      (dom/span #js {:className "log-time"} (date-label (:timestamp %)))
                      (dom/span {} (:message %))) (:ui/events props))
              (dom/p #js {:className "muted"} (if active? "Waiting for output…" "No log output.")))))))))

(defsc Root [_ props]
  {:query [:ui/projects :ui/project :ui/files :ui/results :ui/target :ui/defer :ui/favor-state
           :ui/tab :ui/model-id :ui/file :ui/content :ui/saved-content :ui/selected-job
           :ui/events :ui/event-cursor :ui/result :ui/page :ui/error :ui/notice :ui/submitting
           :ui/open-path :ui/profiles :ui/query-format :ui/query-edn :ui/query-sql :ui/parameters :ui/sql-parameters
           :ui/dialect :ui/baseline-input :ui/left-result :ui/right-result :ui/compare-keys :ui/conflict :ui/artifact-dir :ui/reference-target
           {:ui/models (comp/get-query ModelRow)} {:ui/jobs (comp/get-query JobRow)}]
   :initial-state (fn [_] initial)}
  (let [project (:ui/project props) selected (:ui/model-id props) tab (:ui/tab props)
        dirty (not= (:ui/content props) (:ui/saved-content props))]
    (dom/div #js {:className "app-shell"}
      (dom/header #js {:className "app-header"}
        (dom/button #js {:className "brand" :onClick #(when (may-discard?) (patch! {:ui/project nil :ui/error nil :ui/notice nil :ui/content "" :ui/saved-content ""})) :title "All projects"}
          (dom/span #js {:className "brand-mark"} "w") (dom/span {} "workbench") (pill "LOCAL"))
        (when project
          (dom/div #js {:className "project-switch"}
            (dom/span #js {:className "header-divider"} "/")
            (dom/select #js {:aria-label "Project" :value (:id project) :onChange #(load-project! (.. % -target -value))}
              (map #(dom/option #js {:key (:id %) :value (:id %)} (:name %)) (:ui/projects props)))))
        (dom/div #js {:className "header-right"} (dom/span #js {:className "local-light"}) "Local session"))
      (when (:ui/error props)
        (dom/div #js {:className "message error" :role "alert"} (dom/span {} (:ui/error props))
          (dom/button #js {:className "icon-button" :aria-label "Dismiss error" :onClick #(patch! {:ui/error nil})} "×")))
      (when (:ui/notice props)
        (dom/div #js {:className "message notice" :role "status"} (dom/span {} (:ui/notice props))
          (dom/button #js {:className "icon-button" :aria-label "Dismiss notice" :onClick #(patch! {:ui/notice nil})} "×")))
      (if-not project (welcome props)
        (dom/div #js {:className "project-shell"}
          (dom/div #js {:className "run-toolbar"}
            (dom/div #js {:className "target-controls"}
              (dom/span #js {:className "field-label"} "TARGET")
              (dom/select #js {:aria-label "Target" :value (:ui/target props) :onChange #(value! :ui/target %)}
                (map #(dom/option #js {:key % :value %} %) (or (seq (:targets project)) ["dev"])))
              (checkbox "Use baseline refs" :ui/defer (:ui/defer props))
              (checkbox "Prefer baseline relations" :ui/favor-state (:ui/favor-state props)))
            (dom/div #js {:className "run-actions"}
              (button "Parse" #(submit! "parse" {}) "small" (:ui/submitting props))
              (button "Test" #(submit! "test" (cond-> {} selected (assoc :select selected))) "small" (or dirty (:ui/submitting props)))
              (button (if (:baseline project) "Rebuild baseline" "Build baseline") #(submit! "baseline" {:target (if (some #{"baseline"} (:targets project)) "baseline" (:ui/target props)) :defer false :favor_state false}) "small" (or dirty (:ui/submitting props)))
              (button (if selected "▷ Build selected" "▷ Build project") #(submit! "build" (cond-> {} selected (assoc :select selected))) "primary small" (or dirty (:ui/submitting props)))))
          (dom/div #js {:className "workspace"}
            (sidebar props)
            (dom/main #js {:className "main-pane"}
              (dom/nav #js {:className "workspace-tabs" :aria-label "Workspace"}
                (map (fn [[key label]] (dom/button #js {:key key :className (when (= key tab) "active") :onClick #(patch! {:ui/tab key})} label))
                  [["model" "Model SQL"] ["query" "Query lab"] ["results" "Results"]])
                (dom/span #js {:className "baseline-status"} (dom/span #js {:className (str "status-dot " (if (:baseline project) "succeeded" "queued"))})
                  (if (:baseline project) "Baseline ready" "No baseline yet")))
              (case tab "query" (query-editor props) "results" (results-panel props) (model-editor props)))
            (activity props)))))))

(defonce application (app/fulcro-app {}))
(defn ^:export refresh [] (app/mount! application Root "app"))
(defn ^:export init []
  (refresh)
  (load-projects!)
  (js/window.addEventListener "beforeunload"
    (fn [event] (when (dirty?) (.preventDefault event) (set! (.-returnValue event) ""))))
  (when @timer (js/clearInterval @timer))
  (reset! timer (js/setInterval poll! 750)))
