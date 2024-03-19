(ns app.review.core
  ;; This namespace is WIP and currently not working. Ignore lint errors
  {:clj-kondo/config '{:linters {:unused-namespace {:level :off}}}}

  (:require
   [reagent.core :as r]
   [html-entities :as html-entities]
   [lambdaisland.fetch :as fetch]
   [ajax.core :refer [GET]]
   [malli.core :as m]
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


;; TODO Move somewhere
(defn index-of-file [name]
  (.indexOf (map (fn [x] (:name x)) @files) name))


(defn on-accordion-item-show [^js/Event event]
  (let [snippet-id (int (.-indexNumber (.-dataset (.-target event))))
        snippet (nth @snippets snippet-id)
        file-name (:file snippet)]
    (reset! active-file (index-of-file file-name))))

(def InputSchema
  (let [File [:map [:name :string] [:content :string]]]
    [:map {:closed true}
     [:username [:maybe :string]] ;; TODO We don't want username from backend
     [:fail_reason :string]
     [:how_to_fix :string]
     [:container_file [:maybe File]]
     [:spec_file File]
     [:logs [:map-of :any File]]]))


(defn handle-validated-backend-data [data]
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

  ;; Parse snippets from backend and store them to @snippets
  (doall (for [file @files
               :let [file-index (index-of-file (:name file))]]
           (doall (for [snippet (:snippets file)]
                    (add-snippet-from-backend-map
                     @files
                     file-index
                     snippet)))))

  ;; Highlight snippets in the log text
  (doall (for [snippet @snippets
               :let [file-index (index-of-file (:file snippet))
                     content (:content (get @files file-index))
                     content (highlight-snippet-in-text content snippet)]]
           (reset! files (assoc-in @files [file-index :content] content)))))

(defn handle-backend-error [title description]
  (reset! status "error")
  (reset! error-title title)
  (reset! error-description description))

(defn init-data-review []
  (GET (str "/frontend" (remove-trailing-slash (current-path)) "/random")
       :response-format :json
       :keywords? true

       :error-handler
       (fn [error]
         (handle-backend-error
          (:error (:response error))
          (:description (:response error))))

       :handler
       (fn [data]
         (if (m/validate InputSchema data)
           (handle-validated-backend-data data)
           (handle-backend-error
            "Invalid data"
            "Got invalid data from the backend. This is likely a bug.")))))

(defn left-column []
  (instructions
   [(instructions-item
     true
     "We fetched a random sample from our collected data set")

    (instructions-item
     (>= (count (dissoc @votes :how-to-fix :fail-reason)) (count @snippets))
     "Go through all snippets and either upvote or downvote them")

    (instructions-item
     (not= (:fail-reason @votes) 0)
     "Is the explanation why did the build fail correct?")

    (instructions-item
     (not= (:how-to-fix @votes) 0)
     "Is the explanation how to fix the issue correct?")

    (instructions-item nil "Submit")]))

(defn middle-column []
  (editor @files))

(defn vote [key value]
  (reset! votes (assoc @votes key value)))

(defn on-vote-button-click [key value]
  (let [current-value (key @votes)
        value (if (= value current-value) 0 value)]
    (vote key value)))

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
                       (vote (keyword name) 1))}]
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
                                (vote (keyword name) 1))}]
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
  ;; The js/document is too general, ideally we would like to limit this
  ;; only to #accordionItems but it doesn't exist soon enough
  (.addEventListener js/document "show.bs.collapse" on-accordion-item-show)

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
