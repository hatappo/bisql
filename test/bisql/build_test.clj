(ns bisql.build-test
  (:require [build :as build]
            [clojure.test :refer [deftest is testing]]
            [clojure.tools.build.api :as b]
            [deps-deploy.deps-deploy :as dd]))

;; ---------------------------------------------------------------------------
;; Static defs
;; ---------------------------------------------------------------------------

(deftest lib-is-qualified-symbol
  (is (= 'io.github.hatappo/bisql build/lib))
  (is (= "io.github.hatappo" (namespace build/lib)))
  (is (= "bisql" (name build/lib))))

(deftest class-dir-is-target-classes
  (is (= "target/classes" build/class-dir)))

(deftest scm-url-points-to-github
  (is (= "https://github.com/hatappo/bisql" build/scm-url)))

(deftest basis-is-a-delay
  (is (instance? clojure.lang.Delay build/basis)))

;; ---------------------------------------------------------------------------
;; pom-data structure — the refactor changed indentation inside [:scm …];
;; verify the nesting is still correct.
;; ---------------------------------------------------------------------------

(deftest pom-data-has-expected-top-level-entries
  (let [top-keys (mapv first build/pom-data)]
    (is (= [:description :url :licenses :scm] top-keys))))

(deftest pom-data-description-is-correct
  (is (= [:description "A 2-way SQL toolkit for Clojure."]
         (nth build/pom-data 0))))

(deftest pom-data-url-uses-scm-url
  (is (= [:url build/scm-url]
         (nth build/pom-data 1))))

(deftest pom-data-licenses-structure
  (let [[tag & children] (nth build/pom-data 2)]
    (is (= :licenses tag))
    (let [[license-tag & license-children] (first children)]
      (is (= :license license-tag))
      (is (= [:name "MIT License"]
             (first license-children)))
      (is (= [:url "https://opensource.org/licenses/MIT"]
             (second license-children))))))

(deftest pom-data-scm-nesting-is-correct
  (testing "connection, developerConnection, and tag are children of :scm"
    (let [[tag & children] (nth build/pom-data 3)
          child-keys (mapv first children)]
      (is (= :scm tag))
      (is (= [:url :connection :developerConnection :tag] child-keys))
      (is (= [:url build/scm-url] (first children)))
      (is (= [:connection (str "scm:git:" build/scm-url ".git")]
             (second children)))
      (is (= [:developerConnection "scm:git:git@github.com:hatappo/bisql.git"]
             (nth children 2))))))

(deftest pom-data-scm-tag-defaults-to-HEAD
  (let [[_scm & children] (nth build/pom-data 3)
        [_tag-key tag-value] (nth children 3)]
    (is (= :tag _tag-key))
    ;; When GITHUB_REF_NAME is unset the tag should fall back to "HEAD"
    (is (string? tag-value))
    (when-not (System/getenv "GITHUB_REF_NAME")
      (is (= "HEAD" tag-value)))))

;; ---------------------------------------------------------------------------
;; Private helper: required-version
;; ---------------------------------------------------------------------------

(deftest required-version-returns-version-when-present
  (is (= "1.2.3" (#'build/required-version {:version "1.2.3"}))))

(deftest required-version-throws-when-version-is-missing
  (let [error (try
                (#'build/required-version {})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (some? error))
    (is (= ":version is required." (ex-message error)))))

(deftest required-version-throws-when-version-is-nil
  (let [error (try
                (#'build/required-version {:version nil})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (some? error))
    (is (= ":version is required." (ex-message error)))))

;; ---------------------------------------------------------------------------
;; Private helper: jar-file-path
;; ---------------------------------------------------------------------------

(deftest jar-file-path-formats-correctly
  (is (= "target/bisql-0.1.0.jar"
         (#'build/jar-file-path "0.1.0")))
  (is (= "target/bisql-2.0.0-SNAPSHOT.jar"
         (#'build/jar-file-path "2.0.0-SNAPSHOT"))))

;; ---------------------------------------------------------------------------
;; Private helper: pom-file-path
;; ---------------------------------------------------------------------------

(deftest pom-file-path-formats-correctly
  (is (= "target/classes/META-INF/maven/io.github.hatappo/bisql/pom.xml"
         (#'build/pom-file-path))))

;; ---------------------------------------------------------------------------
;; Private helper: resolved-clojars-repository
;; ---------------------------------------------------------------------------

(deftest resolved-clojars-repository-extracts-repository
  (with-redefs [deps-deploy.deps-deploy/preprocess-options
                (fn [opts]
                  (is (= {:repository "clojars"} opts))
                  {:repository {"clojars" {:url "https://repo.clojars.org/"}}})]
    (is (= {"clojars" {:url "https://repo.clojars.org/"}}
           (#'build/resolved-clojars-repository)))))

;; ---------------------------------------------------------------------------
;; clean
;; ---------------------------------------------------------------------------

(deftest clean-deletes-target-directory
  (let [delete-args (atom nil)]
    (with-redefs [b/delete (fn [opts]
                             (reset! delete-args opts))]
      (build/clean nil)
      (is (= {:path "target"} @delete-args)))))

;; ---------------------------------------------------------------------------
;; jar — orchestration test with mocked build API
;; ---------------------------------------------------------------------------

(deftest jar-calls-build-steps-in-order-and-returns-paths
  (let [call-log (atom [])
        fake-basis {:some "basis"}]
    (with-redefs [b/delete     (fn [opts]
                                 (swap! call-log conj [:delete opts]))
                  b/copy-dir   (fn [opts]
                                 (swap! call-log conj [:copy-dir opts]))
                  b/write-pom  (fn [opts]
                                 (swap! call-log conj [:write-pom opts]))
                  b/jar        (fn [opts]
                                 (swap! call-log conj [:jar opts]))
                  build/basis  (delay fake-basis)]
      (let [result (build/jar {:version "1.0.0"})]
        (is (= "target/bisql-1.0.0.jar" (:jar-file result)))
        (is (= "target/classes/META-INF/maven/io.github.hatappo/bisql/pom.xml"
               (:pom-file result)))
        ;; Verify call order: delete → copy-dir → write-pom → jar
        (is (= :delete  (ffirst @call-log)))
        (is (= :copy-dir (first (second @call-log))))
        (is (= :write-pom (first (nth @call-log 2))))
        (is (= :jar (first (nth @call-log 3))))
        ;; Verify write-pom receives correct options
        (let [[_ pom-opts] (nth @call-log 2)]
          (is (= "target/classes" (:class-dir pom-opts)))
          (is (= 'io.github.hatappo/bisql (:lib pom-opts)))
          (is (= "1.0.0" (:version pom-opts)))
          (is (= fake-basis (:basis pom-opts)))
          (is (= ["src" "resources"] (:src-dirs pom-opts)))
          (is (= build/pom-data (:pom-data pom-opts))))
        ;; Verify b/jar receives correct options
        (let [[_ jar-opts] (nth @call-log 3)]
          (is (= "target/classes" (:class-dir jar-opts)))
          (is (= "target/bisql-1.0.0.jar" (:jar-file jar-opts))))))))

(deftest jar-throws-when-version-is-missing
  (let [error (try
                (build/jar {})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (some? error))
    (is (= ":version is required." (ex-message error)))))

(deftest jar-copies-src-and-resources-directories
  (let [copy-opts (atom nil)]
    (with-redefs [b/delete    (fn [_])
                  b/copy-dir  (fn [opts] (reset! copy-opts opts))
                  b/write-pom (fn [_])
                  b/jar       (fn [_])
                  build/basis (delay {})]
      (build/jar {:version "0.1.0"})
      (is (= ["src" "resources"] (:src-dirs @copy-opts)))
      (is (= "target/classes" (:target-dir @copy-opts))))))

;; ---------------------------------------------------------------------------
;; install
;; ---------------------------------------------------------------------------

(deftest install-builds-jar-then-deploys-locally
  (let [deploy-args (atom nil)]
    (with-redefs [b/delete    (fn [_])
                  b/copy-dir  (fn [_])
                  b/write-pom (fn [_])
                  b/jar       (fn [_])
                  build/basis (delay {})
                  dd/deploy   (fn [opts] (reset! deploy-args opts))]
      (build/install {:version "0.1.0"})
      (is (= :local (:installer @deploy-args)))
      (is (= "target/bisql-0.1.0.jar" (:artifact @deploy-args)))
      (is (= "target/classes/META-INF/maven/io.github.hatappo/bisql/pom.xml"
             (:pom-file @deploy-args))))))

(deftest install-throws-when-version-is-missing
  (let [error (try
                (build/install {})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (some? error))
    (is (= ":version is required." (ex-message error)))))

;; ---------------------------------------------------------------------------
;; deploy
;; ---------------------------------------------------------------------------

(deftest deploy-builds-jar-then-deploys-remotely-with-repository
  (let [deploy-args (atom nil)
        fake-repo {"clojars" {:url "https://repo.clojars.org/"}}]
    (with-redefs [b/delete    (fn [_])
                  b/copy-dir  (fn [_])
                  b/write-pom (fn [_])
                  b/jar       (fn [_])
                  build/basis (delay {})
                  dd/deploy   (fn [opts] (reset! deploy-args opts))
                  deps-deploy.deps-deploy/preprocess-options
                  (fn [_] {:repository fake-repo})]
      (build/deploy {:version "0.1.0"})
      (is (= :remote (:installer @deploy-args)))
      (is (= "target/bisql-0.1.0.jar" (:artifact @deploy-args)))
      (is (= "target/classes/META-INF/maven/io.github.hatappo/bisql/pom.xml"
             (:pom-file @deploy-args)))
      (is (= fake-repo (:repository @deploy-args)))
      (is (= false (:sign-releases? @deploy-args))))))

(deftest deploy-throws-when-version-is-missing
  (let [error (try
                (build/deploy {})
                nil
                (catch clojure.lang.ExceptionInfo ex
                  ex))]
    (is (some? error))
    (is (= ":version is required." (ex-message error)))))

;; ---------------------------------------------------------------------------
;; Behavioral equivalence: jar-file-path produces the same format as the
;; old global (format "target/%s-%s.jar" (name lib) version)
;; ---------------------------------------------------------------------------

(deftest jar-file-path-matches-old-global-format
  (doseq [v ["0.1.0" "0.1.0-SNAPSHOT" "2.0.0-rc1"]]
    (is (= (format "target/%s-%s.jar" (name build/lib) v)
           (#'build/jar-file-path v)))))

(deftest pom-file-path-matches-old-global-format
  (is (= (format "%s/META-INF/maven/%s/%s/pom.xml"
                  build/class-dir
                  (namespace build/lib)
                  (name build/lib))
         (#'build/pom-file-path))))
