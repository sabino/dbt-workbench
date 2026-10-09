(ns dbt-workbench.github-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is]]
            [dbt-workbench.git :as git]
            [dbt-workbench.github :as github]
            [dbt-workbench.workspace :as workspace]))

(def project {:path "/fixture"})
(def clean-state {:available true :branch "feat/model" :changes []})
(def sha (apply str (repeat 40 "a")))
(def pull-request {:number 7 :title "Model change" :state "open" :draft true
                   :html_url "https://github.com/example/project/pull/7"
                   :head {:ref "feat/model" :sha sha} :base {:ref "main"}
                   :user {:login "fixture"}})

(defn- create-stub [calls remote responses]
  (fn [_ args input]
    (swap! calls conj {:args args :input input})
    (cond
      (= ["gh" "--version"] args) {:exit 0 :out "gh version fixture"}
      (= "git" (first args)) {:exit 0 :out (if (= "remote" (nth args 3)) remote (str sha "\n"))}
      :else {:exit 0 :out (json/generate-string (get responses (nth args 4)))})))

(deftest local-detection-without-network
  (doseq [remote ["git@github.com:example/project.git" "https://github.com/example/project.git"
                  "ssh://git@github.com/example/project.git"]]
    (let [calls (atom [])]
      (with-redefs [workspace/find-project (fn [& _] project)
                    git/read-state (fn [& _] clean-state)
                    github/run-process! (create-stub calls remote {})]
        (is (= {:available true :repository "example/project" :branch "feat/model"}
               (github/detect "data" "id")))
        (is (not-any? #(= ["gh" "api"] (take 2 (:args %))) @calls)))))
  (doseq [remote ["https://github.com.evil.test/example/project" "https://user@github.com/example/project"
                  "git@evil.test:example/project" "https://github.com/example/../secret"
                  "https://github.com/example/project?command=oops"]]
    (with-redefs [workspace/find-project (fn [& _] project)
                  git/read-state (fn [& _] clean-state)
                  github/run-process! (create-stub (atom []) remote {})]
      (is (false? (:available (github/detect "data" "id")))))))

(deftest pull-request-and-check-output
  (let [calls (atom [])
        responses {"repos/example/project/pulls" [pull-request]
                   "repos/example/project/pulls/7" pull-request
                   (str "repos/example/project/commits/" sha "/check-runs")
                   {:check_runs [{:id 12 :name "Test models" :status "completed" :conclusion "success"
                                  :details_url "https://github.com/example/project/actions/runs/12"}]}}]
    (with-redefs [workspace/find-project (fn [& _] project)
                  git/read-state (fn [& _] clean-state)
                  github/run-process! (create-stub calls "git@github.com:example/project.git" responses)]
      (is (= 7 (:number (first (:pull_requests (github/list-pull-requests "data" "id"))))))
      (is (= "https://github.com/example/project/pull/7" (:url (github/read-pull-request "data" "id" 7))))
      (is (= "success" (:conclusion (first (:checks (github/read-checks "data" "id" 7))))))
      (is (thrown? clojure.lang.ExceptionInfo (github/read-pull-request "data" "id" "7; evil")))
      (is (not-any? #(some #{"POST" "push"} (:args %)) @calls)))))

(deftest draft-creation-is-explicit-and-never-pushes
  (let [calls (atom [])]
    (with-redefs [workspace/find-project (fn [& _] project)
                  git/read-state (fn [& _] clean-state)
                  github/run-process! (create-stub calls "git@github.com:example/project.git"
                                                   {"repos/example/project/pulls" pull-request})]
      (is (:draft (github/create-draft! "data" "id" {:title "Model change" :body "What changed" :base "main"})))
      (let [post (first (filter #(some #{"POST"} (:args %)) @calls))]
        (is (= {:title "Model change" :body "What changed" :base "main" :head "feat/model" :draft true}
               (json/parse-string (:input post) true))))
      (is (not-any? #(some #{"push" "create"} (:args %)) @calls))
      (is (thrown? clojure.lang.ExceptionInfo (github/create-draft! "data" "id" {:title "Title" :base "main"})))
      (is (thrown? clojure.lang.ExceptionInfo (github/create-draft! "data" "id" {:title "Title" :body "" :base "feat/model"}))))))

(deftest refuse-unpublished-branch-and-invalid-github-schema
  (let [calls (atom []) baseline (create-stub calls "git@github.com:example/project.git"
                                            {"repos/example/project/pulls" [{:number 7}]})]
    (with-redefs [workspace/find-project (fn [& _] project)
                  git/read-state (fn [& _] clean-state)
                  github/run-process! (fn [root args input]
                                        (if (some #{"refs/remotes/origin/feat/model"} args)
                                          {:exit 1 :out "" :err "No remote tracking branch"}
                                          (baseline root args input)))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Publish the active branch"
                           (github/create-draft! "data" "id" {:title "Title" :body "" :base "main"})))
      (is (not-any? #(some #{"POST"} (:args %)) @calls))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid pull request"
                           (github/list-pull-requests "data" "id"))))))
