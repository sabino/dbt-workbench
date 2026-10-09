(require '[clojure.string :as str]
         '[metabase.lib.core :as lib]
         '[metabase.lib.metadata.protocols :as mp]
         '[metabase.driver :as driver]
         '[metabase.driver.postgres]
         '[metabase.driver.sqlite]
         '[metabase.query-processor.store :as store]
         '[metabase.query-processor.compile :as compile]
         '[metabase.driver.sql.query-processor :as sql.qp]
         '[metabase.lib-be.core :as lib-be]
         '[cheshire.core :as json])

(def calls (atom []))
(def database-id 9001)
(def tables [{:lib/type :metadata/table :id 100 :db-id database-id :schema "main" :name "orders" :display-name "Orders" :active true}
             {:lib/type :metadata/table :id 200 :db-id database-id :schema "main" :name "customers" :display-name "Customers" :active true}])
(def fields [{:lib/type :metadata/column :id 101 :table-id 100 :name "id" :display-name "ID" :base-type :type/Integer :effective-type :type/Integer :database-type "INTEGER" :semantic-type :type/PK :active true :position 0}
             {:lib/type :metadata/column :id 102 :table-id 100 :name "total" :display-name "Total" :base-type :type/Decimal :effective-type :type/Decimal :database-type "DECIMAL" :active true :position 1}
             {:lib/type :metadata/column :id 103 :table-id 100 :name "created_at" :display-name "Created At" :base-type :type/DateTime :effective-type :type/DateTime :database-type "TIMESTAMP" :active true :position 2}
             {:lib/type :metadata/column :id 104 :table-id 100 :name "customer_id" :display-name "Customer ID" :base-type :type/Integer :effective-type :type/Integer :database-type "INTEGER" :active true :position 3}
             {:lib/type :metadata/column :id 105 :table-id 100 :name "status" :display-name "Status" :base-type :type/Text :effective-type :type/Text :database-type "TEXT" :active true :position 4}
             {:lib/type :metadata/column :id 201 :table-id 200 :name "id" :display-name "ID" :base-type :type/Integer :effective-type :type/Integer :database-type "INTEGER" :semantic-type :type/PK :active true :position 0}
             {:lib/type :metadata/column :id 202 :table-id 200 :name "name" :display-name "Name" :base-type :type/Text :effective-type :type/Text :database-type "TEXT" :active true :position 1}])
(defn metadata-provider [engine]
  (reify mp/MetadataProvider
    (database [_] (swap! calls conj {:method "database" :engine engine}) {:lib/type :metadata/database :id database-id :engine engine :name "Workbench" :details {} :features #{:basic-aggregations :standard-deviation-aggregations :expressions :expression-aggregations :foreign-keys :left-join :right-join :inner-join :full-join :nested-queries :native-parameters}})
    (metadatas [_ spec] (swap! calls conj {:method "metadatas" :spec spec})
      (let [objects (case (:lib/type spec) :metadata/table tables :metadata/column fields [])]
       (into [] (mp/default-spec-filter-xform spec) objects)))
    (setting [_ k] (swap! calls conj {:method "setting" :key k}) nil)))
(defn field-ref [id & [options]] [:field (merge {:lib/uuid (str (random-uuid))} options) id])
(def cases [{:name "select_filter_limit" :stage {:lib/type :mbql.stage/mbql :source-table 100 :fields [(field-ref 101) (field-ref 102)] :filters [[:> {:lib/uuid (str (random-uuid))} (field-ref 102) 10]] :limit 20}}
            {:name "aggregate_month" :stage {:lib/type :mbql.stage/mbql :source-table 100 :breakout [(field-ref 103 {:temporal-unit :month})] :aggregation [[:sum {:lib/uuid (str (random-uuid))} (field-ref 102)]]}}
            {:name "implicit_select" :stage {:lib/type :mbql.stage/mbql :source-table 100 :limit 3}}
            {:name "parameter_string" :stage {:lib/type :mbql.stage/mbql :source-table 100 :fields [(field-ref 101)] :filters [[:= {:lib/uuid (str (random-uuid))} (field-ref 105) "shipped"]]}}
            {:name "join_customer" :stage {:lib/type :mbql.stage/mbql :source-table 100 :fields [(field-ref 101)] :joins [{:lib/type :mbql/join :stages [{:lib/type :mbql.stage/mbql :source-table 200}] :alias "Customers" :strategy :left-join :conditions [[:= {:lib/uuid (str (random-uuid))} (field-ref 104) (field-ref 201 {:join-alias "Customers"})]] :fields [(field-ref 202 {:join-alias "Customers"})]}]}}
            {:name "unknown_field" :stage {:lib/type :mbql.stage/mbql :source-table 100 :fields [(field-ref 999)]}}
            {:name "native_passthrough" :stage {:lib/type :mbql.stage/native :native "SELECT DATE_TRUNC('month', created_at) FROM main.orders"}}])
(def results
 (with-redefs [lib-be/application-database-metadata-provider (fn [& args] (throw (ex-info "APP_DB_PROVIDER_FORBIDDEN" {:args args})))]
  (vec (for [engine [:postgres :sqlite] mode [:full_compile :full_compile_no_limit :driver_direct] test-case cases]
    (do (reset! calls [])
      (try
        (let [provider (metadata-provider engine)
              query (lib/query provider {:lib/type :mbql/query :database database-id :stages [(:stage test-case)]})
              start (System/nanoTime)
              output (driver/with-driver engine
                      (store/with-metadata-provider provider
                        (case mode :full_compile (compile/compile query) :full_compile_no_limit (compile/compile (lib/disable-default-limit query)) :driver_direct (sql.qp/mbql->native engine query))))]
          {:case (:name test-case) :engine engine :mode mode :status "success" :elapsed_ms (/ (- (System/nanoTime) start) 1e6) :output output :calls @calls})
        (catch Throwable e
          {:case (:name test-case) :engine engine :mode mode :status "failure" :error (str (class e) ": " (.getMessage e)) :chain (vec (take 8 (map (fn [x] {:class (str (class x)) :message (.getMessage x)}) (take-while some? (iterate ex-cause e))))) :calls @calls})))))))
(spit "results.compiler.json" (json/generate-string {:version "v0.64.1" :commit "7010f2797355cb04f2bc2e82e747eaa86357a315" :app_db_provider_forbidden true :results results} {:pretty true}))
(doseq [r results] (println (json/generate-string (select-keys r [:case :engine :mode :status :error :output]))))
(shutdown-agents)
