(ns brepl.lib.stop-hooks
  "Stop hook execution for Claude Code Stop event."
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]
            [clojure.spec.alpha :as s]
            [clojure.string :as str]
            [babashka.process :as process]))

;; =============================================================================
;; Specs for hook validation
;; =============================================================================

;; Common fields (same for REPL and bash)
(s/def ::type #{:repl :bash})
(s/def ::name string?)
(s/def ::required? boolean?)        ;; If true: retry on failure. If false: inform and proceed.
(s/def ::max-retries (s/and int? (complement neg?)))
(s/def ::timeout pos-int?)

;; REPL-specific - code can be string or s-expression
(s/def ::code (s/or :string string? :form list? :symbol symbol?))

;; Bash-specific
(s/def ::command string?)
(s/def ::cwd string?)
(s/def ::env (s/map-of string? string?))

;; Hook specs by type
(s/def ::repl-hook
  (s/keys :req-un [::type ::code]
          :opt-un [::name ::required? ::max-retries ::timeout]))

(s/def ::bash-hook
  (s/keys :req-un [::type ::command]
          :opt-un [::name ::required? ::max-retries ::timeout ::cwd ::env]))

(s/def ::hook
  (s/or :repl (s/and ::repl-hook #(= (:type %) :repl))
        :bash (s/and ::bash-hook #(= (:type %) :bash))))

(s/def ::stop (s/coll-of ::hook))

(s/def ::hooks-config
  (s/keys :opt-un [::stop]))

;; =============================================================================
;; Default values
;; =============================================================================

(def defaults
  {:required? false
   :max-retries 10
   :timeout 60
   :cwd "."
   :env {}})

(defn derive-name
  "Derive hook name from command or code if not provided."
  [hook]
  (or (:name hook)
      (let [raw (or (:command hook) (:code hook) "hook")
            source (if (string? raw) raw (pr-str raw))
            truncated (subs source 0 (min 30 (count source)))]
        (if (< (count source) 30) truncated (str truncated "...")))))

(defn apply-defaults
  "Apply default values to a hook."
  [hook]
  (-> (merge defaults hook)
      (assoc :name (derive-name hook))))

;; =============================================================================
;; Configuration loading
;; =============================================================================

(def hooks-file ".brepl/hooks.edn")

(defn load-hooks
  "Load and parse .brepl/hooks.edn. Returns {:stop [...]} or nil if not found."
  []
  (let [f (io/file hooks-file)]
    (when (.exists f)
      (try
        (edn/read-string (slurp f))
        (catch Exception e
          {:error (.getMessage e)})))))

;; =============================================================================
;; Validation
;; =============================================================================

(defn validate-hooks
  "Validate hooks config against specs.
   Returns {:valid? true/false :errors [...]}."
  [config]
  (if (s/valid? ::hooks-config config)
    {:valid? true :errors []}
    {:valid? false
     :errors (s/explain-data ::hooks-config config)}))

;; =============================================================================
;; State persistence (retry tracking)
;; =============================================================================

(defn state-file-path
  "Get path to state file for a session."
  [session-id]
  (str "/tmp/brepl-stop-hook-" session-id ".edn"))

(defn read-state
  "Read retry state from file. Returns map of hook-name -> retry-count."
  [session-id]
  (let [f (io/file (state-file-path session-id))]
    (if (.exists f)
      (try
        (edn/read-string (slurp f))
        (catch Exception _
          {}))
      {})))

(defn write-state
  "Write retry state to file."
  [session-id state]
  (spit (state-file-path session-id) (pr-str state)))

(defn cleanup-state
  "Remove state file."
  [session-id]
  (let [f (io/file (state-file-path session-id))]
    (when (.exists f)
      (.delete f))))

;; =============================================================================
;; Bash hook execution
;; =============================================================================

(defn execute-bash-hook
  "Execute shell command via babashka.process.
   Returns {:success? bool :stdout str :stderr str :exit int}."
  [hook]
  (let [{:keys [command cwd env timeout]} (apply-defaults hook)
        timeout-ms (* timeout 1000)]
    (try
      ;; Use sh -c to run command through shell for proper expansion
      (let [result (process/shell {:out :string
                                   :err :string
                                   :dir cwd
                                   :extra-env env
                                   :timeout timeout-ms
                                   :continue true}  ;; Don't throw on non-zero exit
                                  "sh" "-c" command)]
        {:success? (zero? (:exit result))
         :stdout (:out result)
         :stderr (:err result)
         :exit (:exit result)})
      (catch java.util.concurrent.TimeoutException _
        {:success? false
         :stdout ""
         :stderr (str "Hook timed out after " timeout " seconds")
         :exit -1})
      (catch Exception e
        {:success? false
         :stdout ""
         :stderr (.getMessage e)
         :exit -1}))))

;; =============================================================================
;; REPL hook execution
;; =============================================================================

;; Forward declaration - will be set by brepl main when loading
(def ^:dynamic *nrepl-eval-fn* nil)
(def ^:dynamic *resolve-port-fn* nil)
(def ^:dynamic *resolve-host-fn* nil)

(defn connection-error?
  "Check if error indicates nREPL server is not running."
  [error-str]
  (and error-str
       (or (str/includes? error-str "Connection refused")
           (str/includes? error-str "Connection reset"))))

(defn execute-repl-hook
  "Execute Clojure code via nREPL.
   Returns {:success? bool :output str :error str :no-server? bool}."
  [hook]
  (let [{:keys [code timeout required?]} (apply-defaults hook)
        ;; Code can be string or s-expression - convert to string for nREPL
        code-str (if (string? code) code (pr-str code))
        port (when *resolve-port-fn* (*resolve-port-fn* nil nil))
        host (when *resolve-host-fn* (*resolve-host-fn* nil))]
    (cond
      ;; No port available
      (nil? port)
      (if required?
        {:success? false
         :output ""
         :error "Inform the user that the hook won't work because nREPL server is not running. Offer to start it or ask the user to do it."
         :no-server? true}
        {:success? true
         :output ""
         :error nil
         :skipped? true})

      ;; Have port, execute
      *nrepl-eval-fn*
      (try
        (let [timeout-ms (* timeout 1000)
              result-future (future (*nrepl-eval-fn* host port code-str {:hook true}))
              result (deref result-future timeout-ms ::timeout)]
          (if (= result ::timeout)
            {:success? false
             :output ""
             :error (str "Hook timed out after " timeout " seconds")}
            (let [{:keys [processed has-error?]} result
                  output (str/join "\n" (concat (:out processed) (:values processed)))
                  error-msg (when has-error?
                              (or (:ex processed)
                                  (str/join "\n" (:err processed))
                                  "Evaluation error"))]
              ;; Check for connection errors (stale .nrepl-port file)
              (if (connection-error? error-msg)
                {:success? false
                 :output ""
                 :error "Inform the user that the hook won't work because nREPL server is not running. Offer to start it or ask the user to do it."
                 :no-server? true}
                {:success? (not has-error?)
                 :output output
                 :error error-msg}))))
        (catch Exception e
          (let [msg (.getMessage e)]
            (if (connection-error? msg)
              {:success? false
               :output ""
               :error "Inform the user that the hook won't work because nREPL server is not running. Offer to start it or ask the user to do it."
               :no-server? true}
              {:success? false
               :output ""
               :error msg}))))

      :else
      {:success? false
       :output ""
       :error "nREPL eval function not initialized"})))

;; =============================================================================
;; Main orchestration
;; =============================================================================

(defn execute-hook
  "Execute a single hook (REPL or bash).
   Returns {:success? bool :output str :error str :no-server? bool}."
  [hook]
  (case (:type hook)
    :repl (execute-repl-hook hook)  ;; Returns :no-server? when nREPL unavailable
    :bash (let [result (execute-bash-hook hook)
                stdout (str/trim (or (:stdout result) ""))
                stderr (str/trim (or (:stderr result) ""))
                ;; Combine output for error context (Claude needs to see what failed)
                combined-output (str/join "\n" (remove str/blank? [stdout stderr]))]
            {:success? (:success? result)
             :output stdout
             :error (when-not (:success? result)
                      (if (str/blank? combined-output)
                        (str "Exit code " (:exit result))
                        (str "Exit code " (:exit result) "\n" combined-output)))})))

(defn run-stop-hooks
  "Main orchestration function.
   Takes session-id and hooks config.
   Returns {:exit-code 0|1|2 :message str}."
  [session-id config]
  (let [hooks (mapv apply-defaults (get config :stop []))]
    (if (empty? hooks)
      {:exit-code 0 :message "No hooks configured"}
      (let [state (read-state session-id)]
        (loop [remaining hooks
               current-state state]
          (if (empty? remaining)
            ;; All hooks passed
            (do
              (cleanup-state session-id)
              {:exit-code 0 :message "All hooks passed"})
            (let [hook (first remaining)
                  hook-name (:name hook)
                  result (execute-hook hook)]
              (cond
                ;; Hook passed - reset retry count, continue
                (:success? result)
                (recur (rest remaining)
                       (dissoc current-state hook-name))

                ;; No server - first time: block (exit 2), second time: inform (exit 1)
                (:no-server? result)
                (let [no-server-key "__no-server__"
                      seen-before? (contains? current-state no-server-key)]
                  (if seen-before?
                    ;; Second attempt - allow stopping
                    (do
                      (cleanup-state session-id)
                      {:exit-code 1
                       :message (:error result)})
                    ;; First attempt - block, force Claude to react
                    (do
                      (write-state session-id (assoc current-state no-server-key true))
                      {:exit-code 2
                       :message (:error result)})))

                ;; Hook failed - check retry logic
                :else
                (let [required? (:required? hook)
                      max-retries (:max-retries hook)
                      current-retries (get current-state hook-name 0)
                      new-retries (inc current-retries)
                      infinite-retries? (zero? max-retries)
                      within-limit? (or infinite-retries? (< current-retries max-retries))]
                  (if (and required? within-limit?)
                    ;; Required hook - retry (exit 2 to make Claude continue)
                    (do
                      (write-state session-id (assoc current-state hook-name new-retries))
                      {:exit-code 2
                       :message (str "Hook '" hook-name "' failed (attempt " new-retries
                                     (when-not infinite-retries?
                                       (str "/" max-retries))
                                     "). Fix the issues shown below and try again:\n"
                                     (:error result))})
                    ;; Optional hook or limit reached - inform (exit 1)
                    (do
                      (write-state session-id (dissoc current-state hook-name))
                      {:exit-code 1
                       :message (if (and required? (not within-limit?))
                                  (str "Hook '" hook-name "' failed after " max-retries " retries: " (:error result))
                                  (str "Hook '" hook-name "' failed: " (:error result)))})))))))))))