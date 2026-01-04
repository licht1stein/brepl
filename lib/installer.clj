(ns brepl.lib.installer
  "Hook installer for Claude Code integration."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.string :as str]))

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
     :Stop [{:matcher ""
             :hooks [{:type "command"
                      :command (str "brepl hook stop" debug-flag)}]}]
     :SessionEnd [{:matcher "*"
                   :hooks [{:type "command"
                            :command "brepl hook session-end"}]}]}))

(defn brepl-hook?
  "Check if a hook entry belongs to brepl."
  [hook-entry]
  (some #(str/starts-with? (:command %) "brepl hook")
        (:hooks hook-entry)))

(defn merge-hook-event
  "Merge new brepl entries with existing non-brepl entries for a single event."
  [existing-entries new-entries]
  (let [non-brepl (remove brepl-hook? existing-entries)]
    (into (vec non-brepl) new-entries)))

(defn merge-hooks
  "Merge brepl hooks with existing hooks, preserving non-brepl hooks."
  [existing-hooks new-hooks]
  (reduce-kv
   (fn [acc event-name new-entries]
     (let [existing-entries (get acc event-name [])]
       (assoc acc event-name (merge-hook-event existing-entries new-entries))))
   existing-hooks
   new-hooks))

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

(def hooks-template
  ";; brepl stop hooks configuration

{:stop
 [;; Example: Run tests via nREPL after Claude stops
  ;; {:type :repl
  ;;  :code \"(clojure.test/run-tests)\"
  ;;  :required? true    ; Must pass - Claude retries until success
  ;;  :max-retries 10    ; Give up after 10 attempts (0 = infinite)
  ;;  :timeout 120}

  ;; Example: Run linter via bash
  ;; {:type :bash
  ;;  :command \"clj-kondo --lint src\"
  ;;  :required? false   ; Optional - inform on failure but don't retry
  ;;  :timeout 30}
  ]}

;; Hook fields:
;;   :type        - :repl or :bash (required)
;;   :required?   - if true: must pass, retry on failure (default: false)
;;   :max-retries - max retry attempts, 0 = infinite (default: 10)
;;   :timeout     - seconds before timeout (default: 60)
;;
;; REPL hooks:
;;   :code        - Clojure code to evaluate (required)
;;
;; Bash hooks:
;;   :command     - shell command to run (required)
;;   :cwd         - working directory (default: \".\")
;;   :env         - environment variables map (default: {})
")

(defn generate-hooks-template
  "Generate .brepl/hooks.edn template if it doesn't exist."
  []
  (let [brepl-dir ".brepl"
        hooks-file (str brepl-dir "/hooks.edn")]
    (if (fs/exists? hooks-file)
      {:created false :message "hooks.edn already exists"}
      (do
        (fs/create-dirs brepl-dir)
        (spit hooks-file hooks-template)
        {:created true :message "Created .brepl/hooks.edn template"}))))

(defn install-hooks
  "Install brepl hooks to .claude/settings.local.json and brepl skill."
  [opts]
  (let [settings (read-settings)
        existing-hooks (get settings :hooks {})
        new-hooks (brepl-hook-config opts)
        merged-hooks (merge-hooks existing-hooks new-hooks)
        updated-settings (assoc settings :hooks merged-hooks)]
    (write-settings updated-settings)
    (let [skill-result (install-skill)
          template-result (generate-hooks-template)]
      {:success true
       :message (str "Hooks installed successfully"
                     (when (:created template-result)
                       ". Created .brepl/hooks.edn template"))})))

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
