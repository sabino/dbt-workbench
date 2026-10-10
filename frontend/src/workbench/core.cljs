(ns workbench.core
  (:require [clojure.string :as str]
            [cljs.reader :as reader]
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
            ["@phosphor-icons/react/dist/csr/SidebarSimple" :as sidebar-icon]
            ["./editor.js" :as editor]
            ["./panels.js" :as panels]
            ["./query-library.js" :as query-library]
            ["./workspace-ui.js" :as workspace-ui]
            ["./document-modes.js" :as document-modes]
            ["./command-center.js" :as command-center]
            [com.fulcrologic.fulcro.application :as app]
            [com.fulcrologic.fulcro.components :as comp :refer [defsc]]
            [com.fulcrologic.fulcro.dom :as dom]
            [com.fulcrologic.fulcro.mutations :as m :refer [defmutation]]))

(declare application load-project! load-file! select-model! select-job! load-result!
         submit! refresh-project! save-file! create-demo! open-project! cancel-job! import-baseline! load-query! load-changes! run-query! json-display close-file! update-file-content! load-catalog! format-query! save-format-options! query-parameters new-file! save-new-file! run-preview! build-project!)

(def default-sql "SELECT customer_id, SUM(amount) AS revenue\nFROM orders\nGROUP BY customer_id\nORDER BY customer_id")
(defonce file-load-counter (atom 0))
(defonce conversion-counter (atom 0))
(defonce untitled-counter (atom 0))
(defonce editor-context-counter (atom 0))
(def initial
  {:ui/inspector nil :ui/bottom-tab "results" :ui/sidebar-collapsed false :ui/sidebar-width 200 :ui/context-width 390 :ui/files-width 290 :ui/file-view "models" :ui/files-search "" :ui/explorer-visible true
   :ui/open-files [] :ui/converting nil :ui/save-dialog nil :ui/always-format false :ui/scratch-visible true :ui/query-conversion nil :ui/query-parameters "{}" :ui/query-saved-content default-sql :ui/query-saved-format "sql"
   :ui/status-job nil :ui/results-view "data" :ui/compare-result nil :ui/theme "system" :ui/projects [] :ui/project nil :ui/models [] :ui/jobs [] :ui/files []
   :ui/results [] :ui/target "dev" :ui/defer true :ui/favor-state false
   :ui/tab "query" :ui/model-id nil :ui/file nil :ui/loading-file nil :ui/content "" :ui/saved-content ""
   :ui/selected-job nil :ui/events [] :ui/event-cursor 0 :ui/result nil :ui/page nil
   :ui/error nil :ui/notice nil :ui/submitting false :ui/open-path "" :ui/profiles ""
   :ui/catalog [] :ui/editor-context {:resources [] :manifest_available false} :ui/editor-context-loading false :ui/editor-context-error nil
   :ui/editor-options {:ednMode "paredit" :rainbowEdn false}
   :ui/format-options {:keywordCase "upper" :useTabs false :tabWidth 2 :indentStyle "standard" :logicalOperatorNewline "before" :expressionWidth 50 :linesBetweenQueries 2}
   :ui/max-rows 10000 :ui/timeout 30 :ui/query-split 50 :ui/query-format "sql" :ui/query-content default-sql
   :ui/parameters "{}" :ui/dialect "duckdb" :ui/baseline-input false
   :ui/editor-revision 0 :ui/query-job nil :ui/query-requesting false :ui/query-selection {:from 0 :to 0 :head 0}
   :ui/build-scope "project" :ui/changes [] :ui/job-filter "all"
   :ui/right-result "" :ui/compare-keys "" :ui/conflict false :ui/artifact-dir "" :ui/reference-target "prod"})

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
(defn native-file-format [file]
  (cond (:untitled file) (:native-mode file "sql")
        (str/ends-with? (:path file) ".sql") "sql"
        (str/ends-with? (:path file) ".edn") "edn"
        :else "text"))
(defn resolve-file-format [file] (or (:editor-mode file) (native-file-format file)))
(defn file-document [file]
  {:mode (resolve-file-format file) :content (:draft file (:content file)) :parameters (:parameters file "{}")
   :selection (:selection file {:from 0 :to 0 :head 0}) :conversion (:conversion file)
   :nativeMode (native-file-format file) :savedContent (:content file) :untitled (boolean (:untitled file))})
(defn file-dirty? [file] (and file (document-modes/isDocumentDirty (clj->js (file-document file)))))
(defn read-editor-document [s]
  (if-let [file (:ui/file s)]
    (let [opened (first (filter #(= (:path file) (:path %)) (:ui/open-files s)))]
      (assoc (file-document opened) :format (resolve-file-format opened) :path (:path file) :key (:path file)))
    {:content (:ui/query-content s) :mode (:ui/query-format s) :format (:ui/query-format s)
     :selection (:ui/query-selection s) :parameters (:ui/query-parameters s) :conversion (:ui/query-conversion s) :key "scratch-query"
     :nativeMode (:ui/query-saved-format s) :savedContent (:ui/query-saved-content s)}))
(defn query-dirty? [s]
  (document-modes/isDocumentDirty (clj->js (read-editor-document (assoc s :ui/file nil)))))
(defn dirty? []
  (or (query-dirty? (current))
      (some file-dirty? (:ui/open-files (current)))))
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
(defn save-editor-options! [options]
  (let [validated {:ednMode (if (#{"paredit" "parinfer" "plain"} (:ednMode options)) (:ednMode options) "paredit")
                   :rainbowEdn (true? (:rainbowEdn options))}]
    (patch! {:ui/editor-options validated})
    (save-preference! "workbench.editor-options" (pr-str validated))))
(defn load-editor-options! []
  (try
    (when-let [saved (read-preference "workbench.editor-options")]
      (let [options (reader/read-string saved)]
        (when (map? options)
          (patch! {:ui/editor-options {:ednMode (if (#{"paredit" "parinfer" "plain"} (:ednMode options)) (:ednMode options) "paredit")
                                      :rainbowEdn (true? (:rainbowEdn options))}}))))
    (catch :default _ nil)))
(def layout-limits
  {:ui/sidebar-width [160 300] :ui/context-width [280 780] :ui/files-width [220 600]
   :ui/query-split [25 75]})
(def layout-keys (conj (vec (keys layout-limits)) :ui/sidebar-collapsed :ui/explorer-visible :ui/file-view))
(defn save-layout! [updates]
  (patch! updates)
  (save-preference! "workbench.layout" (pr-str (select-keys (current) layout-keys))))
(defn load-layout! []
  (try
    (when-let [saved (read-preference "workbench.layout")]
      (let [layout (reader/read-string saved)
            sizes (reduce-kv (fn [values key [minimum maximum]]
                               (let [value (get layout key)]
                                 (if (and (number? value) (js/isFinite value))
                                   (assoc values key (max minimum (min maximum value))) values))) {} layout-limits)
            switches (into {} (filter (fn [[_ value]] (boolean? value)) (select-keys layout [:ui/sidebar-collapsed :ui/explorer-visible])))
            file-view (:ui/file-view layout)]
        (patch! (cond-> (merge sizes switches) (#{"models" "all"} file-view) (assoc :ui/file-view file-view)))))
    (catch :default _ nil)))
(defn inspect-view! [view]
  (if (#{"results" "lineage" "activity" "changes" "runs"} view)
    (patch! {:ui/bottom-tab view})
    (patch! {:ui/inspector view})))
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
    (swap! file-load-counter inc)
    (swap! editor-context-counter inc)
    (save-preference! "workbench.project" nil)
    (patch! {:ui/project nil :ui/file nil :ui/loading-file nil :ui/open-files [] :ui/model-id nil :ui/inspector nil :ui/error nil :ui/notice nil :ui/content "" :ui/saved-content ""
             :ui/editor-context {:resources [] :manifest_available false} :ui/editor-context-loading false :ui/editor-context-error nil
             :ui/converting nil :ui/save-dialog nil :ui/submitting false :ui/query-content default-sql :ui/query-saved-content default-sql :ui/query-saved-format "sql"
             :ui/query-format "sql" :ui/query-conversion nil :ui/query-parameters "{}" :ui/parameters "{}" :ui/scratch-visible true
             :ui/query-selection {:from 0 :to 0 :head 0} :ui/editor-revision (inc (:ui/editor-revision (current)))})))
(defn load-projects! []
  (-> (request! "GET" "/api/projects")
    (.then #(patch! {:ui/projects (:projects %)})) (.catch report-error!)))
(defn load-editor-context! [id]
  (let [request-id (swap! editor-context-counter inc)]
    (patch! {:ui/editor-context-loading true :ui/editor-context-error nil})
    (-> (request! "GET" (str "/api/projects/" id "/editor-context"))
        (.then (fn [context]
                 (when (and (= id (project-id)) (= request-id @editor-context-counter))
                   (patch! {:ui/editor-context context :ui/editor-context-loading false}))))
        (.catch (fn [error]
                  (when (and (= id (project-id)) (= request-id @editor-context-counter))
                    (patch! {:ui/editor-context {:resources [] :manifest_available false}
                             :ui/editor-context-loading false :ui/editor-context-error (.-message error)})))))))
(defn load-models! [id]
  (load-editor-context! id)
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
    (.then (fn [data]
             (when (= id (project-id))
               (patch! {:ui/results (:results data)})
               (when (nil? (:ui/result (current)))
                 (when-let [result (first (remove #(= "compare" (:kind %)) (:results data)))] (load-result! (:id result) 0 true)))))) (.catch report-error!)))
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
    (swap! file-load-counter inc)
    (swap! editor-context-counter inc)
    (patch! {:ui/error nil :ui/notice nil :ui/file nil :ui/loading-file nil :ui/content "" :ui/saved-content ""
           :ui/open-files [] :ui/converting nil :ui/save-dialog nil :ui/submitting false :ui/scratch-visible true
           :ui/editor-context {:resources [] :manifest_available false} :ui/editor-context-loading false :ui/editor-context-error nil
           :ui/query-content default-sql :ui/query-saved-content default-sql :ui/query-saved-format "sql" :ui/query-format "sql" :ui/query-conversion nil :ui/query-parameters "{}" :ui/parameters "{}"
           :ui/query-selection {:from 0 :to 0 :head 0} :ui/editor-revision (inc (:ui/editor-revision (current)))
           :ui/inspector nil :ui/bottom-tab "results" :ui/status-job nil :ui/compare-result nil :ui/models [] :ui/jobs [] :ui/events [] :ui/event-cursor 0 :ui/selected-job nil :ui/query-job nil :ui/query-requesting false
           :ui/result nil :ui/page nil :ui/results [] :ui/model-id nil :ui/conflict false :ui/changes [] })
  (-> (request! "GET" (str "/api/projects/" id))
    (.then (fn [project]
             (let [targets (:targets project)
                   target (if (some #{"dev"} targets) "dev" (or (first targets) "dev"))]
               (save-preference! "workbench.project" id)
               (patch! {:ui/project project :ui/target target :ui/tab "query" :ui/right-result ""})
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
(defn update-file-content! [path content]
  (let [files (:ui/open-files (current)) file (first (filter #(= path (:path %)) files))]
    (when-not (:read_only file)
      (patch! (cond-> {:ui/open-files (mapv #(if (= path (:path %)) (assoc % :draft content) %) files)}
                (= path (get-in (current) [:ui/file :path])) (assoc :ui/content content))))))
(defn update-document-parameters! [event]
  (let [parameters (.. event -target -value) path (get-in (current) [:ui/file :path])]
    (patch! (if path
              {:ui/parameters parameters :ui/open-files (mapv #(if (= path (:path %)) (assoc % :parameters parameters) %) (:ui/open-files (current)))}
              {:ui/parameters parameters :ui/query-parameters parameters}))))
(defn read-document-by-key [key]
  (let [s (current)]
    (if (= key "scratch-query") (read-editor-document (assoc s :ui/file nil))
      (when-let [file (first (filter #(= key (:path %)) (:ui/open-files s)))] (assoc (file-document file) :key key)))))
(defn update-document-view! [key view]
  (let [s (current) active? (= key (:key (read-editor-document s)))
        updates (if (= key "scratch-query")
                  {:ui/query-content (:content view) :ui/query-format (:mode view) :ui/query-conversion (:conversion view)
                   :ui/query-selection (:selection view) :ui/query-parameters (:parameters view)}
                  {:ui/open-files (mapv #(if (= key (:path %))
                                         (assoc % :draft (:content view) :editor-mode (:mode view) :conversion (:conversion view)
                                                  :selection (:selection view) :parameters (:parameters view)) %) (:ui/open-files s))})]
    (patch! (cond-> updates
              active? (assoc :ui/parameters (:parameters view))
              (and active? (not= key "scratch-query"))
              (assoc :ui/content (:content view) :ui/file (assoc (:ui/file s) :editor-mode (:mode view)))))))
(defn format-document-content! [content mode options]
  (if (or (str/blank? content) (= "text" mode)) (js/Promise.resolve content)
    (case mode
      "sql" (js/Promise.resolve (editor/formatSql content (clj->js options)))
      "edn" (-> (request! "POST" (api-path "/query/format") {:content content :format "edn"})
                (.then (fn [response] (:content response)))))))
(defn convert-document! [document mode & [save-format]]
  (let [s (current) save-format (or save-format (when (and (= "edn" (:mode document)) (= "sql" mode)) "sql"))]
    (when (and (:path document) (re-find #"\{[\{%]" (:content document)))
      (throw (js/Error. "Compile dbt templates with Preview model first. SQL-to-EDN conversion requires executable SQL.")))
    (-> (request! "POST" (api-path "/query/convert")
          (cond-> {:content (:content document) :format (:mode document) :parameters (js->clj (js/JSON.parse (:parameters document)) :keywordize-keys true)
                   :target (:ui/target s) :dialect (:ui/dialect s)} save-format (assoc :save_format save-format)))
        (.then (fn [response]
                 (let [view {:mode mode :content (:content response)
                             :parameters (if (contains? response :parameters) (json-display (:parameters response)) (:parameters document))
                             :selection {:from 0 :to 0 :head 0}}]
                   (assoc view :conversion (js->clj (document-modes/recordConversion (clj->js document) (clj->js view)) :keywordize-keys true))))))))
(defn select-editor-mode! [mode]
  (let [s (current) document (read-editor-document s) key (:key document)
        plan (js->clj (document-modes/planModeChange (clj->js document) mode) :keywordize-keys true)]
    (when (and (#{"sql" "edn" "text"} mode) (not= "none" (:kind plan))
               (not (get-in s [:ui/file :read_only])) (not (:ui/loading-file s)))
      (let [id (project-id) request-id (swap! conversion-counter inc)]
        (try
          (patch! {:ui/converting {:key key :id request-id} :ui/error nil :ui/notice nil})
          (-> (if (= "convert" (:kind plan)) (convert-document! document mode)
                (js/Promise.resolve (merge document (dissoc plan :kind))))
              (.then (fn [view]
                       (if (:ui/always-format s)
                         (-> (format-document-content! (:content view) mode (:ui/format-options s))
                             (.then (fn [content]
                                      (let [formatted (assoc view :content content)]
                                        (assoc formatted :conversion
                                          (js->clj (document-modes/recordConversion (clj->js document) (clj->js formatted)) :keywordize-keys true))))))
                         view)))
              (.then (fn [view]
                       (when (and (= id (project-id)) (= request-id (get-in (current) [:ui/converting :id])))
                         (when (document-modes/matchesConversionRequest (clj->js (read-document-by-key key)) (clj->js document))
                           (update-document-view! key view)
                           (when (= key (:key (read-editor-document (current)))) (editor/focusQueryEditor)))
                         (patch! {:ui/converting nil}))))
              (.catch (fn [error]
                        (when (= request-id (get-in (current) [:ui/converting :id]))
                          (patch! {:ui/converting nil}) (report-error! error)))))
          (catch :default error (patch! {:ui/converting nil}) (report-error! error)))))))
(defn activate-query! []
  (swap! file-load-counter inc)
  (patch! {:ui/file nil :ui/loading-file nil :ui/content "" :ui/saved-content "" :ui/model-id nil :ui/conflict false :ui/error nil
           :ui/scratch-visible true :ui/parameters (:ui/query-parameters (current))}))
(defn update-file-selection! [path selection]
  (patch! {:ui/open-files (mapv #(if (= path (:path %)) (assoc % :selection (js->clj selection :keywordize-keys true)) %) (:ui/open-files (current)))}))
(defn activate-file! [file]
  (swap! file-load-counter inc)
  (patch! {:ui/file (dissoc file :draft) :ui/loading-file nil :ui/content (:draft file (:content file)) :ui/saved-content (:content file)
           :ui/parameters (:parameters file "{}")
           :ui/model-id (:unique_id (first (filter #(= (:path file) (:path %)) (vals (:model/id (current))))))
           :ui/tab "query" :ui/conflict false :ui/error nil}))
(defn close-file! [path]
  (let [files (:ui/open-files (current)) file (first (filter #(= path (:path %)) files))]
    (when (or (not (file-dirty? file)) (js/confirm "Discard the unsaved changes in this file?"))
      (let [remaining (filterv #(not= path (:path %)) files)]
        (patch! {:ui/open-files remaining})
        (when (= path (get-in (current) [:ui/file :path]))
          (if-let [next-file (last remaining)] (activate-file! next-file)
            (activate-query!)))))))
(defn new-file! [& [document]]
  (let [number (swap! untitled-counter inc)
        file {:path (str "untitled:" number) :name (str "Untitled-" number) :editor-id (str "untitled:" number)
              :untitled true :native-mode "sql" :editor-mode (:mode document "sql") :content "" :draft (:content document "")
              :parameters (:parameters document "{}") :selection {:from 0 :to 0 :head 0} :conversion (:conversion document)}]
    (patch! {:ui/open-files (conj (:ui/open-files (current)) file)})
    (activate-file! file)
    (js/requestAnimationFrame editor/focusQueryEditor)))
(defn close-active-document! []
  (if-let [file (:ui/file (current))] (close-file! (:path file))
    (when (or (not (query-dirty? (current))) (js/confirm "Discard the unsaved scratch query?"))
      (let [next-file (last (:ui/open-files (current)))]
        (patch! {:ui/scratch-visible (nil? next-file) :ui/query-content "" :ui/query-saved-content "" :ui/query-saved-format "sql" :ui/query-format "sql"
                 :ui/query-conversion nil :ui/query-parameters "{}" :ui/query-selection {:from 0 :to 0 :head 0}
                 :ui/editor-revision (inc (:ui/editor-revision (current)))})
        (if next-file (activate-file! next-file) (activate-query!))))))
(defn load-file! [path & [draft]]
  (let [id (project-id) opened (first (filter #(= path (:path %)) (:ui/open-files (current))))]
    (if (and opened (nil? draft)) (activate-file! opened)
      (let [request-id (swap! file-load-counter inc)]
        (patch! {:ui/loading-file path})
        (-> (request! "GET" (str "/api/projects/" id "/file?path=" (js/encodeURIComponent path)))
          (.then (fn [file]
                   (when (and (= id (project-id)) (= request-id @file-load-counter))
                     (let [file (assoc file :draft (if (and draft (not (:read_only file))) (:content draft) (:content file)))]
                       (patch! {:ui/open-files (conj (filterv #(not= path (:path %)) (:ui/open-files (current))) file)})
                       (activate-file! file)))))
          (.catch (fn [error]
                    (when (= request-id @file-load-counter)
                      (patch! {:ui/loading-file nil}) (report-error! error)))))))))
(defn select-model! [model]
  (when (:path model) (load-file! (:path model))))
(defn prepare-file-save! [file path]
  (let [document (file-document file) native-mode (native-file-format {:path path})
        content (document-modes/canonicalContent (clj->js document) native-mode)
        bound-copy? (and (:untitled file) (#{"sql" "edn"} native-mode) (seq (js->clj (js/JSON.parse (:parameters document)))))]
    (if (and (some? content) (not bound-copy?)) (js/Promise.resolve {:content content :conversion (:conversion file)})
      (let [mode (if (= native-mode "text") "sql" native-mode)]
        (convert-document! (assoc document :path (:path file)) mode mode)))))
(defn persist-file! [file path create?]
  (let [id (project-id) key (:path file) request-id (swap! conversion-counter inc)]
    (try
      (patch! {:ui/submitting true :ui/converting {:key key :id request-id} :ui/error nil})
      (-> (prepare-file-save! file path)
          (.then (fn [prepared]
                   (when (= request-id (get-in (current) [:ui/converting :id])) (patch! {:ui/converting nil}))
                   (-> (request! (if create? "POST" "PUT") (str "/api/projects/" id (if create? "/files" "/file"))
                         (cond-> {:path path :content (:content prepared)} (not create?) (assoc :expected_revision (:revision file))))
                       (.then (fn [saved] {:saved saved :prepared prepared})))))
          (.then (fn [{:keys [saved prepared]}]
                   (when (= id (project-id))
                     (let [files (mapv (fn [opened]
                                        (if (not= key (:path opened)) opened
                                          (let [unchanged? (document-modes/matchesConversionRequest (clj->js (file-document opened)) (clj->js (file-document file)))
                                                adopt? (and unchanged? (:untitled file) (= (resolve-file-format opened) (:mode prepared)))]
                                            (cond-> (assoc (merge opened saved) :untitled false :name (:name saved (last (str/split path #"/"))))
                                              unchanged? (assoc :conversion (:conversion prepared))
                                              adopt? (assoc :draft (:content prepared) :parameters (:parameters prepared) :conversion nil))))) (:ui/open-files (current)))
                           opened (first (filter #(= path (:path %)) files))]
                       (patch! (cond-> {:ui/open-files files :ui/submitting false :ui/save-dialog nil :ui/conflict false :ui/notice "File saved to your project."}
                                 (= key (get-in (current) [:ui/file :path]))
                                 (assoc :ui/file (dissoc opened :draft) :ui/content (:draft opened) :ui/parameters (:parameters opened "{}")
                                        :ui/saved-content (:content saved))
                                 (and (:origin-query file) (= (:draft file) (:ui/query-content (current))))
                                 (assoc :ui/query-saved-content (:draft file) :ui/query-saved-format (resolve-file-format file))))
                       (load-files! id) (load-models! id) (load-changes! id)))))
          (.catch (fn [error]
                    (when (= id (project-id))
                      (patch! {:ui/submitting false :ui/converting nil})
                      (if (and (not create?) (= 409 (.-status error)) (not (str/starts-with? (.-message error) "Wait for")))
                        (patch! {:ui/conflict true :ui/error "This file changed on disk. Your draft is preserved. Copy it before reloading, or keep editing."})
                        (report-error! error))))))
      (catch :default error (patch! {:ui/submitting false :ui/converting nil}) (report-error! error)))))
(defn save-file! []
  (let [s (current) file (first (filter #(= (get-in s [:ui/file :path]) (:path %)) (:ui/open-files s)))]
    (cond
      (or (:ui/submitting s) (:ui/loading-file s) (:ui/converting s) (:read_only file)) nil
      (nil? file) (do (new-file! (read-editor-document s))
                     (patch! {:ui/open-files (mapv #(if (= (:path %) (get-in (current) [:ui/file :path])) (assoc % :origin-query true) %) (:ui/open-files (current)))})
                     (save-file!))
      (:untitled file) (patch! {:ui/save-dialog {:key (:path file) :path (str "models/" (str/lower-case (:name file)) ".sql")}})
      (file-dirty? file) (persist-file! file (:path file) false))))
(defn save-new-file! [path]
  (when-let [file (first (filter #(= (:path %) (get-in (current) [:ui/save-dialog :key])) (:ui/open-files (current))))]
    (persist-file! file (str/trim path) true)))
(defn job-options []
  (let [s (current) defer (and (:ui/defer s) (get-in s [:ui/project :baseline]))]
    {:target (:ui/target s) :defer (boolean defer) :favor_state (boolean (and defer (:ui/favor-state s)))}))
(defn submit! [kind options]
  (patch! {:ui/submitting true :ui/query-requesting (= "query" kind) :ui/error nil :ui/notice nil})
  (-> (request! "POST" (api-path "/jobs") {:kind kind :options (merge (job-options) options)})
    (.then (fn [job]
             (patch! (cond-> {:ui/submitting false :ui/query-requesting false :ui/status-job (:id job)} (= "query" kind) (assoc :ui/query-job (:id job))))
             (select-job! (:id job))))
    (.catch report-error!)))
(defn select-job! [id]
  (patch! {:ui/selected-job id :ui/events [] :ui/event-cursor 0}))
(defn cancel-job! [id]
  (-> (request! "POST" (str "/api/jobs/" id "/cancel") {})
    (.then #(when-not (= "query" (:kind %)) (patch! {:ui/notice (str "Cancellation requested for " (:kind %) ".")})))
    (.catch report-error!)))
(defn load-result! [id & [offset background?]]
  (let [offset (or offset 0) project (project-id)]
    (-> (js/Promise.all #js [(request! "GET" (str "/api/results/" id))
                             (request! "GET" (str "/api/results/" id "/rows?offset=" offset "&limit=10"))])
      (.then (fn [data]
               (when (and (= project (project-id)) (or (not background?) (nil? (:ui/result (current)))))
                 (let [result (aget data 0) comparison? (= "compare" (:kind result))]
                   (patch! (if comparison?
                             {:ui/compare-result result :ui/compare-page (aget data 1) :ui/bottom-tab "results" :ui/results-view "compare" :ui/error nil}
                             (cond-> {:ui/result result :ui/page (aget data 1)}
                               (not background?) (assoc :ui/bottom-tab "results" :ui/results-view "data" :ui/compare-result nil :ui/compare-page nil :ui/tab "query" :ui/error nil))))))))
      (.catch report-error!))))
(defn query-parameters [s]
  (let [input (:ui/parameters s)
        parameters (js->clj (js/JSON.parse (if (str/blank? input) "{}" input)) :keywordize-keys true)]
    (when-not (map? parameters) (throw (js/Error. "Parameters must be a JSON object.")))
    parameters))

(defn run-query! [& [all? editor-content editor-selection]]
  (when-not (:ui/submitting (current))
    (let [s (current) document (read-editor-document s) format (:format document)
          content (or editor-content (:content document))
          selection (if editor-selection (js->clj editor-selection :keywordize-keys true) (:selection document))]
      (editor/focusQueryEditor)
      (try
        (when-not (#{"sql" "edn"} format) (throw (js/Error. "Choose SQL or EDN to execute this document.")))
        (when (:ui/loading-file s) (throw (js/Error. "Wait for the file to finish opening.")))
        (when (get-in s [:ui/file :read_only]) (throw (js/Error. "This file is read-only in the workbench.")))
        (when (and (:path document) (re-find #"\{[\{%]" content))
          (throw (js/Error. "Save this dbt model, then use Preview model to compile its references.")))
        (let [input (js->clj (editor/executionInput content (clj->js selection) format (boolean all?)) :keywordize-keys true)]
          (when (or (empty? (:statements input)) (str/blank? (:content input)))
            (throw (js/Error. "Write or select a query to run.")))
          (submit! "query" (merge input {:editor_content content :selection selection
                                         :format format :editor_mode format :dialect (:ui/dialect s) :max_rows (:ui/max-rows s) :timeout_seconds (:ui/timeout s) :parameters (query-parameters s)})))
        (catch :default e (report-error! e))))))

(defn load-catalog! []
  (let [id (project-id) target (:ui/target (current))]
    (when id
      (-> (request! "GET" (str (api-path "/catalog?target=") (js/encodeURIComponent target)))
        (.then #(when (and (= id (project-id)) (= target (:ui/target (current)))) (patch! {:ui/catalog (:relations %)})))
        (.catch (fn [_] (when (= id (project-id)) (patch! {:ui/catalog []}))))))))
(defn save-format-options! [options]
  (patch! {:ui/format-options options})
  (js/localStorage.setItem "workbench.sql-format-options" (js/JSON.stringify (clj->js options))))
(defn format-query! []
  (let [s (current) document (read-editor-document s) key (:key document)
        id (project-id) request-id (swap! conversion-counter inc)]
    (when-not (or (:ui/converting s) (:ui/submitting s) (:ui/loading-file s) (get-in s [:ui/file :read_only]))
      (try
        (patch! {:ui/converting {:key key :id request-id :operation "format"} :ui/error nil})
        (-> (format-document-content! (:content document) (:mode document) (:ui/format-options s))
            (.then (fn [content]
                     (when (and (= id (project-id)) (= request-id (get-in (current) [:ui/converting :id])))
                       (when (document-modes/matchesConversionRequest (clj->js (read-document-by-key key)) (clj->js document))
                         (update-document-view! key (assoc document :content content))
                         (when (= key (:key (read-editor-document (current)))) (editor/focusQueryEditor)))
                       (patch! {:ui/converting nil}))))
            (.catch (fn [error]
                      (when (= request-id (get-in (current) [:ui/converting :id]))
                        (patch! {:ui/converting nil}) (report-error! error)))))
        (catch :default error (patch! {:ui/converting nil}) (report-error! error))))))

(defn load-query! [job & [rerun?]]
  (let [options (:options job) format (:format options)]
    (activate-query!)
    (patch! {:ui/editor-revision (inc (:ui/editor-revision (current))) :ui/tab "query" :ui/query-format format
             :ui/query-content (or (:editor_content options) (:content options))
             :ui/query-conversion nil :ui/query-parameters (json-display (:parameters options))
             :ui/parameters (json-display (:parameters options))
             :ui/target (:target options) :ui/dialect (:dialect options)
             :ui/query-selection (or (:selection options) {:from 0 :to 0 :head 0})
             :ui/error nil})
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
(defn editor-commands [s]
  (let [document (read-editor-document s) mode (:mode document) file (:ui/file s)
        opened (first (filter #(= (:path file) (:path %)) (:ui/open-files s)))
        blocked? (boolean (or (:read_only file) (:ui/loading-file s) (:ui/submitting s) (:ui/converting s)))
        navigating? (not (or (:ui/loading-file s) (:ui/converting s)))
        edn? (= "edn" mode)
        template? (boolean (re-find #"\{[\{%]" (:content document)))
        command (fn [id label category enabled run] {:id id :label label :category category :enabled (boolean enabled) :run run})]
    [(command "new-file" "New file" "File" true #(new-file!))
     (command "save-file" "Save file" "File" (not blocked?) save-file!)
     (command "close-tab" "Close active tab" "File" true close-active-document!)
     (command "run-query" "Run statement or selection" "Query" (and (#{"sql" "edn"} mode) (not blocked?)) #(run-query! false))
     (command "run-all" "Run all statements" "Query" (and (= mode "sql") (not blocked?) (not template?)) #(run-query! true))
     (command "format-document" "Format document" "Editor" (and (#{"sql" "edn"} mode) (not blocked?) (not template?)) format-query!)
     (command "trigger-completion" "Complete at cursor" "Editor" (and (#{"sql" "edn"} mode) (not blocked?)) #(editor/runEditorCommand "trigger-completion"))
     (command "editor-documentation" "Show documentation at cursor" "Editor" navigating? #(editor/runEditorCommand "show-documentation"))
     (command "goto-definition" "Go to definition" "Editor" navigating? #(editor/runEditorCommand "goto-definition"))
     (command "edn-forward" "EDN: move forward one form" "EDN navigation" (and edn? navigating?) #(editor/runEditorCommand "edn-forward"))
     (command "edn-backward" "EDN: move backward one form" "EDN navigation" (and edn? navigating?) #(editor/runEditorCommand "edn-backward"))
     (command "edn-up" "EDN: move out of form" "EDN navigation" (and edn? navigating?) #(editor/runEditorCommand "edn-up"))
     (command "edn-down" "EDN: move into form" "EDN navigation" (and edn? navigating?) #(editor/runEditorCommand "edn-down"))
     (command "edn-select-expand" "EDN: expand selection" "EDN navigation" (and edn? navigating?) #(editor/runEditorCommand "edn-select-expand"))
     (command "edn-select-contract" "EDN: contract selection" "EDN navigation" (and edn? navigating?) #(editor/runEditorCommand "edn-select-contract"))
     (command "edn-slurp-forward" "EDN: slurp next form" "EDN editing" (and edn? (not blocked?)) #(editor/runEditorCommand "edn-slurp-forward"))
     (command "edn-barf-forward" "EDN: barf last form" "EDN editing" (and edn? (not blocked?)) #(editor/runEditorCommand "edn-barf-forward"))
     (command "edn-slurp-backward" "EDN: slurp previous form" "EDN editing" (and edn? (not blocked?)) #(editor/runEditorCommand "edn-slurp-backward"))
     (command "edn-barf-backward" "EDN: barf first form" "EDN editing" (and edn? (not blocked?)) #(editor/runEditorCommand "edn-barf-backward"))
     (command "edn-wrap-vector" "EDN: wrap in vector" "EDN editing" (and edn? (not blocked?)) #(editor/runEditorCommand "edn-wrap-vector"))
     (command "edn-wrap-map" "EDN: wrap in map" "EDN editing" (and edn? (not blocked?)) #(editor/runEditorCommand "edn-wrap-map"))
     (command "edn-wrap-list" "EDN: wrap in list" "EDN editing" (and edn? (not blocked?)) #(editor/runEditorCommand "edn-wrap-list"))
     (command "edn-splice" "EDN: splice enclosing form" "EDN editing" (and edn? (not blocked?)) #(editor/runEditorCommand "edn-splice"))
     (command "switch-sql" "Translate query to SQL" "Editor" (not blocked?) #(select-editor-mode! "sql"))
     (command "switch-edn" "Translate query to EDN" "Editor" (not blocked?) #(select-editor-mode! "edn"))
     (command "preview-model" "Preview model" "dbt" (and (:ui/model-id s) (not (file-dirty? opened)) (not blocked?)) run-preview!)
     (command "build-project" "Build project" "dbt" (and (not (file-dirty? opened)) (not blocked?)) build-project!)
     (command "test-project" "Test project" "dbt" (and (not (file-dirty? opened)) (not blocked?)) #(submit! "test" (build-options (current))))
     (command "parse-project" "Parse project metadata" "dbt" (not blocked?) #(submit! "parse" {}))
     (command "toggle-files" "Toggle file explorer" "View" true #(save-layout! {:ui/explorer-visible (not (:ui/explorer-visible (current)))}))
     (command "toggle-sidebar" "Toggle navigation sidebar" "View" true #(save-layout! {:ui/sidebar-collapsed (not (:ui/sidebar-collapsed (current)))}))
     (command "show-results" "Show results" "View" true #(inspect-view! "results"))
     (command "show-lineage" "Show lineage" "View" true #(inspect-view! "lineage"))
     (command "show-metadata" "Inspect file metadata" "View" true #(inspect-view! "metadata"))
     (command "show-overview" "Open project overview" "View" true #(inspect-view! "overview"))
     (command "show-documentation" "Search documentation" "View" true #(inspect-view! "documentation"))
     (command "show-git" "Open version control" "View" true #(inspect-view! "git"))
     (command "show-connections" "Manage connections" "View" true #(inspect-view! "connections"))
     (command "show-routing" "Configure environment routing" "View" true #(inspect-view! "routing"))
     (command "show-activity" "Show query activity" "View" true #(inspect-view! "activity"))
     (command "show-changes" "Show file changes" "View" true #(inspect-view! "changes"))
     (command "show-runs" "Inspect runs and tests" "View" true #(inspect-view! "runs"))
     (command "toggle-theme" "Toggle light and dark theme" "View" true #(choose-theme! (if (= (:ui/theme (current)) "dark") "light" "dark")))
     (command "reset-layout" "Reset workspace layout" "View" true #(save-layout! (select-keys initial layout-keys)))]))
(defn run-compare! []
  (let [s (current)]
    (submit! "compare" {:left_result_id (get-in s [:ui/result :id]) :right_result_id (:ui/right-result s)
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
                         submitted-job (first (filter #(= (:ui/status-job (current)) (:id %)) jobs))
                         finished (filterv #(and (= "succeeded" (:status %))
                                                 (not (contains? @completed-jobs (:id %)))) jobs)]
                     (entities! :job/id :ui/jobs :id jobs)
                     (when (seq finished)
                       (swap! completed-jobs into (map :id finished))
                       (refresh-project!))
                     (when (and (#{"succeeded" "failed" "cancelled" "interrupted"} (:status job))
                                (:ui/notice (current)))
                       (patch! {:ui/notice nil}))
                     ;; Submitted jobs open results once. Selecting recorded Activity
                     ;; changes its details without navigating away from the panel.
                     (when (and (= "succeeded" (:status submitted-job)) (:result_id submitted-job)
                                (not (contains? @presented-results (:result_id submitted-job))))
                       (swap! presented-results conj (:result_id submitted-job))
                       (load-result! (:result_id submitted-job)))))))
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
      (case name "sidebar" sidebar-icon/SidebarSimple "theme" sun-icon/Sun "play" play-icon/Play "file" file-icon/FileText "model" table-icon/Table
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
(defn floating-menu [label trigger menu-class content & [class-name]]
  (react/createElement workspace-ui/Popover
    #js {:label label :trigger trigger :menuClass menu-class :children content :className (or class-name "icon-button")}))
(defn pane-resizer [props key label axis variable scope & [pane direction]]
  (let [[minimum maximum] (get layout-limits key)]
    (react/createElement workspace-ui/PaneResizer
      #js {:key (name key) :label label :axis axis :variable variable :scope scope :pane pane :direction (or direction 1)
           :value (get props key) :min minimum :max maximum :default (get initial key) :step (if (= axis "x") 20 5)
           :unit (if (= axis "x") "px" "%") :onResize #(save-layout! {key %})})))
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
      (icon "model") (dom/span #js {:className "model-name"} (or (some-> (:path model) (str/split #"/") last) name))
      (when materialized (dom/span #js {:className "model-kind"} materialized)))))
(def model-row (comp/factory ModelRow {:keyfn :model/id}))

(defsc JobRow [this {:job/keys [id] :keys [kind status created_at started_at finished_at options]}]
  {:query [:job/id :kind :status :created_at :started_at :finished_at :summary :error :result_id :options]
   :ident :job/id}
  (dom/button #js {:className (str "job-row " (when (:active (comp/get-computed this)) "active"))
                   :onClick #(select-job! id)}
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

(defn file-tree [entries models selected prefix]
  (let [direct (sort-by :path (filter #(= prefix (str/join "/" (butlast (str/split (:path %) #"/")))) entries))
        nested (if (str/blank? prefix) entries (filter #(str/starts-with? (:path %) (str prefix "/")) entries))
        folders (->> nested (map #(subs (:path %) (if (str/blank? prefix) 0 (inc (count prefix)))))
                  (filter #(str/includes? % "/")) (map #(first (str/split % #"/"))) distinct sort)
        model-by-path (into {} (map (juxt :path identity) models))]
    (dom/div #js {:className "tree-children"}
      (map (fn [file]
             (if-let [model (get model-by-path (:path file))]
               (model-row (comp/computed model {:active (= (:path file) selected)}))
               (dom/button #js {:className (str "file-row " (when (= (:path file) selected) "active"))
                                :key (:path file) :title (:path file) :onClick #(load-file! (:path file))}
                 (icon "file") (dom/span {} (last (str/split (:path file) #"/")))))) direct)
      (map (fn [folder]
             (let [path (if (str/blank? prefix) folder (str prefix "/" folder))]
               (dom/details #js {:className "tree-folder" :key path :open true}
                 (dom/summary {} (icon "chevron") (icon "folder") (dom/span {} folder))
                 (file-tree entries models selected path)))) folders))))

(defn sidebar [props]
  (let [collapsed? (:ui/sidebar-collapsed props)]
    (dom/aside #js {:className (str "sidebar " (when collapsed? "collapsed")) :aria-label "Project navigation"}
      (dom/div #js {:className "sidebar-heading"}
        (dom/span #js {:className "navigation-label"} "Project")
        (dom/button #js {:className "icon-button" :title (if collapsed? "Expand navigation" "Collapse navigation")
                         :aria-label (if collapsed? "Expand navigation" "Collapse navigation") :aria-expanded (not collapsed?)
                         :onClick #(save-layout! {:ui/sidebar-collapsed (not (:ui/sidebar-collapsed (current)))})} (icon "sidebar")))
      (dom/div #js {:className "sidebar-project"}
        (dom/div #js {:className "navigation-label"}
          (dom/select #js {:className "sidebar-project-picker" :aria-label "Project" :value (get-in props [:ui/project :id])
                          :onChange #(load-project! (.. % -target -value))}
            (map #(dom/option #js {:key (:id %) :value (:id %)} (:name %)) (:ui/projects props)))
          (dom/span {} "dbt project"))
        (dom/button #js {:className "icon-button" :aria-label "Choose project" :title "Choose project" :onClick choose-project!} (icon "folder")))
      (dom/nav #js {:className "project-navigation" :aria-label "Project tools"}
        (map (fn [[key label name]]
               (dom/button #js {:key key :title label :aria-label label :aria-pressed (= key (:ui/inspector props))
                                :className (when (= key (:ui/inspector props)) "active")
                                :onClick #(inspect-view! (when (not= key (:ui/inspector (current))) key))}
                 (icon name) (dom/span #js {:className "navigation-label"} label)))
          [["overview" "Overview" "command"] ["documentation" "Documentation" "file"]
           ["routing" "Environment routing" "compare"] ["connections" "Connections" "database"] ["git" "Version control" "changes"]
           ["metadata" "Metadata" "model"]]))
      (dom/div #js {:className "sidebar-bottom"}
        (dom/button #js {:className "navigation-action" :title "Refresh project" :aria-label "Refresh project" :onClick refresh-project!}
          (icon "refresh") (dom/span #js {:className "navigation-label"} "Refresh project"))
        (dom/button #js {:className "navigation-action" :title "Restore default layout" :aria-label "Restore default layout"
                         :onClick #(save-layout! (select-keys initial layout-keys))}
          (icon "settings") (dom/span #js {:className "navigation-label"} "Reset layout"))
        (dom/span #js {:className "project-path navigation-label" :title (get-in props [:ui/project :path])} (get-in props [:ui/project :path]))))))

(defn dialect-control [props]
  (dom/select #js {:value (:ui/dialect props) :aria-label "Execution dialect" :onChange #(value! :ui/dialect %)}
    (dom/option #js {:value "duckdb"} "DuckDB · native")
    (dom/option #js {:value "sqlite"} "SQLite · preview")
    (dom/option #js {:value "snowflake"} "Snowflake · SELECT")
    (dom/option #js {:value "federated"} "Federation · DuckDB")))
(declare results-panel bottom-workspace)
(defn global-status-bar [props]
  (let [jobs (:ui/jobs props)
        active-job (first (filter #(#{"queued" "running" "cancelling"} (:status %)) jobs))
        job (or active-job (first (filter #(= (:ui/status-job props) (:job/id %)) jobs)) (first jobs))
        status (:status job) active? (#{"queued" "running" "cancelling"} status)
        duration (when (:started_at job) (- (.getTime (js/Date. (or (:finished_at job) (.now js/Date)))) (.getTime (js/Date. (:started_at job)))))
        kind (str/capitalize (or (:kind job) "job")) summary (:summary job)
        message (cond
                  (:ui/error props) (:ui/error props)
                  (:ui/submitting props) "Submitting…"
                  (:ui/notice props) (:ui/notice props)
                  (= status "queued") (str kind " queued · waiting for the project")
                  (= status "running") (str kind " running" (when (:statement_index summary) (str " · statement " (:statement_index summary) " of " (:statement_count summary))))
                  (= status "cancelling") (str "Cancelling " (str/lower-case kind) "…")
                  (= status "succeeded") (str kind " completed" (when (= "query" (:kind job)) (str " · " (reduce + 0 (map :row_count (:statement_results summary))) " rows · " (:statement_count summary 1) " statement(s)")))
                  (= status "failed") (str kind " failed · " (:error job))
                  (= status "cancelled") (str kind " cancelled")
                  (= status "interrupted") (str kind " interrupted")
                  :else "Ready · write a query or choose a project")]
    (dom/footer #js {:className (str "global-status-bar query-status-bar " status) :role "status" :aria-label "Workbench status" :aria-live "polite"}
      (dom/div #js {:className "query-status-message"}
        (dom/span #js {:className (str "status-dot " (if (:ui/error props) "failed" (if active? "running" status)))})
        (dom/span #js {:title message :role (when (:ui/error props) "alert")} message))
      (dom/div #js {:className "query-status-controls"}
        (when duration (dom/span {} (str (.toFixed (/ duration 1000) 1) " s")))
        (dom/span {} (str (str/upper-case (:format (read-editor-document props))) " · " (:ui/target props)))
        (when active? (button (if (= "query" (:kind job)) "Cancel query" "Cancel job") #(cancel-job! (:job/id job)) "small danger" (= "cancelling" status)))
        (when (:ui/error props) (dom/button #js {:className "icon-button" :aria-label "Dismiss error" :onClick #(patch! {:ui/error nil})} (icon "close")))
        (when (:ui/notice props) (dom/button #js {:className "icon-button" :aria-label "Dismiss notice" :onClick #(patch! {:ui/notice nil})} (icon "close")))))))
(defn format-preferences [props]
  (let [options (:ui/format-options props)
        control (fn [title key choices]
                  (field-label title (dom/select #js {:aria-label title :value (str (get options key))
                                                     :onChange #(let [value (.. % -target -value)]
                                                                  (save-format-options! (assoc (:ui/format-options (current)) key (cond (#{:tabWidth :expressionWidth :linesBetweenQueries} key) (js/Number value) (= key :useTabs) (= value "true") :else value))))}
                                       (map (fn [[value label]] (dom/option #js {:key (str value) :value (str value)} label)) choices))))]
    (floating-menu "SQL formatting" "Style" "format-preferences-menu"
      (dom/div {}
        (dom/h3 {} "SQL formatting")
        (control "Keyword case" :keywordCase [["upper" "UPPERCASE"] ["lower" "lowercase"] ["preserve" "Keep typed case"]])
        (control "Indent with" :useTabs [[false "Spaces"] [true "Tabs"]])
        (control "Indent width" :tabWidth [[2 "2"] [4 "4"] [8 "8"]])
        (control "Clause layout" :indentStyle [["standard" "Standard line breaks"] ["tabularLeft" "Aligned to the left"] ["tabularRight" "Aligned to the right"]])
        (control "Expression width" :expressionWidth [[0 "Always break expressions"] [40 "40 characters"] [50 "50 characters"] [80 "80 characters"] [120 "120 characters"]])
        (control "Logical operator breaks" :logicalOperatorNewline [["before" "Before AND / OR"] ["after" "After AND / OR"]])
        (control "Blank lines between queries" :linesBetweenQueries [[1 "1"] [2 "2"] [3 "3"]])
        (dom/label #js {:className "check-control"}
          (dom/input #js {:type "checkbox" :checked (:ui/always-format props) :aria-label "Always format on mode switch"
                          :onChange #(let [enabled (.. % -target -checked)]
                                       (patch! {:ui/always-format enabled}) (save-preference! "workbench.always-format" (str enabled)))})
          "Always format on mode switch")
        (dom/p #js {:className "hint"} "Preferences are saved in this browser. Format applies them to the active document.")) "button small")))
(defn editor-preferences [props]
  (let [options (:ui/editor-options props) context (:ui/editor-context props)]
    (floating-menu "Editor settings" "Editor" "editor-preferences-menu"
      (dom/div {}
        (dom/h3 {} "Editor")
        (field-label "EDN editing mode"
          (dom/select #js {:aria-label "EDN editing mode" :value (:ednMode options)
                          :onChange #(save-editor-options! (assoc (:ui/editor-options (current)) :ednMode (.. % -target -value)))}
            (dom/option #js {:value "paredit"} "Paredit: edit balanced forms")
            (dom/option #js {:value "parinfer"} "Parinfer: infer structure from indentation")
            (dom/option #js {:value "plain"} "Plain: standard text editing")))
        (dom/label #js {:className "check-control"}
          (dom/input #js {:type "checkbox" :checked (boolean (:rainbowEdn options)) :aria-label "Rainbow EDN delimiters"
                          :onChange #(save-editor-options! (assoc (:ui/editor-options (current)) :rainbowEdn (.. % -target -checked)))})
          "Rainbow EDN delimiters")
        (dom/p #js {:className "hint"} "These preferences apply in this browser. Changing settings preserves the buffer. Typing and structural editing can change it.")
        (dom/p #js {:className "hint"} "Use Complete at cursor, Show documentation at cursor, or Go to definition from the command center. Keyboard shortcuts are configurable.")
        (dom/p #js {:className "hint"} "Project completions and reference hints use recorded manifest metadata. Parse the project to refresh models, sources, macros, and columns.")
        (cond
          (:ui/editor-context-loading props) (dom/p #js {:className "hint" :role "status"} "Loading project editor metadata…")
          (:ui/editor-context-error props) (dom/p #js {:className "hint" :role "status"} (str "Project editor metadata unavailable: " (:ui/editor-context-error props)))
          (:empty_reason context) (dom/p #js {:className "hint" :role "status"} (:empty_reason context)))) "button small")))

(defn render-document-tabs [props]
  (dom/nav #js {:className "file-tabs" :aria-label "Open files"}
    (when (:ui/scratch-visible props)
      (dom/div #js {:className (str "file-tab " (when-not (:ui/file props) "active")) :key "scratch-query"}
        (dom/button #js {:title "Scratch query" :aria-label "Query 1" :onClick activate-query!} (icon "command") "Query 1"
          (when (query-dirty? props) (dom/span #js {:className "tab-dirty" :aria-hidden true} "*")))
        (dom/button #js {:className "icon-button" :aria-label "Close Query 1" :onClick #(do (activate-query!) (close-active-document!))} (icon "close"))))
    (map (fn [file]
           (dom/div #js {:className (str "file-tab " (when (= (:path file) (get-in props [:ui/file :path])) "active")) :key (:path file)}
             (dom/button #js {:onClick #(activate-file! file) :title (str (:path file) (when (file-dirty? file) " · Unsaved changes"))
                             :aria-label (or (:name file) (last (str/split (:path file) #"/")))}
               (icon "file") (or (:name file) (last (str/split (:path file) #"/")))
               (when (file-dirty? file) (dom/span #js {:className "tab-dirty" :aria-hidden true} "*")))
             (dom/button #js {:className "icon-button" :aria-label (str "Close " (:path file)) :onClick #(close-file! (:path file))} (icon "close")))) (:ui/open-files props))
    (dom/button #js {:className "new-file-tab icon-button" :aria-label "New file" :title "New file" :onClick #(new-file!)} (icon "plus"))))

(defn render-document-editors [props]
  (let [schema (clj->js (into {} (map (fn [relation] [(:name relation) (:columns relation)]) (:ui/catalog props))))
        intelligence (clj->js (:ui/editor-context props)) options (clj->js (:ui/editor-options props))]
    (dom/div #js {:className "file-editor-panes"}
      (dom/div #js {:key "scratch-query" :className "file-editor-pane" :hidden (boolean (:ui/file props))}
        (react/createElement editor/QueryEditor
          #js {:key (:ui/editor-revision props) :value (:ui/query-content props) :format (:ui/query-format props) :schema schema
               :intelligence intelligence :options options :dialect (:ui/dialect props) :onNavigate load-file!
               :readOnly (boolean (or (:ui/loading-file props) (= "scratch-query" (get-in props [:ui/converting :key]))))
               :onChange #(patch! {:ui/query-content %})
               :onSelection #(patch! {:ui/query-selection (js->clj % :keywordize-keys true)})
               :selection (clj->js (:ui/query-selection props)) :onRun (fn [all? content selection] (run-query! all? content selection))}))
      (map (fn [file]
             (dom/div #js {:key (:editor-id file (:path file)) :className "file-editor-pane" :hidden (not= (:path file) (get-in props [:ui/file :path]))}
               (react/createElement editor/QueryEditor
                 #js {:value (:draft file) :format (resolve-file-format file) :schema schema :label "File editor" :file true
                      :intelligence intelligence :options options :dialect (:ui/dialect props) :onNavigate load-file!
                      :readOnly (boolean (or (:read_only file) (:ui/loading-file props) (= (:path file) (get-in props [:ui/converting :key])))) :selection (clj->js (:selection file {:from 0 :to 0 :head 0}))
                      :onSelection #(update-file-selection! (:path file) %)
                      :onChange #(update-file-content! (:path file) %) :onSave save-file!
                      :onRun (fn [all? content selection] (run-query! all? content selection))}))) (:ui/open-files props)))))

(defn query-editor [props]
  (let [document (read-editor-document props) mode (:format document) edn? (= "edn" mode)
        file (:ui/file props) opened (first (filter #(= (:path file) (:path %)) (:ui/open-files props))) dirty (file-dirty? opened)
        template? (and file (re-find #"\{[\{%]" (:content document)))
        read-only? (:read_only file)
        converting? (= (:key document) (get-in props [:ui/converting :key]))
        blocked? (or read-only? (:ui/submitting props) (:ui/loading-file props) converting?)]
    (dom/div #js {:className "query-workspace" :style #js {"--query-split" (str (:ui/query-split props) "%")}}
      (dom/div #js {:className "code-card file-workspace"}
        (render-document-tabs props)
        (dom/div #js {:className "code-toolbar query-toolbar"}
          (dom/div #js {:className "segmented"}
            (button "SQL" #(select-editor-mode! "sql") (when (= "sql" mode) "selected") blocked?)
            (button "EDN" #(select-editor-mode! "edn") (when edn? "selected") blocked?)
            (when (and file (not (str/ends-with? (:path file) ".sql")) (not (str/ends-with? (:path file) ".edn")))
              (button "Text" #(select-editor-mode! "text") (when (= "text" mode) "selected") blocked?))
            (dom/button #js {:className "button" :disabled true :title "Visual query builder is disabled"} "Visual"))
          (dom/div #js {:className "query-run-actions"}
            (button "Format" format-query! "small" (or blocked? (= "text" mode) template?))
            (format-preferences props)
            (editor-preferences props)
            (react/createElement query-library/QueryLibrary
              #js {:key (get-in props [:ui/project :id]) :projectId (get-in props [:ui/project :id])
                   :query (clj->js {:content (:content document) :format mode
                                   :parameters (:ui/parameters props)
                                   :target (:ui/target props) :dialect (:ui/dialect props)})
                   :onNotice #(patch! {:ui/notice %})
                   :onLoad (fn [query]
                             (let [q (js->clj query :keywordize-keys true)]
                               (activate-query!)
                               (patch! {:ui/query-content (:content q) :ui/query-format (if (= "visual" (:format q)) "edn" (:format q))
                                        :ui/query-conversion nil :ui/query-parameters (:parameters q "{}")
                                        :ui/parameters (:parameters q "{}") :ui/dialect (:dialect q "duckdb")
                                        :ui/query-selection {:from 0 :to 0 :head 0} :ui/error nil})))})
            (floating-menu "Query settings" (icon "settings") "query-settings-menu"
              (dom/div {}
                (field-label "Parameters"
                  (dom/input #js {:aria-label "Parameters" :className "mono" :value (:ui/parameters props)
                                  :onChange update-document-parameters!}))
                (field-label "Execution dialect" (dialect-control props))
                (field-label "Maximum result rows" (dom/input #js {:type "number" :min 1 :max 100000 :value (:ui/max-rows props) :aria-label "Maximum result rows" :onChange #(patch! {:ui/max-rows (js/Number (.. % -target -value))})}))
                (field-label "Timeout in seconds" (dom/input #js {:type "number" :min 1 :max 120 :value (:ui/timeout props) :aria-label "Timeout in seconds" :onChange #(patch! {:ui/timeout (js/Number (.. % -target -value))})}))))
            (when (#{"sql" "edn"} mode)
              (button (if edn? "Run query" "Run statement") #(run-query! false) "primary" (or blocked? template?)))
            (when (= "sql" mode) (button "Run all" #(run-query! true) "small" (or blocked? template?)))))
        (when (:ui/loading-file props) (dom/div #js {:className "file-loading" :aria-live "polite"} (str "Opening " (:ui/loading-file props) "…")))
        (when converting? (dom/div #js {:className "file-loading" :aria-live "polite"}
                           (if (= "format" (get-in props [:ui/converting :operation])) "Formatting document…" "Translating document…")))
        (when read-only? (dom/div #js {:className "file-loading" :role "status"} (str (:preview_reason file) " File details are available in Metadata.")))
        (dom/div #js {:className "file-actions"}
          (dom/span #js {:className "hint document-path" :title (:path document)}
            (cond (:untitled file) (str (:name file) " · Save to choose a project path")
                  file (:path document) :else "Scratch query · SQL / EDN translates the query"))
          (when (and file (not= mode (native-file-format file)) (#{"sql" "edn"} (native-file-format file)))
            (dom/span #js {:className "representation-hint"} (str (str/upper-case mode) " view · saves as " (str/upper-case (native-file-format file)))))
          (when dirty (dom/span #js {:className "unsaved"} "Unsaved"))
          (button "Save file" save-file! "small" (or (and file (not dirty)) blocked?))
          (when (:ui/model-id props) (button "Preview model" run-preview! "small" (or dirty blocked?)))
          (dom/kbd {} "Shift+Enter / F5"))
        (render-document-editors props))
      (pane-resizer props :ui/query-split "Resize query and results" "y" "--query-split" ".query-workspace")
      (bottom-workspace props))))

(defn result-option [r]
  (dom/option #js {:key (:id r) :value (:id r)}
    (str (str/capitalize (or (:kind r) "result")) " · " (:dialect r) " · " (:row_count r) " rows · "
         (date-label (:created_at r)) " · " (subs (:id r) 0 (min 6 (count (:id r)))))))
(defn comparison [props]
  (let [result (:ui/result props) outcome (:ui/compare-result props) summary (:summary outcome) page (:ui/compare-page props) offset (:offset page 0) limit (:limit page 10)]
    (dom/section #js {:className "comparison card" :aria-label "Compare saved results"}
      (dom/div #js {:className "comparison-heading"} (icon "compare") (dom/h3 {} "Compare current result"))
      (if result
        (dom/div {}
          (dom/p #js {:className "hint"} (str "Current: " (str/capitalize (:kind result)) " · " (:dialect result) " · " (:row_count result) " rows · " (date-label (:created_at result))))
          (dom/div #js {:className "comparison-fields"}
            (field-label "Compare to" (dom/select #js {:aria-label "Compare to" :value (:ui/right-result props) :onChange #(patch! {:ui/right-result (.. % -target -value) :ui/compare-result nil :ui/compare-page nil})}
               (dom/option #js {:value ""} "Choose another saved result…")
               (map result-option (remove #(or (= "compare" (:kind %)) (= (:id result) (:id %))) (:ui/results props)))))
            (field-label "Match rows by keys (optional)" (dom/input #js {:aria-label "Key columns" :value (:ui/compare-keys props) :placeholder "customer_id" :onChange #(value! :ui/compare-keys %)})))
          (button "Compare results" run-compare! "primary small" (or (str/blank? (:ui/right-result props)) (= (:id result) (:ui/right-result props)) (:ui/submitting props)))
          (dom/p #js {:className "hint"} "Without keys, compare complete saved rows. Duplicate counts are preserved.")
          (when outcome
            (dom/div #js {:className "comparison-outcome" :role "status"}
              (dom/strong {} (if (:equal summary) "Rows match" (str (:differences summary) " differences")))
              (dom/p {} (str (:left_rows summary) " current rows · " (:right_rows summary) " compared rows"))
              (when (false? (:schema_equal summary)) (dom/p #js {:className "schema-difference"} "Column types or names differ."))
              (dom/p #js {:className "hint"} (:comparison_scope summary))
              (react/createElement panels/ResultTable #js {:page (clj->js (:ui/compare-page props))})
              (dom/div #js {:className "pagination"}
                (dom/span {} (str (if (zero? (:row_count outcome)) 0 (inc offset)) "-" (min (:row_count outcome) (+ offset (count (:rows page)))) " of " (:row_count outcome) " differences"))
                (button "Previous differences" #(load-result! (:id outcome) (max 0 (- offset limit))) "small" (zero? offset))
                (button "Next differences" #(load-result! (:id outcome) (+ offset limit)) "small" (>= (+ offset limit) (:row_count outcome))))
              (dom/a #js {:className "button small" :href (str "/api/results/" (:id outcome) "/download")} (icon "download") "Download differences"))))
        (dom/p #js {:className "hint"} "Run a query or preview a model to select the current result.")))))
(defn results-panel [props]
  (let [result (:ui/result props) page (:ui/page props) columns (:columns page)
        offset (or (:offset page) 0) limit (or (:limit page) 10) total (or (:row_count page) (:row_count result) 0)
        selected-job (first (filter #(= (:job/id %) (:ui/selected-job props)) (:ui/jobs props)))
        statements (get-in selected-job [:summary :statement_results])]
    (dom/div #js {:className "results-panel"}
      (dom/div #js {:className "panel-title"}
        (dom/div {} (dom/span #js {:className "eyebrow"} "RESULT EXPLORER") (dom/h2 {} "Results"))
        (when result (pill (str (:row_count result) " rows") "accent")))
      (dom/div #js {:className "result-toolbar"}
        (dom/nav #js {:className "result-tabs" :aria-label "Statement results"}
          (if (seq statements)
            (map (fn [item]
                   (dom/button #js {:key (:result_id item) :className (str "result-tab " (when (= (:result_id item) (:id result)) "active"))
                                    :aria-pressed (= (:result_id item) (:id result))
                                    :onClick #(load-result! (:result_id item))}
                     (icon "model") (str "Result " (:statement_index item) " · " (:row_count item) " rows"))) statements)
            (dom/span #js {:className "result-tab active"} (icon "model") "Results")))
        (when result (dom/span #js {:className "result-count"} (str total " rows")))
        (floating-menu "Browse history" (icon "clock") "result-picker"
          (dom/div {}
            (field-label "Saved result"
              (dom/select #js {:aria-label "Saved result" :value (or (:id result) "") :onChange #(when-not (str/blank? (.. % -target -value)) (load-result! (.. % -target -value)))}
                (dom/option #js {:value ""} "Choose a saved result...") (map result-option (remove #(= "compare" (:kind %)) (:ui/results props)))))))
        (when result (dom/a #js {:className "icon-button" :aria-label "Download CSV" :title "Download CSV" :href (str "/api/results/" (:id result) "/download?format=csv")} (icon "download"))))
      (dom/nav #js {:className "results-view-tabs" :aria-label "Results view"}
        (map (fn [[key label]] (dom/button #js {:key key :aria-pressed (= key (:ui/results-view props)) :className (when (= key (:ui/results-view props)) "active") :onClick #(patch! {:ui/results-view key})} label))
             [["data" "Data"] ["chart" "Chart"] ["compare" "Compare"] ["sql" "SQL & provenance"]]))
      (case (:ui/results-view props)
        "compare" (comparison props)
        "chart" (react/createElement panels/ResultChart #js {:result (clj->js result)})
        "sql" (dom/div #js {:className "result-sql card"} (dom/h4 {} "EXECUTED SQL") (dom/pre {} (json-display (:sql result))) (dom/h4 {} "INPUT PROVENANCE") (dom/pre {} (json-display (:provenance result))))
      (if result
        (dom/div #js {:className "result-data"}
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
          (when (empty? (:rows page)) (dom/p #js {:className "empty-rows"} "The query returned no rows."))
          (dom/div #js {:className "pagination"}
            (dom/span {} (str (if (zero? total) 0 (inc offset)) "-" (min total (+ offset (count (:rows page)))) " of " total " rows"))
            (dom/div {} (button "Previous" #(load-result! (:id result) (max 0 (- offset limit))) "small" (zero? offset))
              (dom/span #js {:className "page-number"} (str (inc (quot offset limit))))
              (button "Next" #(load-result! (:id result) (+ offset limit)) "small" (>= (+ offset limit) total)))))
        (dom/div #js {:className "empty-editor"} (icon "play") (dom/h3 {} "An idea, then a result.")
          (dom/p {} "Preview a model or run a query. Its result will appear here with a record of SQL and input bindings.")))
      ))))

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
              (when (:result_id selected) (button "View results" #(load-result! (:result_id selected)) "small"))
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

(defn project-inspector [props view]
  (react/createElement panels/ProjectInspector
    #js {:project (clj->js (:ui/project props)) :view view :modelId (:ui/model-id props) :jobs (clj->js (:ui/jobs props)) :target (:ui/target props)
         :file (clj->js (:ui/file props)) :content (:ui/content props)
         :onOpenFile load-file! :onError report-error! :onNotice #(patch! {:ui/notice %}) :onInspect inspect-view!
         :onRun #(submit! %1 (js->clj %2 :keywordize-keys true))
         :onTarget (fn [id type] (patch! {:ui/target id :ui/dialect (if (= type "snowflake") "snowflake" "duckdb")}) (load-catalog!))
         :onRefresh refresh-project!
         :onQuery (fn [content dialect] (activate-query!) (patch! {:ui/query-content content :ui/query-format "sql" :ui/query-conversion nil :ui/query-parameters "{}" :ui/parameters "{}" :ui/dialect dialect :ui/query-selection {:from 0 :to 0 :head 0}}))}))

(defn bottom-workspace [props]
  (let [view (:ui/bottom-tab props)]
    (dom/section #js {:className "workspace-bottom" :aria-label "Query panels"}
      (dom/nav #js {:className "bottom-tabs" :aria-label "Query panels"}
        (map (fn [[key label name]]
               (dom/button #js {:key key :className (when (= key view) "active") :aria-pressed (= key view)
                                :onClick #(patch! (cond-> {:ui/bottom-tab key} (= key "results") (assoc :ui/results-view "data")))}
                 (icon name) label))
          [["results" "Results" "model"] ["lineage" "Lineage" "changes"] ["activity" "Activity" "clock"]
           ["changes" "Changes" "file"] ["runs" "Runs & tests" "check"]]))
      (dom/div #js {:className (str "bottom-content " (if (= view "results") "query-results" (str "bottom-" view)))}
        (case view "results" (results-panel props) "activity" (job-activity props) "changes" (changes-panel props)
              (project-inspector props view))))))

(defn project-context-pane [props]
  (dom/aside #js {:key "project-context" :className "inspector-panel project-context-pane" :aria-label "Project workspace"}
    (dom/div #js {:className "inspector-heading"}
      (dom/strong {} (get {"overview" "Overview" "documentation" "Documentation" "connections" "Connections"
                           "routing" "Environment routing" "git" "Version control" "metadata" "Metadata"} (:ui/inspector props)))
      (dom/button #js {:className "icon-button" :aria-label "Close project view" :title "Close project view" :onClick #(inspect-view! nil)} (icon "close")))
    (project-inspector props (:ui/inspector props))))

(defn file-pane [props]
  (let [models (:ui/models props) models? (= "models" (:ui/file-view props)) search (str/lower-case (:ui/files-search props))
        entries (if models? models (:ui/files props))
        filtered (filter #(str/includes? (str/lower-case (:path %)) search) entries)]
    (dom/aside #js {:key "files-workspace" :className (str "files-pane " (when-not (:ui/explorer-visible props) "explorer-hidden"))
                   :aria-label "Project files"}
      (dom/div #js {:className "inspector-heading"}
        (dom/strong {} (icon "file") "Files")
        (dom/button #js {:className "icon-button" :title (if (:ui/explorer-visible props) "Hide file explorer" "Show file explorer")
                         :aria-label (if (:ui/explorer-visible props) "Hide file explorer" "Show file explorer") :aria-expanded (:ui/explorer-visible props)
                         :onClick #(save-layout! {:ui/explorer-visible (not (:ui/explorer-visible (current)))})} (icon "folder")))
      (when (:ui/explorer-visible props)
        (dom/div #js {:key "file-explorer" :className "file-explorer" :aria-label "File explorer"}
          (dom/nav #js {:className "file-view-tabs" :aria-label "File explorer views"}
            (dom/button #js {:className (when models? "active") :aria-pressed models? :onClick #(save-layout! {:ui/file-view "models"})} "Models")
            (dom/button #js {:className (when-not models? "active") :aria-pressed (not models?) :onClick #(save-layout! {:ui/file-view "all"})} "All files"))
          (dom/div #js {:className "search-field"} (icon "search")
            (dom/input #js {:aria-label (if models? "Search models" "Search project files") :placeholder "Filter files…"
                            :value (:ui/files-search props) :onChange #(value! :ui/files-search %)}))
          (dom/div #js {:className "file-tree"}
            (cond
              (empty? entries) (dom/p #js {:className "sidebar-hint"} (if models? "Parse the project to discover models." "No project files."))
              (empty? filtered) (dom/p #js {:className "sidebar-hint"} (if models? "No matching models." "No matching files."))
              :else (file-tree filtered models (get-in props [:ui/file :path]) ""))))))))

(defsc Root [_ props]
  {:query [:ui/converting :ui/save-dialog :ui/always-format :ui/scratch-visible :ui/query-conversion :ui/query-parameters :ui/query-saved-content :ui/query-saved-format
           :ui/max-rows :ui/timeout :ui/query-split :ui/bottom-tab :ui/sidebar-collapsed :ui/sidebar-width :ui/context-width :ui/files-width :ui/file-view :ui/files-search :ui/explorer-visible :ui/inspector :ui/open-files :ui/status-job :ui/results-view :ui/compare-result :ui/compare-page :ui/theme :ui/projects :ui/project :ui/files :ui/results :ui/target :ui/defer :ui/favor-state
           :ui/tab :ui/model-id :ui/file :ui/loading-file :ui/content :ui/saved-content :ui/selected-job
           :ui/events :ui/event-cursor :ui/result :ui/page :ui/error :ui/notice :ui/submitting
           :ui/open-path :ui/profiles :ui/query-format :ui/query-content :ui/parameters
           :ui/catalog :ui/editor-context :ui/editor-context-loading :ui/editor-context-error :ui/editor-options
           :ui/format-options :ui/editor-revision :ui/query-job :ui/query-requesting :ui/query-selection :ui/build-scope :ui/changes :ui/job-filter
           :ui/dialect :ui/baseline-input :ui/right-result :ui/compare-keys :ui/conflict :ui/artifact-dir :ui/reference-target
           {:ui/models (comp/get-query ModelRow)} {:ui/jobs (comp/get-query JobRow)}]
   :initial-state (fn [_] initial)}
  (let [project (:ui/project props) selected (:ui/model-id props)
        dirty (file-dirty? (first (filter #(= (get-in props [:ui/file :path]) (:path %)) (:ui/open-files props))))]
    (dom/div #js {:className "app-shell"}
      (dom/header #js {:className "app-header"}
        (dom/button #js {:className "brand" :onClick choose-project! :title "All projects"}
          (dom/span #js {:className "brand-mark"} "w") (dom/span {} "workbench"))
        (when project
          (dom/div #js {:className "project-switch"}
            (dom/span #js {:className "header-divider" :aria-hidden true})
            (dom/div #js {:className "target-control"} (icon "database")
            (dom/select #js {:aria-label "Target" :value (:ui/target props) :onChange #(do (value! :ui/target %) (load-catalog!))}
              (map #(dom/option #js {:key % :value %} %) (or (seq (:targets project)) ["dev"]))))))
        (when project
          (react/createElement panels/ProjectBranch #js {:project (clj->js project) :notice (:ui/notice props) :onInspect inspect-view!}))
        (when project
          (react/createElement command-center/CommandCenter #js {:key (:id project) :commands (clj->js (editor-commands props)) :files (clj->js (:ui/files props))
                                                                 :onOpenFile load-file! :onFocusEditor #(js/requestAnimationFrame editor/focusQueryEditor)}))
        (when project
          (dom/div #js {:className "run-toolbar"}
            (dom/div #js {:className "run-actions"}
              (button "Test" #(submit! "test" (build-options props)) "small" (or dirty (:ui/submitting props)))
              (button "Build" build-project! "primary small" (or dirty (:ui/submitting props)))
              (floating-menu "Build options" "Build options" "build-options-menu"
                (dom/div {}
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
                  (button "Refresh model graph" #(submit! "parse" {}) "small" (:ui/submitting props))) "button small"))))
        (dom/div #js {:className "theme-control"} (icon "theme")
          (dom/select #js {:aria-label "Theme" :value (:ui/theme props) :onChange #(choose-theme! (.. % -target -value))}
            (dom/option #js {:value "system"} "System theme")
            (dom/option #js {:value "light"} "Light theme")
            (dom/option #js {:value "dark"} "Dark theme")))
        (dom/div #js {:className "header-right"} (dom/span #js {:className "local-light"}) "Local session"))
      (if-not project (welcome props)
        (dom/div #js {:className "project-shell"}
          (dom/div #js {:className (str "workspace " (when (:ui/inspector props) "with-project-context ") (when (:ui/sidebar-collapsed props) "navigation-collapsed ") (when-not (:ui/explorer-visible props) "files-collapsed"))
                        :style #js {"--sidebar-width" (str (:ui/sidebar-width props) "px") "--context-width" (str (:ui/context-width props) "px") "--files-width" (str (:ui/files-width props) "px")}}
            (sidebar props)
            (when-not (:ui/sidebar-collapsed props)
              (pane-resizer props :ui/sidebar-width "Resize navigation" "x" "--sidebar-width" ".workspace" ".sidebar"))
            (when (:ui/inspector props) (project-context-pane props))
            (when (:ui/inspector props)
              (pane-resizer props :ui/context-width "Resize project view" "x" "--context-width" ".workspace" ".project-context-pane"))
            (dom/main #js {:key "query-workspace" :className "main-pane"}
              (dom/nav #js {:className "workspace-tabs" :aria-label "Workspace"}
                (dom/button #js {:className "active" :onClick editor/focusQueryEditor} (icon "command") "Query lab"))
              (query-editor props))
            (pane-resizer props :ui/files-width "Resize files" "x" "--files-width" ".workspace" ".files-pane" -1)
            (file-pane props))))

      (react/createElement workspace-ui/SaveFileDialog
        #js {:open (boolean (:ui/save-dialog props)) :initialPath (get-in props [:ui/save-dialog :path])
             :fileName (get-in props [:ui/file :name]) :busy (boolean (:ui/submitting props)) :error (:ui/error props)
             :onSave save-new-file! :onClose #(patch! {:ui/save-dialog nil :ui/error nil})})
      (global-status-bar props))))


(defonce application (app/fulcro-app {}))
(defn ^:export refresh [] (app/mount! application Root "app"))
(defn ^:export init []
  (refresh)
  (load-layout!)
  (load-editor-options!)
  (patch! {:ui/always-format (= "true" (read-preference "workbench.always-format"))})
  (try (when-let [saved (js/localStorage.getItem "workbench.sql-format-options")] (patch! {:ui/format-options (merge (:ui/format-options initial) (js->clj (js/JSON.parse saved) :keywordize-keys true))})) (catch :default _ nil))
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
