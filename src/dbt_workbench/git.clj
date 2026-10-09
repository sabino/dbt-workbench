(ns dbt-workbench.git
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.text :as text]
            [dbt-workbench.workspace :as workspace])
  (:import (java.nio.file Files)))

(defn- run-git [root args]
  (let [environment (into {} (remove #(str/starts-with? (key %) "GIT_") (System/getenv)))]
    (apply shell/sh (concat ["git" "-c" "core.hooksPath=/dev/null" "-c" "core.fsmonitor=false" "-c" "commit.gpgsign=false" "-C" root]
                           args [:env (assoc environment "GIT_OPTIONAL_LOCKS" "0" "GIT_TERMINAL_PROMPT" "0" "GIT_LITERAL_PATHSPECS" "1")]))))

(defn- require-success [result]
  (when-not (zero? (:exit result))
    (storage/fail! (str "Git operation failed: " (str/trim (:err result))) 409))
  (:out result))

(defn- read-repository [directory id]
  (let [project (workspace/find-project directory id)
        root (:path project)
        result (run-git root ["rev-parse" "--show-toplevel"])]
    (cond
      (not (zero? (:exit result))) {:available false :reason "This project is not a Git repository."}
      (not= (storage/canonical-path root) (storage/canonical-path (str/trim (:out result))))
      {:available false :reason "Open the Git repository root to manage source control safely."}
      :else {:available true :root root :project project})))

(defn- require-repository [directory id]
  (let [repository (read-repository directory id)]
    (when-not (:available repository) (storage/fail! (:reason repository) 409))
    repository))

(defn- validate-path! [{:keys [root project]} path]
  (when-not (and (string? path) (not (str/blank? path))
                 (not (str/includes? path "\\")) (not (str/includes? path "\u0000")))
    (storage/fail! "Use a relative project file path."))
  (let [root-path (.toPath (io/file root))
        relative (.toPath (io/file path))
        resolved (.normalize (.resolve root-path relative))
        parts (text/split-on path "/")
        config (workspace/read-yaml (io/file root "dbt_project.yml"))
        ignored (into workspace/excluded (keep #(some-> (config %) str (text/split-on "/") first)
                                               [:target-path :log-path :packages-install-path]))
        profiles-path (.toPath (io/file (:profiles_dir project)))]
    (when (or (.isAbsolute relative) (not (.startsWith resolved root-path))
              (some #{".." "." ""} parts) (some ignored parts)
              (= "profiles.yml" (last parts))
              (not (workspace/editable (last (text/split-on path "."))))
              (and (not= profiles-path root-path) (.startsWith resolved profiles-path))
              (.isDirectory (.toFile resolved)))
      (storage/fail! "Only project source files can be managed with Git."))
    (loop [current root-path remaining (seq relative)]
      (when remaining
        (let [next-path (.resolve current (str (first remaining)))]
          (when (Files/isSymbolicLink next-path) (storage/fail! "Symbolic links cannot be managed with Git."))
          (recur next-path (next remaining)))))
    path))

(defn- parse-status [output]
  (loop [records (seq (text/split-on output "\u0000")) changes []]
    (if (or (empty? records) (str/blank? (first records))) changes
        (let [record (first records) index (subs record 0 1) working (subs record 1 2)
              renamed? (some #{"R" "C"} [index working])]
          (recur (if renamed? (nnext records) (next records))
                 (conj changes (cond-> {:path (subs record 3) :index_status index :working_status working
                                        :staged (not (contains? #{" " "?"} index))
                                        :unstaged (not= " " working) :untracked (= "??" (subs record 0 2))}
                                 renamed? (assoc :original_path (second records)))))))))

(defn- read-changes [root]
  (parse-status (require-success (run-git root ["status" "--porcelain=v1" "-z" "--untracked-files=all"]))))

(defn- read-branches [root]
  (->> (require-success (run-git root ["for-each-ref" "--format=%(refname:short)%00%(HEAD)%00%(objectname:short)" "refs/heads/"]))
       str/split-lines
       (mapv (fn [line] (let [[branch head commit] (text/split-on line "\u0000")]
                         {:name branch :current (= "*" head) :commit commit})))))

(defn- read-log [root]
  (if (zero? (:exit (run-git root ["rev-parse" "--verify" "HEAD"])))
    (->> (require-success (run-git root ["log" "-30" "--format=%H%x00%h%x00%s%x00%an%x00%aI"]))
         str/split-lines
         (mapv (fn [line] (let [[commit short message author timestamp] (text/split-on line "\u0000")]
                           {:id commit :short_id short :message message :author author :committed_at timestamp}))))
    []))

(defn read-state [directory id]
  (let [{:keys [available root reason]} (read-repository directory id)]
    (if-not available {:available false :reason reason :branch nil :changes [] :branches [] :commits []}
      {:available true :branch (str/trim (:out (run-git root ["symbolic-ref" "--quiet" "--short" "HEAD"])))
       :changes (read-changes root) :branches (read-branches root) :commits (read-log root)})))

(defn read-diff [directory id {:keys [path source base_branch] :or {source "working"}}]
  (let [repository (require-repository directory id) root (:root repository)
        _ (validate-path! repository path)
        _ (when-not (contains? #{"working" "index"} source) (storage/fail! "Diff source must be working or index."))
        _ (when (and base_branch (not (some #(= base_branch (:name %)) (read-branches root))))
            (storage/fail! "Choose an existing local branch."))
        untracked? (some #(and (= path (:path %)) (:untracked %)) (read-changes root))
        args (cond
               base_branch ["diff" "--no-ext-diff" "--no-textconv" (str "refs/heads/" base_branch) "--" path]
               (and untracked? (= "working" source)) ["diff" "--no-ext-diff" "--no-textconv" "--no-index" "--" "/dev/null" path]
               (= "index" source) ["diff" "--no-ext-diff" "--no-textconv" "--cached" "--" path]
               :else ["diff" "--no-ext-diff" "--no-textconv" "--" path])
        result (run-git root args)]
    (when-not (or (zero? (:exit result)) (and untracked? (nil? base_branch) (= "working" source) (= 1 (:exit result)))) (require-success result))
    {:path path :source source :base_branch base_branch :diff (:out result)}))

(defn stage! [directory id path]
  (locking directory
    (let [repository (require-repository directory id)]
      (validate-path! repository path)
      (require-success (run-git (:root repository) ["add" "--" path]))
      (read-state directory id))))

(defn unstage! [directory id path]
  (locking directory
    (let [repository (require-repository directory id) root (:root repository)]
      (validate-path! repository path)
      (require-success (run-git root (if (zero? (:exit (run-git root ["rev-parse" "--verify" "HEAD"])))
                                      ["reset" "--quiet" "HEAD" "--" path]
                                      ["rm" "--cached" "--force" "--" path])))
      (read-state directory id))))

(defn- require-clean! [root]
  (when (seq (read-changes root)) (storage/fail! "Commit or remove local changes before changing branches." 409)))

(defn create-branch! [directory id branch]
  (locking directory
    (let [root (:root (require-repository directory id))]
      (when-not (and (string? branch) (not (str/blank? branch)) (not (str/starts-with? branch "-")) (not (str/includes? branch "\u0000"))
                     (zero? (:exit (run-git root ["check-ref-format" "--branch" branch]))))
        (storage/fail! "Use a valid Git branch name."))
      (require-clean! root)
      (require-success (run-git root ["switch" "-c" branch]))
      (read-state directory id))))

(defn switch-branch! [directory id branch]
  (locking directory
    (let [root (:root (require-repository directory id))]
      (when-not (some #(= branch (:name %)) (read-branches root)) (storage/fail! "Choose an existing local branch."))
      (require-clean! root)
      (require-success (run-git root ["switch" "--no-guess" "--" branch]))
      (read-state directory id))))

(defn commit! [directory id message]
  (locking directory
    (when-not (and (string? message) (not (str/blank? message)) (<= (count message) 10000)
                   (not (str/includes? message "\u0000")))
      (storage/fail! "Enter a commit message between 1 and 10000 characters."))
    (let [repository (require-repository directory id) root (:root repository)
          changes (filter :staged (read-changes root))]
      (when (empty? changes) (storage/fail! "Stage source changes before committing." 409))
      (doseq [{:keys [path original_path]} changes]
        (validate-path! repository path)
        (when original_path (validate-path! repository original_path)))
      (require-success (run-git root ["commit" "-m" message]))
      (read-state directory id))))

(defn- require-idle! [root]
  (doseq [marker ["rebase-merge" "rebase-apply" "CHERRY_PICK_HEAD" "MERGE_HEAD"]]
    (let [path (str/trim (require-success (run-git root ["rev-parse" "--git-path" marker])))
          file (io/file path)]
      (when (.exists (if (.isAbsolute file) file (io/file root path)))
        (storage/fail! "Resolve or abort the active Git operation before starting another." 409)))))

(defn- finish-operation! [directory id root operation args]
  (let [result (run-git root args)
        state (read-state directory id)]
    (if (zero? (:exit result)) (assoc state :operation operation :operation_failed false)
      (assoc state :operation operation :operation_failed true
             :error (str "Git " operation " failed. Resolve or abort any conflicts in your terminal. "
                         (str/trim (str (:err result) "\n" (:out result))))))))

(defn rebase! [directory id base-branch]
  (locking directory
    (let [root (:root (require-repository directory id))]
      (when-not (some #(= base-branch (:name %)) (read-branches root))
        (storage/fail! "Choose an existing local branch."))
      (require-clean! root)
      (require-idle! root)
      (finish-operation! directory id root "rebase"
                         ["-c" "rebase.autoStash=false" "-c" "rebase.updateRefs=false"
                          "rebase" "--no-autostash" (str "refs/heads/" base-branch)]))))

(defn cherry-pick! [directory id commit]
  (locking directory
    (let [root (:root (require-repository directory id))]
      (when-not (and (string? commit) (re-matches #"[0-9a-fA-F]{7,40}" commit)
                     (zero? (:exit (run-git root ["cat-file" "-e" (str commit "^{commit}")]))))
        (storage/fail! "Choose an existing local commit hash."))
      (require-clean! root)
      (require-idle! root)
      (finish-operation! directory id root "cherry-pick" ["cherry-pick" commit]))))
