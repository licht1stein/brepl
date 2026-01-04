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

;; Common fields
(s/def ::type #{:repl :bash})
(s/def ::name string?)
(s/def ::retry-on-failure? boolean?)
(s/def ::max-retries (s/and int? (complement neg?)))
(s/def ::required? boolean?)
(s/def ::timeout pos-int?)

;; REPL-specific
(s/def ::code string?)

;; Bash-specific
(s/def ::command string?)
(s/def ::cwd string?)
(s/def ::env (s/map-of string? string?))

;; Hook specs by type
(s/def ::repl-hook
  (s/keys :req-un [::type ::name ::code]
          :opt-un [::retry-on-failure? ::max-retries ::required? ::timeout]))

(s/def ::bash-hook
  (s/keys :req-un [::type ::name ::command]
          :opt-un [::retry-on-failure? ::max-retries ::required? ::timeout ::cwd ::env]))

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
  {:retry-on-failure? false
   :max-retries 10
   :required? false
   :timeout 60
   :cwd "."
   :env {}})

(defn apply-defaults
  "Apply default values to a hook."
  [hook]
  (merge defaults hook))

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

(defn execute-repl-hook
  "Execute Clojure code via nREPL.
   Returns {:success? bool :output str :error str}."
  [hook]
  (let [{:keys [code timeout required?]} (apply-defaults hook)
        port (when *resolve-port-fn* (*resolve-port-fn* nil nil))
        host (when *resolve-host-fn* (*resolve-host-fn* nil))]
    (cond
      ;; No port available
      (nil? port)
      (if required?
        {:success? false
         :output ""
         :error "nREPL not available but hook is required"}
        {:success? true
         :output ""
         :error nil
         :skipped? true})

      ;; Have port, execute
      *nrepl-eval-fn*
      (try
        (let [timeout-ms (* timeout 1000)
              result-future (future (*nrepl-eval-fn* host port code {:hook true}))
              result (deref result-future timeout-ms ::timeout)]
          (if (= result ::timeout)
            {:success? false
             :output ""
             :error (str "Hook timed out after " timeout " seconds")}
            (let [{:keys [processed has-error?]} result
                  output (str/join "\n" (concat (:out processed) (:values processed)))
                  error (when has-error?
                          (or (:ex processed)
                              (str/join "\n" (:err processed))
                              "Evaluation error"))]
              {:success? (not has-error?)
               :output output
               :error error})))
        (catch Exception e
          {:success? false
           :output ""
           :error (.getMessage e)}))

      :else
      {:success? false
       :output ""
       :error "nREPL eval function not initialized"})))

;; =============================================================================
;; Main orchestration
;; =============================================================================

(defn execute-hook
  "Execute a single hook (REPL or bash).
   Returns {:success? bool :output str :error str}."
  [hook]
  (case (:type hook)
    :repl (execute-repl-hook hook)
    :bash (let [result (execute-bash-hook hook)]
            ;; Normalize bash result to common format
            {:success? (:success? result)
             :output (str/trim (or (:stdout result) ""))
             :error (let [stderr (str/trim (or (:stderr result) ""))]
                      (if (str/blank? stderr)
                        (when-not (:success? result)
                          (str "Exit code " (:exit result)))
                        stderr))})))

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
              (if (:success? result)
                ;; Hook passed - reset retry count, continue
                (recur (rest remaining)
                       (dissoc current-state hook-name))
                ;; Hook failed
                (let [retry-on-failure? (:retry-on-failure? hook)
                      max-retries (:max-retries hook)
                      current-retries (get current-state hook-name 0)
                      new-retries (inc current-retries)
                      infinite-retries? (zero? max-retries)
                      within-limit? (or infinite-retries? (< current-retries max-retries))]
                  (if (and retry-on-failure? within-limit?)
                    ;; Retry - exit 2 to make Claude continue
                    (do
                      (write-state session-id (assoc current-state hook-name new-retries))
                      {:exit-code 2
                       :message (str "Hook '" hook-name "' failed (attempt " new-retries
                                     (when-not infinite-retries?
                                       (str "/" max-retries))
                                     "): " (:error result))})
                    ;; No retry or limit reached - exit 1, Claude informed
                    (do
                      (write-state session-id (dissoc current-state hook-name))
                      {:exit-code 1
                       :message (if (and retry-on-failure? (not within-limit?))
                                  (str "Hook '" hook-name "' failed after " max-retries " retries: " (:error result))
                                  (str "Hook '" hook-name "' failed: " (:error result)))})))))))))))