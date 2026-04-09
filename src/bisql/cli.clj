(ns bisql.cli
  (:require [bisql.core :as bisql]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]))

(def ^:private default-db-options
  {:dbtype "postgresql"
   :host "localhost"
   :port "5432"
   :dbname "bisql_dev"
   :user "bisql"
   :password "bisql"})

(def ^:private default-schema "public")
(def ^:private default-base-dir "src/sql")

(def ^:private env-option-map
  {"BISQL_CONFIG" :config
   "BISQL_DBTYPE" :dbtype
   "BISQL_HOST" :host
   "BISQL_PORT" :port
   "BISQL_DBNAME" :dbname
   "BISQL_USER" :user
   "BISQL_PASSWORD" :password
   "BISQL_SCHEMA" :schema
   "BISQL_BASE_DIR" :base-dir})

(defn- getenv
  [name]
  (System/getenv name))

(defn- usage
  []
  (str/join
   "\n"
   ["Usage:"
    "  clojure -M -m bisql.cli gen-config [options]"
    "  clojure -M -m bisql.cli gen-crud [options]"
    "  clojure -M -m bisql.cli gen-ns [options]"
    ""
    "Options:"
    "  --config PATH"
    "  --dbtype DBTYPE"
    "  --host HOST"
    "  --port PORT"
    "  --dbname DBNAME"
    "  --user USER"
    "  --password PASSWORD"
    "  --schema SCHEMA"
    "  --base-dir PATH"
    "  --help"
    ""
    "Environment variables:"
    "  BISQL_CONFIG"
    "  BISQL_DBTYPE"
    "  BISQL_HOST"
    "  BISQL_PORT"
    "  BISQL_DBNAME"
    "  BISQL_USER"
    "  BISQL_PASSWORD"
    "  BISQL_SCHEMA"
    "  BISQL_BASE_DIR"]))

(defn- normalize-option-key
  [option]
  (let [k (keyword (subs option 2))]
    (cond
      (= k :output-root) :base-dir
      :else k)))

(defn- parse-cli-options
  [args]
  (loop [remaining args
         options {}]
    (if (empty? remaining)
      options
      (let [[option value & rest-args] remaining]
        (cond
          (= option "--help")
          (assoc options :help? true)

          (not (str/starts-with? option "--"))
          (throw (ex-info "Unknown command line argument."
                          {:argument option}))

          (nil? value)
          (throw (ex-info "Missing value for command line option."
                          {:option option}))

          :else
          (recur rest-args
                 (assoc options
                        (normalize-option-key option)
                        value)))))))

(defn- env-options
  []
  (reduce-kv
   (fn [options env-name option-key]
     (if-some [value (not-empty (getenv env-name))]
       (assoc options option-key value)
       options))
   {}
   env-option-map))

(defn- normalize-config-key
  [k]
  (cond
    (keyword? k) k
    (string? k) (keyword k)
    :else k))

(defn- normalize-config-map
  [m]
  (reduce-kv (fn [acc k v]
               (assoc acc (normalize-config-key k) v))
             {}
             m))

(defn- flatten-config-options
  [config]
  (let [db-options (normalize-config-map (:db config))
        generate-options (normalize-config-map (:generate config))]
    (merge db-options generate-options)))

(defn- read-config-options
  [config-path]
  (when config-path
    (let [config (edn/read-string (slurp config-path))]
      (when-not (map? config)
        (throw (ex-info "Config file must contain an EDN map."
                        {:config config-path})))
      (flatten-config-options (normalize-config-map config)))))

(defn- effective-options
  [cli-options]
  (let [env-opts (env-options)
        config-path (or (:config cli-options) (:config env-opts))
        config-opts (or (read-config-options config-path) {})]
    (merge config-opts
           (dissoc env-opts :config)
           (dissoc cli-options :config))))

(defn- datasource-spec
  [{:keys [user password dbtype host port dbname]}]
  {:dbtype (or dbtype (:dbtype default-db-options))
   :host (or host (:host default-db-options))
   :port (Integer/parseInt (str (or port (:port default-db-options))))
   :dbname (or dbname (:dbname default-db-options))
   :user (or user (:user default-db-options))
   :password (or password (:password default-db-options))})

(defn- project-relative-path
  [output-root path]
  (let [project-root (.toPath (io/file "."))
        output-file (.toPath (io/file output-root path))]
    (str (.normalize (.relativize project-root (.normalize output-file))))))

(defn- config-path
  [cli-options]
  (or (:config cli-options)
      (:config (env-options))
      "bisql.edn"))

(defn- write-config-template!
  [path]
  (spit path
        (str/join
         "\n"
         ["{:db {"
          "      ;; :dbtype \"postgresql\""
          "      ;; :host \"localhost\""
          "      ;; :port 5432"
          "      ;; :dbname \"bisql_dev\""
          "      ;; :user \"bisql\""
          "      ;; :password \"bisql\""
          "      }"
          " :generate {"
          "            ;; :schema \"public\""
          "            ;; :base-dir \"src/sql\""
          "            }}"
          ""])))

(defn- crud-result
  [options]
  (let [datasource (jdbc/get-datasource (datasource-spec options))
        schema (or (:schema options) default-schema)]
    (bisql/generate-crud datasource {:schema schema})))

(defn- print-generated-files!
  [headline output-root files]
  (println headline)
  (doseq [{:keys [path]} files]
    (println (project-relative-path output-root path))))

(defn- run-gen-crud!
  [options]
  (let [base-dir (or (:base-dir options) default-base-dir)
        generated-crud (crud-result options)
        file-result (bisql/write-crud-files! generated-crud {:output-root base-dir})
        file-count (count (:files file-result))
        template-count (count (:templates generated-crud))]
    (print-generated-files!
     (str "Wrote " file-count " CRUD SQL files (" template-count " SQL templates) to " base-dir)
     base-dir
     (:files file-result))))

(defn- run-gen-ns!
  [options]
  (let [base-dir (or (:base-dir options) default-base-dir)
        generated-crud (crud-result options)
        file-result (bisql/write-crud-query-namespaces! generated-crud {:output-root base-dir})
        file-count (count (:files file-result))]
    (print-generated-files!
     (str "Wrote " file-count " query namespace files to " base-dir)
     base-dir
     (:files file-result))))

(defn -main
  [& args]
  (let [[command & option-args] args
        cli-options (parse-cli-options option-args)]
    (cond
      (or (:help? cli-options) (nil? command))
      (println (usage))

      (= command "gen-config")
      (let [path (config-path cli-options)]
        (write-config-template! path)
        (println (str "Wrote config template to " path)))

      :else
      (let [options (effective-options cli-options)]
        (cond
          (= command "gen-crud")
          (run-gen-crud! options)

          (= command "gen-ns")
          (run-gen-ns! options)

          :else
          (throw (ex-info "Unknown command."
                          {:command command})))))))
