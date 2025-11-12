(ns brepl.lib.validator
  "Validates Clojure code syntax using edamame parser."
  (:require [edamame.core :as edamame]
            [clojure.java.shell :as shell]
            [clojure.string :as str]))

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
   Parses ALL forms to catch both missing and extra delimiters.
   Supports all Clojure reader macros (regex, deref, var-quote, etc.)."
  [content]
  (try
    ;; Parse all forms with full reader macro support including reader conditionals
    ;; Use :auto-resolve identity to accept :: keywords as valid syntax
    (edamame/parse-string-all content {:all true
                                       :read-cond :allow
                                       :auto-resolve identity})
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

(defn- parinfer-available?
  "Check if parinfer-rust is available on the system."
  []
  (= 0 (:exit (shell/sh "which" "parinfer-rust"))))

(defn auto-fix-brackets
  "Attempt to auto-fix bracket errors using parinfer-rust if available.
   Returns fixed content if successful, or nil if unable to fix."
  [content]
  (when (parinfer-available?)
    (let [result (shell/sh "parinfer-rust" "--mode" "smart" :in content)]
      (when (= 0 (:exit result))
        (let [fixed (str/trim-newline (:out result))]
          ;; Verify the fix actually resolves the error
          (when (nil? (delimiter-error? fixed))
            fixed))))))

(defn clojure-file?
  "Check if file path has a Clojure file extension."
  [file-path]
  (let [ext (-> file-path
                (clojure.string/split #"\.")
                last
                clojure.string/lower-case)]
    (contains? #{"clj" "cljs" "cljc" "cljx"} ext)))
