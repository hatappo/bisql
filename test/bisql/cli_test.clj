(ns bisql.cli-test
  (:require [bisql.cli :as cli]
            [bisql.core :as bisql]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is]]
            [next.jdbc :as jdbc]))

(deftest cli-gen-crud-uses-db-spec-and-base-dir
  (let [datasource-spec* (atom nil)
        crud-args* (atom nil)
        write-args* (atom nil)
        output (with-out-str
                 (with-redefs [jdbc/get-datasource (fn [spec]
                                                     (reset! datasource-spec* spec)
                                                     ::datasource)
                               bisql/generate-crud (fn [datasource options]
                                                     (reset! crud-args* [datasource options])
                                                     {:dialect "postgresql"
                                                      :schema "public"
                                                      :templates [{:table "users"}]
                                                      :warnings ["WARNING: duplicate generated CRUD template. Please report it here: https://github.com/hatappo/bisql/issues"]})
                               bisql/write-crud-files! (fn [crud-result options]
                                                         (reset! write-args* [crud-result options])
                                                         {:files [{:path "postgresql/public/users/crud.sql"}]})]
                   (cli/-main "gen-crud"
                              "--dbtype" "postgresql"
                              "--host" "db.example.com"
                              "--port" "5432"
                              "--dbname" "bisql_dev"
                              "--user" "bisql"
                              "--password" "secret"
                              "--schema" "public"
                              "--base-dir" "src/app/sql")))]
    (is (= {:dbtype "postgresql"
            :host "db.example.com"
            :port 5432
            :dbname "bisql_dev"
            :user "bisql"
            :password "secret"}
           @datasource-spec*))
    (is (= [::datasource {:schema "public"}]
           @crud-args*))
    (is (= [{:dialect "postgresql"
             :schema "public"
             :templates [{:table "users"}]
             :warnings ["WARNING: duplicate generated CRUD template. Please report it here: https://github.com/hatappo/bisql/issues"]}
            {:output-root "src/app/sql"}]
           @write-args*))
    (is (str/includes? output "Wrote 1 CRUD SQL files"))
    (is (str/includes? output "src/app/sql/postgresql/public/users/crud.sql"))
    (is (str/includes? output "https://github.com/hatappo/bisql/issues"))))

(deftest cli-gen-declarations-uses-db-spec-defaults-and-root
  (let [write-args* (atom nil)
        output (with-out-str
                 (with-redefs [bisql/write-declaration-files! (fn [options]
                                                                (reset! write-args* options)
                                                                {:files [{:path "postgresql/public/users/crud.clj"}]})]
                   (cli/-main "gen-declarations"
                              "--host" "db.example.com"
                              "--port" "15432"
                              "--dbname" "app_dev"
                              "--user" "bisql"
                              "--password" "secret"
                              "--schema" "private"
                              "--base-dir" "src/app/sql")))]
    (is (= {:output-root "src/app/sql"
            :suppress-unused-public-var? false
            :include-sql-template? false}
           @write-args*))
    (is (str/includes? output "Wrote 1 declaration namespace files"))
    (is (str/includes? output "src/app/sql/postgresql/public/users/crud.clj"))))

(deftest cli-gen-declarations-supports-unused-public-var-suppression
  (let [write-args* (atom nil)]
    (with-redefs [bisql/write-declaration-files! (fn [options]
                                                   (reset! write-args* options)
                                                       {:files []})]
      (cli/-main "gen-declarations" "--suppress-unused-public-var"))
    (is (= {:output-root "src/sql"
            :suppress-unused-public-var? true
            :include-sql-template? false}
           @write-args*))))

(deftest cli-gen-declarations-supports-including-sql-template
  (let [write-args* (atom nil)]
    (with-redefs [bisql/write-declaration-files! (fn [options]
                                                   (reset! write-args* options)
                                                       {:files []})]
      (cli/-main "gen-declarations" "--include-sql-template"))
    (is (= {:output-root "src/sql"
            :suppress-unused-public-var? false
            :include-sql-template? true}
           @write-args*))))

(deftest cli-gen-declarations-supports-double-dash-separator
  (let [write-args* (atom nil)]
    (with-redefs [bisql/write-declaration-files! (fn [options]
                                                   (reset! write-args* options)
                                                   {:files []})]
      (cli/-main "gen-declarations"
                 "--"
                 "--include-sql-template"
                 "--suppress-unused-public-var"))
    (is (= {:output-root "src/sql"
            :suppress-unused-public-var? true
            :include-sql-template? true}
           @write-args*))))

(deftest cli-options-fall-back-to-environment-variables
  (let [datasource-spec* (atom nil)
        crud-args* (atom nil)
        write-args* (atom nil)
        output (with-out-str
                 (with-redefs [cli/getenv (fn [name]
                                            (get {"BISQL_HOST" "env.example.com"
                                                  "BISQL_PORT" "25432"
                                                  "BISQL_DBNAME" "env_dev"
                                                  "BISQL_USER" "env-user"
                                                  "BISQL_PASSWORD" "env-secret"
                                                  "BISQL_SCHEMA" "env_schema"
                                                  "BISQL_BASE_DIR" "src/env/sql"}
                                                 name))
                               jdbc/get-datasource (fn [spec]
                                                     (reset! datasource-spec* spec)
                                                     ::datasource)
                               bisql/generate-crud (fn [datasource options]
                                                     (reset! crud-args* [datasource options])
                                                     {:dialect "postgresql"
                                                      :schema "public"
                                                      :templates [{:table "users"}]})
                               bisql/write-crud-files! (fn [crud-result options]
                                                         (reset! write-args* [crud-result options])
                                                         {:files [{:path "postgresql/public/users/crud.sql"}]})]
                   (cli/-main "gen-crud")))]
    (is (= {:dbtype "postgresql"
            :host "env.example.com"
            :port 25432
            :dbname "env_dev"
            :user "env-user"
            :password "env-secret"}
           @datasource-spec*))
    (is (= [::datasource {:schema "env_schema"}]
           @crud-args*))
    (is (= [{:dialect "postgresql"
             :schema "public"
             :templates [{:table "users"}]}
            {:output-root "src/env/sql"}]
           @write-args*))
    (is (str/includes? output "src/env/sql/postgresql/public/users/crud.sql"))))

(deftest cli-options-load-config-file-with-env-and-cli-precedence
  (let [config-file (doto (io/file (str (System/getProperty "java.io.tmpdir")
                                        "/bisql-cli-config.edn"))
                      (spit "{:db {:dbtype \"postgresql\"\n      :host \"config.example.com\"\n      :port 35432\n      :dbname \"config_dev\"\n      :user \"config-user\"\n      :password \"config-secret\"}\n :generate {:schema \"config_schema\"\n            :base-dir \"src/config/sql\"}}"))
        datasource-spec* (atom nil)
        crud-args* (atom nil)
        write-args* (atom nil)
        output (with-out-str
                 (with-redefs [cli/getenv (fn [name]
                                            (get {"BISQL_CONFIG" (.getPath config-file)
                                                  "BISQL_HOST" "env.example.com"
                                                  "BISQL_BASE_DIR" "src/env/sql"}
                                                 name))
                               jdbc/get-datasource (fn [spec]
                                                     (reset! datasource-spec* spec)
                                                     ::datasource)
                               bisql/generate-crud (fn [datasource options]
                                                     (reset! crud-args* [datasource options])
                                                     {:dialect "postgresql"
                                                      :schema "public"
                                                      :templates [{:table "users"}]})
                               bisql/write-crud-files! (fn [crud-result options]
                                                         (reset! write-args* [crud-result options])
                                                         {:files [{:path "postgresql/public/users/crud.sql"}]})]
                   (cli/-main "gen-crud"
                              "--port" "45432"
                              "--schema" "cli_schema")))]
    (is (= {:dbtype "postgresql"
            :host "env.example.com"
            :port 45432
            :dbname "config_dev"
            :user "config-user"
            :password "config-secret"}
           @datasource-spec*))
    (is (= [::datasource {:schema "cli_schema"}]
           @crud-args*))
    (is (= [{:dialect "postgresql"
             :schema "public"
             :templates [{:table "users"}]}
            {:output-root "src/env/sql"}]
           @write-args*))
    (is (str/includes? output "src/env/sql/postgresql/public/users/crud.sql"))))

(deftest cli-gen-config-writes-template
  (let [config-file (str (System/getProperty "java.io.tmpdir")
                         "/bisql-gen-config.edn")
        _ (when (.exists (io/file config-file))
            (.delete (io/file config-file)))
        output (with-out-str
                 (cli/-main "gen-config" "--config" config-file))
        config-text (slurp config-file)
        config (read-string config-text)]
    (is (str/includes? output (str "Wrote config template to " config-file)))
    (is (str/includes? config-text ";; :dbtype \"postgresql\""))
    (is (str/includes? config-text ";; :base-dir \"src/sql\""))
    (is (= {:db {}
            :generate {}}
           config))))
