(ns dbt-workbench.file-creation-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [dbt-workbench.server :as server]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.workspace :as workspace])
  (:import (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.charset StandardCharsets)
           (java.nio.file Files FileVisitOption OpenOption StandardOpenOption)))

(defn with-project! [run!]
  (let [directory (.toFile (Files/createTempDirectory "file-creation-test-" (make-array java.nio.file.attribute.FileAttribute 0)))
        root (io/file directory "project")]
    (try
      (storage/save-text! (io/file root "dbt_project.yml")
                          "name: example\ntarget-path: generated/build\nlog-path: activity/logs\npackages-install-path: vendor/packages\n")
      (let [project (workspace/open-project! (str directory) (str root) (str root))]
        (run! (str directory) (:id project) (io/file (:path project))))
      (finally
        (with-open [paths (Files/walk (.toPath directory) (make-array FileVisitOption 0))]
          (doseq [path (reverse (.toList paths))] (Files/deleteIfExists path)))))))
(defn capture-error [run!]
  (try (run!) nil (catch clojure.lang.ExceptionInfo error (ex-data error))))

(deftest source-file-creation-and-preview
  (with-project!
    (fn [directory id _]
      (doseq [[path content] [["models/nested/orders.sql" "SELECT 1 AS order_id;\n"]
                              ["seeds/orders.csv" "order_id,amount\n1,20\n"]
                              ["NOTICE" "Project notes\n"] ["notes/empty" ""]]]
        (let [preview (workspace/create-file! directory id {:path path :content content})]
          (is (= content (:content preview)))
          (is (= path (:path preview)))
          (is (false? (:read_only preview)))
          (is (= (storage/digest content) (:revision preview)))
          (is (= (alength (.getBytes ^String content StandardCharsets/UTF_8)) (:size_bytes preview)))
          (is (= preview (workspace/read-file directory id path))))))))

(deftest path-and-content-validation-leaves-no-target
  (with-project!
    (fn [directory id root]
      (doseq [path ["../outside.sql" "models/../outside.sql" "/tmp/outside.sql" "C:/outside.sql"
                    "models\\outside.sql" "models//outside.sql" "models/./outside.sql" "models/"
                    "profiles.yml" "nested/PROFILES.YML" ".git/config" "models/target/file.sql"
                    "logs/file.sql" ".venv/file.py" "node_modules/file.js" "dbt_packages/file.sql"
                    "generated/file.sql" "activity/file.txt" "vendor/file.yml"]]
        (is (thrown? clojure.lang.ExceptionInfo (workspace/create-file! directory id {:path path :content "text"})) path))
      (doseq [content [nil (str "nul" (char 0)) (String. (char-array [(char 0xd800)]))
                       (str (.repeat "é" (int (/ workspace/max-file-preview-bytes 2))) "x")]]
        (is (thrown? clojure.lang.ExceptionInfo (workspace/create-file! directory id {:path "rejected/invalid.txt" :content content}))))
      (is (not (.exists (io/file root "rejected"))))
      (is (= ["dbt_project.yml"] (mapv :path (workspace/list-files directory id))))
      (let [content (.repeat "é" (int (/ workspace/max-file-preview-bytes 2)))
            preview (workspace/create-file! directory id {:path "limit.txt" :content content})]
        (is (= workspace/max-file-preview-bytes (:size_bytes preview)))
        (is (= content (:content preview)))))))

(deftest existing-entries-and-competing-writers-are-preserved
  (with-project!
    (fn [directory id root]
      (workspace/create-file! directory id {:path "existing.sql" :content "original"})
      (is (= 409 (:status (capture-error #(workspace/create-file! directory id {:path "existing.sql" :content "replacement"})))))
      (is (= "original" (slurp (io/file root "existing.sql"))))
      (.mkdir (io/file root "folder"))
      (is (= 409 (:status (capture-error #(workspace/create-file! directory id {:path "folder" :content "replacement"})))))
      (let [write! workspace/write-created-file!
            marker "competing writer"]
        (with-redefs [workspace/write-created-file!
                      (fn [parent name data]
                        (Files/write (.toPath (io/file root name)) (.getBytes marker StandardCharsets/UTF_8)
                                     (into-array OpenOption [StandardOpenOption/CREATE_NEW StandardOpenOption/WRITE]))
                        (write! parent name data))]
          (is (= 409 (:status (capture-error #(workspace/create-file! directory id {:path "race.sql" :content "replacement"}))))))
        (is (= marker (slurp (io/file root "race.sql"))))))))

(deftest symlink-parents-and-targets-are-refused
  (with-project!
    (fn [directory id root]
      (let [outside (io/file directory "outside")
            original (io/file outside "original.txt")]
        (storage/save-text! original "preserved")
        (Files/createSymbolicLink (.toPath (io/file root "linked")) (.toPath outside) (make-array java.nio.file.attribute.FileAttribute 0))
        (Files/createSymbolicLink (.toPath (io/file root "linked.txt")) (.toPath original) (make-array java.nio.file.attribute.FileAttribute 0))
        (Files/createSymbolicLink (.toPath (io/file root "broken")) (.toPath (io/file outside "absent")) (make-array java.nio.file.attribute.FileAttribute 0))
        (doseq [path ["linked/new.txt" "linked/deep/new.txt" "linked.txt" "broken/new.txt"]]
          (is (thrown? clojure.lang.ExceptionInfo (workspace/create-file! directory id {:path path :content "replacement"}))))
        (is (= "preserved" (slurp original)))
        (is (not (.exists (io/file outside "new.txt"))))
        (is (not (.exists (io/file outside "deep"))))))))

(defn request! [base method path body]
  (let [bytes (if (string? body) (.getBytes ^String body StandardCharsets/UTF_8) body)
        builder (doto (HttpRequest/newBuilder (URI. (str base path))) (.header "Content-Type" "application/json"))
        publisher (if bytes (HttpRequest$BodyPublishers/ofByteArray bytes) (HttpRequest$BodyPublishers/noBody))
        response (.send (HttpClient/newHttpClient) (.build (.method builder method publisher)) (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response) :body (json/parse-string (.body response) true)}))
(deftest file-creation-http-boundaries
  (with-project!
    (fn [directory id root]
      (let [app (server/start! {:port 0 :data-dir directory})
            base (str "http://127.0.0.1:" (.getPort (.getAddress ^com.sun.net.httpserver.HttpServer (:server app))))
            path (str "/api/projects/" id "/files")]
        (try
          (let [created (request! base "POST" path (json/generate-string {:path "notes/new.txt" :content "Created\n"}))]
            (is (= 201 (:status created)))
            (is (= "Created\n" (get-in created [:body :content])))
            (is (= (workspace/read-file directory id "notes/new.txt") (:body created))))
          (is (= 409 (:status (request! base "POST" path (json/generate-string {:path "notes/new.txt" :content "overwrite"})))))
          (is (= 422 (:status (request! base "POST" path (json/generate-string {:path "bad.txt" :content "text" :extra true})))))
          (is (= 400 (:status (request! base "POST" path "{\"path\":\"bad.txt\",\"content\":\"\\ud800\"}"))))
          (is (= 400 (:status (request! base "POST" path (byte-array [(byte 0x7b) (unchecked-byte 0xc3) (byte 0x28) (byte 0x7d)])))))
          (is (not (.exists (io/file root "bad.txt"))))
          (let [content (.repeat "x" workspace/max-file-preview-bytes)
                result (request! base "POST" path (json/generate-string {:path "limit.txt" :content content}))]
            (is (= 201 (:status result)))
            (is (= workspace/max-file-preview-bytes (get-in result [:body :size_bytes]))))
          (is (= 405 (:status (request! base "PUT" path "{}"))))
          (is (= 2000000 (server/request-body-limit "POST" (str "/api/projects/" id "/jobs"))))
          (is (= 2000000 (server/request-body-limit "POST" (str "/api/projects/" id "/query/convert"))))
          (is (> (server/request-body-limit "POST" path) workspace/max-file-preview-bytes))
          (is (> (server/request-body-limit "PUT" (str "/api/projects/" id "/file")) workspace/max-file-preview-bytes))
          (finally (server/stop! app)))))))
