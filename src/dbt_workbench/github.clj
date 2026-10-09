(ns dbt-workbench.github
  (:require [cheshire.core :as json]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [dbt-workbench.git :as git]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.workspace :as workspace]))

(defn run-process! [root args input]
  (let [environment (into {} (remove #(str/starts-with? (key %) "GIT_") (System/getenv)))]
    (try
      (apply shell/sh (concat args [:dir root :in (or input "")
                                    :env (assoc environment "GH_HOST" "github.com" "GH_PROMPT_DISABLED" "1"
                                                "GIT_TERMINAL_PROMPT" "0" "GIT_OPTIONAL_LOCKS" "0")]))
      (catch java.io.IOException _ {:exit 127 :out "" :err "Required local command is unavailable."}))))

(defn- parse-repository [remote]
  (let [remote (str/trim remote)
        [_ owner repository] (or (re-matches #"https://github\.com/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?" remote)
                                 (re-matches #"git@github\.com:([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?" remote)
                                 (re-matches #"ssh://git@github\.com/([A-Za-z0-9-]+)/([A-Za-z0-9_.-]+?)(?:\.git)?/?" remote))]
    (when (and owner repository (not (contains? #{"." ".."} repository)))
      (str owner "/" repository))))

(defn detect [directory id]
  (let [root (:path (workspace/find-project directory id))
        state (git/read-state directory id)
        remote (when (:available state) (run-process! root ["git" "-C" root "remote" "get-url" "origin"] nil))
        repository (when (and remote (zero? (:exit remote))) (parse-repository (:out remote)))
        installed? (and repository (zero? (:exit (run-process! root ["gh" "--version"] nil))))]
    (cond
      (not (:available state)) {:available false :reason (:reason state)}
      (not repository) {:available false :reason "Configure an origin remote hosted on github.com to use pull requests."}
      (not installed?) {:available false :repository repository :reason "Install GitHub CLI to use pull requests."}
      :else {:available true :repository repository :branch (:branch state)})))

(defn- require-github [directory id]
  (let [connection (detect directory id)]
    (when-not (:available connection) (storage/fail! (:reason connection) 409))
    (assoc connection :root (:path (workspace/find-project directory id)))))

(defn- call-api! [{:keys [root]} args input]
  (let [result (run-process! root (into ["gh" "api" "--hostname" "github.com"] args) input)]
    (when-not (zero? (:exit result))
      (storage/fail! (str "GitHub operation failed: " (str/trim (:err result))) 409))
    (try (json/parse-string (:out result) true)
         (catch Exception _ (storage/fail! "GitHub returned an invalid JSON response." 502)))))

(defn- validate-number! [number]
  (when-not (and (integer? number) (pos? number) (<= number 2147483647))
    (storage/fail! "Choose a valid pull request number.")))

(defn- format-pull-request [repository pull-request]
  (let [{:keys [number title state draft html_url]} pull-request]
    (when-not (and (integer? number) (pos? number) (string? title) (contains? #{"open" "closed"} state)
                   (boolean? draft) (= html_url (str "https://github.com/" repository "/pull/" number)))
      (storage/fail! "GitHub returned invalid pull request fields." 502))
    {:number number :title title :state state :draft draft :url html_url
     :head (get-in pull-request [:head :ref]) :base (get-in pull-request [:base :ref])
     :author (get-in pull-request [:user :login]) :updated_at (:updated_at pull-request)
     :merged (boolean (:merged pull-request))}))

(defn list-pull-requests [directory id]
  (let [{:keys [repository] :as connection} (require-github directory id)
        response (call-api! connection [(str "repos/" repository "/pulls") "--method" "GET"
                                        "-f" "state=open" "-f" "per_page=30"] nil)]
    (when-not (sequential? response) (storage/fail! "GitHub returned an invalid pull request list." 502))
    {:repository repository :pull_requests (mapv #(format-pull-request repository %) response)}))

(defn read-pull-request [directory id number]
  (validate-number! number)
  (let [{:keys [repository] :as connection} (require-github directory id)]
    (format-pull-request repository (call-api! connection [(str "repos/" repository "/pulls/" number)] nil))))

(defn read-checks [directory id number]
  (validate-number! number)
  (let [{:keys [repository] :as connection} (require-github directory id)
        pull-request (call-api! connection [(str "repos/" repository "/pulls/" number)] nil)
        sha (get-in pull-request [:head :sha])]
    (when-not (and (string? sha) (re-matches #"[0-9a-fA-F]{40}" sha))
      (storage/fail! "GitHub returned an invalid commit hash." 502))
    (let [response (call-api! connection [(str "repos/" repository "/commits/" sha "/check-runs") "--method" "GET" "-f" "per_page=100"] nil)
          checks (:check_runs response)]
      (when-not (sequential? checks) (storage/fail! "GitHub returned an invalid check list." 502))
      {:number number :commit sha :checks
       (mapv (fn [{:keys [id name status conclusion details_url]}]
               (when-not (and (integer? id) (string? name) (string? status)
                              (or (nil? conclusion) (string? conclusion)))
                 (storage/fail! "GitHub returned invalid check fields." 502))
               {:id id :name name :status status :conclusion conclusion :url details_url}) checks)})))

(defn create-draft! [directory id {:keys [title body base]}]
  (locking directory
    (when-not (and (string? title) (not (str/blank? title)) (<= (count title) 256)
                   (not (str/includes? title "\n")) (not (str/includes? title "\r")) (not (str/includes? title "\u0000"))
                   (string? body) (<= (count body) 65536) (not (str/includes? body "\u0000"))
                   (string? base) (not (str/blank? base)) (not (str/starts-with? base "-"))
                   (not (str/includes? base "\u0000")))
      (storage/fail! "Provide a title, body, and base branch for the draft pull request."))
    (let [{:keys [root repository branch] :as connection} (require-github directory id)
          state (git/read-state directory id)]
      (when (str/blank? branch) (storage/fail! "Switch to a named branch before creating a pull request." 409))
      (when (= base branch) (storage/fail! "Choose a base branch different from the active branch."))
      (when (seq (:changes state)) (storage/fail! "Commit local changes before creating a draft pull request." 409))
      (when-not (zero? (:exit (run-process! root ["git" "-C" root "check-ref-format" (str "refs/heads/" base)] nil)))
        (storage/fail! "Choose a valid base branch."))
      (let [head (run-process! root ["git" "-C" root "rev-parse" "--verify" "HEAD"] nil)
            remote (run-process! root ["git" "-C" root "rev-parse" "--verify" (str "refs/remotes/origin/" branch)] nil)]
        (when-not (and (zero? (:exit head)) (zero? (:exit remote)) (= (:out head) (:out remote)))
          (storage/fail! "Publish the active branch yourself before creating its draft pull request." 409)))
      (format-pull-request repository
                           (call-api! connection [(str "repos/" repository "/pulls") "--method" "POST" "--input" "-"]
                                      (json/generate-string {:title title :body body :base base :head branch :draft true}))))))
