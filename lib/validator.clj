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
      (and expected line col)
      (str msg " at line " line ", column " col)

      expected
      (str msg " (expected: " expected ")")

      :else msg)))

(defn delimiter-error?
  "Parse content with edamame and return error info if delimiters are invalid.
   Returns nil if valid, or a map with error details if invalid."
  [content]
  (try
    (edamame/parse-string content)
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
  "Attempt to auto-fix unclosed bracket errors recursively.
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

        (:expected-delimiter error)
        ;; Try appending the expected delimiter and recurse
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
