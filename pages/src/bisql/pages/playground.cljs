(ns bisql.pages.playground
  (:require [bisql.engine :as engine]
            [cljs.pprint :as pprint]
            [cljs.reader :as reader]
            [replicant.dom :as r]))

(def examples
  [{:id "bind-values"
    :section "Variables"
    :title "Bind values"
    :description "`/*$ */` comments become bind variables. The sample value must be written immediately after the comment."
    :sql "SELECT * FROM users WHERE id = /*$id*/1"
    :params "{:id 42}"}
   {:id "default-value"
    :section "Variables"
    :title "DEFAULT sentinel"
    :description "`bisql/DEFAULT` is rendered as the SQL keyword `DEFAULT` instead of a bind parameter."
    :sql (str "INSERT INTO users (\n"
              "  email,\n"
              "  status\n"
              ")\n"
              "VALUES (\n"
              "  /*$email*/'alice@example.com',\n"
              "  /*$status*/'active'\n"
              ")")
    :params "{:email \"alice@example.com\"\n :status bisql/DEFAULT}"}
   {:id "all-value"
    :section "Variables"
    :title "ALL sentinel"
    :description "`bisql/ALL` is rendered as the SQL keyword `ALL`. This is useful in clauses such as `LIMIT ALL`."
    :sql "SELECT * FROM users ORDER BY id LIMIT /*$limit*/10"
    :params "{:limit bisql/ALL}"}
   {:id "inline-elseif"
    :section "Control flow"
    :title "Inline elseif and else"
    :description "`elseif` and `else` bodies can be written inline with `=>`."
    :sql (str "SELECT *\n"
              "FROM users\n"
              "WHERE 1 = 1\n"
              "/*%if active */\n"
              "AND status = 'active'\n"
              "/*%elseif pending => AND status = 'pending' */\n"
              "/*%else => AND status = 'inactive' */\n"
              "/*%end */")
    :params "{:active false\n :pending true}"}
   {:id "for-separating"
    :section "Control flow"
    :title "For with separating"
    :description "`for` loops can declare a separator with `separating`."
    :sql (str "UPDATE users\n"
              "SET\n"
              "/*%for item in items separating , */\n"
              "  /*!item.name*/column_name = /*$item.value*/'sample'\n"
              "/*%end */\n"
              "WHERE id = /*$id*/1")
    :params "{:id 42\n :items [{:name \"display_name\" :value \"Alice\"}\n          {:name \"status\" :value \"active\"}]}"}
   {:id "direct-template"
    :section "Direct templates"
    :title "Direct SQL template"
    :description "You can render an ad-hoc SQL template directly without loading a resource file."
    :sql "SELECT * FROM users WHERE email = /*$email*/'alice@example.com'"
    :params "{:email \"alice@example.com\"}"}])

(defonce *state
  (atom {:selected-example-id (:id (first examples))
         :example-title (:title (first examples))
         :example-description (:description (first examples))
         :sql-template (:sql (first examples))
         :params-edn (:params (first examples))
         :output-sql ""
         :output-data ""
         :output-error ""}))

(defonce *root (atom nil))

(defn pprint-str
  [x]
  (with-out-str
    (pprint/pprint x)))

(defn example-by-id
  [example-id]
  (some #(when (= example-id (:id %)) %) examples))

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
  (when-let [{:keys [title description sql params]} (example-by-id example-id)]
    (swap! *state assoc
           :selected-example-id example-id
           :example-title title
           :example-description description
           :sql-template sql
           :params-edn params)
    (render-current!)))

(declare render-app)

(defn rerender!
  []
  (when-let [root @*root]
    (r/render root (render-app @*state))))

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

        :set-sql-template
        (swap! *state assoc :sql-template target-value)

        :set-params-edn
        (swap! *state assoc :params-edn target-value)

        :render
        (render-current!)

        nil))
    (rerender!)))

(defn example-option
  [{:keys [id section title]} selected-id]
  [:option {:value id
            :selected (= id selected-id)}
   (str section " / " title)])

(defn panel
  [title & body]
  (into [:div.panel
         [:div.panel-header title]]
        body))

(defn render-app
  [{:keys [selected-example-id
           example-title
           example-description
           sql-template
           params-edn
           output-sql
           output-data
           output-error]}]
  [:main.page
   [:header.hero
    [:p.eyebrow "bisql"]
    [:h1 "Playground"]
    [:p.lede
     "Edit a SQL template and EDN params, then render the final SQL and bind params in the browser."]]

   [:section.toolbar
    [:label.field
     [:span "Example"]
     [:select {:value selected-example-id
               :on {:change [:select-example]}}
      (map #(example-option % selected-example-id) examples)]]
    [:button {:type "button"
              :on {:click [:render]}}
     "Render"]]

   [:section.summary
    [:h2 example-title]
    [:p example-description]]

   [:section.workspace
    (panel
     "SQL template"
     [:textarea {:spellcheck false
                 :value sql-template
                 :on {:input [:set-sql-template]}}])
    (panel
     "Params (EDN)"
     [:textarea {:spellcheck false
                 :value params-edn
                 :on {:input [:set-params-edn]}}])]

   [:section.workspace.output-grid
    (panel
     "Output SQL"
     [:pre output-sql])
    (panel
     "Output data"
     [:pre output-data])]

   [:section.panel.error-panel
    [:div.panel-header "Errors"]
    [:pre output-error]]])

(defn ^:export main
  []
  (reset! *root (.getElementById js/document "app"))
  (r/set-dispatch! dispatch!)
  (render-current!)
  (rerender!))
