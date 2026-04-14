(ns bisql.pages.playground
  (:require [bisql.engine :as engine]
            [bisql.pages.examples :as examples]
            [cljs.pprint :as pprint]
            [cljs.reader :as reader]
            [clojure.string :as str]
            [replicant.dom :as r]))

(defonce *state
  (atom {:catalog nil
         :selected-example-id nil
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

(defn pprint-str
  [x]
  (with-out-str
    (pprint/pprint x)))

(defn flatten-examples
  [catalog]
  (mapcat :examples (:groups catalog)))

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
    (map? x) (into (empty x) (map (fn [[k v]] [k (normalize-edn-value v)])) x)
    (vector? x) (mapv normalize-edn-value x)
    (list? x) (apply list (map normalize-edn-value x))
    (seq? x) (doall (map normalize-edn-value x))
    :else x))

(defn resolve-sql-template
  [{:keys [kind sql-template]}]
  (case kind
    :direct-template
    sql-template

    :resource
    sql-template

    :resource-query
    sql-template))

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

(declare rerender!)

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

(defn render-app
  [{:keys [catalog
           selected-example-id
           example-title
           example-description
           page-error]}]
  (let [{:keys [previous next]} (neighboring-examples catalog selected-example-id)
        description-block (into [:div.summary-description]
                                (description-nodes example-description))]
    [:main.page
     [:header.hero
      [:p.eyebrow "bisql"]
      [:h1 "Playground"]
      [:p.lede
       "Edit a SQL template and EDN params, then render the final SQL and bind params in the browser."]]

     [:div.docs-layout
      [:aside.sidebar.panel
       [:div.panel-header "Examples"]
       (if catalog
         [:div.sidebar-list
          (map #(example-group % selected-example-id) (:groups catalog))]
         [:div.sidebar-loading "Loading examples..."])
       [:nav.doc-nav.doc-nav-sidebar
        (if previous
          [:button.doc-nav-link
           {:type "button"
            :value (:id previous)
            :on {:click [:select-example]}}
           [:span.doc-nav-label "Previous"]
           [:span.doc-nav-title (:title previous)]]
          [:div.doc-nav-spacer])
        (if next
          [:button.doc-nav-link
           {:type "button"
            :value (:id next)
            :on {:click [:select-example]}}
           [:span.doc-nav-label "Next"]
           [:span.doc-nav-title (:title next)]]
          [:div.doc-nav-spacer])]]

      [:div.content-column
       [:section.toolbar
        [:div.toolbar-copy
         [:span.toolbar-label "Selected example"]
         [:strong.toolbar-title example-title]]]

       [:section.summary
        [:h2 example-title]
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
          [:div.cm-host {:id "error-output"}]]]]]]]))

(defn rerender!
  []
  (when-let [root @*root]
    (r/render root (render-app @*state))
    (let [{:keys [sql-template
                  params-edn
                  output-sql
                  output-data
                  output-error]} @*state]
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
                       :class-names ["cm-output" "cm-error-output"]}))))

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
        :select-example
        (load-example! target-value)

        :render
        (do
          (render-current!)
          (rerender!))

        nil))))

(defn ^:export main
  []
  (reset! *root (.getElementById js/document "app"))
  (r/set-dispatch! dispatch!)
  (swap! *state assoc :catalog examples/catalog)
  (load-example! (-> examples/catalog :groups first :examples first :id)))
