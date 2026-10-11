(ns brepl.lib.installer
  "Hook installer for Claude Code integration."
  (:require [babashka.fs :as fs]
            [brepl.lib.skill-content :as skill-content]
            [cheshire.core :as json]
            [cheshire.factory :as json-factory]
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
     {:PreToolUse [{:matcher "Edit|Write|Bash"
                    :hooks [{:type "command"
                             :command (str "brepl hook validate" debug-flag)
                             :continueOnError false}]}]
      :PostToolUse [{:matcher ""
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

(defn install-skill
  "Install brepl skill to .claude/skills/brepl/."
  []
  (let [target-dir ".claude/skills/brepl"
        skill-file (str target-dir "/SKILL.md")]
    (fs/create-dirs target-dir)
    (spit skill-file skill-content/skill-md)
    {:success true :message "Skill installed to .claude/skills/brepl"}))

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
  ;;  :code (clojure.test/run-tests)
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
;;   :code        - Clojure code as s-expression or string (required)
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

;; ECA (Editor Code Assistant) hook installer support

(defn eca-config-path []
  ".eca/config.json")

(defn eca-env [key] (System/getenv key))

(defn eca-global-config-path []
  (str (fs/path (or (eca-env "XDG_CONFIG_HOME")
                    (str (fs/path (System/getProperty "user.home") ".config")))
                "eca" "config.json")))

(defn config-error [message]
  (throw (ex-info (str message " No config changed. Review and migrate the project config manually.") {})))

(defn read-eca-config
  "Read JSON with comments, rejecting invalid config. Missing files mean empty config."
  ([] (read-eca-config (eca-config-path)))
  ([path]
   (if (fs/exists? path)
     (let [values (try
                    (binding [json-factory/*json-factory*
                             (json-factory/make-json-factory {:allow-comments true})]
                      (doall (json/parsed-seq (java.io.StringReader. (slurp path)) true)))
                    (catch Exception e
                      (config-error (str "Cannot read " path ": " (.getMessage e)))))
           config (first values)]
       (when-not (= 1 (count values))
         (config-error (str path " must contain exactly one JSON object.")))
       (when-not (map? config)
         (config-error (str path " must contain a JSON object.")))
       config)
     {})))

(defn write-eca-config
  "Write config to .eca/config.json."
  [config]
  (let [path (eca-config-path)
        parent-dir (fs/parent path)]
    ;; Ensure parent directory exists
    (when parent-dir
      (fs/create-dirs parent-dir))
    ;; Write config file
    (spit path (json/generate-string config {:pretty true}))))

(defn brepl-eca-hook-config
  "Return original ECA hook definitions for exact legacy matching."
  []
  {:brepl-validate
   {:type "preToolCall"
    :matcher "eca__(write_file|edit_file)"
    :actions [{:type "shell"
               :shell "brepl eca validate"}]}
   :brepl-eval
   {:type "postToolCall"
    :matcher "eca__(write_file|edit_file)"
    :actions [{:type "shell"
               :shell "brepl eca eval"}]}
   :brepl-session-end
   {:type "sessionEnd"
    :actions [{:type "shell"
               :shell "brepl eca session-end"}]}})

(defn eca-mode-hooks
  "Hooks to write for a mode. :with-eval and :balance-only install direct
   hooks. Any other mode (including :uninstall) installs none."
  [mode]
  (let [hooks (-> (brepl-eca-hook-config)
                  (dissoc :brepl-session-end)
                  (assoc-in [:brepl-validate :visible] false)
                  (assoc-in [:brepl-eval :visible] false))]
    (case mode
      :with-eval hooks
      :balance-only (assoc-in hooks [:brepl-eval :actions 0 :shell]
                              "brepl eca eval --balance-only")
      {})))

(defn validate-eca-config [config]
  (when (and (contains? config :hooks) (not (map? (:hooks config))))
    (config-error "hooks must be an object."))
  config)

(defn remove-legacy-eca-hooks [config]
  (reduce-kv
   (fn [result key definition]
     (if (contains? (:hooks config) key)
       (if (or (= definition (get-in config [:hooks key]))
               (some (fn [mode]
                       (when-let [hook (get (eca-mode-hooks mode) key)]
                         (contains? #{hook (dissoc hook :visible)}
                                    (get-in config [:hooks key]))))
                     [:with-eval :balance-only]))
         (update result :hooks dissoc key)
         (config-error (str "Customized legacy hook " (name key) ". Compare its complete definition before removal.")))
       result))
   config (brepl-eca-hook-config)))

(defn safe-eca-target! [path global-path]
  (loop [part (fs/absolutize path)]
    (when part
      (when (fs/sym-link? part)
        (config-error "Project config path must not contain symbolic links."))
      (recur (fs/parent part))))
  (when (or (= (fs/normalize (fs/absolutize path))
               (fs/normalize (fs/absolutize global-path)))
            (and (fs/exists? path) (fs/exists? global-path)
                 (fs/same-file? path global-path)))
    (config-error "Project config must not be the global config.")))

(defn eca-project-state []
  (let [path (eca-config-path)
        global-path (eca-global-config-path)
        _ (safe-eca-target! path global-path)
        config (validate-eca-config (read-eca-config path))
        cleaned (remove-legacy-eca-hooks config)]
    {:config config :cleaned cleaned}))

(def eca-skill-tip
  (str "Tip: this command does not install the brepl skill. In ECA, run "
       "`/plugin-install brepl` to get it from the official marketplace, or "
       "copy resources/skills/brepl/SKILL.md from "
       "https://github.com/licht1stein/brepl into this project's "
       ".eca/skills/brepl/SKILL.md."))

(def eca-config-help
  (str "brepl eca install [--balance-only | --with-eval]\n"
       "brepl eca uninstall\n"
       "Install without a flag opens a terminal menu with no default. Enter or EOF cancels without writes.\n"
       "Nonterminal use requires an explicit flag.\n"
       "Balance-only: validate and fix brackets, never evaluate. With-eval: also evaluate changed files via nREPL.\n"
       "Generated validate/eval hooks use visible: false: hide the hook block, not hook execution or feedback to the model.\n"
       "Evaluation executes code and can have side effects, such as file or database changes.\n"
       "Uninstall takes no flags: it removes exact known direct hooks if present, and nothing else.\n"
       "Customized known hooks cause failure without writes. No sessionEnd hook is installed or required.\n"
       "Only project .eca/config.json is changed, at most once; the global config is never read or written.\n"
       "JSON comments are accepted, but writes remove comments and change formatting.\n"
       "This command never manages the brepl skill or any ECA plugin; see the tip printed after install.\n"
       "Restart ECA after changes."))

(def eca-install-modes [:balance-only :with-eval])

(defn eca-mode-arg [args]
  (let [flags (into {} (map (fn [mode] [(str "--" (name mode)) mode])) eca-install-modes)]
    (cond
      (empty? args) nil
      (and (= 1 (count args)) (contains? flags (first args))) (flags (first args))
      :else (config-error (str "Invalid flags. Use " (str/join " or " (sort (keys flags))) " or --help.")))))

(defn choose-eca-mode [read-line-fn]
  (println "1) balance-only Validate and repair brackets, without evaluation.")
  (println "2) with-eval    Validate, repair brackets, and automatic evaluation.")
  (println)
  (println "Warning: automatic evaluation executes code and can have side effects,")
  (println "including file or database changes.")
  (println)
  (print "Choice (no default; Enter or EOF cancels): ")
  (flush)
  (let [answer (some-> (read-line-fn) str/trim)]
    (cond
      (nil? answer) (config-error "Menu cancelled at end of input.")
      (= "" answer) (config-error "Menu cancelled.")
      :else (or (some (fn [[index mode]]
                        (when (contains? #{(str (inc index)) (name mode)} answer)
                          mode))
                      (map-indexed vector eca-install-modes))
                (config-error "Invalid menu choice.")))))

(defn updated-eca-config [{:keys [cleaned]} mode]
  (let [hooks (eca-mode-hooks mode)]
    (cond-> cleaned
      (seq hooks) (update :hooks merge hooks))))

(def eca-json-warning
  "Warning: Saving changes removes JSON comments and reformats the file.")

(defn ^:private capitalize-first [s]
  (if (seq s) (str (str/upper-case (subs s 0 1)) (subs s 1)) s))

(def ^:private eca-mode-fact
  "Hook summary per mode; excludes :uninstall, which has its own wording."
  {:balance-only "bracket repair hooks installed; evaluation disabled"
   :with-eval "bracket repair and evaluation hooks installed"})

(defn ^:private eca-result-message
  "User-facing result text for a mode, independent of file writes."
  [mode changed?]
  (str (case mode
         :uninstall (if changed?
                      "Project brepl hooks removed."
                      "Unchanged: no project brepl hooks found.")
         (let [fact (eca-mode-fact mode)]
           (if changed?
             (str (capitalize-first fact) ". Restart ECA.")
             (str "Unchanged: " fact "."))))
       (when (not= mode :uninstall) (str "\n" eca-skill-tip))))

(defn update-eca-project [state mode]
  (let [config (:config state)
        updated (updated-eca-config state mode)
        changed? (not= config updated)]
    (when changed?
      (when (and (not (:menu? state)) (fs/exists? (eca-config-path)))
        (println eca-json-warning)
        (flush))
      (write-eca-config updated))
    {:success true :changed? changed? :config-path (eca-config-path)
     :config-path-shown? (:menu? state)
     :message (eca-result-message mode changed?)}))

(defn configure-eca-project
  "Validate flags and config before selecting a mode and making at most one write.
   :install supports --balance-only/--with-eval and a terminal menu.
   :uninstall takes no flags other than --help."
  ([operation args]
   (configure-eca-project operation args
                          {:terminal? (some? (System/console)) :read-line-fn read-line}))
  ([operation args {:keys [terminal? read-line-fn]}]
   (if (= ["--help"] (vec args))
     {:success true :message eca-config-help}
     (case operation
       :uninstall
       (if (seq args)
         (config-error "brepl eca uninstall takes no flags. Use --help for details.")
         (update-eca-project (eca-project-state) :uninstall))

       :install
       (let [mode (eca-mode-arg args)]
         (when (and (nil? mode) (not terminal?))
           (config-error "No terminal. Select an explicit mode flag; see brepl eca install --help."))
         (let [state (assoc (eca-project-state) :menu? (nil? mode))
               mode (or mode
                        (do
                          (println "Config file:" (eca-config-path))
                          (when (fs/exists? (eca-config-path))
                            (println eca-json-warning))
                          (choose-eca-mode read-line-fn)))]
           (update-eca-project state mode)))))))
