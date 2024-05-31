(ns app.components.snippets
  (:require
   [reagent.core :as r]
   [reagent.dom.server :refer [render-to-string]]
   [app.helpers :refer [previous-siblings]]))

(def snippets (r/atom []))

(defn clear-selection []
  ;; Generated from
  ;; https://stackoverflow.com/a/13415236/3285282
  (cond
    (.-getSelection js/window) (.removeAllRanges (.getSelection js/window))
    (.-selection js/document) (.empty (.-selection js/document))
    :else nil))

(defn selection-contains-snippets? []
  (let [selection (.getSelection js/window)
        spans (.getElementsByClassName js/document "snippet")]
    (when (not-empty spans)
      (some (fn [span] (.containsNode selection span true))
            spans))))

(defn highlight-current-snippet []
  ;; The implementation heavily relies on JavaScript interop. I took the
  ;; "Best Solution" code from:
  ;; https://itecnote.com/tecnote/javascript-selected-text-highlighting-prob/
  ;; and translated it from Javascript to ClojureScript using:
  ;; https://roman01la.github.io/javascript-to-clojurescript/
  (let [rangee (.getRangeAt (.getSelection js/window) 0)
        span (.createElement js/document "span")]
    (set! (.-className span) "snippet")
    (set! (.-id span) (str "snippet-" (count @snippets)))
    (set! (.-index-number (.-dataset span)) (count @snippets))
    (.appendChild span (.extractContents rangee))
    (.insertNode rangee span)))

;; TODO Rename ... this doesn't highlight snippets in text, this makes the whole text a snippet
(defn highlight-snippet-in-text [id text comment]
   (render-to-string
    [:span {:class "snippet"
            :id id
            :title comment
            :dangerouslySetInnerHTML
            {:__html text}}]))

(defn selection-node-id []
  (let [base (.-anchorNode (.getSelection js/window))]
    (if base (.-id (.-parentNode base)) nil)))

(defn add-snippet [files active-file]
  ;; The `files` and `active-file` parameters needs to be passed as atom
  ;; references, not their dereferenced value
  (when (and (= (selection-node-id) "log")
             (not (selection-contains-snippets?)))
    (highlight-current-snippet)

    ;; Save the log with highlights, so they are remembered when switching
    ;; between file tabs
    (let [log (.-innerHTML (.getElementById js/document "log"))]
      (reset! files (assoc-in @files [@active-file :content] log)))

    (let [selection (.getSelection js/window)
          content (.toString selection)

          ;; The position is calculated from the end of the last node
          ;; This can be be either a previous snippet span or if the text
          ;; longer than 65536 characters than it is implictily split into
          ;; multiple sibling text nodes
          start (.-anchorOffset selection)

          ;; Calculate the real starting index from the beginning of the log
          offset (->> selection
                      .-anchorNode
                      previous-siblings
                      (map #(.-textContent %))
                      (map #(count %))
                      (reduce +))
          start (+ start offset)

          ;; Index of the last snippet character. When parsing in python, don't
          ;; forget to do text[start:end+1]
          end (+ start (count content) -1)

          snippet
          {:text content
           :start-index start
           :end-index end
           :comment nil
           :file (:name (get @files @active-file))}]
      (swap! snippets conj snippet))
    (clear-selection)))

(defn add-snippet-from-backend-map [files file-index map]
  (let [snippet
        {:text nil
         :start-index (:start_index map)
         :end-index (:end_index map)
         :comment (:user_comment map)
         :file (:name (get files file-index))}]
    (swap! snippets conj snippet)))

;; For some reason, compiler complains it cannot infer type of the `target`
;; variable, so I am specifying it as a workaround
(defn on-snippet-textarea-change [^js/Event event]
  (let [target (.-target event)
        index (int (.-indexNumber (.-dataset (.-parentElement target))))
        value (.-value target)]
    (reset! snippets (assoc-in @snippets [index :comment] value))))

(defn on-click-delete-snippet [^js/Event event]
  (let [target (.-target event)
        snippet-id (int (.-indexNumber (.-dataset (.-parentElement target))))]
    ;; We don't want to remove the element entirely because we want to preserve
    ;; snippet numbers that follows
    (swap! snippets assoc snippet-id nil)

    ;; Remove the highlight
    (let [span (.getElementById js/document (str "snippet-" snippet-id))
          text (.-innerHTML span)]
      (.replaceWith span text))))
