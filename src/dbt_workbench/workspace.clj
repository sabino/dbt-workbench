(ns dbt-workbench.workspace
  (:require [clj-yaml.core :as yaml]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [dbt-workbench.text :as text]
            [dbt-workbench.storage :as storage])
  (:import (java.nio ByteBuffer)
           (java.nio.charset CharacterCodingException CodingErrorAction StandardCharsets)
           (java.nio.file Files StandardCopyOption)))

(def excluded #{"target" "logs" "dbt_packages" "dbt_modules" ".git" ".venv" "node_modules"})
(def max-file-preview-bytes (* 2 1024 1024))
(defn read-yaml [path]
  (if (.isFile (io/file path))
    (let [data (yaml/parse-string (slurp path))]
      (if (map? data) data (storage/fail! "Expected a YAML mapping."))) {}))
(defn list-projects [directory] (:projects (storage/read-json (io/file directory "projects.json") {:version 1 :projects []})))
(defn find-project [directory id]
  (or (first (filter #(= id (:id %)) (list-projects directory))) (storage/fail! "Project not found." 404)))
(defn project-data [directory id]
  (find-project directory id)
  (doto (io/file directory "projects" id) .mkdirs))
(defn open-project! [directory path profiles]
  (let [root (storage/canonical-path path)
        config (read-yaml (io/file root "dbt_project.yml"))
        profile (storage/canonical-path (or profiles (if (.isFile (io/file root "profiles.yml")) root (io/file (System/getProperty "user.home") ".dbt"))))]
    (when-not (.isFile (io/file root "dbt_project.yml")) (storage/fail! "Open a directory containing dbt_project.yml."))
    (when-not (.isDirectory (io/file profile)) (storage/fail! "The profiles directory does not exist."))
    (locking directory
      (let [projects (list-projects directory)]
        (or (first (filter #(and (= root (:path %)) (= profile (:profiles_dir %))) projects))
            (let [project {:id (storage/create-id) :name (str (:name config)) :path root :profiles_dir profile :is_demo false :created_at (storage/timestamp)}]
              (storage/save-json! (io/file directory "projects.json") {:version 1 :projects (conj (vec projects) project)})
              project))))))
(defn copy-tree! [source destination]
  (doseq [file (file-seq (io/file source))]
    (when (Files/isSymbolicLink (.toPath file)) (storage/fail! "Symbolic links cannot be copied as reference artifacts."))
    (let [relative (.relativize (.toPath (io/file source)) (.toPath file))
          target (.resolve (.toPath (io/file destination)) relative)]
      (if (.isDirectory file)
        (Files/createDirectories target (make-array java.nio.file.attribute.FileAttribute 0))
        (Files/copy (.toPath file) target (into-array StandardCopyOption [StandardCopyOption/REPLACE_EXISTING]))))))
(defn create-demo! [directory]
  (locking directory
    (or (first (filter :is_demo (list-projects directory)))
        (let [id "demo-jaffle-shop" data (io/file directory "projects" id)
              root (io/file data "workspace") profiles (io/file data "profiles") databases (io/file data "databases")
              project {:id id :name "Jaffle Shop" :path (storage/canonical-path root) :profiles_dir (storage/canonical-path profiles) :is_demo true :created_at (storage/timestamp)}
              baseline (str (io/file databases "baseline.duckdb"))]
          (copy-tree! "resources/demo" root)
          (.mkdirs profiles) (.mkdirs databases)
          (storage/save-text! (io/file profiles "profiles.yml")
                             (yaml/generate-string {:jaffle_shop {:target "dev" :outputs
                                                                {:baseline {:type "duckdb" :path baseline :schema "main" :threads 1}
                                                                 :dev {:type "duckdb" :path (str (io/file databases "dev.duckdb")) :schema "main" :threads 1
                                                                       :attach [{:path baseline :alias "baseline" :read_only true}]}}}}))
          (storage/save-json! (io/file directory "projects.json") {:version 1 :projects (conj (vec (list-projects directory)) project)})
          project))))
(defn list-files [directory id]
  (let [project (find-project directory id) root (.toPath (io/file (:path project)))
        config (read-yaml (io/file (:path project) "dbt_project.yml"))
        ignored (into excluded (keep #(some-> (config %) str (text/split-on "/") first) [:target-path :log-path :packages-install-path]))]
    (->> (tree-seq #(and (.isDirectory %) (not (Files/isSymbolicLink (.toPath %))))
                  #(remove (fn [file] (ignored (.getName file))) (seq (.listFiles %))) (.toFile root))
         (filter #(.isFile %))
         (remove #(Files/isSymbolicLink (.toPath %)))
         (map (fn [file] (let [path (str (.relativize root (.toPath file))) kind (str/lower-case (last (text/split-on (.getName file) ".")))]
                          {:path path :name (.getName file) :kind kind :size_bytes (.length file)})))
         (remove #(= "profiles.yml" (:name %)))
         (sort-by :path) vec)))
(defn file-path [directory id path]
  (when-not (and (string? path) (not (str/blank? path)) (not (str/includes? path "\\")) (not (str/includes? path "\u0000")))
    (storage/fail! "Use a relative project file path."))
  (let [root (.toPath (io/file (:path (find-project directory id)))) relative (.toPath (io/file path))
        file (.normalize (.resolve root relative))]
    (when (or (.isAbsolute relative) (not (.startsWith file root))
              (some #{".."} (text/split-on path "/"))
              (not (some #(= path (:path %)) (list-files directory id))))
      (storage/fail! "Only existing project files inside the opened project can be edited."))
    (loop [current root parts (seq relative)]
      (when parts
        (let [next-path (.resolve current (str (first parts)))]
          (when (Files/isSymbolicLink next-path) (storage/fail! "Symbolic links cannot be edited."))
          (recur next-path (next parts)))))
    (.toFile file)))
(defn decode-file-bytes [data]
  (when-not (some zero? data)
    (try
      (str (.decode (doto (.newDecoder StandardCharsets/UTF_8)
                      (.onMalformedInput CodingErrorAction/REPORT)
                      (.onUnmappableCharacter CodingErrorAction/REPORT))
                    (ByteBuffer/wrap data)))
      (catch CharacterCodingException _ nil))))
(defn read-file [directory id path]
  (let [file (file-path directory id path) size (.length file)
        data (when (<= size max-file-preview-bytes)
               (with-open [stream (io/input-stream file)] (.readNBytes stream (inc max-file-preview-bytes))))
        too-large? (or (> size max-file-preview-bytes) (> (count data) max-file-preview-bytes))
        content (when-not too-large? (decode-file-bytes data))
        read-only? (nil? content)]
    (cond-> {:path path :content (or content "") :size_bytes size :read_only read-only?}
      read-only? (assoc :preview_reason (if too-large? "This file exceeds the 2 MiB text preview limit." "Binary content is not displayed."))
      (not read-only?) (assoc :revision (storage/digest content)))))
(defn list-changes [directory id]
  (vec (reverse (storage/read-json (io/file (project-data directory id) "changes.json") []))))
(defn save-file! [directory id {:keys [path content expected_revision reason]}]
  (locking directory
    (let [file (file-path directory id path) before (read-file directory id path)]
      (when (:read_only before) (storage/fail! "This file is read-only in the workbench."))
      (when-not (= expected_revision (:revision before)) (storage/fail! "This file changed on disk. Reload before saving." 409))
      (when-not (string? content) (storage/fail! "File content must be text."))
      (when (str/includes? content "\u0000") (storage/fail! "Text files cannot contain NUL bytes."))
      (when (> (alength (.getBytes ^String content StandardCharsets/UTF_8)) max-file-preview-bytes)
        (storage/fail! "Text edits must fit within the 2 MiB preview limit."))
      (when-not (= content (:content before))
        (let [history (io/file (project-data directory id) "changes.json")
              records (storage/read-json history [])
              entry {:id (storage/create-id) :path path :saved_at (storage/timestamp) :before (:content before) :after content
                     :before_revision (:revision before) :after_revision (storage/digest content) :reason (or reason "File saved") }]
          (storage/save-text! file content)
          (try (storage/save-json! history (conj (vec records) entry))
               (catch Exception e (storage/save-text! file (:content before)) (throw e)))))
      (read-file directory id path))))
(defn upgrade-demo-config [config]
  (let [models (walk/postwalk (fn [value] (if (and (map? value) (contains? value :materialized))
                                          (-> value (assoc :+materialized (get value :+materialized (:materialized value))) (dissoc :materialized)) value)) (:models config))]
    (cond-> (assoc config :models models)
      (some #(str/includes? % "<2") (:require-dbt-version config)) (assoc :require-dbt-version [">=2.0.5" "<3.0.0"]))))
(defn upgrade-demo-tests [config]
  (walk/postwalk (fn [value]
                   (if-not (map? value) value
                     (reduce (fn [result [test-name fields]]
                               (let [test (get result test-name) arguments (select-keys test fields)]
                                 (if (and (map? test) (seq arguments))
                                   (assoc result test-name (assoc (apply dissoc test fields) :arguments (merge arguments (:arguments test)))) result)))
                             value [[:accepted_values [:values]] [:relationships [:to :field]]]))) config))
(defn upgrade-demo! [directory project]
  (when (:is_demo project)
    (doseq [[path transform] [["dbt_project.yml" upgrade-demo-config] ["models/schema.yml" upgrade-demo-tests] ["models/staging/schema.yml" upgrade-demo-tests]]]
      (let [before (read-file directory (:id project) path) data (yaml/parse-string (:content before)) updated (transform data)]
        (when-not (= data updated)
          (save-file! directory (:id project) {:path path :content (yaml/generate-string updated) :expected_revision (:revision before) :reason "Native dbt v2 compatibility migration"}))))))
(defn read-manifest [directory id]
  (storage/read-json (io/file (project-data directory id) "manifest.json") {}))
(defn list-models [directory id]
  (let [project (find-project directory id) config (read-yaml (io/file (:path project) "dbt_project.yml"))
        package (:name config) folders (or (:model-paths config) ["models"])
        files (filter #(and (= "sql" (:kind %)) (some (fn [folder] (str/starts-with? (:path %) (str folder "/"))) folders)) (list-files directory id))
        nodes (vals (:nodes (read-manifest directory id)))
        models (into {} (for [node nodes :when (= "model" (:resource_type node))]
                          [(:original_file_path node) {:unique_id (:unique_id node) :name (:name node) :path (:original_file_path node)
                                                      :materialized (get-in node [:config :materialized]) :depends_on (get-in node [:depends_on :nodes]) :columns (:columns node)}]))]
    (->> files (map (fn [file] (or (models (:path file))
                                 (let [name (text/remove-suffix (:name file) ".sql")]
                                   {:unique_id (str "model." package "." name) :name name :path (:path file) :materialized "unknown" :depends_on [] :columns {}}))))
         (sort-by :name) vec)))
(defn list-targets [directory id]
  (let [project (find-project directory id) config (read-yaml (io/file (:path project) "dbt_project.yml"))
        profiles (read-yaml (io/file (:profiles_dir project) "profiles.yml"))]
    (->> (get-in profiles [(keyword (or (:profile config) (:name config))) :outputs]) keys (map name) sort vec)))
(defn read-baseline [directory id & [baseline-id]]
  (let [baselines (storage/read-json (io/file (project-data directory id) "baselines.json") [])]
    (if baseline-id (or (first (filter #(= baseline-id (:id %)) baselines)) (storage/fail! "Reference baseline not found." 404)) (last baselines))))
(defn register-baseline! [directory id artifact-dir target]
  (let [source (io/file artifact-dir) manifest (storage/read-json (io/file source "manifest.json") nil)
        config (read-yaml (io/file (:path (find-project directory id)) "dbt_project.yml"))]
    (when-not (and (map? (:nodes manifest)) (or (nil? (get-in manifest [:metadata :project_name])) (= (:name config) (get-in manifest [:metadata :project_name]))))
      (storage/fail! "The reference must contain a valid manifest for this project."))
    (locking directory
      (let [data (project-data directory id) baseline-id (storage/create-id) destination (io/file data "baselines" baseline-id)
            path (io/file data "baselines.json")
            baseline {:id baseline-id :path (str destination) :artifact_dir (str destination) :manifest_path (str (io/file destination "manifest.json"))
                      :target target :created_at (storage/timestamp) :manifest_revision (storage/digest (slurp (io/file source "manifest.json"))) :data_is_snapshot false}]
        (when (.startsWith (.toPath destination) (.toPath (.getCanonicalFile source))) (storage/fail! "Reference source cannot contain its destination."))
        (copy-tree! source destination)
        (doseq [file (file-seq destination) :when (.isFile file)] (.setWritable file false false))
        (storage/save-json! path (conj (vec (storage/read-json path [])) baseline))
        baseline))))
