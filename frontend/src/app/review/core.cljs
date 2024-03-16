(ns app.review.core
  ;; This namespace is WIP and currently not working. Ignore lint errors
  {:clj-kondo/config '{:linters {:unused-namespace {:level :off}}}}

  (:require
   [reagent.core :as r]
   [html-entities :as html-entities]
   [lambdaisland.fetch :as fetch]
   [app.helpers :refer
    [current-path
     remove-trailing-slash]]
   [app.editor.core :refer [editor active-file]]
   [app.components.accordion :refer [accordion]]
   [app.components.jumbotron :refer
    [render-error
     loading-screen
     render-succeeded]]
   [app.three-column-layout.core :refer
    [three-column-layout
     instructions-item
     instructions]]
   [app.components.snippets :refer
    [snippets
     add-snippet
     add-snippet-from-backend-map
     on-snippet-textarea-change
     highlight-snippet-in-text]]))

(def files (r/atom nil))
(def error-description (r/atom nil))
(def error-title (r/atom nil))
(def status (r/atom nil))
(def form
  (r/atom {:fas nil
           :how-to-fix nil
           :fail-reason nil}))

(def votes
  (r/atom {:how-to-fix 0
           :fail-reason 0}))

(defn init-data-review []
  (let [url (str "/frontend" (remove-trailing-slash (current-path)) "/random")]
    (-> (fetch/get url {:accept :json :content-type :json})
        (.then (fn [resp]
                 (-> resp :body (js->clj :keywordize-keys true))))
        (.then (fn [data]
                 (if (:error data)
                   (do
                     (reset! status "error")
                     (reset! error-title (:error data))
                     (reset! error-description (:description data)))
                   (do
                     ;; (swap! form (update))
                     (reset! form (assoc @form :how-to-fix (:how_to_fix data)))
                     (reset! form (assoc @form :fail-reason (:fail_reason data)))

                     (reset!
                      files
                      (vec (map (fn [log]
                                  ;; We must html encode all HTML characters
                                  ;; because we are going to render the log
                                  ;; files dangerously
                                  (update log :content #(.encode html-entities %)))
                                (vals (:logs data)))))

                     (doall (for [file @files
                                  :let [file-index (.indexOf (map (fn [x] (:name x)) @files) "backend.log")]]
                              (doall (for [snippet (:snippets file)]
                                       (add-snippet-from-backend-map
                                        files
                                        file-index
                                        snippet)))))

                   )))))))

(defn left-column []
  (instructions
   [(instructions-item
     true
     "We fetched a random sample from our collected data set")

    (instructions-item
     nil
     "Go through all snippets and either upvote or downvote them")

    (instructions-item
     nil
     "Is the explanation why did the build fail correct?")

    (instructions-item
     nil
     "Is the explanation how to fix the issue correct?")

    (instructions-item nil "Submit")]))

(defn middle-column []
  (editor @files))

(defn on-vote-button-click [key value]
  (let [current-value (key @votes)
        value (if (= value current-value) 0 value)]
    (reset! votes (assoc @votes key value))))

(defn buttons [name]
  (let [key (keyword name)]
    [[:button {:type "button"
               :class ["btn btn-vote" (if (> (key @votes) 0)
                               "btn-primary"
                               "btn-outline-primary")]
               :on-click #(on-vote-button-click key 1)}
      "+1"]
     [:button {:type "button"
               :class ["btn btn-vote" (if (< (key @votes) 0)
                               "btn-danger"
                               "btn-outline-danger")]
               :on-click #(on-vote-button-click key -1)}
      "-1"]]))

(defn snippet [text index]
  (let [name (str "snippet-" index)]
    {:title "Snippet"
     :body
     [:textarea
      {:class "form-control"
       :rows "3"
       :placeholder "What makes this snippet relevant?"
       :value text
       :on-change #(do (on-snippet-textarea-change %)
                       (on-vote-button-click (keyword name) 1))}]
     :buttons (buttons name)}))

(defn on-change-form-input [event]
  (let [target (.-target event)
        key (keyword (.-name target))
        value (.-value target)]
    (reset! form (assoc @form key value))))

(defn card [title text name placeholder]
  [:div {:class "card review-card"}
   [:div {:class "card-body"}
    [:h6 {:class "card-title"} title]
    [:textarea {:class "form-control" :rows 3
                :value text
                :placeholder placeholder
                :name name
                :on-change #(do (on-change-form-input %)
                                (on-vote-button-click (keyword name) 1))}]
    [:div {:class "btn-group"}
     (into [:<>] (buttons name))]]])

(defn right-column []
  [:<>
    [:label {:class "form-label"} "Your FAS username:"]
    [:input {:type "text"
             :class "form-control"
             :placeholder "Optional - Your FAS username"
             :value (or (:fas @form) (.getItem js/localStorage "fas"))
             :name "fas"
             :on-change #(on-change-form-input %)}]

   [:label {:class "form-label"} "Interesting snippets:"]
   (when (not-empty @snippets)
     [:div {}
      [:button {:class "btn btn-secondary btn-lg"
                :on-click #(add-snippet files active-file)} "Add"]
      [:br]
      [:br]])

   ;; TODO When clicking any of the accordion items, the snippet should display
   ;; in the middle column log file. That will be the only currently highlighted
   ;; snippet, so that it is easily understandable.
   (accordion
    "accordionItems"
    (vec (map-indexed (fn [i x] (snippet (:comment x) i)) @snippets)))

   (when (empty? @snippets)
     [:div {:class "card" :id "no-snippets"}
      [:div {:class "card-body"}
       [:h5 {:class "card-title"} "No snippets yet"]
       [:p {:class "card-text"}
        (str "Please select interesting parts of the log files and press the "
             "'Add' button to annotate them")]
       [:button {:class "btn btn-secondary btn-lg"
                 :on-click #(add-snippet files active-file)}
        "Add"]]])

   [:br]
   (card
    "Why did the build fail?"
    (:fail-reason @form)
    "fail-reason"
    "Please describe what caused the build to fail.")

   [:br]
   (card
    "How to fix the issue?"
    (:how-to-fix @form)
    "how-to-fix"
    (str "Please describe how to fix the issue in "
         "order for the build to succeed."))

   [:div {}
    [:label {:class "form-label"} "Ready to submit the results?"]
    [:br]
    [:button {:type "submit"
              :class "btn btn-primary btn-lg"
              :on-click nil}
     "Submit"]]])

(defn review []
  (cond
    (= @status "error")
    (render-error @error-title @error-description)

    (= @status "submitted")
    (render-succeeded)

    @files
    (three-column-layout
     (left-column)
     (middle-column)
     (right-column))

    :else
    (loading-screen "Please wait, fetching logs from our dataset.")))
