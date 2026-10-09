(ns dbt-workbench.storage
  (:require [cheshire.core :as json]
            [clojure.java.io :as io])
  (:import (java.nio.file Files StandardCopyOption)
           (java.security MessageDigest)
           (java.time Instant)
           (java.util UUID)))

(defn timestamp [] (str (Instant/now)))
(defn create-id [] (str (.replace (str (UUID/randomUUID)) "-" "")))
(defn canonical-path [path] (.getCanonicalPath (io/file path)))
(defn read-json [path fallback]
  (if (.isFile (io/file path)) (json/parse-string (slurp path) true) fallback))
(defn digest [content]
  (let [bytes (.digest (MessageDigest/getInstance "SHA-256") (.getBytes content "UTF-8"))]
    (apply str (map #(format "%02x" (bit-and % 255)) bytes))))
(defn save-text! [path content]
  (let [file (io/file path) parent (.getParentFile file)]
    (.mkdirs parent)
    (let [temporary (Files/createTempFile (.toPath parent) ".workbench-" ".tmp" (make-array java.nio.file.attribute.FileAttribute 0))]
      (try
        (spit (.toFile temporary) content)
        (Files/move temporary (.toPath file) (into-array StandardCopyOption [StandardCopyOption/ATOMIC_MOVE StandardCopyOption/REPLACE_EXISTING]))
        (finally (Files/deleteIfExists temporary))))))
(defn save-json! [path data] (save-text! path (json/generate-string data {:pretty true})))
(defn fail! [message & [status]] (throw (ex-info message {:status (or status 400)})))
