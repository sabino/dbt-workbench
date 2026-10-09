(ns dbt-workbench.git-test
  (:require [clojure.java.io :as io]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [dbt-workbench.git :as git]
            [dbt-workbench.storage :as storage])
  (:import (java.nio.file Files)))

(defn- run-git! [root & args]
  (let [result (apply shell/sh (concat ["git" "-C" (str root)] args))]
    (when-not (zero? (:exit result)) (throw (ex-info (:err result) result)))
    (:out result)))

(defn- create-fixture! []
  (let [directory (.toFile (Files/createTempDirectory "workbench-git-" (make-array java.nio.file.attribute.FileAttribute 0)))
        root (io/file directory "repo") profiles (io/file directory "profiles")]
    (.mkdirs root) (.mkdirs profiles) (.mkdirs (io/file root "models"))
    (spit (io/file root "dbt_project.yml") "name: fixture\ntarget-path: artifacts\n")
    (spit (io/file root "models" "a.sql") "select 1\n")
    (storage/save-json! (io/file directory "projects.json")
                        {:projects [{:id "test" :path (.getCanonicalPath root) :profiles_dir (.getCanonicalPath profiles)}]})
    (run-git! root "init" "-b" "main")
    (run-git! root "config" "user.name" "Fixture")
    (run-git! root "config" "user.email" "fixture@example.invalid")
    (run-git! root "add" "dbt_project.yml" "models/a.sql")
    (run-git! root "-c" "core.hooksPath=/dev/null" "-c" "commit.gpgsign=false" "commit" "-m" "Initial fixture")
    {:directory directory :root root}))

(defn- delete-fixture! [directory]
  (doseq [file (reverse (file-seq directory))] (io/delete-file file true)))

(deftest local-source-control-workflow
  (let [{:keys [directory root]} (create-fixture!)]
    (try
      (is (= "main" (:branch (git/read-state directory "test"))))
      (is (= 1 (count (:commits (git/read-state directory "test")))))
      (git/create-branch! directory "test" "feat/example")
      (is (= "feat/example" (:branch (git/read-state directory "test"))))
      (git/switch-branch! directory "test" "main")
      (spit (io/file root "models/a.sql") "select 2\n")
      (is (str/includes? (:diff (git/read-diff directory "test" {:path "models/a.sql"})) "+select 2"))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"local changes" (git/switch-branch! directory "test" "feat/example")))
      (git/stage! directory "test" "models/a.sql")
      (is (:staged (first (:changes (git/read-state directory "test")))))
      (is (str/includes? (:diff (git/read-diff directory "test" {:path "models/a.sql" :source "index"})) "+select 2"))
      (git/unstage! directory "test" "models/a.sql")
      (is (false? (:staged (first (:changes (git/read-state directory "test"))))))
      (git/stage! directory "test" "models/a.sql")
      (spit (io/file root "models/a.sql") "select 3\n")
      (git/commit! directory "test" "Update model")
      (is (= "Update model" (:message (first (:commits (git/read-state directory "test"))))))
      (is (= "select 2\n" (run-git! root "show" "HEAD:models/a.sql")))
      (is (str/includes? (:diff (git/read-diff directory "test" {:path "models/a.sql" :base_branch "feat/example"})) "+select 3"))
      (spit (io/file root "models/new file.sql") "select 4\n")
      (is (str/includes? (:diff (git/read-diff directory "test" {:path "models/new file.sql"})) "+select 4"))
      (git/stage! directory "test" "models/new file.sql")
      (git/unstage! directory "test" "models/new file.sql")
      (is (.exists (io/file root "models/new file.sql")))
      (finally (delete-fixture! directory)))))

(deftest source-control-boundaries-and-hooks
  (let [{:keys [directory root]} (create-fixture!)]
    (try
      (doseq [path ["../outside.sql" "/tmp/outside.sql" "profiles.yml" "target/a.sql" "artifacts/a.sql" "models" ".git/config"]]
        (is (thrown? clojure.lang.ExceptionInfo (git/stage! directory "test" path))))
      (is (thrown? clojure.lang.ExceptionInfo (git/create-branch! directory "test" "--bad")))
      (is (thrown? clojure.lang.ExceptionInfo (git/switch-branch! directory "test" "main; touch nope")))
      (is (thrown? clojure.lang.ExceptionInfo (git/commit! directory "test" "")))
      (let [hook (io/file root ".git/hooks/pre-commit")]
        (spit hook "#!/bin/sh\nexit 99\n")
        (.setExecutable hook true))
      (spit (io/file root "models/a.sql") "select 9\n")
      (git/stage! directory "test" "models/a.sql")
      (is (= "Skip hook" (:message (first (:commits (git/commit! directory "test" "Skip hook"))))))
      (spit (io/file root "profiles.yml") "secret: example\n")
      (run-git! root "add" "profiles.yml")
      (is (thrown? clojure.lang.ExceptionInfo (git/commit! directory "test" "Cannot commit credentials")))
      (finally (delete-fixture! directory)))))

(deftest nonrepository-state
  (let [{:keys [directory root]} (create-fixture!)]
    (try
      (delete-fixture! (io/file root ".git"))
      (let [state (git/read-state directory "test")]
        (is (false? (:available state)))
        (is (empty? (:changes state)))
        (is (str/includes? (:reason state) "not a Git")))
      (finally (delete-fixture! directory)))))

(deftest deletion-and-unborn-index
  (let [{:keys [directory root]} (create-fixture!)]
    (try
      (io/delete-file (io/file root "models/a.sql"))
      (git/stage! directory "test" "models/a.sql")
      (is (= "D" (:index_status (first (:changes (git/read-state directory "test"))))))
      (git/unstage! directory "test" "models/a.sql")
      (is (not (.exists (io/file root "models/a.sql"))))
      (git/stage! directory "test" "models/a.sql")
      (git/commit! directory "test" "Remove model")
      (is (empty? (:changes (git/read-state directory "test"))))
      (delete-fixture! (io/file root ".git"))
      (run-git! root "init" "-b" "main")
      (git/stage! directory "test" "dbt_project.yml")
      (spit (io/file root "dbt_project.yml") "name: changed\n")
      (git/unstage! directory "test" "dbt_project.yml")
      (is (empty? (filter :staged (:changes (git/read-state directory "test")))))
      (is (= "name: changed\n" (slurp (io/file root "dbt_project.yml"))))
      (finally (delete-fixture! directory)))))

(deftest local-rebase-and-cherry-pick
  (let [{:keys [directory root]} (create-fixture!)]
    (try
      (git/create-branch! directory "test" "feat/model")
      (spit (io/file root "models/b.sql") "select 8\n")
      (git/stage! directory "test" "models/b.sql")
      (git/commit! directory "test" "Add another model")
      (let [commit (:id (first (:commits (git/read-state directory "test"))))]
        (git/switch-branch! directory "test" "main")
        (is (false? (:operation_failed (git/cherry-pick! directory "test" commit))))
        (is (= "select 8\n" (slurp (io/file root "models/b.sql")))))
      (git/switch-branch! directory "test" "feat/model")
      (is (false? (:operation_failed (git/rebase! directory "test" "main"))))
      (is (thrown? clojure.lang.ExceptionInfo (git/rebase! directory "test" "--exec=bad")))
      (is (thrown? clojure.lang.ExceptionInfo (git/cherry-pick! directory "test" "HEAD~1")))
      (spit (io/file root "models/a.sql") "select 3\n")
      (is (thrown? clojure.lang.ExceptionInfo (git/rebase! directory "test" "main")))
      (finally (delete-fixture! directory)))))

(deftest preserve-rebase-conflicts
  (let [{:keys [directory root]} (create-fixture!)]
    (try
      (git/create-branch! directory "test" "feat/conflict")
      (spit (io/file root "models/a.sql") "select 2\n")
      (git/stage! directory "test" "models/a.sql")
      (git/commit! directory "test" "Feature version")
      (git/switch-branch! directory "test" "main")
      (spit (io/file root "models/a.sql") "select 3\n")
      (git/stage! directory "test" "models/a.sql")
      (git/commit! directory "test" "Main version")
      (git/switch-branch! directory "test" "feat/conflict")
      (let [result (git/rebase! directory "test" "main")]
        (is (:operation_failed result))
        (is (str/includes? (:error result) "Resolve or abort"))
        (is (= "U" (:index_status (first (:changes result)))))
        (is (str/includes? (slurp (io/file root "models/a.sql")) "<<<<<<<")))
      (finally (delete-fixture! directory)))))

(deftest preserve-cherry-pick-conflicts
  (let [{:keys [directory root]} (create-fixture!)]
    (try
      (git/create-branch! directory "test" "feat/pick-conflict")
      (spit (io/file root "models/a.sql") "select 2\n")
      (git/stage! directory "test" "models/a.sql")
      (let [commit (:id (first (:commits (git/commit! directory "test" "Feature version"))))]
        (git/switch-branch! directory "test" "main")
        (spit (io/file root "models/a.sql") "select 3\n")
        (git/stage! directory "test" "models/a.sql")
        (git/commit! directory "test" "Main version")
        (let [result (git/cherry-pick! directory "test" commit)]
          (is (:operation_failed result))
          (is (= "U" (:index_status (first (:changes result)))))
          (is (.isFile (io/file root ".git/CHERRY_PICK_HEAD")))
          (is (str/includes? (slurp (io/file root "models/a.sql")) "<<<<<<<"))))
      (finally (delete-fixture! directory)))))
