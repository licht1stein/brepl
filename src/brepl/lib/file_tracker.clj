(ns brepl.lib.file-tracker
  "Track file changes across tool uses for automatic REPL reloading.
   Uses mtime (modification time) for O(n) stat calls instead of reading files."
  (:require [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.string :as str]))

(defn find-clojure-files
  "Find all Clojure files in the given directory (default: cwd).
   Excludes common non-source directories."
  ([] (find-clojure-files "."))
  ([dir]
   (let [excluded-dirs #{".git" "node_modules" "target" ".cpcache" ".clj-kondo" ".lsp"}]
     (->> (fs/glob dir "**/*.{clj,cljs,cljc,edn}")
          (remove (fn [path]
                    (some #(str/includes? (str path) (str "/" % "/"))
                          excluded-dirs)))
          (map str)
          (filter #(.isFile (io/file %)))
          vec))))

(defn file-mtime
  "Get file modification time in millis. Returns nil if file doesn't exist."
  [path]
  (when (fs/exists? path)
    (fs/file-time->millis (fs/last-modified-time path))))

(defn mtime-all-files
  "Get mtime for all given files. Returns map of path -> mtime."
  [file-paths]
  (->> file-paths
       (map (fn [path] [path (file-mtime path)]))
       (into {})))

(defn state-file
  "Get the state file path for a session."
  [session-id]
  (let [dir (io/file "/tmp" (str "brepl-tracker-" session-id))]
    (.mkdirs dir)
    (io/file dir "mtimes.edn")))

(defn save-mtimes
  "Save file mtimes for a session."
  [session-id mtimes]
  (spit (state-file session-id) (pr-str mtimes)))

(defn load-mtimes
  "Load saved file mtimes for a session."
  [session-id]
  (let [f (state-file session-id)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn detect-changes
  "Compare current mtimes with saved mtimes.
   Returns vector of changed file paths (modified or new files)."
  [session-id]
  (let [saved (load-mtimes session-id)]
    (when saved
      (let [current-files (find-clojure-files)
            current (mtime-all-files current-files)]
        (->> current
             (filter (fn [[path mtime]]
                       (let [old-mtime (get saved path)]
                         ;; Changed if: new file (no old-mtime) or mtime differs
                         (or (nil? old-mtime)
                             (not= old-mtime mtime)))))
             (map first)
             vec)))))

(defn snapshot!
  "Take a snapshot of all Clojure files in the project.
   Returns the number of files tracked."
  [session-id]
  (let [files (find-clojure-files)
        mtimes (mtime-all-files files)]
    (save-mtimes session-id mtimes)
    (count files)))

(defn cleanup-state
  "Clean up tracking state for a session."
  [session-id]
  (let [dir (io/file "/tmp" (str "brepl-tracker-" session-id))]
    (when (.exists dir)
      (doseq [file (.listFiles dir)]
        (.delete file))
      (.delete dir))))
