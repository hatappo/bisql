(ns build
  (:require [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

(def lib 'io.github.hatappo/bisql)
(def class-dir "target/classes")
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

(defn- required-version
  [{:keys [version]}]
  (or version
      (throw (ex-info ":version is required."
                      {}))))

(defn- jar-file-path
  [version]
  (format "target/%s-%s.jar" (name lib) version))

(defn- pom-file-path
  []
  (format "%s/META-INF/maven/%s/%s/pom.xml"
          class-dir
          (namespace lib)
          (name lib)))

(defn- resolved-clojars-repository
  []
  (:repository (#'deps-deploy.deps-deploy/preprocess-options
                {:repository "clojars"})))

(defn clean
  [_]
  (b/delete {:path "target"}))

(defn jar
  [opts]
  (let [version (required-version opts)
        jar-file (jar-file-path version)
        pom-file (pom-file-path)]
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
   :pom-file pom-file}))

(defn install
  [opts]
  (let [{:keys [jar-file pom-file]} (jar opts)]
    (dd/deploy {:installer :local
                :artifact jar-file
                :pom-file pom-file})))

(defn deploy
  [opts]
  (let [{:keys [jar-file pom-file]} (jar opts)]
    (dd/deploy {:installer :remote
                :artifact jar-file
                :pom-file pom-file
                :repository (resolved-clojars-repository)
                :sign-releases? false})))
