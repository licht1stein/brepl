#!/usr/bin/env bb
(ns brepl
  (:require [babashka.cli :as cli]
            [clojure.java.io :as io]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [bencode.core :as bencode]
            [clojure.pprint :as pp]
            [clojure.walk :as walk]
            [cheshire.core :as json]
            [brepl.lib.validator :as validator]
            [brepl.lib.backup :as backup]
            [brepl.lib.installer :as installer]
            [brepl.lib.stop-hooks :as stop-hooks]
            [brepl.lib.file-tracker :as file-tracker])
  (:import [java.net Socket]
           [java.io PushbackInputStream]))

(def version "2.6.3")

(def cli-spec
  {:e {:desc "Expression to evaluate (everything after -e is treated as code)"
       :ref "<expr>"}
   :f {:desc "File to load and execute"
       :ref "<file>"}
   :m {:desc "Raw nREPL message (EDN format)"
       :ref "<message>"
       :alias :message}
   :h {:desc "nREPL host"
       :ref "<host>"
       :default "localhost"
       :default-desc "localhost or BREPL_HOST"}
   :p {:desc "nREPL port (required - auto-detects from .nrepl-port or BREPL_PORT)"
       :ref "<port>"}
   :verbose {:desc "Show raw nREPL messages instead of parsed output"
             :coerce :boolean}
   :version {:desc "Show brepl version"
             :coerce :boolean}
   :help {:desc "Show this help message"
          :alias :?
          :coerce :boolean}
   :hook {:desc "Output Claude Code hook-compatible JSON format"
          :coerce :boolean}})

(defn print-help []
  (println "brepl - Bracket-fixing REPL")
  (println)
  (println "USAGE:")
  (println "    brepl [OPTIONS] <expr>")
  (println "    brepl [OPTIONS] -e <expr>")
  (println "    brepl [OPTIONS] <<'EOF' ... EOF")
  (println "    brepl [OPTIONS] -f <file>")
  (println "    brepl [OPTIONS] -m <message>")
  (println "    brepl hooks <subcommand> [args]")
  (println "    brepl skill <subcommand>")
  (println "    brepl balance <file> [--dry-run]")
  (println)
  (println "OPTIONS:")
  (println (cli/format-opts {:spec cli-spec :order [:e :f :m :h :p :verbose :version :help]}))
  (println)
  (println "PORT RESOLUTION:")
  (println "    Port is resolved in the following order:")
  (println "    1. -p <port> command line argument")
  (println "    2. .nrepl-port file:")
  (println "       - For -f: searches from file's directory upward to find project-specific port")
  (println "       - For -e/-m: uses .nrepl-port in current directory")
  (println "    3. BREPL_PORT environment variable")
  (println "    4. Error if none found")
  (println)
  (println "EXAMPLES:")
  (println "    brepl '(+ 1 2 3)'              # Positional argument (implicit -e)")
  (println "    brepl -e '(+ 1 2 3)'           # Explicit -e flag")
  (println "    brepl <<'EOF'                  # Stdin heredoc (implicit -e)")
  (println "      (+ 1 2 3)")
  (println "      EOF")
  (println "    echo '(+ 1 2)' | brepl         # Piped stdin")
  (println "    brepl -f script.clj")
  (println "    brepl -m '{\"op\" \"describe\"}'")
  (println "    brepl -p 7888 '(println \"Hello\")'")
  (println "    BREPL_PORT=7888 brepl '(+ 1 2)'")
  (println "    brepl balance src/core.clj     # Fix unbalanced brackets"))

(defn read-nrepl-port []
  (when (.exists (io/file ".nrepl-port"))
    (-> (slurp ".nrepl-port")
        str/trim
        Integer/parseInt)))

(defn find-nrepl-port-in-parents
  "Search for .nrepl-port file starting from the given directory and walking up to CWD.
  Stops at CWD to avoid searching outside the project.
  Returns the port number from the first .nrepl-port file found, or nil if none found."
  [start-dir]
  (let [cwd (fs/absolutize (fs/cwd))]
    (loop [dir (fs/absolutize start-dir)]
      (when dir
        (let [port-file (fs/file dir ".nrepl-port")]
          (if (fs/exists? port-file)
            (let [port (try
                         (-> (slurp port-file)
                             str/trim
                             Integer/parseInt)
                         (catch Exception e
                           ;; If we can't parse the port file, continue searching
                           nil))]
              (if port
                port
                (recur (fs/parent dir))))
            ;; No port file, continue if we haven't reached CWD yet
            (let [parent (fs/parent dir)]
              (when (and parent (not (= dir cwd)))
                (recur parent)))))))))

(defn get-env-var [var-name]
  (System/getenv var-name))

(defn resolve-host [cli-host]
  (or cli-host (get-env-var "BREPL_HOST") "localhost"))

(defn resolve-port
  "Resolve the nREPL port from various sources.
  Priority: CLI arg > .nrepl-port file > BREPL_PORT env var
  When file-path is provided (for -f option), searches for .nrepl-port
  starting from the file's directory and walking up the tree."
  ([cli-port] (resolve-port cli-port nil))
  ([cli-port file-path]
   (or cli-port
       (if file-path
         ;; For -f option: search from file's directory upward
         (let [parent-dir (fs/parent (fs/absolutize file-path))]
           (when parent-dir
             (find-nrepl-port-in-parents parent-dir)))
         ;; For -e and -m options: use current directory
         (read-nrepl-port))
       (when-let [env-port (get-env-var "BREPL_PORT")]
         (Integer/parseInt env-port)))))

(defn stdin-available?
  "Check if stdin has data available without blocking"
  []
  (pos? (.available System/in)))

(defn read-stdin
  "Read all available input from stdin.
  Returns nil if stdin has no data."
  []
  (when (stdin-available?)
    (try
      (let [input (slurp *in*)]
        (when-not (str/blank? input)
          input))
      (catch Exception _ nil))))

(defn exit-with-help [msg]
  (when msg (println msg) (println))
  (print-help)
  (System/exit 1))

(defn validate-args [opts stdin-available?]
  (when (:help opts) (print-help) (System/exit 0))
  (when (:version opts) (println (str "brepl " version)) (System/exit 0))

  (let [modes (select-keys opts [:e :f :m])
        mode-count (count modes)]
    (cond
      (> mode-count 1) (exit-with-help "Error: Cannot specify multiple options (-e, -f, -m) together")
      ;; Allow zero modes if stdin is available
      (and (zero? mode-count) (not stdin-available?))
      (exit-with-help "Error: Must specify one of -e EXPR, -f FILE, or -m MESSAGE")
      (and (:f modes) (not (.exists (io/file (:f opts)))))
      (do (println "Error: File does not exist:" (:f opts))
          (System/exit 1)))))

;; nREPL client implementation
(defn ->str [x]
  (if (bytes? x)
    (String. x)
    x))

(defn ->str-deep [x]
  (walk/postwalk ->str x))

(defn send-eval-message [host port code opts]
  (let [socket (Socket. host port)
        out (.getOutputStream socket)
        in (PushbackInputStream. (.getInputStream socket))
        msg-id (str (System/currentTimeMillis))
        msg {"op" "eval"
             "code" (str code)
             "id" msg-id}]

    ;; Print client message in verbose mode
    (when (:verbose opts)
      (pp/pprint msg))

    (bencode/write-bencode out msg)
    (.flush out)

    ;; Collect all response messages until we get "done" status
    (loop [responses []]
      (let [response (bencode/read-bencode in)
            _ (when (:verbose opts) (pp/pprint (->str-deep response)))
            status-set (some->> (get response "status") (map ->str) set)]
        (if (contains? status-set "done")
          (do
            (.close socket)
            (if (:verbose opts)
              responses ; Return empty since we already printed
              (conj responses response)))
          (recur (if (:verbose opts)
                   responses ; Don't accumulate in verbose mode
                   (conj responses response))))))))

(defn process-eval-responses [responses]
  (reduce (fn [acc resp]
            (cond-> acc
              (get resp "out")    (update :out conj (->str (get resp "out")))
              (get resp "err")    (update :err conj (->str (get resp "err")))
              (get resp "value")  (update :values conj (->str (get resp "value")))
              (get resp "ex")     (assoc :ex (->str (get resp "ex")))
              (get resp "status") (update :status into (->str (flatten [(get resp "status")])))))
          {:out [] :err [] :values [] :ex nil :status []}
          responses))

(defn format-hook-response [processed has-error?]
  (if-not has-error?
    {:continue true :suppressOutput true}
    (let [parts (cond-> []
                  (:ex processed)  (conj (:ex processed))
                  (seq (:err processed)) (into (:err processed))
                  (seq (:out processed)) (into (:out processed)))
          msg (if (seq parts)
                (str/join " | " parts)
                "Evaluation error occurred")]
      {:continue true
       :suppressOutput true
       :decision "block"
       :stopReason msg
       :reason (str "Code evaluation failed:\n" (str/join "\n" parts))})))

(defn eval-expression [host port code opts]
  (try
    (let [responses (send-eval-message host port code opts)]

      ;; Process responses to check for errors even in verbose mode
      (let [processed (process-eval-responses responses)
            has-error? (or (:ex processed)
                           (some #{"eval-error"} (flatten (:status processed))))]

        ;; If hook mode, return processed data
        (if (:hook opts)
          {:processed processed
           :has-error? has-error?}

          ;; Otherwise handle output normally
          (do
            ;; If verbose mode, responses were already printed
            (if (:verbose opts)
              has-error? ; Just return error status

              ;; Otherwise print output normally
              (do
                ;; Print stdout output
                (doseq [out (:out processed)]
                  (print out))

                ;; Print stderr output to stderr
                (doseq [err (:err processed)]
                  (binding [*out* *err*]
                    (print err)
                    (flush)))

                ;; Print evaluation results
                (doseq [val (:values processed)]
                  (println val))

                ;; Handle exceptions
                (when (:ex processed)
                  (binding [*out* *err*]
                    (println "Exception:" (:ex processed))))

                ;; Handle empty response
                (when (and (empty? (:values processed))
                           (empty? (:out processed))
                           (empty? (:err processed))
                           (not (:ex processed)))
                  (when (some #{"eval-error"} (flatten (:status processed)))
                    (binding [*out* *err*]
                      (println "Evaluation error occurred"))))

                ;; Return error status
                has-error?))))))

    (catch Exception e
      (if (:hook opts)
        {:processed {:ex (str "Connection error: " (.getMessage e))}
         :has-error? true}
        (do
          (println "Error connecting to nREPL server at" (str host ":" port))
          (println (.getMessage e))
          (System/exit 1))))))

(defn eval-file [host port file-path opts]
  (let [code (str "(load-file \"" file-path "\")")]
    (eval-expression host port code opts)))

(defn send-raw-message [host port message-str opts]
  (try
    (let [socket (Socket. host port)
          out (.getOutputStream socket)
          in (PushbackInputStream. (.getInputStream socket))
          ;; Parse the EDN message and add an ID if not present
          msg (read-string message-str)
          msg-with-id (if (get msg "id")
                        msg
                        (assoc msg "id" (str (System/currentTimeMillis))))]

      ;; Print client message in verbose mode
      (when (:verbose opts)
        (pp/pprint msg-with-id))

      (bencode/write-bencode out msg-with-id)
      (.flush out)

      ;; Collect all response messages until we get "done" status or no status
      (loop [responses []
             timeout-count 0]
        (let [response (bencode/read-bencode in)
              _ (when (:verbose opts) (pp/pprint (->str-deep response)))
              status-set (some->> (get response "status") (map ->str) set)]
          (cond
            (contains? status-set "done")
            (do
              (.close socket)
              (if (:verbose opts)
                responses ; Return empty since we already printed
                (conj responses response)))

            ;; For operations that don't return "done", stop after reasonable response count
            (> timeout-count 10)
            (do
              (.close socket)
              responses)

            ;; Otherwise keep reading
            :else
            (recur (if (:verbose opts)
                     responses ; Don't accumulate in verbose mode
                     (conj responses response))
                   (inc timeout-count))))))

    (catch Exception e
      (println "Error sending message to nREPL server at" (str host ":" port))
      (println (.getMessage e))
      (System/exit 1))))

(defn process-raw-message [host port message-str opts]
  (let [responses (send-raw-message host port message-str opts)]
    ;; In verbose mode, everything was already printed
    (when-not (:verbose opts)
      (doseq [resp responses]
        (pp/pprint (->str-deep resp))))))

(defn -main [& args]
  (let [parsed (cli/parse-opts args {:spec cli-spec :args->opts [:e]})
        ;; Check if stdin is available (non-blocking)
        has-stdin? (and (not (:f parsed))
                        (not (:m parsed))
                        (not (:e parsed))
                        (stdin-available?))
        host (resolve-host (:h parsed))
        port (resolve-port (:p parsed) (:f parsed))]

    (validate-args parsed has-stdin?)

    ;; After validation passes, try reading stdin if we have no input
    (let [should-try-stdin? (and (not (:f parsed))
                                 (not (:m parsed))
                                 (not (:e parsed)))
          stdin-input (when should-try-stdin? (read-stdin))
          opts (if stdin-input
                 (assoc parsed :e stdin-input)
                 parsed)]

      (when-not port
        (println "Error: No port specified, no .nrepl-port file found, and BREPL_PORT not set")
        (System/exit 1))

      (let [result (cond
                     (:e opts) (eval-expression host port (:e opts) opts)
                     (:f opts) (eval-file host port (:f opts) opts)
                   (:m opts) (do (process-raw-message host port (:m opts) opts)
                                 false))] ; raw messages don't track errors

        ;; Handle hook mode
        (if (:hook opts)
          (let [{:keys [processed has-error?]} result
                hook-response (format-hook-response processed has-error?)]
            (println (json/generate-string hook-response))
            (System/exit (if has-error? 2 0)))

          ;; Regular mode - for backward compatibility, use exit code 2 for eval errors
          (when result
            (System/exit 2)))))))

(defn debug-log [msg]
  (spit "/tmp/brepl-hook-debug.log"
        (str (java.time.LocalDateTime/now) " - " msg "\n")
        :append true))

(defn save-hook-debug-json [tool-name hook-data]
  (try
    (let [timestamp (str (System/currentTimeMillis))
          debug-dir "./tmp/hooks-requests"
          filename (str debug-dir "/" tool-name "-" timestamp ".json")]
      (fs/create-dirs debug-dir)
      (spit filename (json/generate-string hook-data {:pretty true}))
      (debug-log (str "Saved debug JSON to: " filename)))
    (catch Exception e
      (debug-log (str "ERROR saving debug JSON: " (.getMessage e))))))

(defn json-exit [response exit-code]
  (println (json/generate-string response))
  (System/exit exit-code))

(defn block-hook
  ([reason] (block-hook "PreToolUse" reason))
  ([event-name reason]
   (json-exit {:hookSpecificOutput
               {:hookEventName event-name
                :permissionDecision "deny"
                :permissionDecisionReason reason}} 1)))

(defn allow-hook
  ([] (json-exit {:hookSpecificOutput
                  {:hookEventName "PreToolUse"
                   :permissionDecision "allow"}} 0))
  ([event-name] (json-exit {:hookSpecificOutput
                             {:hookEventName event-name
                              :permissionDecision "allow"}} 0))
  ([tool-name tool-input fixed-content]
   (let [updated-input (case tool-name
                         "Edit" (assoc tool-input :new_string fixed-content)
                         "Write" (assoc tool-input :content fixed-content)
                         tool-input)]
     (json-exit {:hookSpecificOutput
                 {:hookEventName "PreToolUse"
                  :permissionDecision "allow"
                  :permissionDecisionReason "Auto-fixed bracket errors"
                  :updatedInput updated-input}} 0))))

;; Hook subcommand handlers
(defn get-file-path-from-stdin []
  (try
    (let [file-path (-> (json/parse-stream *in* true)
                        :tool_input
                        :file_path)]
      (debug-log (str "eval hook called for: " file-path))
      file-path)
    (catch Exception e
      (debug-log (str "ERROR parsing stdin: " (.getMessage e)))
      nil)))


(defn parse-hook-input
  "Parse raw hook input from stdin. Returns the full hook data map."
  [args]
  (try
    (let [debug-mode? (some #(= "--debug" %) args)
          hook-data (if debug-mode?
                      (let [raw-input (slurp *in*)]
                        (json/parse-string raw-input true))
                      (json/parse-stream *in* true))]
      (when debug-mode?
        (save-hook-debug-json (:tool_name hook-data) hook-data))
      hook-data)
    (catch Exception e
      (debug-log (str "ERROR parsing stdin: " (.getMessage e)))
      nil)))
(defn get-hook-input [args]
  (if (>= (count args) 2)
    [(first args) (second args) nil nil]
    (try
      (let [debug-mode? (some #(= "--debug" %) args)
            ;; Read stdin as string if debug mode, otherwise use stream
            hook-data (if debug-mode?
                       (let [raw-input (slurp *in*)]
                         (json/parse-string raw-input true))
                       (json/parse-stream *in* true))
            {:keys [tool_name tool_input]} hook-data
            {:keys [file_path content old_string new_string]} tool_input]

        ;; Save debug JSON if debug mode
        (when debug-mode?
          (save-hook-debug-json tool_name hook-data))

        (debug-log (str "Hook called - Tool: " tool_name " File: " file_path))
        (case tool_name
          "Write"  [file_path content tool_name tool_input]
          "Edit"   [file_path new_string tool_name tool_input]
          [nil nil nil nil]))
      (catch Exception e
        (debug-log (str "ERROR parsing stdin: " (.getMessage e)))
        [nil nil nil nil]))))

(defn apply-edit
  "Simulate Edit tool: replace first occurrence of old_string with new_string in file content."
  [file-content old-string new-string]
  (when-let [idx (str/index-of file-content old-string)]
    (str (subs file-content 0 idx)
         new-string
         (subs file-content (+ idx (count old-string))))))

(defn compute-end-delta
  "Compare two strings from the end, return chars removed and added.
   Returns [removed-suffix added-suffix] or nil if changes aren't just at end."
  [original fixed]
  (let [orig-len (count original)
        fixed-len (count fixed)
        ;; Find common prefix length
        common-prefix (loop [i 0]
                        (if (and (< i orig-len)
                                 (< i fixed-len)
                                 (= (nth original i) (nth fixed i)))
                          (recur (inc i))
                          i))]
    ;; Everything after common prefix is the delta
    [(subs original common-prefix)
     (subs fixed common-prefix)]))

(defn adjust-new-string-for-fix
  "Adjust new_string based on what parmezan changed at the end of the file."
  [new-string result-file fixed-file]
  (let [[removed added] (compute-end-delta result-file fixed-file)]
    (cond
      ;; Parmezan added closing brackets at end
      (and (empty? removed) (seq added))
      (str new-string added)

      ;; Parmezan removed extra brackets from end
      (and (seq removed) (empty? added)
           (str/ends-with? new-string removed))
      (subs new-string 0 (- (count new-string) (count removed)))

      ;; Complex change - can't adjust simply
      :else nil)))

(defn emacs-edit-command?
  "Check if a Bash command is an emacs editing command (el, ew, ed, es, ei, etc.)"
  [command]
  (when command
    (boolean (re-find #"^(el|ew|ed|es|ei|eu|esr|ess|esw|esk|est)\s" command))))

(defn handle-validate [args]
  (let [hook-data (parse-hook-input args)
        session-id (:session_id hook-data)
        tool-name (:tool_name hook-data)
        tool-input (:tool_input hook-data)]

    ;; Always take a snapshot for file change detection
    (when session-id
      (file-tracker/snapshot! session-id))

    ;; For non-Edit/Write/Bash tools, just allow
    (when-not (#{"Edit" "Write" "Bash"} tool-name)
      (allow-hook "PreToolUse"))

    ;; For Bash: only validate if it looks like an emacs edit command
    (when (= "Bash" tool-name)
      (let [command (:command tool-input)]
        (when-not (emacs-edit-command? command)
          (allow-hook "PreToolUse"))
        ;; For emacs commands, allow - emacs handles bracket fixing
        (allow-hook "PreToolUse")))

    ;; For Edit/Write: do bracket validation
    (let [{:keys [file_path content old_string new_string]} tool-input
          file-path file_path]

      ;; Skip if no file path or not a Clojure file
      (when (or (nil? file-path) (nil? (or content new_string)))
        (allow-hook "PreToolUse"))

      (when-not (validator/clojure-file? file-path (or content new_string ""))
        (allow-hook "PreToolUse"))

      ;; Compute what the file will look like after this operation
      (let [file-exists? (.exists (io/file file-path))
            current-file (when file-exists? (slurp file-path))
            result-file (case tool-name
                          "Write" content
                          "Edit" (if (and current-file old_string new_string)
                                   (apply-edit current-file old_string new_string)
                                   content)
                          content)]

        (when (nil? result-file)
          (block-hook "PreToolUse" "Could not simulate edit - old_string not found in file"))

        ;; Try to validate/fix brackets with parmezan
        (if-let [fixed (validator/auto-fix-brackets result-file)]
          (do
            ;; Create backup if in Claude Code session
            (when session-id
              (backup/create-backup file-path session-id))

            (if (= fixed result-file)
              (allow-hook "PreToolUse")
              ;; Needs fixing
              (case tool-name
                "Write"
                (allow-hook tool-name tool-input fixed)

                "Edit"
                (if-let [adjusted (adjust-new-string-for-fix new_string result-file fixed)]
                  (allow-hook tool-name tool-input adjusted)
                  (allow-hook "PreToolUse"))

                (allow-hook "PreToolUse"))))
          ;; If parmezan couldn't fix it at all, block
          (block-hook "PreToolUse" (str "Syntax error in " file-path ": unable to auto-fix delimiter errors")))))))

(defn diff-lines
  "Compare original and fixed content, return info about changed lines."
  [original fixed]
  (let [orig-lines (str/split-lines original)
        fixed-lines (str/split-lines fixed)
        orig-count (count orig-lines)
        fixed-count (count fixed-lines)
        max-lines (max orig-count fixed-count)]
    (loop [i 0
           changes []]
      (if (>= i max-lines)
        changes
        (let [orig-line (get orig-lines i)
              fixed-line (get fixed-lines i)]
          (recur (inc i)
                 (cond
                   ;; Line added
                   (nil? orig-line)
                   (conj changes {:line (inc i) :type :added :content fixed-line})
                   ;; Line removed
                   (nil? fixed-line)
                   (conj changes {:line (inc i) :type :removed :content orig-line})
                   ;; Line changed
                   (not= orig-line fixed-line)
                   (conj changes {:line (inc i) :type :changed :from orig-line :to fixed-line})
                   ;; No change
                   :else changes)))))))

(defn format-balance-report
  "Format balance changes for Claude to understand."
  [file-path changes]
  (let [header (str "Auto-fixed brackets in " file-path ":")
        details (for [{:keys [line type content from to]} changes]
                  (case type
                    :added (str "  Line " line ": added \"" content "\"")
                    :removed (str "  Line " line ": removed \"" content "\"")
                    :changed (str "  Line " line ": \"" from "\" → \"" to "\"")))]
    (str/join "\n" (cons header details))))

(defn balance-file!
  "Run balance on file, writing fixes in-place.
   Returns nil if no fix needed, or a report string if fixes were made."
  [file-path]
  (when (validator/clojure-file? file-path)
    (let [content (slurp file-path)
          fixed (validator/auto-fix-brackets content)]
      (when (and fixed (not= fixed content))
        (let [changes (diff-lines content fixed)
              report (format-balance-report file-path changes)]
          (debug-log report)
          (spit file-path fixed)
          report)))))

(defn handle-eval [args]
  (let [hook-data (parse-hook-input args)
        session-id (:session_id hook-data)]

    ;; No session = no tracking, just allow
    (when-not session-id
      (allow-hook "PostToolUse"))

    ;; Detect changed files
    (let [changed-files (file-tracker/detect-changes session-id)]

      ;; No changes detected
      (when (empty? changed-files)
        (allow-hook "PostToolUse"))

      ;; Balance and reload each changed file
      (let [port (resolve-port nil nil)
            host (resolve-host nil)
            results (for [file-path changed-files
                          :when (and (validator/clojure-file? file-path)
                                     (.exists (io/file file-path)))]
                      (let [balance-report (balance-file! file-path)
                            eval-result (when port
                                          (eval-file host port file-path {:hook true}))
                            eval-error (when (:has-error? eval-result)
                                         (get-in eval-result [:processed :ex] "Unknown error"))]
                        {:file file-path
                         :balanced balance-report
                         :error eval-error}))
            messages (->> results
                          (mapcat (fn [{:keys [file balanced error]}]
                                    (cond-> []
                                      balanced (conj (str "Fixed brackets in " file))
                                      error (conj (str "Error in " file ": " error)))))
                          (remove nil?))]

        ;; Update snapshot for next comparison
        (file-tracker/snapshot! session-id)

        (if (seq messages)
          (json-exit {:hookSpecificOutput
                      {:hookEventName "PostToolUse"
                       :permissionDecision "allow"
                       :permissionDecisionReason (str/join "\n" messages)}} 0)
          (allow-hook "PostToolUse"))))))

(defn handle-install [args]
  (let [opts (cli/parse-opts args {:spec {:strict-eval {:coerce :boolean}
                                           :debug {:coerce :boolean}}})
        status (installer/check-status)]
    (installer/install-hooks opts)
    (println "Installing Claude Code hooks...")
    (when (:debug opts)
      (println "Debug mode enabled - hook JSON will be saved to ./tmp/hooks-requests/"))
    (println "Hooks installed successfully.")
    (println)
    (when (:installed status)
      (println "Settings file:" (:settings-path status)))
    (System/exit 0)))

(defn handle-uninstall [_args]
  (installer/uninstall-hooks)
  (println "Removing Claude Code hooks...")
  (println "Hooks uninstalled successfully.")
  (System/exit 0))

(defn handle-session-end [_args]
  (let [input (try
                (json/parse-stream *in* true)
                (catch Exception e
                  (binding [*out* *err*]
                    (println "Error parsing SessionEnd event JSON:" (.getMessage e)))
                  (System/exit 1)))
        session-id (or (:session_id input) "unknown")]
    (backup/cleanup-session session-id)
    ;; Also cleanup stop hook state
    (stop-hooks/cleanup-state session-id)
    ;; Cleanup file tracker state
    (file-tracker/cleanup-state session-id)
    (System/exit 0)))

(defn handle-stop [_args]
  ;; Initialize stop-hooks dynamic vars for REPL hook execution
  (alter-var-root #'stop-hooks/*nrepl-eval-fn* (constantly eval-expression))
  (alter-var-root #'stop-hooks/*resolve-port-fn* (constantly resolve-port))
  (alter-var-root #'stop-hooks/*resolve-host-fn* (constantly resolve-host))

  ;; Parse stdin JSON to get session_id
  (let [input (try
                (json/parse-stream *in* true)
                (catch Exception e
                  (binding [*out* *err*]
                    (println "Error parsing Stop event JSON:" (.getMessage e)))
                  (System/exit 1)))
        session-id (or (:session_id input) "unknown")]

    ;; Load and validate hooks config
    (let [config (stop-hooks/load-hooks)]
      (cond
        ;; No config file - success, no hooks to run
        (nil? config)
        (System/exit 0)

        ;; Parse error in config
        (:error config)
        (do
          (binding [*out* *err*]
            (println "Error parsing .brepl/hooks.edn:" (:error config)))
          (System/exit 1))

        ;; Validate config
        :else
        (let [validation (stop-hooks/validate-hooks config)]
          (if-not (:valid? validation)
            (do
              (binding [*out* *err*]
                (println "Invalid .brepl/hooks.edn:")
                (println (:errors validation)))
              (System/exit 1))

            ;; Run hooks
            (let [result (stop-hooks/run-stop-hooks session-id config)]
              (when (pos? (:exit-code result))
                (binding [*out* *err*]
                  (println (:message result))))
              (System/exit (:exit-code result)))))))))

(defn handle-skill-install [_args]
  (let [result (installer/install-skill)]
    (if (:success result)
      (do (println "Installing brepl skill...")
          (println (:message result))
          (System/exit 0))
      (do (println "Error:" (:message result))
          (System/exit 1)))))

(defn handle-skill-uninstall [_args]
  (let [result (installer/uninstall-skill)]
    (println "Removing brepl skill...")
    (println (:message result))
    (System/exit 0)))

(defn show-hook-help [subcommand]
  (println "brepl hooks - Claude Code integration commands")
  (println)
  (println "USAGE:")
  (println "    brepl hooks validate [--debug] <file> <content>")
  (println "    brepl hooks eval [--debug] <file>")
  (println "    brepl hooks stop")
  (println "    brepl hooks install [--strict-eval] [--debug]")
  (println "    brepl hooks uninstall")
  (println "    brepl hooks session-end")
  (println)
  (println "SUBCOMMANDS:")
  (println "    validate       Validate Clojure file syntax before edit")
  (println "    eval           Evaluate file and check for runtime errors")
  (println "    stop           Run stop hooks from .brepl/hooks.edn")
  (println "    install        Install hooks in .claude/settings.local.json")
  (println "    uninstall      Remove hooks from .claude/settings.local.json")
  (println "    session-end    Clean up session backup files (reads JSON from stdin)")
  (println)
  (println "FLAGS:")
  (println "    --debug        Save hook JSON input to ./tmp/hooks-requests/")
  (println "    --strict-eval  Exit with error on eval failures (install only)")
  (System/exit (if (and subcommand
                        (not (contains? #{"validate" "eval" "stop" "install" "uninstall" "session-end"} subcommand)))
                 1 0)))

(defn show-balance-help []
  (println "brepl balance - Fix unbalanced brackets in Clojure files")
  (println)
  (println "USAGE:")
  (println "    brepl balance <file>")
  (println "    brepl balance <file> --dry-run")
  (println)
  (println "OPTIONS:")
  (println "    --dry-run      Print fixed content to stdout instead of overwriting")
  (println)
  (println "EXAMPLES:")
  (println "    brepl balance src/core.clj             # Fix file in place")
  (println "    brepl balance src/core.clj --dry-run   # Preview fix to stdout")
  (System/exit 0))

(defn handle-balance [args]
  (let [help? (some #(contains? #{"-h" "--help" "-?"} %) args)
        dry-run? (some #(= "--dry-run" %) args)
        file-args (remove #(contains? #{"-h" "--help" "-?" "--dry-run"} %) args)
        file-path (first file-args)]
    (cond
      (or help? (nil? file-path))
      (show-balance-help)

      (not (.exists (io/file file-path)))
      (do (binding [*out* *err*]
            (println "Error: File not found:" file-path))
          (System/exit 1))

      :else
      (let [content (slurp file-path)
            fixed (validator/auto-fix-brackets content)]
        (if fixed
          (if dry-run?
            (do (print fixed)
                (flush)
                (System/exit 0))
            (do (spit file-path fixed)
                (println "Fixed:" file-path)
                (System/exit 0)))
          (do (binding [*out* *err*]
                (println "Error: Unable to fix" file-path))
              (System/exit 1)))))))

(defn -main-balance
  "Handle brepl balance command"
  [args]
  (handle-balance args))

(defn show-skill-help [subcommand]
  (println "brepl skill - Claude Code skill management")
  (println)
  (println "USAGE:")
  (println "    brepl skill install")
  (println "    brepl skill uninstall")
  (println)
  (println "SUBCOMMANDS:")
  (println "    install        Install brepl skill to .claude/skills/brepl")
  (println "    uninstall      Remove brepl skill from .claude/skills/brepl")
  (System/exit (if (and subcommand
                        (not (contains? #{"install" "uninstall"} subcommand)))
                 1 0)))

(defn handle-skill-subcommand [subcommand args]
  (case subcommand
    "install"   (handle-skill-install args)
    "uninstall" (handle-skill-uninstall args)
    (show-skill-help subcommand)))

(defn handle-hook-subcommand [subcommand args]
  (case subcommand
    "validate"    (handle-validate args)
    "eval"        (handle-eval args)
    "stop"        (handle-stop args)
    "install"     (handle-install args)
    "uninstall"   (handle-uninstall args)
    "session-end" (handle-session-end args)
    (show-hook-help subcommand)))

(defn -main-hook
  "Handle brepl hook subcommands"
  [args]
  (if (empty? args)
    (handle-hook-subcommand nil nil)
    (let [subcommand (first args)
          remaining-args (rest args)]
      (handle-hook-subcommand subcommand remaining-args))))

(defn -main-skill
  "Handle brepl skill subcommands"
  [args]
  (if (empty? args)
    (handle-skill-subcommand nil nil)
    (let [subcommand (first args)
          remaining-args (rest args)]
      (handle-skill-subcommand subcommand remaining-args))))


(when (= *file* (System/getProperty "babashka.file"))
  (cond
    (and (> (count *command-line-args*) 0)
         (#{"hook" "hooks"} (first *command-line-args*)))
    (-main-hook (rest *command-line-args*))

    (and (> (count *command-line-args*) 0)
         (= "skill" (first *command-line-args*)))
    (-main-skill (rest *command-line-args*))

    (and (> (count *command-line-args*) 0)
         (= "balance" (first *command-line-args*)))
    (-main-balance (rest *command-line-args*))

    :else
    (apply -main *command-line-args*)))
