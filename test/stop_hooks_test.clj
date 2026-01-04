(ns stop-hooks-test
  "Tests for stop hooks functionality.

  These tests are written in TDD red-phase style - they define expected behavior
  based on the spec before the implementation exists. They will fail initially
  and pass once lib/stop_hooks.clj is implemented."
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.nrepl.server :as srv]
            [babashka.process :refer [shell]]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

;; =============================================================================
;; Test Helpers
;; =============================================================================

;; Get absolute path to brepl script (works from any cwd)
(def brepl-path
  (-> (System/getProperty "user.dir")
      (io/file "brepl")
      .getAbsolutePath))

(defn find-free-port
  "Find an available port by creating and immediately closing a ServerSocket"
  []
  (let [socket (java.net.ServerSocket. 0)
        port (.getLocalPort socket)]
    (.close socket)
    port))

(defn with-nrepl-server
  "Start nREPL server, execute function with port, then stop server"
  [f]
  (let [port (find-free-port)
        server (srv/start-server! {:port port :quiet true})]
    (try
      (Thread/sleep 100)
      (f port)
      (finally
        (srv/stop-server! server)))))

(defn with-temp-dir
  "Create a temp directory, execute function, then cleanup"
  [f]
  (let [dir (fs/create-temp-dir {:prefix "brepl-test-"})]
    (try
      (f (str dir))
      (finally
        (fs/delete-tree dir)))))

(defn create-hooks-config
  "Create .brepl/hooks.edn with given content"
  [dir content]
  (let [brepl-dir (io/file dir ".brepl")]
    (.mkdirs brepl-dir)
    (spit (io/file brepl-dir "hooks.edn") content)))

(defn create-nrepl-port-file
  "Create .nrepl-port file in directory"
  [dir port]
  (spit (io/file dir ".nrepl-port") (str port)))

(defn run-stop-hook
  "Run brepl hook stop with given stdin input and options"
  ([dir] (run-stop-hook dir {}))
  ([dir {:keys [session-id stdin-data env timeout]
         :or {session-id "test-session-123"
              timeout 10000}}]
   (let [stdin-json (json/generate-string
                     (merge {:hook_event_name "Stop"
                             :session_id session-id}
                            stdin-data))
         result (shell {:out :string
                        :err :string
                        :continue true
                        :dir dir
                        :in stdin-json
                        :timeout timeout
                        :extra-env (or env {})}
                       brepl-path "hook" "stop")]
     (assoc result
            :stdout-parsed (when (seq (:out result))
                             (try (json/parse-string (:out result) true)
                                  (catch Exception _ nil)))))))

(defn cleanup-state-files
  "Remove any state files for a session"
  [session-id]
  (let [state-file (io/file (str "/tmp/brepl-stop-hook-" session-id ".edn"))]
    (when (.exists state-file)
      (.delete state-file))))

;; =============================================================================
;; Schema Validation Tests
;; =============================================================================

(deftest valid-repl-hook-schema-test
  (testing "Scenario: Valid REPL hook"
    (testing "Given a hook map with :type :repl, :name 'tests', and :code '(run-tests)'"
      (with-temp-dir
        (fn [dir]
          (create-hooks-config dir
            "{:stop [{:type :repl
                      :name \"tests\"
                      :code \"(run-tests)\"}]}")
          (testing "When the schema is validated"
            (let [result (run-stop-hook dir)]
              (testing "Then the hook is accepted (no validation error)"
                ;; Without nREPL, optional hook should be skipped silently
                ;; The key is no schema validation error
                (is (not (re-find #"(?i)schema|validation.*failed|invalid.*hook"
                                  (str (:err result))))
                    "Should not report schema validation errors")))))))))

(deftest valid-bash-hook-schema-test
  (testing "Scenario: Valid bash hook"
    (testing "Given a hook map with :type :bash, :name 'lint', and :command 'clj-kondo --lint src'"
      (with-temp-dir
        (fn [dir]
          (create-hooks-config dir
            "{:stop [{:type :bash
                      :name \"lint\"
                      :command \"echo hello\"}]}")
          (testing "When the schema is validated"
            (let [result (run-stop-hook dir)]
              (testing "Then the hook is accepted"
                ;; Should execute without schema errors
                (is (= 0 (:exit result))
                    "Should exit 0 for successful bash hook")))))))))

(deftest missing-required-field-schema-test
  (testing "Scenario: Missing required field"
    (testing "Given a REPL hook missing the :code field"
      (with-temp-dir
        (fn [dir]
          (create-hooks-config dir
            "{:stop [{:type :repl}]}")
          (testing "When the schema is validated"
            (let [result (run-stop-hook dir)]
              (testing "Then validation fails"
                (is (= 1 (:exit result))
                    "Should exit 1 for validation error")
                (is (re-find #"(?i)code|invalid"
                             (str (:err result)))
                    "Should mention missing :code field")))))))))

(deftest invalid-field-type-schema-test
  (testing "Scenario: Invalid field type"
    (testing "Given a hook map with :timeout \"sixty\" (string instead of integer)"
      (with-temp-dir
        (fn [dir]
          (create-hooks-config dir
            "{:stop [{:type :repl
                      :name \"test\"
                      :code \"(+ 1 2)\"
                      :timeout \"sixty\"}]}")
          (testing "When the schema is validated"
            (let [result (run-stop-hook dir)]
              (testing "Then validation fails with explanation of type mismatch"
                (is (= 1 (:exit result))
                    "Should exit 1 for validation error")
                (is (re-find #"(?i)timeout|integer|type"
                             (str (:err result)))
                    "Should mention type mismatch for :timeout")))))))))

;; =============================================================================
;; REPL Hook Execution Tests
;; =============================================================================

(deftest successful-repl-hook-execution-test
  (testing "Scenario: Successful REPL hook execution"
    (with-nrepl-server
      (fn [port]
        (testing "Given a REPL hook with :code '(+ 1 2)' and an nREPL server is running"
          (with-temp-dir
            (fn [dir]
              (create-hooks-config dir
                (str "{:stop [{:type :repl
                               :name \"simple-eval\"
                               :code \"(+ 1 2)\"}]}"))
              (create-nrepl-port-file dir port)
              (testing "When the stop hook executes"
                (let [result (run-stop-hook dir)]
                  (testing "Then the hook is marked as successful"
                    (is (= 0 (:exit result))
                        "Should exit 0 for successful REPL evaluation")))))))))))

(deftest repl-hook-with-evaluation-error-test
  (testing "Scenario: REPL hook with evaluation error"
    (with-nrepl-server
      (fn [port]
        (testing "Given a REPL hook with :code '(/ 1 0)' and an nREPL server is running"
          (with-temp-dir
            (fn [dir]
              (create-hooks-config dir
                (str "{:stop [{:type :repl
                               :name \"divide-by-zero\"
                               :code \"(/ 1 0)\"}]}"))
              (create-nrepl-port-file dir port)
              (testing "When the stop hook executes"
                (let [result (run-stop-hook dir)]
                  (testing "Then the hook is marked as failed"
                    (is (= 1 (:exit result))
                        "Should exit 1 for evaluation error"))
                  (testing "And the error message is captured"
                    (is (re-find #"(?i)divide|arithmetic|zero"
                                 (str (:err result)))
                        "Should capture division by zero error")))))))))))

(deftest no-nrepl-with-required-hook-test
  (testing "Scenario: No nREPL available with required hook"
    (testing "Given a REPL hook with :required? true and no nREPL server is running"
      (with-temp-dir
        (fn [dir]
          (create-hooks-config dir
            "{:stop [{:type :repl
                      :name \"required-tests\"
                      :code \"(run-tests)\"
                      :required? true}]}")
          ;; No .nrepl-port file, no server
          (testing "When the stop hook executes first time"
            (let [result (run-stop-hook dir)]
              (testing "Then it blocks (exit 2) to force Claude to react"
                (is (= 2 (:exit result))
                    "Should exit 2 on first attempt to force Claude to inform user"))
              (testing "And outputs message asking Claude to inform user"
                (is (re-find #"(?i)nrepl|inform.*user|not.*running"
                             (str (:err result)))
                    "Should mention nREPL unavailable for required hook")))))))))

(deftest no-nrepl-with-optional-hook-test
  (testing "Scenario: No nREPL available with optional hook"
    (testing "Given a REPL hook with :required? false and no nREPL server is running"
      (with-temp-dir
        (fn [dir]
          (create-hooks-config dir
            "{:stop [{:type :repl
                      :name \"optional-tests\"
                      :code \"(run-tests)\"
                      :required? false}]}")
          ;; No .nrepl-port file, no server
          (testing "When the stop hook executes"
            (let [result (run-stop-hook dir)]
              (testing "Then the hook is skipped silently"
                (is (= 0 (:exit result))
                    "Should exit 0 when optional hook skipped"))
              (testing "And execution continues (no error about missing nREPL)"
                (is (not (re-find #"(?i)error|fail"
                                  (str (:err result))))
                    "Should not report errors for skipped optional hook")))))))))

;; =============================================================================
;; Bash Hook Execution Tests
;; =============================================================================

(deftest successful-bash-hook-test
  (testing "Scenario: Successful bash hook execution"
    (testing "Given a bash hook with :command 'echo hello'"
      (with-temp-dir
        (fn [dir]
          (create-hooks-config dir
            "{:stop [{:type :bash
                      :name \"echo-test\"
                      :command \"echo hello\"}]}")
          (testing "When the stop hook executes"
            (let [result (run-stop-hook dir)]
              (testing "Then exit code 0 marks success"
                (is (= 0 (:exit result))
                    "Should exit 0 for successful bash command")))))))))

(deftest bash-hook-with-non-zero-exit-test
  (testing "Scenario: Bash hook with non-zero exit"
    (testing "Given a bash hook with :command 'exit 1'"
      (with-temp-dir
        (fn [dir]
          (create-hooks-config dir
            "{:stop [{:type :bash
                      :name \"failing-command\"
                      :command \"exit 1\"}]}")
          (testing "When the stop hook executes"
            (let [result (run-stop-hook dir)]
              (testing "Then the hook is marked as failed"
                (is (= 1 (:exit result))
                    "Should exit 1 for failed bash command")))))))))

(deftest bash-hook-with-cwd-test
  (testing "Scenario: Bash hook with custom working directory"
    (testing "Given a bash hook with :cwd pointing to /tmp"
      (with-temp-dir
        (fn [dir]
          ;; Create a subdirectory and marker file
          (let [sub-dir (io/file dir "subdir")]
            (.mkdirs sub-dir)
            (spit (io/file sub-dir "marker.txt") "found"))
          (create-hooks-config dir
            "{:stop [{:type :bash
                      :name \"cwd-test\"
                      :command \"cat marker.txt\"
                      :cwd \"subdir\"}]}")
          (testing "When the stop hook executes"
            (let [result (run-stop-hook dir)]
              (testing "Then command runs in the specified directory"
                (is (= 0 (:exit result))
                    "Should exit 0 when command finds file in cwd")))))))))

(deftest bash-hook-with-env-test
  (testing "Scenario: Bash hook with environment variables"
    (testing "Given a bash hook with :env {\"TEST_VAR\" \"test_value\"}"
      (with-temp-dir
        (fn [dir]
          (create-hooks-config dir
            "{:stop [{:type :bash
                      :name \"env-test\"
                      :command \"test \\\"$TEST_VAR\\\" = \\\"test_value\\\"\"
                      :env {\"TEST_VAR\" \"test_value\"}}]}")
          (testing "When the stop hook executes"
            (let [result (run-stop-hook dir)]
              (testing "Then command has TEST_VAR set"
                (is (= 0 (:exit result))
                    "Should exit 0 when env var is correctly set")))))))))

;; =============================================================================
;; State Persistence Tests
;; =============================================================================

(deftest track-retry-count-test
  (testing "Scenario: Track retry count across invocations"
    (testing "Given a failing hook with :required? true"
      (with-temp-dir
        (fn [dir]
          (let [session-id (str "retry-test-" (System/currentTimeMillis))]
            (try
              (create-hooks-config dir
                "{:stop [{:type :bash
                          :name \"always-fails\"
                          :command \"exit 1\"
                          :required? true
                          :max-retries 5}]}")
              (testing "When invoked twice"
                (let [result1 (run-stop-hook dir {:session-id session-id})
                      result2 (run-stop-hook dir {:session-id session-id})]
                  (testing "Then exit code is 2 (retry) both times"
                    (is (= 2 (:exit result1))
                        "First invocation should return exit 2")
                    (is (= 2 (:exit result2))
                        "Second invocation should return exit 2"))))
              (finally
                (cleanup-state-files session-id)))))))))

(deftest reset-retry-count-on-success-test
  (testing "Scenario: Reset retry count on success"
    (with-temp-dir
      (fn [dir]
        (let [session-id (str "reset-test-" (System/currentTimeMillis))
              counter-file (io/file dir "counter")]
          (try
            ;; Use a counter file to make the hook fail first, then succeed
            (spit counter-file "0")
            (create-hooks-config dir
              (str "{:stop [{:type :bash
                             :name \"eventually-passes\"
                             :command \"count=$(cat counter); if [ $count -lt 2 ]; then echo $((count + 1)) > counter; exit 1; else exit 0; fi\"
                             :required? true}]}"))
            ;; Run hook 3 times - should fail, fail, succeed
            (testing "Given a hook that failed multiple times then succeeds"
              (run-stop-hook dir {:session-id session-id})
              (run-stop-hook dir {:session-id session-id})
              (let [result3 (run-stop-hook dir {:session-id session-id})]
                (testing "Then retry count resets to 0 on success"
                  (is (= 0 (:exit result3))
                      "Should exit 0 when hook finally succeeds"))))
            (finally
              (cleanup-state-files session-id))))))))

;; =============================================================================
;; Orchestration Tests
;; =============================================================================

(deftest all-hooks-pass-test
  (testing "Scenario: All hooks pass"
    (testing "Given multiple passing hooks"
      (with-temp-dir
        (fn [dir]
          (create-hooks-config dir
            "{:stop [{:type :bash
                      :name \"first\"
                      :command \"echo first\"}
                     {:type :bash
                      :name \"second\"
                      :command \"echo second\"}
                     {:type :bash
                      :name \"third\"
                      :command \"echo third\"}]}")
          (testing "When run-stop-hooks executes"
            (let [result (run-stop-hook dir)]
              (testing "Then exit code is 0"
                (is (= 0 (:exit result))
                    "Should exit 0 when all hooks pass")))))))))

(deftest blocking-hook-fails-retry-test
  (testing "Scenario: Blocking hook fails (retry)"
    (testing "Given hook with :required? true that fails"
      (with-temp-dir
        (fn [dir]
          (let [session-id (str "blocking-retry-" (System/currentTimeMillis))]
            (try
              (create-hooks-config dir
                "{:stop [{:type :bash
                          :name \"blocking-fail\"
                          :command \"exit 1\"
                          :required? true
                          :max-retries 5}]}")
              (testing "And retry count < max-retries"
                (testing "When run-stop-hooks executes"
                  (let [result (run-stop-hook dir {:session-id session-id})]
                    (testing "Then exit code is 2 (Claude must continue)"
                      (is (= 2 (:exit result))
                          "Should exit 2 to force Claude to continue")))))
              (finally
                (cleanup-state-files session-id)))))))))

(deftest blocking-hook-exhausts-retries-test
  (testing "Scenario: Blocking hook exhausts retry limit"
    (testing "Given hook with :required? true, :max-retries 3"
      (with-temp-dir
        (fn [dir]
          (let [session-id (str "exhaust-retries-" (System/currentTimeMillis))]
            (try
              (create-hooks-config dir
                "{:stop [{:type :bash
                          :name \"exhaust-retries\"
                          :command \"exit 1\"
                          :required? true
                          :max-retries 3}]}")
              (testing "And hook has failed 3 times"
                ;; Run 3 times to exhaust retries
                (run-stop-hook dir {:session-id session-id})
                (run-stop-hook dir {:session-id session-id})
                (run-stop-hook dir {:session-id session-id})
                (testing "When run-stop-hooks executes again"
                  (let [result (run-stop-hook dir {:session-id session-id})]
                    (testing "Then exit code is 1 (retry limit reached, Claude can stop)"
                      (is (= 1 (:exit result))
                          "Should exit 1 when retries exhausted"))
                    (testing "And outputs message about retry limit"
                      (is (re-find #"(?i)retry|limit|exhaust|max"
                                   (str (:err result)))
                          "Should mention retry limit reached")))))
              (finally
                (cleanup-state-files session-id)))))))))

(deftest non-blocking-hook-fails-test
  (testing "Scenario: Non-blocking hook fails"
    (testing "Given hook with :required? false that fails"
      (with-temp-dir
        (fn [dir]
          (create-hooks-config dir
            "{:stop [{:type :bash
                      :name \"non-blocking-fail\"
                      :command \"exit 1\"
                      :required? false}]}")
          (testing "When run-stop-hooks executes"
            (let [result (run-stop-hook dir)]
              (testing "Then exit code is 1 (Claude informed, can stop)"
                (is (= 1 (:exit result))
                    "Should exit 1 for non-blocking failure")))))))))

;; =============================================================================
;; Configuration Loading Tests
;; =============================================================================

(deftest no-config-file-test
  (testing "Scenario: No configuration file exists"
    (testing "Given no .brepl/hooks.edn file exists"
      (with-temp-dir
        (fn [dir]
          ;; Don't create hooks.edn
          (testing "When brepl hook stop executes"
            (let [result (run-stop-hook dir)]
              (testing "Then brepl returns success with no hooks executed"
                (is (= 0 (:exit result))
                    "Should exit 0 when no config exists")))))))))

(deftest invalid-config-format-test
  (testing "Scenario: Invalid configuration format"
    (testing "Given .brepl/hooks.edn contains malformed EDN"
      (with-temp-dir
        (fn [dir]
          (create-hooks-config dir "{:stop [invalid edn here")
          (testing "When brepl hook stop executes"
            (let [result (run-stop-hook dir)]
              (testing "Then brepl exits with code 1"
                (is (= 1 (:exit result))
                    "Should exit 1 for malformed config"))
              (testing "And outputs error to stderr"
                (is (re-find #"(?i)error|invalid|parse|edn"
                             (str (:err result)))
                    "Should mention parse error")))))))))

;; =============================================================================
;; Claude Code Integration Tests
;; =============================================================================

(deftest receive-stop-event-input-test
  (testing "Scenario: Receive stop event input from Claude Code"
    (testing "Given Claude Code fires the Stop event"
      (with-temp-dir
        (fn [dir]
          (create-hooks-config dir
            "{:stop [{:type :bash
                      :name \"simple\"
                      :command \"echo done\"}]}")
          (testing "When brepl hook stop receives JSON input via stdin"
            (let [result (run-stop-hook dir {:stdin-data {:transcript_path "/tmp/transcript.txt"
                                                          :some_other_field "value"}})]
              (testing "Then it parses session_id and proceeds with hook execution"
                (is (= 0 (:exit result))
                    "Should successfully execute hooks with Claude Code input")))))))))
