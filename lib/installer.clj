(ns brepl.lib.installer
  "Hook installer for Claude Code integration."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]))

(defn settings-local-path []
  ".claude/settings.local.json")

(defn read-settings
  "Read existing .claude/settings.local.json or return empty map."
  []
  (let [path (settings-local-path)]
    (if (fs/exists? path)
      (try
        (json/parse-string (slurp path) true)
        (catch Exception e
          (println "Warning: Could not parse existing settings.local.json")
          {}))
      {})))

(defn write-settings
  "Write settings to .claude/settings.local.json."
  [settings]
  (let [path (settings-local-path)]
    ;; Ensure .claude directory exists
    (fs/create-dirs ".claude")
    ;; Write settings file
    (spit path (json/generate-string settings {:pretty true}))))

(defn brepl-hook-config
  "Generate brepl hook configuration for Claude Code."
  [opts]
  (let [debug-flag (when (:debug opts) " --debug")]
    {:PreToolUse [{:matcher "Edit|Write"
                   :hooks [{:type "command"
                            :command (str "brepl hook validate" debug-flag)
                            :continueOnError false}]}]
     :PostToolUse [{:matcher "Edit|Write"
                    :hooks [{:type "command"
                             :command (str "brepl hook eval" debug-flag)
                             :continueOnError (not (:strict-eval opts))}]}]
     :SessionEnd [{:matcher "*"
                   :hooks [{:type "command"
                            :command "brepl hook session-end"}]}]}))

(defn merge-hooks
  "Merge new hooks with existing ones, avoiding duplicates."
  [existing-hooks new-hooks]
  ;; For now, just replace with new hooks
  ;; In production, would do smarter merging
  new-hooks)

(defn find-brepl-resources
  "Find the brepl resources directory."
  []
  (let [;; Try current directory first (for development)
        dev-path "resources/skills/brepl"
        ;; Try relative to script file location
        script-file (System/getProperty "babashka.file")
        script-dir (when script-file (str (fs/parent script-file)))
        script-resources (when script-dir (str script-dir "/resources/skills/brepl"))]
    (cond
      (and dev-path (fs/exists? dev-path)) dev-path
      (and script-resources (fs/exists? script-resources)) script-resources
      :else nil)))

(defn install-skill
  "Install brepl skill to .claude/skills/brepl/."
  []
  (let [resources-dir (find-brepl-resources)
        target-dir ".claude/skills/brepl"]
    (if resources-dir
      (do
        (fs/create-dirs target-dir)
        (fs/copy-tree resources-dir target-dir {:replace-existing true})
        {:success true :message "Skill installed to .claude/skills/brepl"})
      {:success false :message "Could not find brepl skill resources"})))

(defn uninstall-skill
  "Remove brepl skill from .claude/skills/brepl/."
  []
  (let [target-dir ".claude/skills/brepl"]
    (if (fs/exists? target-dir)
      (do
        (fs/delete-tree target-dir)
        {:success true :message "Skill removed from .claude/skills/brepl"})
      {:success true :message "Skill not found (already uninstalled)"})))

(defn install-hooks
  "Install brepl hooks to .claude/settings.local.json and brepl skill."
  [opts]
  (let [settings (read-settings)
        new-hooks (brepl-hook-config opts)
        updated-settings (assoc settings :hooks new-hooks)]
    (write-settings updated-settings)
    (let [skill-result (install-skill)]
      (if (:success skill-result)
        {:success true :message "Hooks and skill installed successfully"}
        {:success true :message (str "Hooks installed. " (:message skill-result))}))))

(defn uninstall-hooks
  "Remove brepl hooks from .claude/settings.local.json."
  []
  (let [settings (read-settings)
        updated-settings (dissoc settings :hooks)
        path (settings-local-path)]
    (if (empty? updated-settings)
      ;; If no other settings, remove file
      (when (fs/exists? path)
        (fs/delete path))
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
