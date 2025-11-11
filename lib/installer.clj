(ns brepl.lib.installer
  "Hook installer for Claude Code integration."
  (:require [clojure.java.io :as io]
            [cheshire.core :as json]))

(defn settings-local-path []
  ".claude/settings.local.json")

(defn read-settings
  "Read existing .claude/settings.local.json or return empty map."
  []
  (let [path (settings-local-path)
        file (io/file path)]
    (if (.exists file)
      (try
        (json/parse-string (slurp file) true)
        (catch Exception e
          (println "Warning: Could not parse existing settings.local.json")
          {}))
      {})))

(defn write-settings
  "Write settings to .claude/settings.local.json."
  [settings]
  (let [path (settings-local-path)
        dir (io/file ".claude")]
    ;; Ensure .claude directory exists
    (.mkdirs dir)
    ;; Write settings file
    (spit path (json/generate-string settings {:pretty true}))))

(defn brepl-hook-config
  "Generate brepl hook configuration for Claude Code."
  [opts]
  {:PreToolUse [{:matcher "Edit|Write|Update"
                 :hooks [{:type "command"
                          :command "brepl hook validate"
                          :continueOnError false}]}]
   :PostToolUse [{:matcher "Edit|Write|Update"
                  :hooks [{:type "command"
                           :command "brepl hook eval"
                           :continueOnError (not (:strict-eval opts))}]}]
   :SessionEnd [{:matcher "*"
                 :hooks [{:type "command"
                          :command "brepl hook session-end"}]}]})

(defn merge-hooks
  "Merge new hooks with existing ones, avoiding duplicates."
  [existing-hooks new-hooks]
  ;; For now, just replace with new hooks
  ;; In production, would do smarter merging
  new-hooks)

(defn install-hooks
  "Install brepl hooks to .claude/settings.local.json."
  [opts]
  (let [settings (read-settings)
        new-hooks (brepl-hook-config opts)
        updated-settings (assoc settings :hooks new-hooks)]
    (write-settings updated-settings)
    {:success true :message "Hooks installed successfully"}))

(defn uninstall-hooks
  "Remove brepl hooks from .claude/settings.local.json."
  []
  (let [settings (read-settings)
        updated-settings (dissoc settings :hooks)]
    (if (empty? updated-settings)
      ;; If no other settings, remove file
      (let [file (io/file (settings-local-path))]
        (when (.exists file)
          (.delete file)))
      ;; Otherwise just update
      (write-settings updated-settings))
    {:success true :message "Hooks uninstalled successfully"}))

(defn check-status
  "Check hook installation status."
  []
  (let [settings (read-settings)
        has-hooks (contains? settings :hooks)]
    {:installed has-hooks
     :settings-path (settings-local-path)
     :hooks (when has-hooks (:hooks settings))}))
