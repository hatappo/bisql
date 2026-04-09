(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'io.github.hatappo/bisql)
(def version (or (System/getenv "BISQL_VERSION")
                 "0.1.0-SNAPSHOT"))
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))
(def pom-file (format "%s/META-INF/maven/%s/%s/pom.xml"
                      class-dir
                      (namespace lib)
                      (name lib)))
(def basis (delay (b/create-basis {:project "deps.edn"})))
(def scm-url "https://github.com/hatappo/bisql")

(def pom-data
  [[:description "A 2-way SQL toolkit for Clojure."]
   [:url scm-url]
   [:licenses
    [:license
     [:name "MIT License"]
     [:url "https://opensource.org/licenses/MIT"]]]
   [:scm
    [:url scm-url]
    [:connection (str "scm:git:" scm-url ".git")]
    [:developerConnection "scm:git:git@github.com:hatappo/bisql.git"]
    [:tag (or (System/getenv "GITHUB_REF_NAME")
              "HEAD")]]])

(defn clean
  [_]
  (b/delete {:path "target"}))

(defn jar
  [_]
  (clean nil)
  (b/copy-dir {:src-dirs ["src" "resources"]
               :target-dir class-dir})
  (b/write-pom {:class-dir class-dir
                :lib lib
                :version version
                :basis @basis
                :src-dirs ["src" "resources"]
                :pom-data pom-data})
  (b/jar {:class-dir class-dir
          :jar-file jar-file})
  {:jar-file jar-file
   :pom-file pom-file})

(defn install
  [_]
  (let [{:keys [jar-file pom-file]} (jar nil)]
    (dd/deploy {:installer :local
                :artifact jar-file
                :pom-file pom-file})))

(defn deploy
  [_]
  (let [{:keys [jar-file pom-file]} (jar nil)]
    (dd/deploy {:installer :remote
                :artifact jar-file
                :pom-file pom-file
                :sign-releases? false})))
