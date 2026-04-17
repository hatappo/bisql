(ns bisql.pages.playground
  (:require [bisql.engine :as engine]
            [bisql.pages.docs :as docs]
            [bisql.pages.examples :as examples]
            [cljs.pprint :as pprint]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [replicant.dom :as r]))

(defonce *state
  (atom {:catalog examples/catalog
         :docs-catalog docs/catalog
         :route-page :docs
         :selected-doc-slug nil
         :selected-example-id nil
         :sidebar-open? false
         :example-title ""
         :example-description ""
         :sql-template ""
         :params-edn ""
         :output-sql ""
         :output-data ""
         :output-error ""
         :page-error ""}))

(defonce *root (atom nil))
(defonce *editors (atom {}))

(def github-url
  "https://github.com/hatappo/bisql")

(declare example-by-id
         load-example!
         rerender!
         sync-route-from-location!
         share-x-url)

(defn pprint-str
  [x]
  (with-out-str
    (pprint/pprint x)))

(defn flatten-examples
  [catalog]
  (mapcat :examples (:groups catalog)))

(defn doc-pages
  []
  (:pages docs/catalog))

(defn first-doc-slug
  []
  (some-> (doc-pages) first :slug))

(defn doc-by-slug
  [slug]
  (some #(when (= slug (:slug %)) %) (doc-pages)))

(defn normalize-doc-slug
  [slug]
  (if (doc-by-slug slug)
    slug
    (first-doc-slug)))

(defn first-example-id
  []
  (-> examples/catalog :groups first :examples first :id))

(defn normalize-example-id
  [example-id]
  (if (example-by-id examples/catalog example-id)
    example-id
    (first-example-id)))

(defn root-path
  []
  (let [path (or (.-__BISQL_ROOT_PATH__ js/window) "/")]
    (if (str/ends-with? path "/")
      path
      (str path "/"))))

(defn asset-prefix
  []
  (or (.-__BISQL_ASSET_PREFIX__ js/window) "./"))

(defn split-path-segments
  [path]
  (->> (str/split (or path "") #"/")
       (remove str/blank?)
       vec))

(defn relative-pathname
  []
  (let [pathname (.-pathname js/location)
        root (root-path)]
    (if (str/starts-with? pathname root)
      (subs pathname (count root))
      pathname)))

(defn doc-index
  [slug]
  (first
   (keep-indexed
    (fn [idx page]
      (when (= slug (:slug page))
        idx))
    (doc-pages))))

(defn neighboring-docs
  [slug]
  (let [pages (vec (doc-pages))
        idx (or (doc-index slug) 0)]
    {:previous (when (pos? idx)
                 (nth pages (dec idx)))
     :next (when (< idx (dec (count pages)))
             (nth pages (inc idx)))}))

(defn parse-route
  []
  (let [segments (split-path-segments (relative-pathname))]
    (cond
      (empty? segments)
      {:page :docs
       :doc-slug (first-doc-slug)}

      (= ["docs"] segments)
      {:page :docs
       :doc-slug (first-doc-slug)}

      (and (= "docs" (first segments))
           (= 2 (count segments)))
      {:page :docs
       :doc-slug (normalize-doc-slug (second segments))}

      (= ["playground"] segments)
      {:page :playground
       :example-id (first-example-id)}

      (and (= "playground" (first segments))
           (= 2 (count segments)))
      {:page :playground
       :example-id (normalize-example-id (second segments))}

      :else
      {:page :docs
       :doc-slug (first-doc-slug)})))

(defn route-url
  [{:keys [page doc-slug example-id]}]
  (let [root (root-path)]
    (case page
      :docs (str root "docs/" (normalize-doc-slug doc-slug) "/")
      :playground (let [example-id (normalize-example-id example-id)]
                    (str root "playground/" example-id "/"))
      root)))

(defn absolute-route-url
  [route]
  (str (.-origin js/location) (route-url route)))

(defn navigate!
  [route]
  (.pushState js/history nil "" (route-url route))
  (sync-route-from-location!))

(defn sync-route-from-location!
  []
  (let [{:keys [page doc-slug example-id]} (parse-route)]
    (swap! *state assoc
           :route-page page
           :sidebar-open? false
           :selected-doc-slug (normalize-doc-slug doc-slug))
    (case page
      :playground
      (let [example-id (normalize-example-id example-id)]
        (if (= example-id (:selected-example-id @*state))
          (rerender!)
          (load-example! example-id)))

      :docs
      (rerender!)

      (rerender!))))

(defn example-by-id
  [catalog example-id]
  (some #(when (= example-id (:id %)) %)
        (flatten-examples catalog)))

(defn example-index
  [catalog example-id]
  (first
   (keep-indexed
    (fn [idx example]
      (when (= example-id (:id example))
        idx))
    (flatten-examples catalog))))

(defn neighboring-examples
  [catalog example-id]
  (let [examples (vec (flatten-examples catalog))
        idx (or (example-index catalog example-id) 0)]
    {:previous (when (pos? idx)
                 (nth examples (dec idx)))
     :next (when (< idx (dec (count examples)))
             (nth examples (inc idx)))}))

(defn normalize-edn-value
  [x]
  (cond
    (and (symbol? x) (= "bisql/DEFAULT" (str x))) engine/DEFAULT
    (and (symbol? x) (= "bisql/ALL" (str x))) engine/ALL
    (and (list? x)
         (symbol? (first x))
         (= "bisql/LIKE_STARTS_WITH" (str (first x))))
    (engine/LIKE_STARTS_WITH (normalize-edn-value (second x)))
    (and (list? x)
         (symbol? (first x))
         (= "bisql/LIKE_ENDS_WITH" (str (first x))))
    (engine/LIKE_ENDS_WITH (normalize-edn-value (second x)))
    (and (list? x)
         (symbol? (first x))
         (= "bisql/LIKE_CONTAINS" (str (first x))))
    (engine/LIKE_CONTAINS (normalize-edn-value (second x)))
    (map? x) (into (empty x) (map (fn [[k v]] [k (normalize-edn-value v)])) x)
    (vector? x) (mapv normalize-edn-value x)
    (list? x) (apply list (map normalize-edn-value x))
    (seq? x) (doall (map normalize-edn-value x))
    :else x))

(defn resolve-sql-template
  [{:keys [kind sql-template]}]
  (case kind
    :direct-template sql-template
    :resource sql-template
    :resource-query sql-template))

(defn example-params-str
  [example]
  (pprint-str (get-in example [:input :params])))

(defn inline-markdown-nodes
  [s]
  (let [parts (str/split (or s "") #"`" -1)]
    (map-indexed
     (fn [idx part]
       (if (odd? idx)
         [:code.inline-code {:replicant/key (str "code-" idx)} part]
         [:span {:replicant/key (str "text-" idx)} part]))
     parts)))

(defn description-nodes
  [description]
  (let [paragraphs (->> (str/split (or description "") #"\n\s*\n")
                        (map str/trim)
                        (remove str/blank?))]
    (if (seq paragraphs)
      (map-indexed
       (fn [idx paragraph]
         (into [:p {:replicant/key (str "paragraph-" idx)}]
               (inline-markdown-nodes paragraph)))
       paragraphs)
      [])))

(defn parse-params
  [s]
  (-> s
      reader/read-string
      normalize-edn-value))

(defn render-current!
  []
  (let [{:keys [sql-template params-edn]} @*state]
    (try
      (let [params (parse-params params-edn)
            result (engine/render-query {:sql-template sql-template} params)
            summary (cond-> {}
                      (contains? result :params) (assoc :params (:params result))
                      (and (contains? result :meta)
                           (seq (:meta result))) (assoc :meta (:meta result)))]
        (swap! *state assoc
               :output-sql (:sql result)
               :output-data (pprint-str summary)
               :output-error ""))
      (catch :default ex
        (swap! *state assoc
               :output-sql ""
               :output-data ""
               :output-error
               (pprint-str {:message (ex-message ex)
                            :data (ex-data ex)}))))))

(defn load-example!
  [example-id]
  (when-let [catalog (:catalog @*state)]
    (when-let [{:keys [title description input] :as example}
               (example-by-id catalog example-id)]
      (let [sql-template (resolve-sql-template input)]
        (when-not (string? sql-template)
          (throw (ex-info "Embedded SQL template is missing."
                          {:example-id example-id
                           :input-kind (:kind input)})))
        (swap! *state assoc
               :selected-example-id example-id
               :sidebar-open? false
               :example-title title
               :example-description description
               :sql-template sql-template
               :params-edn (example-params-str example)
               :output-sql ""
               :output-data ""
               :output-error ""
               :page-error "")
        (render-current!)
        (rerender!)))))

(defn destroy-editor!
  [editor-key]
  (when-let [{:keys [view]} (get @*editors editor-key)]
    (let [^js view view]
      (.toTextArea view)
      (swap! *editors dissoc editor-key))))

(defn destroy-all-editors!
  []
  (doseq [editor-key (keys @*editors)]
    (destroy-editor! editor-key)))

(defn attached-to-host?
  [view host]
  (let [^js view view
        wrapper (.getWrapperElement view)]
    (= host (.-parentNode wrapper))))

(defn replace-editor-doc!
  [view text]
  (let [^js view view
        current (.getValue view)]
    (when-not (= current text)
      (.setValue view text))))

(defn docs-code-mode
  [^js code-node]
  (let [class-name (or (.-className code-node) "")
        language (some->> (re-find #"language-([A-Za-z0-9_-]+)" class-name)
                          second
                          str/lower-case)]
    (case language
      ("clj" "clojure" "edn") "clojure"
      ("sql") "text/x-sql"
      ("sh" "shell" "bash" "zsh") "shell"
      nil)))

(defn highlight-docs-code-blocks!
  [host]
  (when-let [^js run-mode (some-> js/window .-CodeMirror .-runMode)]
    (doseq [^js code-node (array-seq (.querySelectorAll host "pre code"))]
      (let [source (or (.-textContent code-node) "")
            pre-node (.-parentNode code-node)
            mode (docs-code-mode code-node)]
        (when mode
          (.add (.-classList code-node) "cm-s-default")
          (when pre-node
            (.add (.-classList pre-node) "cm-s-default"))
          (set! (.-innerHTML code-node) "")
          (run-mode source mode code-node))))))

(defn ensure-editor!
  [{:keys [editor-key host-id text editable? on-change language class-names]}]
  (if-let [host (.getElementById js/document host-id)]
    (let [{:keys [view host-node]} (get @*editors editor-key)]
      (if (or (nil? view)
              (not= host-node host)
              (not (attached-to-host? view host)))
        (do
          (destroy-editor! editor-key)
          (let [textarea (.createElement js/document "textarea")
                _ (.appendChild host textarea)
                ^js view (.fromTextArea js/CodeMirror
                                        textarea
                                        #js {:value text
                                             :mode (case language
                                                     :sql "text/x-sql"
                                                     :clojure "clojure"
                                                     nil)
                                             :lineNumbers true
                                             :lineWrapping true
                                             :readOnly (if editable? false "nocursor")})]
            (.setValue view text)
            (when on-change
              (.on view "change"
                   (fn [^js cm _change]
                     (on-change (.getValue cm)))))
            (doseq [class-name (cond
                                 (string? class-names) [class-names]
                                 (sequential? class-names) class-names
                                 :else [])]
              (.add (.-classList (.getWrapperElement view)) class-name))
            (swap! *editors assoc editor-key {:view view
                                              :host-node host})))
        (replace-editor-doc! view text)))
    (destroy-editor! editor-key)))

(defn sync-docs-content!
  [selected-doc-slug]
  (when-let [host (.getElementById js/document "docs-content")]
    (let [page (doc-by-slug selected-doc-slug)
          markdown (or (:markdown page) "")
          html (if-let [marked (.-marked js/window)]
                 (.parse marked markdown)
                 markdown)
          route {:page :docs
                 :doc-slug selected-doc-slug}
          title (str (:title page) " · bisql Docs")]
      (set! (.-innerHTML host) html)
      (when-let [^js heading (.querySelector host "h1")]
        (.add (.-classList heading) "docs-title-row")
        (let [share-link (.createElement js/document "a")
              icon (.createElement js/document "img")]
          (.add (.-classList share-link) "title-share-link")
          (set! (.-href share-link) (share-x-url {:title title
                                                  :route route}))
          (set! (.-target share-link) "_blank")
          (set! (.-rel share-link) "noreferrer")
          (set! (.-ariaLabel share-link) "Share on X")
          (set! (.-src icon) (str (root-path) "img/share/twitter-x.svg"))
          (set! (.-alt icon) "X")
          (.appendChild share-link icon)
          (.appendChild heading share-link)))
      (highlight-docs-code-blocks! host))))

(defn clear-docs-content!
  []
  (when-let [host (.getElementById js/document "docs-content")]
    (set! (.-innerHTML host) "")))

(defn panel
  [title & body]
  (into [:div.panel
         [:div.panel-header title]]
        body))

(defn example-link
  [{:keys [id title]} selected-id]
  [:button.sidebar-link
   {:type "button"
    :value id
    :data-active (= id selected-id)
    :on {:click [:select-example]}}
   [:span.sidebar-title title]])

(defn example-group
  [{:keys [id title examples]} selected-id]
  [:section.sidebar-group
   {:replicant/key id}
   [:div.sidebar-group-title title]
   [:div.sidebar-group-links
    (map #(example-link % selected-id) examples)]])

(defn doc-link
  [{:keys [slug title]} selected-slug]
  [:button.sidebar-link
   {:type "button"
    :value slug
    :data-active (= slug selected-slug)
    :on {:click [:select-doc]}}
   [:span.sidebar-title title]])

(defn docs-sidebar
  [selected-doc-slug sidebar-open?]
  [:aside.sidebar.panel
   [:div.panel-header "Docs"]
   [:button.sidebar-toggle
    {:type "button"
     :data-open sidebar-open?
     :on {:click [:toggle-sidebar]}}
   (if sidebar-open? "Hide docs" "Show docs")]
   [:div.sidebar-body
    {:data-open sidebar-open?}
    [:div.sidebar-list
     [:section.sidebar-group
      {:replicant/key "docs-group"}
      [:div.sidebar-group-title "Pages"]
      [:div.sidebar-group-links
       (map #(doc-link % selected-doc-slug) (doc-pages))]]]]])

(defn examples-sidebar
  [catalog selected-example-id sidebar-open?]
  [:aside.sidebar.panel
   [:div.panel-header "Examples"]
   [:button.sidebar-toggle
    {:type "button"
     :data-open sidebar-open?
     :on {:click [:toggle-sidebar]}}
    (if sidebar-open? "Hide examples" "Show examples")]
   [:div.sidebar-body
    {:data-open sidebar-open?}
    (if catalog
      [:div.sidebar-list
       (map #(example-group % selected-example-id) (:groups catalog))]
      [:div.sidebar-loading "Loading examples..."])]])

(defn site-header
  [{:keys [route-page selected-doc-slug selected-example-id]}]
  [:header.site-header
   [:div.site-brand
    [:img.site-logo {:src (str (root-path) "img/bicycle.svg")
                     :alt "bisql"}]
    [:div.site-brand-copy
     [:a.site-brand-link
      {:href (route-url {:page :docs
                         :doc-slug (or selected-doc-slug (first-doc-slug))})}
      [:div.site-brand-name "bisql"]]
     [:span.site-brand-tagline "2-way-SQL toolkit for Clojure"]]]
   [:nav.site-nav
    [:a.site-link {:href (route-url {:page :docs
                                     :doc-slug (or selected-doc-slug (first-doc-slug))})
                   :data-active (= route-page :docs)}
     "Docs"]
    [:a.site-link {:href (route-url {:page :playground
                                     :example-id (or selected-example-id (first-example-id))})
                   :data-active (= route-page :playground)}
     "Playground"]
    [:a.site-link
     {:href github-url
      :target "_blank"
     :rel "noreferrer"}
     "GitHub"]]])

(defn pager-nav
  [{:keys [previous next previous-value next-value action]}]
  [:nav.toolbar-nav
   (if previous
     [:button.toolbar-nav-link
      {:type "button"
       :value previous-value
       :on {:click [action]}}
      "Prev"]
     [:button.toolbar-nav-link.toolbar-nav-link-placeholder
      {:type "button"
       :tab-index -1
       :disabled true
       :aria-hidden "true"}
      "Prev"])
   (if next
     [:button.toolbar-nav-link
      {:type "button"
       :value next-value
       :on {:click [action]}}
      "Next"]
     [:div.toolbar-nav-link.toolbar-nav-link-finished
      {:role "note"
       :aria-label "Reached the last page"}
      [:span.toolbar-nav-finished-line.toolbar-nav-finished-line-top
       "Thanks for "]
      [:span.toolbar-nav-finished-line.toolbar-nav-finished-line-bottom
       " your reading"]])])

(defn share-x-url
  [{:keys [title route]}]
  (let [url (absolute-route-url route)
        encoded-text (js/encodeURIComponent (str title "\n" url))]
    (str "https://twitter.com/intent/tweet?text=" encoded-text)))

(defn share-x-link
  [{:keys [title route class-name]}]
  [:a.title-share-link
   {:href (share-x-url {:title title
                        :route route})
    :class class-name
    :target "_blank"
    :rel "noreferrer"
    :aria-label "Share on X"}
   [:img {:src (str (root-path) "img/share/twitter-x.svg")
          :alt "X"}]])

(defn page-actions
  [{:keys [previous next previous-value next-value action]}]
  [:div.page-actions
   (pager-nav {:previous previous
               :next next
               :previous-value previous-value
               :next-value next-value
               :action action})])

(defn render-docs-page
  [selected-doc-slug sidebar-open?]
  (let [{:keys [previous next]} (neighboring-docs selected-doc-slug)]
    [:div.docs-layout {:replicant/key "docs-page"}
     (docs-sidebar selected-doc-slug sidebar-open?)
     [:div.content-column
     [:section.panel.toolbar-panel
      [:div.panel-header-row
       (page-actions {:previous previous
                      :next next
                      :previous-value (:slug previous)
                      :next-value (:slug next)
                      :action :select-doc})]]
      [:section.panel.docs-panel
       [:div.docs-markdown {:id "docs-content"}]]
      [:div.bottom-nav
       (page-actions {:previous previous
                      :next next
                      :previous-value (:slug previous)
                      :next-value (:slug next)
                      :action :select-doc})]]]))

(defn render-playground-page
  [{:keys [catalog
           selected-example-id
           sidebar-open?
           example-title
           example-description
           page-error]}]
    (let [{:keys [previous next]} (neighboring-examples catalog selected-example-id)
        description-block (into [:div.summary-description]
                                (description-nodes example-description))
        route {:page :playground
               :example-id selected-example-id}
        title (str example-title " · bisql Playground")]
    [:div.docs-layout {:replicant/key "playground-page"}
     (examples-sidebar catalog selected-example-id sidebar-open?)
     [:div.content-column
      [:section.panel.toolbar-panel
      [:div.panel-header-row
       (page-actions {:previous previous
                      :next next
                      :previous-value (:id previous)
                      :next-value (:id next)
                      :action :select-example})]]

      [:section.summary
       [:div.summary-title-row
        [:h1 example-title]
        (share-x-link {:title title
                       :route route})]
       (into [:div.summary-body]
             (concat
              [description-block]
              [[:button {:type "button"
                         :on {:click [:render]}}
                "Render"]]))]

      (when (seq page-error)
        [:section.panel.error-panel
         [:div.panel-header "Page errors"]
         [:pre page-error]])

      [:section.workspace
       (panel
        "Input SQL Template"
        [:div.cm-host {:id "sql-editor"}])
       (panel
        "Input params"
        [:div.cm-host {:id "params-editor"}])]

      [:section.workspace.output-grid
       [:div.output-sql-column
        (panel
         "Output SQL"
         [:div.cm-host {:id "sql-output"}])]
       [:div.output-side-column
        (panel
         "Output data"
         [:div.cm-host {:id "data-output"}])
        [:section.panel.error-panel
         [:div.panel-header "Errors"]
         [:div.cm-host {:id "error-output"}]]]]
      [:div.bottom-nav
       (page-actions {:previous previous
                      :next next
                      :previous-value (:id previous)
                      :next-value (:id next)
                      :action :select-example})]]]))

(defn render-app
  [{:keys [route-page selected-doc-slug sidebar-open?] :as state}]
  [:main.page
   (site-header state)
   (case route-page
     :docs (render-docs-page selected-doc-slug sidebar-open?)
     :playground (render-playground-page state)
     (render-docs-page selected-doc-slug sidebar-open?))])

(defn rerender!
  []
  (when-let [root @*root]
    (r/render root (render-app @*state))
    (let [{:keys [route-page
                  selected-doc-slug
                  sql-template
                  params-edn
                  output-sql
                  output-data
                  output-error]} @*state]
      (case route-page
        :docs
        (do
          (destroy-all-editors!)
          (sync-docs-content! selected-doc-slug))

        :playground
        (do
          (clear-docs-content!)
          (ensure-editor! {:editor-key :sql-input
                           :host-id "sql-editor"
                           :text sql-template
                           :editable? true
                           :language :sql
                           :class-names ["cm-input"]
                           :on-change #(swap! *state assoc :sql-template %)})
          (ensure-editor! {:editor-key :params-input
                           :host-id "params-editor"
                           :text params-edn
                           :editable? true
                           :language :clojure
                           :class-names ["cm-input"]
                           :on-change #(swap! *state assoc :params-edn %)})
          (ensure-editor! {:editor-key :sql-output
                           :host-id "sql-output"
                           :text output-sql
                           :editable? false
                           :language :sql
                           :class-names ["cm-output"]})
          (ensure-editor! {:editor-key :data-output
                           :host-id "data-output"
                           :text output-data
                           :editable? false
                           :language :clojure
                           :class-names ["cm-output"]})
          (ensure-editor! {:editor-key :error-output
                           :host-id "error-output"
                           :text output-error
                           :editable? false
                           :language :clojure
                           :class-names ["cm-output" "cm-error-output"]}))

        nil))))

(defn dispatch!
  [event-data handler]
  (let [dom-event (:replicant/dom-event event-data)
        target-value (some-> dom-event .-target .-value)
        actions (cond
                  (and (vector? handler)
                       (keyword? (first handler))) [handler]
                  (sequential? handler) handler
                  :else [handler])]
    (doseq [[action & _args] actions]
      (case action
        :select-doc
        (navigate! {:page :docs
                    :doc-slug target-value})

        :select-example
        (navigate! {:page :playground
                    :example-id target-value})

        :toggle-sidebar
        (do
          (swap! *state update :sidebar-open? not)
          (rerender!))

        :render
        (do
          (render-current!)
          (rerender!))

        nil))))

(defn ^:export main
  []
  (reset! *root (.getElementById js/document "app"))
  (r/set-dispatch! dispatch!)
  (.addEventListener js/window "popstate" sync-route-from-location!)
  (sync-route-from-location!))
