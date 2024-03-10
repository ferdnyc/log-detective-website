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
     on-click-delete-snippet]]
   [app.contribute-events :refer
    [on-how-to-fix-textarea-change
     on-change-fas
     on-change-fail-reason
     on-accordion-item-show]]))

(def files (r/atom nil))
(def error-description (r/atom nil))
(def error-title (r/atom nil))
(def status (r/atom nil))
(def fas (r/atom nil))

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
                     (reset!
                      files
                      (vec (map (fn [log]
                                  ;; We must html encode all HTML characters
                                  ;; because we are going to render the log
                                  ;; files dangerously
                                  (update log :content #(.encode html-entities %)))
                                (vals (:logs data)))))

                     ;; TODO I don't want to use reduce here
                     (reduce (fn [x _]
                               (add-snippet-from-backend-map
                                files
                                active-file
                                x))
                             (flatten (map (fn [x] (:snippets x)) @files))))))))))

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

(defn buttons []
  [[:button {:type "button"
             :class "btn btn-outline-primary"
             :on-click nil}
    "+1"]
   [:button {:type "button"
             :class "btn btn-outline-danger"
             :on-click nil}
    "-1"]])

(defn snippet [text]
  {:title "Snippet"
   :body
   [:textarea
    {:class "form-control"
     :rows "3"
     :placeholder "What makes this snippet relevant?"
     :value text
     :on-change #(on-snippet-textarea-change %)}]
   :buttons (buttons)})

(defn card [title text]
  [:div {:class "card review-card"}
   [:div {:class "card-body"}
    [:h6 {:class "card-title"} title]
    [:textarea {:class "form-control" :rows 3
                :value text
                :placeholder "Please describe what caused the build to fail."
                :on-change #(on-change-fail-reason %)}]
    [:div {:class "btn-group"}
     (into [:<>] (buttons))]]])

(defn right-column []
  [:<>
    [:label {:class "form-label"} "Your FAS username:"]
    [:input {:type "text"
             :class "form-control"
             :placeholder "Optional - Your FAS username"
             :value (or @fas (.getItem js/localStorage "fas"))
             :on-change #(on-change-fas %)}]

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
    (vec (map (fn [x] (snippet (:comment x))) @snippets)))

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
    "Here is some explanation why the build failed")

   [:br]
   (card
    "How to fix the issue?"
    "Here is some explanation how to fix the issue")

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
