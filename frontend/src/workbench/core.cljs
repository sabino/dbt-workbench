(ns workbench.core
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
            [cljs.pprint :as pprint]
            [workbench.builder :as builder]
            ["react" :as react]
            ["@phosphor-icons/react/dist/csr/Play" :as play-icon]
            ["@phosphor-icons/react/dist/csr/FileText" :as file-icon]
            ["@phosphor-icons/react/dist/csr/Table" :as table-icon]
            ["@phosphor-icons/react/dist/csr/Check" :as check-icon]
            ["@phosphor-icons/react/dist/csr/ArrowUpRight" :as arrow-icon]
            ["@phosphor-icons/react/dist/csr/FolderSimple" :as folder-icon]
            ["@phosphor-icons/react/dist/csr/Clock" :as clock-icon]
            ["@phosphor-icons/react/dist/csr/X" :as close-icon]
            ["@phosphor-icons/react/dist/csr/Plus" :as plus-icon]
            ["@phosphor-icons/react/dist/csr/Command" :as command-icon]
            ["@phosphor-icons/react/dist/csr/ArrowsLeftRight" :as compare-icon]
            ["@phosphor-icons/react/dist/csr/Database" :as database-icon]
            ["@phosphor-icons/react/dist/csr/MagnifyingGlass" :as search-icon]
            ["@phosphor-icons/react/dist/csr/ArrowClockwise" :as refresh-icon]
            ["@phosphor-icons/react/dist/csr/Hammer" :as build-icon]
            ["@phosphor-icons/react/dist/csr/FastForward" :as run-all-icon]
            ["@phosphor-icons/react/dist/csr/DownloadSimple" :as download-icon]
            ["@phosphor-icons/react/dist/csr/GitBranch" :as changes-icon]
            ["@phosphor-icons/react/dist/csr/CaretRight" :as chevron-icon]
            ["@phosphor-icons/react/dist/csr/DotsThree" :as dots-icon]
            ["@phosphor-icons/react/dist/csr/SlidersHorizontal" :as settings-icon]
            ["@phosphor-icons/react/dist/csr/ArrowsOutSimple" :as expand-icon]
            ["@phosphor-icons/react/dist/csr/Circle" :as circle-icon]
            ["@phosphor-icons/react/dist/csr/Sun" :as sun-icon]
            ["./editor.js" :as editor]
            [com.fulcrologic.fulcro.application :as app]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.dom :as dom]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]))

(declare application load-project! load-file! select-model! select-job! load-result!
         submit! refresh-project! save-file! create-demo! open-project! cancel-job! import-baseline! convert-query! load-query! load-changes! run-query! json-display load-catalog! format-query! save-format-options!)

(def default-edn "{:select [:customer_id [[:sum :amount] :revenue]]\n :from [:orders]\n :group-by [:customer_id]\n :order-by [[:customer_id :asc]]}")
(def default-sql "SELECT customer_id, SUM(amount) AS revenue\nFROM orders\nGROUP BY customer_id\nORDER BY customer_id")
(def initial
  {:ui/theme "system" :ui/projects [] :ui/project nil :ui/models [] :ui/jobs [] :ui/files []
   :ui/results [] :ui/target "dev" :ui/defer true :ui/favor-state false
   :ui/tab "model" :ui/model-id nil :ui/file nil :ui/content "" :ui/saved-content ""
   :ui/selected-job nil :ui/events [] :ui/event-cursor 0 :ui/result nil :ui/page nil
   :ui/error nil :ui/notice nil :ui/submitting false :ui/open-path "" :ui/profiles ""
   :ui/catalog [] :ui/visual-query nil :ui/format-options {:keywordCase "upper" :useTabs false :tabWidth 2 :indentStyle "standard" :logicalOperatorNewline "before" :expressionWidth 50 :linesBetweenQueries 2}
   :ui/query-format "sql" :ui/query-edn default-edn :ui/query-sql default-sql
   :ui/parameters "{\"status\": \"completed\"}" :ui/sql-parameters "{}" :ui/dialect "duckdb" :ui/baseline-input false
   :ui/editor-revision 0 :ui/query-job nil :ui/query-requesting false :ui/query-selection {:from 0 :to 0 :head 0} :ui/converting false :ui/conversion-note nil
   :ui/model-search "" :ui/build-scope "project" :ui/activity-tab "activity" :ui/changes [] :ui/job-filter "all"
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
(defn patch! [updates]
  (comp/transact! application [(change {:updates updates})] {:synchronous? true})
  ;; Root-level forms need fresh props before the next input event.
  (app/render! application))
(defn value! [key event] (patch! {key (.. event -target -value)}))
(defn checked! [key event] (patch! {key (.. event -target -checked)}))
(defn project-id [] (get-in (current) [:ui/project :id]))
(defn dirty? [] (not= (:ui/content (current)) (:ui/saved-content (current))))
(defn may-discard? [] (or (not (dirty?)) (js/confirm "Discard the unsaved changes in this editor?")))
(defn api-path [suffix] (str "/api/projects/" (project-id) suffix))
(defn human-error [x]
  (cond (string? x) x (map? x) (or (:message x) (pr-str x)) :else (str x)))
(defn report-error! [error]
  (patch! {:ui/error (or (.-message error) (str error)) :ui/submitting false :ui/query-requesting false}))
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
(defn read-preference [key]
  (try (js/localStorage.getItem key) (catch :default _ nil)))
(defn save-preference! [key value]
  (try (if value (js/localStorage.setItem key value) (js/localStorage.removeItem key))
       (catch :default _ nil)))
(defn apply-theme! [preference]
  (let [dark? (or (= preference "dark")
                  (and (= preference "system") (.-matches (js/window.matchMedia "(prefers-color-scheme: dark)"))))]
    (.setAttribute js/document.documentElement "data-theme" (if dark? "dark" "light"))))
(defn choose-theme! [preference]
  (save-preference! "workbench.theme" preference)
  (apply-theme! preference)
  (patch! {:ui/theme preference}))
(defn choose-project! []
  (when (may-discard?)
    (save-preference! "workbench.project" nil)
    (patch! {:ui/project nil :ui/error nil :ui/notice nil :ui/content "" :ui/saved-content ""})))
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
(defn load-changes! [id]
  (-> (request! "GET" (str "/api/projects/" id "/changes"))
    (.then #(when (= id (project-id)) (patch! {:ui/changes (:changes %)})))
    (.catch report-error!)))
(defn refresh-project! []
  (when-let [id (project-id)]
    (-> (request! "GET" (str "/api/projects/" id))
      (.then #(when (= id (project-id)) (patch! {:ui/project %}))) (.catch report-error!))
    (load-models! id) (load-files! id) (load-results! id) (load-changes! id) (load-catalog!)))
(defn load-project! [id]
  (when (may-discard?)
    (patch! {:ui/error nil :ui/notice nil :ui/file nil :ui/content "" :ui/saved-content ""
           :ui/models [] :ui/jobs [] :ui/events [] :ui/event-cursor 0 :ui/selected-job nil :ui/query-job nil :ui/query-requesting false
           :ui/result nil :ui/page nil :ui/results [] :ui/model-id nil :ui/conflict false :ui/changes [] })
  (-> (request! "GET" (str "/api/projects/" id))
    (.then (fn [project]
             (let [targets (:targets project)
                   target (if (some #{"dev"} targets) "dev" (or (first targets) "dev"))]
               (save-preference! "workbench.project" id)
               (patch! {:ui/project project :ui/target target :ui/tab "model" :ui/left-result "" :ui/right-result ""})
               (load-projects!) (load-models! id) (load-files! id) (load-results! id) (load-changes! id) (load-catalog!))))
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
(defn load-file! [path & [draft]]
  (when (may-discard?)
    (let [id (project-id)]
    (-> (request! "GET" (str "/api/projects/" id "/file?path=" (js/encodeURIComponent path)))
      (.then (fn [file]
               (when (= id (project-id))
                 (patch! {:ui/file file :ui/content (if draft (:content draft) (:content file))
                           :ui/saved-content (:content file)
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
                     (load-models! (project-id)) (load-changes! (project-id))))
        (.catch (fn [e]
                  (if (and (= 409 (.-status e)) (not (str/starts-with? (.-message e) "Wait for")))
                    (patch! {:ui/conflict true :ui/submitting false
                             :ui/error "This file changed on disk. Your draft is preserved. Copy it before reloading, or keep editing."})
                    (report-error! e))))))))
(defn job-options []
  (let [s (current) defer (and (:ui/defer s) (get-in s [:ui/project :baseline]))]
    {:target (:ui/target s) :defer (boolean defer) :favor_state (boolean (and defer (:ui/favor-state s)))}))
(defn submit! [kind options]
  (patch! {:ui/submitting true :ui/query-requesting (= "query" kind) :ui/error nil :ui/notice nil})
  (-> (request! "POST" (api-path "/jobs") {:kind kind :options (merge (job-options) options)})
    (.then (fn [job]
             (patch! (cond-> {:ui/submitting false :ui/query-requesting false} (= "query" kind) (assoc :ui/query-job (:id job))))
             (select-job! (:id job))
             (when (#{"preview" "compare"} kind) (patch! {:ui/notice "Results will open when the job finishes."}))))
    (.catch report-error!)))
(defn select-job! [id]
  (patch! {:ui/selected-job id :ui/events [] :ui/event-cursor 0}))
(defn cancel-job! [id]
  (-> (request! "POST" (str "/api/jobs/" id "/cancel") {})
    (.then #(when-not (= "query" (:kind %)) (patch! {:ui/notice (str "Cancellation requested for " (:kind %) ".")})))
    (.catch report-error!)))
(defn load-result! [id & [offset keep-editor?]]
  (let [offset (or offset 0)]
    (-> (js/Promise.all #js [(request! "GET" (str "/api/results/" id))
                             (request! "GET" (str "/api/results/" id "/rows?offset=" offset "&limit=10"))])
      (.then (fn [data]
               (patch! {:ui/result (aget data 0) :ui/page (aget data 1) :ui/tab (if (or keep-editor? (= "query" (:ui/tab (current)))) (:ui/tab (current)) "results") :ui/error nil})))
      (.catch report-error!))))
(defn query-parameters [s]
  (let [input (if (= "sql" (:ui/query-format s)) (:ui/sql-parameters s) (:ui/parameters s))
        parameters (js->clj (js/JSON.parse (if (str/blank? input) "{}" input)) :keywordize-keys true)]
    (when-not (map? parameters) (throw (js/Error. "Parameters must be a JSON object.")))
    parameters))

(defn run-query! [& [all? editor-content editor-selection]]
  (when-not (:ui/submitting (current))
    (let [s (current) mode (:ui/query-format s) format (if (= "visual" mode) "edn" mode)
          content (or editor-content (if (= "edn" format) (:ui/query-edn s) (:ui/query-sql s)))
          selection (if editor-selection (js->clj editor-selection :keywordize-keys true) (:ui/query-selection s))]
      (when-not (= "visual" mode) (editor/focusQueryEditor))
      (try
        (let [input (js->clj (editor/executionInput content (clj->js selection) format (boolean all?)) :keywordize-keys true)]
          (when (or (empty? (:statements input)) (str/blank? (:content input)))
            (throw (js/Error. "Write or select a query to run.")))
          (submit! "query" (merge input {:editor_content content :selection selection
                                         :format format :editor_mode mode :dialect (:ui/dialect s) :parameters (query-parameters s)})))
        (catch :default e (report-error! e))))))

(defn load-catalog! []
  (let [id (project-id) target (:ui/target (current))]
    (when id
      (-> (request! "GET" (str (api-path "/catalog?target=") (js/encodeURIComponent target)))
        (.then #(when (and (= id (project-id)) (= target (:ui/target (current)))) (patch! {:ui/catalog (:relations %)})))
        (.catch (fn [_] (when (= id (project-id)) (patch! {:ui/catalog []}))))))))
(defn pretty-edn [query] (with-out-str (pprint/pprint query)))
(defn read-visual-query [content]
  (let [query (reader/read-string content)]
    (when-not (builder/supported? query)
      (throw (js/Error. "This query uses expressions outside the visual builder. Keep editing SQL or EDN; the draft is preserved.")))
    query))
(defn save-format-options! [options]
  (patch! {:ui/format-options options})
  (js/localStorage.setItem "workbench.sql-format-options" (js/JSON.stringify (clj->js options))))
(defn format-query! []
  (let [s (current) sql? (= "sql" (:ui/query-format s))]
    (try
      (patch! {(if sql? :ui/query-sql :ui/query-edn) (if sql? (editor/formatSql (:ui/query-sql s) (clj->js (:ui/format-options s))) (pretty-edn (reader/read-string (:ui/query-edn s))))
               :ui/query-selection {:from 0 :to 0 :head 0} :ui/conversion-note "Formatted with your preferences." :ui/error nil})
      (catch :default error (report-error! error)))))
(defn convert-query! [mode]
  (let [s (current) id (project-id) source-mode (:ui/query-format s)
        source-format (if (= "visual" source-mode) "edn" source-mode)
        target-format (if (= "visual" mode) "edn" mode)
        key (if (= "edn" source-format) :ui/query-edn :ui/query-sql) content (get s key)
        apply-conversion (fn [converted]
                           (let [text (:content converted) visual (when (= "visual" mode) (read-visual-query text))
                                 text (if (= "sql" mode) (editor/formatSql text (clj->js (:ui/format-options (current)))) text)]
                             (patch! {(if (= "edn" target-format) :ui/query-edn :ui/query-sql) text
                                      (if (= "edn" target-format) :ui/parameters :ui/sql-parameters) (json-display (or (:parameters converted) (query-parameters s)))
                                      :ui/query-format mode :ui/visual-query visual :ui/query-selection {:from 0 :to 0 :head 0}
                                      :ui/conversion-note (:note converted) :ui/converting false :ui/error nil})
                             (when visual (load-catalog!))))]
    (when (and (not= mode source-mode) (not (:ui/converting s)))
      (try
        (patch! {:ui/converting true :ui/error nil})
        (if (= source-format target-format)
          (apply-conversion {:content content :parameters (query-parameters s)})
          (-> (request! "POST" (api-path "/query/convert") {:content content :format source-format :parameters (query-parameters s) :target (:ui/target s) :dialect (:ui/dialect s)})
            (.then (fn [converted]
                     (when (= id (project-id))
                       (if (and (= content (get (current) key)) (= source-mode (:ui/query-format (current))))
                         (apply-conversion converted)
                         (patch! {:ui/converting false :ui/conversion-note "Draft changed. Switch mode again to convert it."})))))
            (.catch (fn [error] (when (= id (project-id)) (patch! {:ui/converting false}) (report-error! error))))))
        (catch :default error (patch! {:ui/converting false}) (report-error! error))))))

(defn load-query! [job & [rerun?]]
  (let [options (:options job) format (:format options) mode (:editor_mode options format)]
    (patch! {:ui/editor-revision (inc (:ui/editor-revision (current))) :ui/tab "query" :ui/query-format mode :ui/visual-query (when (= "visual" mode) (read-visual-query (:content options)))
             (if (= "edn" format) :ui/query-edn :ui/query-sql) (or (:editor_content options) (:content options))
             (if (= "edn" format) :ui/parameters :ui/sql-parameters) (json-display (:parameters options))
             :ui/target (:target options) :ui/dialect (:dialect options)
             :ui/query-selection (or (:selection options) {:from 0 :to 0 :head 0})
             :ui/conversion-note nil :ui/error nil})
    (when rerun? (submit! "query" options))))

(defn build-options [s]
  (case (:ui/build-scope s)
    "selected" {:select (:ui/model-id s)}
    "baseline" {:defer false :favor_state false}
    {}))

(defn build-project! []
  (let [s (current)]
    (submit! (if (= "baseline" (:ui/build-scope s)) "baseline" "build") (build-options s))))
(defn run-preview! []
  (let [s (current)]
    (submit! "preview" {:model_id (:ui/model-id s) :dialect (:ui/dialect s) :baseline (:ui/baseline-input s)})))
(defn run-compare! []
  (let [s (current)]
    (submit! "compare" {:left_result_id (:ui/left-result s) :right_result_id (:ui/right-result s)
                        :key_columns (into [] (remove str/blank?) (map str/trim (.split (:ui/compare-keys s) ",")))})))
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
                       (load-result! (:result_id job) 0 (= "query" (:kind job))))))))
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
    (react/createElement
      (case name "theme" sun-icon/Sun "play" play-icon/Play "file" file-icon/FileText "model" table-icon/Table
        "check" check-icon/Check "arrow" arrow-icon/ArrowUpRight "folder" folder-icon/FolderSimple
        "clock" clock-icon/Clock "close" close-icon/X "plus" plus-icon/Plus "command" command-icon/Command
        "compare" compare-icon/ArrowsLeftRight "database" database-icon/Database "search" search-icon/MagnifyingGlass
        "refresh" refresh-icon/ArrowClockwise "build" build-icon/Hammer "run-all" run-all-icon/FastForward
        "download" download-icon/DownloadSimple "changes" changes-icon/GitBranch "chevron" chevron-icon/CaretRight
        "dots" dots-icon/DotsThree "settings" settings-icon/SlidersHorizontal "expand" expand-icon/ArrowsOutSimple circle-icon/Circle)
      #js {:size 19 :weight "regular"})))
(defn button [label action & [class disabled]]
  (dom/button #js {:className (str "button " class) :onClick (fn [_] (action))
                   :onMouseDown (fn [event] (when (#{"Run statement" "Run all" "Run query"} label) (.preventDefault event))) :disabled (boolean disabled)}
    (when-let [name (case label "Test" "play" "Build" "build" "Run statement" "play" "Run query" "play" "Run all" "run-all" "Replay" "clock" nil)] (icon name))
    label))
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

(defsc JobRow [this {:job/keys [id] :keys [kind status created_at started_at finished_at result_id options]}]
  {:query [:job/id :kind :status :created_at :started_at :finished_at :summary :error :result_id :options]
   :ident :job/id}
  (dom/button #js {:className (str "job-row " (when (:active (comp/get-computed this)) "active"))
                   :onClick #(do (select-job! id) (when result_id (load-result! result_id 0 (= "query" (:ui/tab (current))))))}
    (dom/span #js {:className (str "status-dot " status)})
    (dom/span #js {:className "job-copy"}
      (dom/span #js {:className "job-time"} (date-label created_at)
        (dom/span #js {:className "job-duration"}
          (if (and started_at finished_at)
            (str (.round js/Math (- (.getTime (js/Date. finished_at)) (.getTime (js/Date. started_at)))) " ms")
            (str/capitalize (or status "queued")))))
      (dom/span #js {:className "job-status"} (str/capitalize (or kind "job")) (dom/span {} status))
      (when (= "query" kind) (dom/code #js {:className "query-snippet"} (subs (or (:content options) "") 0 (min 90 (count (or (:content options) "")))))))
    (icon "chevron")))
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

(defn model-tree [models selected prefix]
  (let [direct (filter #(= prefix (str/join "/" (butlast (str/split (:path %) #"/")))) models)
        folders (->> models
                  (map :path)
                  (filter #(str/starts-with? % (str prefix "/")))
                  (map #(first (str/split (subs % (inc (count prefix))) #"/")))
                  (filter #(some (fn [model] (str/starts-with? (:path model) (str prefix "/" % "/"))) models))
                  distinct sort)]
    (dom/div #js {:className "tree-children"}
      (map #(model-row (comp/computed % {:active (= (:unique_id %) selected)})) direct)
      (map (fn [folder]
             (dom/details #js {:className "tree-folder" :key (str prefix "/" folder) :open true}
               (dom/summary {} (icon "chevron") (icon "folder") (dom/span {} folder))
               (model-tree models selected (str prefix "/" folder)))) folders))))

(defn sidebar [props]
  (let [models (:ui/models props)
        filtered (filter #(str/includes? (str/lower-case (:name %)) (str/lower-case (:ui/model-search props))) models)
        files (:ui/files props)
        targets (or (seq (get-in props [:ui/project :targets])) ["dev"])]
  (dom/aside #js {:className "sidebar"}
    (dom/div #js {:className "sidebar-heading"} (dom/span {} "Project")
      (dom/button #js {:className "icon-button" :title "Refresh project" :onClick refresh-project! :aria-label "Refresh project"} (icon "refresh")))
    (dom/div #js {:className "sidebar-project"}
      (icon "folder")
      (dom/div {} (dom/select #js {:className "sidebar-project-picker" :aria-label "Project" :value (get-in props [:ui/project :id])
                                  :onChange #(load-project! (.. % -target -value))}
                     (map #(dom/option #js {:key (:id %) :value (:id %)} (:name %)) (:ui/projects props)))
        (dom/span {} "Analytics project"))
      (dom/button #js {:className "icon-button" :aria-label "Choose project" :title "Choose project" :onClick choose-project!} (icon "folder")))
    (dom/div #js {:className "search-field"} (icon "search")
      (dom/input #js {:className "model-search" :aria-label "Search models" :placeholder "Search models..."
                      :value (:ui/model-search props) :onChange #(value! :ui/model-search %) }))
    (dom/details #js {:className "tree-folder model-list" :open true}
      (dom/summary {} (icon "chevron") (icon "folder") (dom/span {} "models"))
      (cond
        (empty? models) (dom/p #js {:className "sidebar-hint"} "Parse the project to discover models.")
        (empty? filtered) (dom/p #js {:className "sidebar-hint"} "No matching models.")
        :else (model-tree filtered (:ui/model-id props) "models")))
    (when (str/blank? (:ui/model-search props))
      (map (fn [folder]
             (let [entries (filter #(str/starts-with? (:path %) (str folder "/")) files)]
               (dom/details #js {:className "tree-folder" :key folder}
                 (dom/summary {} (icon "chevron") (icon "folder") (dom/span {} folder))
                 (if (seq entries)
                   (map (fn [file]
                          (dom/button #js {:className "file-row" :key (:path file) :title (:path file) :onClick #(load-file! (:path file))}
                            (icon "file") (dom/span {} (subs (:path file) (inc (count folder)))))) entries)
                   (dom/p #js {:className "sidebar-hint"} "No files in this folder.")))))
        ["macros" "seeds" "snapshots" "tests" "analyses"]))
    (dom/details #js {:className "files-list"}
      (dom/summary {} "Project files" (pill (count (:ui/files props))))
      (map #(dom/button #js {:key (:path %) :className (str "file-row " (when (= (:path %) (get-in props [:ui/file :path])) "active"))
                             :onClick (fn [_] (load-file! (:path %))) :title (:path %)}
              (icon "file") (dom/span {} (:path %))) (:ui/files props)))
    (dom/details #js {:className "tree-folder target-tree" :open true}
      (dom/summary {} (icon "chevron") (icon "folder") (dom/span {} "target"))
      (map (fn [target]
             (dom/button #js {:key target :className (str "target-row " (when (= target (:ui/target props)) "active"))
                              :onClick #(patch! {:ui/target target})}
               (icon "database") (dom/span {} target))) targets))
    (dom/div #js {:className "sidebar-bottom"}
      (dom/span #js {:className "project-path" :title (get-in props [:ui/project :path])} (get-in props [:ui/project :path]))))))

(defn dialect-control [props]
  (dom/select #js {:value (:ui/dialect props) :aria-label "Execution dialect" :onChange #(value! :ui/dialect %)}
    (dom/option #js {:value "duckdb"} "DuckDB · native")
    (dom/option #js {:value "sqlite"} "SQLite · preview")))
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

(declare results-panel)
(defn query-status-bar [props]
  (let [jobs (filter #(= "query" (:kind %)) (:ui/jobs props))
        job (if (:ui/query-job props) (first (filter #(= (:ui/query-job props) (:job/id %)) jobs)) (first jobs))
        status (:status job) active? (#{"queued" "running" "cancelling"} status)
        duration (when (:started_at job)
                   (- (.getTime (js/Date. (or (:finished_at job) (.now js/Date)))) (.getTime (js/Date. (:started_at job)))))
        summary (:summary job)
        message (cond
                  (:ui/converting props) "Parsing and converting…"
                  (:ui/query-requesting props) "Submitting query…"
                  (:ui/error props) (:ui/error props)
                  (or (= status "queued") (and (:ui/query-job props) (nil? job))) "Queued · waiting for the project"
                  (= status "running") (str "Running" (when (:statement_index summary) (str " · statement " (:statement_index summary) " of " (:statement_count summary))))
                  (= status "cancelling") "Cancelling…"
                  (= status "succeeded") (str "Completed · " (reduce + 0 (map :row_count (:statement_results summary))) " rows · " (:statement_count summary 1) " statement(s)")
                  (= status "failed") (str "Failed · " (:error job))
                  (= status "cancelled") "Cancelled"
                  (= status "interrupted") "Interrupted · load the query to rerun"
                  :else "Ready · select text or place the cursor in a statement")]
    (dom/div #js {:className (str "query-status-bar " status) :role "status" :aria-label "Query status" :aria-live "polite"}
      (dom/div #js {:className "query-status-message"}
        (dom/span #js {:className (str "status-dot " (if active? "running" status))})
        (dom/span #js {:title message} message))
      (dom/div #js {:className "query-status-controls"}
        (when duration (dom/span {} (str (.toFixed (/ duration 1000) 1) " s")))
        (dom/span {} (str (str/upper-case (:ui/query-format props)) " · " (:ui/target props)))
        (when active? (button "Cancel query" #(cancel-job! (:job/id job)) "small danger" (= "cancelling" status)))))))

(defn format-preferences [props]
  (let [options (:ui/format-options props)
        control (fn [title key choices]
                  (field-label title (dom/select #js {:aria-label title :value (str (get options key))
                                                     :onChange #(let [value (.. % -target -value)]
                                                                  (save-format-options! (assoc (:ui/format-options (current)) key (cond (#{:tabWidth :expressionWidth :linesBetweenQueries} key) (js/Number value) (= key :useTabs) (= value "true") :else value))))}
                                       (map (fn [[value label]] (dom/option #js {:key (str value) :value (str value)} label)) choices))))]
    (dom/details #js {:className "format-preferences"}
      (dom/summary {} "Style")
      (dom/div #js {:className "format-preferences-menu"}
        (dom/h3 {} "SQL formatting")
        (control "Keyword case" :keywordCase [["upper" "UPPERCASE"] ["lower" "lowercase"] ["preserve" "Keep typed case"]])
        (control "Indent with" :useTabs [[false "Spaces"] [true "Tabs"]])
        (control "Indent width" :tabWidth [[2 "2"] [4 "4"] [8 "8"]])
        (control "Clause layout" :indentStyle [["standard" "Standard line breaks"] ["tabularLeft" "Aligned to the left"] ["tabularRight" "Aligned to the right"]])
        (control "Expression width" :expressionWidth [[0 "Always break expressions"] [40 "40 characters"] [50 "50 characters"] [80 "80 characters"] [120 "120 characters"]])
        (control "Logical operator breaks" :logicalOperatorNewline [["before" "Before AND / OR"] ["after" "After AND / OR"]])
        (control "Blank lines between queries" :linesBetweenQueries [[1 "1"] [2 "2"] [3 "3"]])
        (dom/p #js {:className "hint"} "Preferences are saved in this browser. Format applies them; generated SQL uses them automatically.")))))

(defn query-editor [props]
  (let [mode (:ui/query-format props) visual? (= "visual" mode) edn? (not= "sql" mode)]
    (dom/div #js {:className "query-workspace"}
      (dom/div #js {:className "code-card"}
        (dom/div #js {:className "code-toolbar query-toolbar"}
          (dom/div #js {:className "segmented"}
            (button "SQL" #(convert-query! "sql") (when (= "sql" mode) "selected") (:ui/converting props))
            (button "EDN" #(convert-query! "edn") (when (= "edn" mode) "selected") (:ui/converting props))
            (button "Visual" #(convert-query! "visual") (when visual? "selected") (:ui/converting props)))
          (dom/div #js {:className "query-run-actions"}
            (when-not visual? (button "Format" format-query! "small" (:ui/converting props)))
            (format-preferences props)
            (dom/details #js {:className "query-settings"}
              (dom/summary #js {:aria-label "Query settings" :title "Query settings"} (icon "settings"))
              (dom/div #js {:className "query-settings-menu"}
                (field-label "Parameters"
                  (dom/input #js {:aria-label "Parameters" :className "mono" :value (if edn? (:ui/parameters props) (:ui/sql-parameters props))
                                  :onChange #(value! (if edn? :ui/parameters :ui/sql-parameters) %)}))
                (field-label "Execution dialect" (dialect-control props))))
            (button (if edn? "Run query" "Run statement") #(run-query! false) "primary" (:ui/submitting props))
            (when-not edn? (button "Run all" #(run-query! true) "small" (:ui/submitting props)))
            (dom/kbd {} "Shift+Enter / F5")))
        (if visual?
          (builder/query-builder (:ui/visual-query props) (:ui/catalog props)
            #(patch! {:ui/visual-query % :ui/query-edn (pretty-edn %) :ui/error nil :ui/conversion-note nil}))
          (react/createElement editor/QueryEditor
          #js {:key (:ui/editor-revision props) :value (if edn? (:ui/query-edn props) (:ui/query-sql props)) :format (:ui/query-format props)
               :onChange #(patch! {(if edn? :ui/query-edn :ui/query-sql) % :ui/conversion-note nil})
               :onSelection #(patch! {:ui/query-selection (js->clj % :keywordize-keys true)})
               :selection (clj->js (:ui/query-selection props)) :onRun (fn [all? content selection] (run-query! all? content selection))}))
        (query-status-bar props))
      (dom/div #js {:className "query-results"} (results-panel props)))))

(defn result-option [r]
  (dom/option #js {:key (:id r) :value (:id r)}
    (str (str/capitalize (or (:kind r) "result")) " · " (:dialect r) " · " (:row_count r) " rows · "
         (date-label (:created_at r)) " · " (subs (:id r) 0 (min 6 (count (:id r)))))))
(defn comparison [props]
  (dom/section #js {:className "comparison card" :aria-label "Compare saved results"}
    (dom/div #js {:className "comparison-heading"} (icon "compare") (dom/h3 {} "Compare results"))
    (dom/p #js {:className "hint"} "Choose a baseline and candidate to find added, removed, or changed rows.")
    (dom/div #js {:className "comparison-fields"}
      (field-label "Baseline result" (dom/select #js {:aria-label "Baseline result" :value (:ui/left-result props) :onChange #(value! :ui/left-result %)}
                                  (dom/option #js {:value ""} "Choose baseline…") (map result-option (remove #(= "compare" (:kind %)) (:ui/results props)))))
      (field-label "Candidate result" (dom/select #js {:aria-label "Candidate result" :value (:ui/right-result props) :onChange #(value! :ui/right-result %)}
                                   (dom/option #js {:value ""} "Choose candidate…") (map result-option (remove #(= "compare" (:kind %)) (:ui/results props)))))
      (field-label "Match rows by key columns (optional)"
        (dom/input #js {:aria-label "Key columns" :value (:ui/compare-keys props) :placeholder "customer_id, order_id" :onChange #(value! :ui/compare-keys %)}))
      (button "Compare results" run-compare! "primary small"
        (or (str/blank? (:ui/left-result props)) (str/blank? (:ui/right-result props))
            (= (:ui/left-result props) (:ui/right-result props)) (:ui/submitting props))))
    (dom/p #js {:className "hint"} "Without keys, compare complete rows. Comparisons use saved rows, without rerunning queries.")))
(defn results-panel [props]
  (let [result (:ui/result props) page (:ui/page props) columns (:columns page)
        comparison? (= "compare" (:kind result)) summary (:summary result)
        offset (or (:offset page) 0) limit (or (:limit page) 10) total (or (:row_count page) (:row_count result) 0)
        selected-job (first (filter #(= (:job/id %) (:ui/selected-job props)) (:ui/jobs props)))
        statements (get-in selected-job [:summary :statement_results])]
    (dom/div #js {:className "results-panel"}
      (dom/div #js {:className "panel-title"}
        (dom/div {} (dom/span #js {:className "eyebrow"} "RESULT EXPLORER") (dom/h2 {} "Results"))
        (when result (pill (str (:row_count result) " rows") "accent")))
      (when (= "results" (:ui/tab props)) (comparison props))
      (dom/div #js {:className "result-toolbar"}
        (dom/nav #js {:className "result-tabs" :aria-label "Statement results"}
          (if (seq statements)
            (map (fn [item]
                   (dom/button #js {:key (:result_id item) :className (str "result-tab " (when (= (:result_id item) (:id result)) "active"))
                                    :aria-pressed (= (:result_id item) (:id result))
                                    :onClick #(load-result! (:result_id item) 0 true)}
                     (icon "model") (str "Result " (:statement_index item) " · " (:row_count item) " rows"))) statements)
            (dom/span #js {:className "result-tab active"} (icon "model") "Results")))
        (when result (dom/span #js {:className "result-count"} (str total " rows")))
        (dom/details #js {:className "saved-results"}
          (dom/summary #js {:title "Browse history" :aria-label "Browse history"} (icon "clock"))
          (dom/div #js {:className "result-picker"}
            (field-label "Saved result"
              (dom/select #js {:aria-label "Saved result" :value (or (:id result) "") :onChange #(when-not (str/blank? (.. % -target -value)) (load-result! (.. % -target -value)))}
                (dom/option #js {:value ""} "Choose a saved result...") (map result-option (:ui/results props))))))
        (when result (dom/a #js {:className "icon-button" :aria-label "Download CSV" :title "Download CSV" :href (str "/api/results/" (:id result) "/download?format=csv")} (icon "download"))))
      (if result
        (dom/div {}
          (when comparison?
            (dom/div #js {:className (str "comparison-outcome " (if (:equal summary) "matches" "differs")) :role "status"}
              (dom/strong {} (if (:equal summary) "Rows match" (str (or (:differences summary) total) " differences")))
              (dom/span {} (str (:left_rows summary) " left rows · " (:right_rows summary) " right rows"))
              (when (false? (:schema_equal summary)) (dom/p #js {:className "schema-difference"} "Column types/names differ."))
              (dom/p #js {:className "hint"} (or (:comparison_scope summary) "Comparison applies to these saved result rows."))))
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
            (dom/span {} (str (if (zero? total) 0 (inc offset)) "-" (min total (+ offset (count (:rows page)))) " of " total " rows"))
            (dom/div {} (button "Previous" #(load-result! (:id result) (max 0 (- offset limit))) "small" (zero? offset))
              (dom/span #js {:className "page-number"} (str (inc (quot offset limit))))
              (button "Next" #(load-result! (:id result) (+ offset limit)) "small" (>= (+ offset limit) total))))
          (dom/details #js {:className "result-sql card"}
            (dom/summary {} "SQL & provenance")
            (dom/h4 {} "EXECUTED SQL") (dom/pre {} (json-display (:sql result)))
            (dom/h4 {} "INPUT PROVENANCE") (dom/pre {} (json-display (:provenance result)))))
        (dom/div #js {:className "empty-editor"} (icon "play") (dom/h3 {} "An idea, then a result.")
          (dom/p {} "Preview a model or run a query. Its result will appear here with a record of SQL and input bindings.")))
      )))

(defn job-activity [props]
  (let [selected (first (filter #(= (:job/id %) (:ui/selected-job props)) (:ui/jobs props)))
        active? (#{"queued" "running" "cancelling"} (:status selected))]
    (dom/aside #js {:className "activity"}
      (dom/div #js {:className "activity-filter"}
        (dom/select #js {:aria-label "Filter activity" :value (:ui/job-filter props) :onChange #(value! :ui/job-filter %)}
          (map (fn [[value label]] (dom/option #js {:key value :value value} label))
            [["all" "All activity"] ["query" "Queries"] ["build" "Builds"] ["test" "Tests"] ["failed" "Failed runs"]])))
      (dom/div #js {:className "job-list"}
        (if (seq (:ui/jobs props)) (map #(job-row (comp/computed % {:active (= (:job/id %) (:ui/selected-job props))}))
                                   (filter #(case (:ui/job-filter props) "all" true "failed" (= "failed" (:status %)) (= (:ui/job-filter props) (:kind %))) (:ui/jobs props)))
          (dom/div #js {:className "activity-empty"} (icon "clock") (dom/p {} "Your runs land here.") (dom/span {} "Build, test, or preview to get started."))))
      (when selected
        (dom/div #js {:className "job-details"}
          (dom/div #js {:className "log-heading"} (dom/strong {} "Activity details")
            (when active? (button "Cancel" #(cancel-job! (:job/id selected)) "small danger" (= "cancelling" (:status selected)))))
          (dom/dl #js {:className "activity-metadata"}
            (dom/dt {} "Started") (dom/dd {} (date-label (or (:started_at selected) (:created_at selected))))
            (dom/dt {} "Status") (dom/dd #js {:className "detail-status"}
                                    (dom/span #js {:className (str "status-dot " (:status selected))}) (str/capitalize (:status selected)))
            (dom/dt {} "Target") (dom/dd {} (or (get-in selected [:options :target]) (:ui/target props)))
            (dom/dt {} "Type") (dom/dd {} (str/capitalize (:kind selected))))
          (when (= "query" (:kind selected))
            (dom/div #js {:className "replay-actions"}
              (button "Load query" #(load-query! selected) "small")
              (button "Replay" #(load-query! selected true) "small" (:ui/submitting props))))
          (dom/details #js {:className "job-summary"}
            (dom/summary {} "Run metadata")
            (dom/pre {} (json-display (select-keys selected [:created_at :started_at :finished_at :status :options :result_id]))))
          (when (:error selected) (dom/div #js {:className "job-error"} (human-error (:error selected))))
          (when (:summary selected) (dom/details #js {:className "job-summary"} (dom/summary {} "Run summary") (dom/pre {} (json-display (:summary selected)))))
          (dom/details #js {:className "job-logs" :open (boolean active?)}
            (dom/summary {} "Logs")
            (dom/div #js {:className "log-view" :role "log" :aria-label "Job logs"}
            (if (seq (:ui/events props))
              (map #(dom/div #js {:className (str "log-line " (:level %)) :key (:seq %)}
                      (dom/span #js {:className "log-time"} (date-label (:timestamp %)))
                      (dom/span {} (:message %))) (:ui/events props))
              (dom/p #js {:className "muted"} (if active? "Waiting for output…" "No log output."))))))))))

(defn changes-panel [props]
  (dom/div #js {:className "changes-panel"}
    (if (seq (:ui/changes props))
      (map (fn [entry]
             (dom/details #js {:key (:id entry) :className "change-entry"}
               (dom/summary {} (dom/strong {} (:path entry)) (dom/span {} (date-label (:saved_at entry))))
               (dom/p #js {:className "hint"} (str (or (:reason entry) "File saved") ". Load either version as a draft, then save to apply it."))
               (dom/h4 {} "Before") (dom/pre {} (:before entry))
               (dom/h4 {} "After") (dom/pre {} (:after entry))
               (button "Load before" #(load-file! (:path entry) {:content (:before entry)}) "small" (nil? (:before entry)))
               (button "Load after" #(load-file! (:path entry) {:content (:after entry)}) "small"))) (:ui/changes props))
      (dom/p #js {:className "sidebar-hint"} "Saved project edits appear here. Query runs stay in Activity."))))

(defn activity [props]
  (dom/aside #js {:className "history-panel"}
    (dom/nav #js {:className "history-tabs" :aria-label "History"}
      (dom/button #js {:className (str "button " (when (= "activity" (:ui/activity-tab props)) "selected")) :onClick #(patch! {:ui/activity-tab "activity"})} (icon "clock") "Activity")
      (dom/button #js {:className (str "button " (when (= "changes" (:ui/activity-tab props)) "selected")) :onClick #(patch! {:ui/activity-tab "changes"})} (icon "changes") "Changes"))
    (if (= "changes" (:ui/activity-tab props)) (changes-panel props) (job-activity props))))

(defsc Root [_ props]
  {:query [:ui/theme :ui/projects :ui/project :ui/files :ui/results :ui/target :ui/defer :ui/favor-state
           :ui/tab :ui/model-id :ui/file :ui/content :ui/saved-content :ui/selected-job
           :ui/events :ui/event-cursor :ui/result :ui/page :ui/error :ui/notice :ui/submitting
           :ui/open-path :ui/profiles :ui/query-format :ui/query-edn :ui/query-sql :ui/parameters :ui/sql-parameters
           :ui/catalog :ui/visual-query :ui/format-options :ui/editor-revision :ui/query-job :ui/query-requesting :ui/query-selection :ui/converting :ui/conversion-note :ui/model-search :ui/build-scope :ui/activity-tab :ui/changes :ui/job-filter
           :ui/dialect :ui/baseline-input :ui/left-result :ui/right-result :ui/compare-keys :ui/conflict :ui/artifact-dir :ui/reference-target
           {:ui/models (comp/get-query ModelRow)} {:ui/jobs (comp/get-query JobRow)}]
   :initial-state (fn [_] initial)}
  (let [project (:ui/project props) selected (:ui/model-id props) tab (:ui/tab props)
        dirty (not= (:ui/content props) (:ui/saved-content props))]
    (dom/div #js {:className "app-shell"}
      (dom/header #js {:className "app-header"}
        (dom/button #js {:className "brand" :onClick choose-project! :title "All projects"}
          (dom/span #js {:className "brand-mark"} "w") (dom/span {} "workbench"))
        (when project
          (dom/div #js {:className "project-switch"}
            (dom/span #js {:className "header-divider" :aria-hidden true})
            (dom/div #js {:className "target-control"} (icon "database")
            (dom/select #js {:aria-label "Target" :value (:ui/target props) :onChange #(value! :ui/target %)}
              (map #(dom/option #js {:key % :value %} %) (or (seq (:targets project)) ["dev"]))))))
        (when project
          (dom/div #js {:className "run-toolbar"}
            (dom/div #js {:className "run-actions"}
              (button "Test" #(submit! "test" (build-options props)) "small" (or dirty (:ui/submitting props)))
              (button "Build" build-project! "primary small" (or dirty (:ui/submitting props)))
              (dom/details #js {:className "build-options"}
                (dom/summary {} "Build options")
                (dom/div #js {:className "build-options-menu card"}
                  (field-label "Build scope"
                    (dom/select #js {:aria-label "Build scope" :value (:ui/build-scope props) :onChange #(value! :ui/build-scope %)}
                      (dom/option #js {:value "project"} "Entire project")
                      (dom/option #js {:value "selected" :disabled (not selected)} "Selected model")
                      (dom/option #js {:value "baseline"} "Save reference baseline")))
                  (dom/p #js {:className "hint"} "Build creates models and runs tests. Test checks existing models. Both use the selected target.")
                  (when (:baseline project)
                    (dom/div {}
                      (checkbox "Use baseline refs" :ui/defer (:ui/defer props))
                      (checkbox "Prefer baseline relations" :ui/favor-state (:ui/favor-state props))))
                  (button "Refresh model graph" #(submit! "parse" {}) "small" (:ui/submitting props)))))))
        (dom/div #js {:className "theme-control"} (icon "theme")
          (dom/select #js {:aria-label "Theme" :value (:ui/theme props) :onChange #(choose-theme! (.. % -target -value))}
            (dom/option #js {:value "system"} "System theme")
            (dom/option #js {:value "light"} "Light theme")
            (dom/option #js {:value "dark"} "Dark theme")))
        (dom/div #js {:className "header-right"} (dom/span #js {:className "local-light"}) "Local session"))
      (when (:ui/error props)
        (dom/div #js {:className "message error" :role "alert"} (dom/span {} (:ui/error props))
          (dom/button #js {:className "icon-button" :aria-label "Dismiss error" :onClick #(patch! {:ui/error nil})} "×")))
      (when (:ui/notice props)
        (dom/div #js {:className "message notice" :role "status"} (dom/span {} (:ui/notice props))
          (dom/button #js {:className "icon-button" :aria-label "Dismiss notice" :onClick #(patch! {:ui/notice nil})} "×")))
      (if-not project (welcome props)
        (dom/div #js {:className "project-shell"}
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
  (try (when-let [saved (js/localStorage.getItem "workbench.sql-format-options")] (patch! {:ui/format-options (merge (:ui/format-options initial) (js->clj (js/JSON.parse saved) :keywordize-keys true))})) (catch :default _ nil))
  (format-query!)
  (let [saved-theme (read-preference "workbench.theme")
        theme (if (#{"light" "dark" "system"} saved-theme) saved-theme "system")]
    (choose-theme! theme)
    (.addEventListener (js/window.matchMedia "(prefers-color-scheme: dark)") "change"
      (fn [_] (apply-theme! (:ui/theme (current))))))
  (-> (request! "GET" "/api/projects")
      (.then (fn [data]
               (patch! {:ui/projects (:projects data)})
               (when-let [id (read-preference "workbench.project")]
                 (if (some #(= id (:id %)) (:projects data))
                   (load-project! id)
                   (save-preference! "workbench.project" nil)))))
      (.catch report-error!))
  (js/window.addEventListener "beforeunload"
    (fn [event] (when (dirty?) (.preventDefault event) (set! (.-returnValue event) ""))))
  (when @timer (js/clearInterval @timer))
  (reset! timer (js/setInterval poll! 750)))
