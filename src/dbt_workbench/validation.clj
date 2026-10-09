(ns dbt-workbench.validation
  (:require [malli.core :as malli]
            [dbt-workbench.storage :as storage]))

(def options-schema
  [:map
   [:target {:optional true} [:string {:min 1 :max 128}]]
   [:format {:optional true} [:enum "sql" "edn"]]
   [:editor_mode {:optional true} [:enum "visual" "sql" "edn"]]
   [:dialect {:optional true} [:enum "duckdb" "sqlite"]]
   [:content {:optional true} [:string {:max 2000000}]]
   [:editor_content {:optional true} [:string {:max 2000000}]]
   [:parameters {:optional true} [:map-of :keyword [:or :nil :string :boolean :int :double]]]
   [:statements {:optional true} [:vector {:min 1 :max 100} [:string {:min 1 :max 2000000}]]]
   [:selection {:optional true} [:map [:from :int] [:to :int] [:head :int]]]
   [:defer {:optional true} :boolean] [:favor_state {:optional true} :boolean]
   [:baseline {:optional true} :boolean] [:baseline_id {:optional true} [:maybe :string]]
   [:max_rows {:optional true} [:int {:min 1 :max 100000}]]
   [:timeout_seconds {:optional true} [:and [:fn number?] [:fn #(< 0 % 121)]]]
   [:select {:optional true} [:or :string [:vector :string]]]
   [:model_id {:optional true} :string] [:execution_scope {:optional true} [:enum "statement" "selection" "all" "document"]]
   [:left_result_id {:optional true} :string] [:right_result_id {:optional true} :string]
   [:key_columns {:optional true} [:vector :string]]])
(def schemas
  {:open [:map {:closed true} [:path [:string {:min 1 :max 4096}]] [:profiles_dir {:optional true} [:maybe :string]]]
   :save [:map {:closed true} [:path [:string {:min 1 :max 4096}]] [:content [:string {:max 2000000}]] [:expected_revision [:maybe :string]]]
   :import [:map {:closed true} [:artifact_dir [:string {:min 1 :max 4096}]] [:target [:string {:min 1 :max 128}]]]
   :job [:map {:closed true} [:kind [:enum "parse" "build" "test" "baseline" "preview" "query" "compare"]] [:options {:optional true} options-schema]]
   :convert [:map {:closed true} [:content [:string {:min 1 :max 2000000}]] [:format [:enum "sql" "edn"]]
             [:parameters {:optional true} [:map-of :keyword :any]] [:target {:optional true} :string] [:dialect {:optional true} [:enum "duckdb" "sqlite"]]]})
(defn validate! [kind value]
  (when-not (malli/validate (schemas kind) value) (storage/fail! (str "Invalid " (name kind) " request fields.") 422))
  value)
