(ns brepl.lib.validator
  "Validates Clojure code syntax using edamame parser."
  (:require [edamame.core :as edamame]))

(defn format-error-message
  "Format a detailed error message from delimiter error data."
  [error file-path]
  (let [msg (:message error)
        expected (:expected-delimiter error)
        line (:line error)
        col (:column error)]
    (cond
      ;; Unmatched delimiter means extra closing bracket
      (clojure.string/includes? msg "Unmatched delimiter")
      msg

      (and expected line col)
      (str msg " at line " line ", column " col)

      expected
      (str msg)

      :else msg)))

(defn delimiter-error?
  "Parse content with edamame and return error info if delimiters are invalid.
   Returns nil if valid, or a map with error details if invalid.
   Parses ALL forms to catch both missing and extra delimiters."
  [content]
  (try
    ;; Parse all forms, not just the first one
    (edamame/parse-string-all content)
    nil
    (catch Exception e
      (let [msg (ex-message e)
            data (ex-data e)]
        {:type :delimiter-error
         :message msg
         :line (:line data)
         :column (:column data)
         :expected-delimiter (:edamame/expected-delimiter data)
         :opened-delimiter (:edamame/opened-delimiter data)
         :opened-loc (:edamame/opened-delimiter-loc data)}))))

(defn auto-fix-brackets
  "Attempt to auto-fix bracket errors recursively.
   Handles both missing brackets (append) and extra brackets (remove from end).
   Does NOT fix string literals - too complex to insert quotes at correct position.
   Returns fixed content if successful, or nil if unable to fix."
  [content]
  (loop [current content
         attempts 0]
    (let [error (delimiter-error? current)]
      (cond
        (nil? error)
        ;; No error, we're done
        current

        (>= attempts 10)
        ;; Give up after 10 attempts to prevent infinite loops
        nil

        ;; Don't try to fix string delimiters - requires inserting at correct position
        (= "\"" (:expected-delimiter error))
        nil

        ;; Unmatched delimiter - try removing from end
        (and (clojure.string/includes? (:message error) "Unmatched delimiter")
             (> (count current) 0))
        (recur (subs current 0 (dec (count current)))
               (inc attempts))

        ;; Missing bracket/brace/paren - append expected
        (and (:expected-delimiter error)
             (not= "" (:expected-delimiter error)))
        (recur (str current (:expected-delimiter error))
               (inc attempts))

        :else
        ;; Can't fix this error
        nil))))

(defn clojure-file?
  "Check if file path has a Clojure file extension."
  [file-path]
  (let [ext (-> file-path
                (clojure.string/split #"\.")
                last
                clojure.string/lower-case)]
    (contains? #{"clj" "cljs" "cljc" "cljx"} ext)))
