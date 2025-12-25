;; Add parmezan dependency at runtime
(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {io.github.borkdude/parmezan {:git/sha "a10b6c9019e7b01fe31045929505fe7bd5f2b468"}}})

(ns brepl.lib.validator
  "Validates and fixes Clojure code syntax using parmezan."
  (:require [borkdude.parmezan :as parmezan]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defn auto-fix-brackets
  "Attempt to auto-fix bracket errors using parmezan.
   Returns fixed content if successful, or nil if unable to fix."
  [content]
  (try
    (parmezan/parmezan content)
    (catch Exception e
      nil)))

(defn- has-bb-shebang-from-file?
  "Check if file starts with a Babashka shebang by reading only first line."
  [file-path]
  (try
    (with-open [rdr (io/reader file-path)]
      (when-let [first-line (.readLine rdr)]
        (and (str/starts-with? first-line "#!")
             (str/includes? first-line "bb"))))
    (catch Exception _
      false)))

(defn- has-bb-shebang-from-string?
  "Check if string content starts with a Babashka shebang."
  [content]
  (when content
    (let [first-line (first (str/split-lines content))]
      (and first-line
           (str/starts-with? first-line "#!")
           (str/includes? first-line "bb")))))

(defn clojure-file?
  "Check if file is a Clojure/Babashka source file.
   Checks file extension and optionally checks for shebang.

   With one arg (file-path): checks extension, then reads first line if needed.
   With two args (file-path content): checks extension, then checks content string."
  ([file-path]
   (let [ext (-> file-path
                (str/split #"\.")
                 last
                 str/lower-case)]
     (or (contains? #{"clj" "cljs" "cljc" "cljx" "bb"} ext)
         (has-bb-shebang-from-file? file-path))))
  ([file-path content]
   (let [ext (-> file-path
                (str/split #"\.")
                 last
                 str/lower-case)]
     (or (contains? #{"clj" "cljs" "cljc" "cljx" "bb"} ext)
         (has-bb-shebang-from-string? content)))))
