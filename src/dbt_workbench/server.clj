(ns dbt-workbench.server
  (:gen-class)
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [dbt-workbench.text :as text]
            [dbt-workbench.query-processor :as processor]
            [dbt-workbench.runtime :as runtime]
            [dbt-workbench.storage :as storage]
            [dbt-workbench.workspace :as workspace]
            [dbt-workbench.validation :as validation])
  (:import (com.sun.net.httpserver HttpServer HttpExchange HttpHandler)
           (java.net InetSocketAddress URLDecoder URI)
           (java.nio.charset StandardCharsets)
           (java.util.concurrent Executors)))

(defn default-data-dir []
  (storage/canonical-path (or (System/getenv "DBT_WORKBENCH_DATA_DIR")
                             (io/file (or (System/getenv "XDG_DATA_HOME") (io/file (System/getProperty "user.home") ".local/share")) "dbt-workbench"))))
(defn decode-query [raw]
  (into {} (for [part (text/split-on (or raw "") "&") :when (seq part)
                :let [[key value] (let [index (.indexOf ^String part "=")] (if (neg? index) [part ""] [(subs part 0 index) (subs part (inc index))]))]]
             [(keyword (URLDecoder/decode key "UTF-8")) (URLDecoder/decode (or value "") "UTF-8")])))
(defn integer-option [value fallback minimum maximum]
  (let [number (try (if value (Long/parseLong (str value)) fallback) (catch Exception _ (storage/fail! "Expected an integer.")))]
    (when-not (<= minimum number maximum) (storage/fail! "Integer option is out of range.")) number))
(defn json-response [data & [status]] {:status (or status 200) :type "application/json; charset=utf-8" :body (json/generate-string data)})
(defn csv-cell [value]
  (let [text (str value)] (str "\"" (str/replace text "\"" "\"\"") "\"")))
(defn csv-response [runtime id]
  (let [result (runtime/find-result runtime id) rows (runtime/read-result-rows runtime id)]
    {:status 200 :type "text/csv; charset=utf-8" :headers {"Content-Disposition" (str "attachment; filename=result-" id ".csv")}
     :body (str/join "\r\n" (cons (str/join "," (map :name (:columns result))) (map #(str/join "," (map csv-cell %)) rows)))}))
(defn handle-api! [app method path query body]
  (let [directory (:directory app) engine (:runtime app) segments (vec (remove str/blank? (text/split-on path "/")))
        [_ area id action detail] segments]
    (case area
      "health" (json-response {:status "ok" :version "0.2.0" :backend "clojure" :query_processor "honeysql+jdbc" :preview_engines ["duckdb" "sqlite"]})
      "projects"
      (do
        (when (and id (not (#{"demo" "open"} id))) (workspace/find-project directory id))
        (when (and (= "POST" method) (not (or (#{"demo" "open"} id) (#{"jobs" "query" "baseline"} action)))) (storage/fail! "Method not allowed." 405))
        (cond
        (and (= "GET" method) (nil? id)) (json-response {:projects (workspace/list-projects directory)})
        (and (= "POST" method) (= id "demo")) (json-response (workspace/create-demo! directory) 201)
        (and (= "POST" method) (= id "open")) (json-response (do (validation/validate! :open body) (workspace/open-project! directory (:path body) (:profiles_dir body))) 201)
        (nil? action) (json-response (assoc (workspace/find-project directory id) :targets (workspace/list-targets directory id) :baseline (workspace/read-baseline directory id)))
        (= action "models") (json-response {:models (workspace/list-models directory id)})
        (= action "catalog") (json-response (processor/read-catalog! directory id (:target query)))
        (= action "files") (json-response {:files (workspace/list-files directory id)})
        (= action "file") (case method
                            "GET" (json-response (workspace/read-file directory id (:path query)))
                            "PUT" (locking engine (runtime/require-idle! engine id) (json-response (workspace/save-file! directory id (validation/validate! :save body))))
                            (storage/fail! "Method not allowed." 405))
        (= action "changes") (json-response {:changes (workspace/list-changes directory id)})
        (and (= action "query") (= detail "convert") (= method "POST")) (json-response (processor/convert-query directory id (validation/validate! :convert body)))
        (and (= action "baseline") (= detail "import") (= method "POST"))
        (locking engine (runtime/require-idle! engine id) (json-response (do (validation/validate! :import body) (workspace/register-baseline! directory id (:artifact_dir body) (:target body))) 201))
        (= action "jobs") (case method "GET" (json-response {:jobs (runtime/list-jobs engine id)})
                                "POST" (json-response (do (validation/validate! :job body) (runtime/submit! engine id (:kind body) (:options body {}))) 202)
                                (storage/fail! "Method not allowed." 405))
        (= action "results") (json-response {:results (runtime/list-results engine id)})
        :else (storage/fail! "API route not found." 404)))
      "jobs" (case action nil (json-response (runtime/find-job engine id))
                        "events" (json-response {:events (runtime/list-events engine id (integer-option (:after query) 0 0 Long/MAX_VALUE))})
                        "cancel" (if (= method "POST") (json-response (runtime/cancel-job! engine id)) (storage/fail! "Method not allowed." 405))
                        (storage/fail! "API route not found." 404))
      "results" (case action nil (json-response (dissoc (runtime/find-result engine id) :data_path))
                           "rows" (json-response (runtime/result-page engine id (integer-option (:offset query) 0 0 Long/MAX_VALUE) (integer-option (:limit query) 100 1 1000)))
                           "download" (csv-response engine id)
                           (storage/fail! "API route not found." 404))
      (storage/fail! "API route not found." 404))))
(defn static-response [path]
  (let [relative (if (= path "/") "index.html" (subs path (count "/static/")))
        root (.toPath (.getCanonicalFile (io/file "resources/static")))
        file (.normalize (.resolve root relative))]
    (when-not (and (.startsWith file root) (.isFile (.toFile file))) (storage/fail! "Asset not found." 404))
    {:status 200 :type (cond (str/ends-with? path ".css") "text/css" (str/ends-with? path ".js") "application/javascript" (str/ends-with? path ".svg") "image/svg+xml" :else "text/html; charset=utf-8")
     :body (java.nio.file.Files/readAllBytes file)}))
(defn validate-request! [^HttpExchange exchange]
  (let [headers (.getRequestHeaders exchange) host (.getFirst headers "Host") origin (.getFirst headers "Origin")
        hostname (when host (.getHost (URI. (str "http://" host))))]
    (when-not (#{"127.0.0.1" "localhost" "[::1]" "::1"} hostname) (storage/fail! "This server accepts localhost requests only." 403))
    (when (and origin (or (= "null" origin) (not= host (.getRawAuthority (URI. origin))))) (storage/fail! "This local API accepts same-origin requests only." 403))
    (when (= "cross-site" (.getFirst headers "Sec-Fetch-Site")) (storage/fail! "Cross-site requests are disabled." 403))))
(defn handle-exchange! [app ^HttpExchange exchange]
  (let [response (try
                   (validate-request! exchange)
                   (let [uri (.getRequestURI exchange) path (.getPath uri) method (.getRequestMethod exchange)]
                     (if (str/starts-with? path "/api/")
                       (let [bytes (.readNBytes (.getRequestBody exchange) 2000001)]
                         (when (> (alength bytes) 2000000) (storage/fail! "Request body is too large." 413))
                         (handle-api! app method path (decode-query (.getRawQuery uri))
                                      (if (pos? (alength bytes)) (try (json/parse-string (String. bytes StandardCharsets/UTF_8) true)
                                                                     (catch Exception _ (storage/fail! "Invalid JSON request."))) {})))
                       (if (and (= method "GET") (or (= path "/") (str/starts-with? path "/static/")))
                         (static-response path) (storage/fail! "Route not found." 404))))
                   (catch clojure.lang.ExceptionInfo e (json-response {:detail (ex-message e)} (:status (ex-data e) 400)))
                   (catch Exception e (json-response {:detail (or (ex-message e) "Unexpected server error.")} 500)))
        bytes (if (string? (:body response)) (.getBytes ^String (:body response) StandardCharsets/UTF_8) (:body response))
        headers (.getResponseHeaders exchange)]
    (doseq [[key value] (merge {"Content-Type" (:type response) "Cache-Control" "no-store" "X-Content-Type-Options" "nosniff"
                               "Referrer-Policy" "no-referrer" "X-Frame-Options" "DENY"} (:headers response))] (.set headers key value))
    (.sendResponseHeaders exchange (:status response) (alength ^bytes bytes))
    (with-open [output (.getResponseBody exchange)] (.write output ^bytes bytes))))
(defn start! [{:keys [host port data-dir] :or {host "127.0.0.1" port 8787}}]
  (when-not (#{"127.0.0.1" "localhost" "::1"} host) (storage/fail! "Bind the workbench to a loopback address."))
  (let [directory (storage/canonical-path (or data-dir (default-data-dir)))
        engine (runtime/start-runtime! directory) server (HttpServer/create (InetSocketAddress. host port) 0)
        pool (Executors/newFixedThreadPool 8)
        app {:directory directory :runtime engine :server server :pool pool}]
    (.createContext server "/" (reify HttpHandler (handle [_ exchange] (handle-exchange! app exchange))))
    (.setExecutor server pool) (.start server) app))
(defn stop! [app]
  (.stop ^HttpServer (:server app) 0)
  (.shutdownNow ^java.util.concurrent.ExecutorService (:pool app))
  (runtime/stop-runtime! (:runtime app)))
(defn parse-args [args]
  (loop [arguments args options {}]
    (if-let [argument (first arguments)]
      (case argument
        "--host" (recur (nnext arguments) (assoc options :host (second arguments)))
        "--port" (recur (nnext arguments) (assoc options :port (integer-option (second arguments) 8787 1 65535)))
        "--data-dir" (recur (nnext arguments) (assoc options :data-dir (second arguments)))
        (storage/fail! (str "Unknown option " argument))) options)))
(defn -main [& args]
  (let [options (parse-args args) app (start! options)]
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(stop! app)))
    (println (str "dbt workbench (Clojure): http://" (:host options "127.0.0.1") ":" (:port options 8787)))
    (println (str "Workspace data: " (:directory app)))
    @(promise)))
