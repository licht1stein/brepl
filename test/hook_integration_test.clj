(ns hook-integration-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :as shell]
            [clojure.string :as str]
            [cheshire.core :as json]))

;; Test helpers

(defn run-hook-via-stdin
  "Run brepl hook command with JSON payload via stdin, exactly like Claude Code does"
  [subcommand payload]
  (let [json-input (json/generate-string payload)
        result (shell/sh "./brepl" "hook" subcommand
                         :in json-input
                         :out-enc "UTF-8")]
    (assoc result :response
           (when (seq (:out result))
             (try
               (json/parse-string (:out result) true)
               (catch Exception e
                 {:parse-error (.getMessage e)
                  :raw-output (:out result)}))))))

(defn create-edit-payload
  "Create Edit tool hook payload matching Claude Code structure"
  [file-path old-string new-string]
  {:hook_event_name "PreToolUse"
   :tool_name "Edit"
   :tool_input {:file_path file-path
                :old_string old-string
                :new_string new-string}})

(defn create-write-payload
  "Create Write tool hook payload matching Claude Code structure"
  [file-path content]
  {:hook_event_name "PreToolUse"
   :tool_name "Write"
   :tool_input {:file_path file-path
                :content content}})

(defn create-update-payload
  "Create Update tool hook payload matching Claude Code structure"
  [file-path content]
  {:hook_event_name "PreToolUse"
   :tool_name "Update"
   :tool_input {:file_path file-path
                :content content}})

;; Integration tests

(deftest validate-hook-edit-with-valid-code-test
  (testing "Scenario: Edit hook receives valid Clojure code via stdin"
    (let [temp-file (java.io.File/createTempFile "test-valid-" ".clj")]
      (try
        (testing "Given a Clojure file with valid code"
          (spit temp-file "(defn foo [x] (+ x 1))")
          (is (.exists temp-file)))

        (testing "When Claude sends Edit payload with valid new code via stdin"
          (let [payload (create-edit-payload
                         (.getAbsolutePath temp-file)
                         "(defn foo [x] (+ x 1))"
                         "(defn foo [x] (* x 2))")
                result (run-hook-via-stdin "validate" payload)]

            (testing "Then hook returns allow decision in correct format"
              (is (= 0 (:exit result))
                  "Should exit with code 0")
              (is (= "PreToolUse" (get-in result [:response :hookSpecificOutput :hookEventName]))
                  "Should include hookEventName")
              (is (= "allow" (get-in result [:response :hookSpecificOutput :permissionDecision]))
                  "Should use permissionDecision field")
              (is (nil? (get-in result [:response :hookSpecificOutput :updatedInput]))
                  "Should not include updatedInput for valid code"))))

        (finally
          (.delete temp-file))))))

(deftest validate-hook-edit-with-extra-paren-test
  (testing "Scenario: Edit hook receives code with extra closing paren via stdin"
    (let [temp-file (java.io.File/createTempFile "test-extra-" ".clj")]
      (try
        (testing "Given a Clojure file"
          (spit temp-file "(defn bar [y] (- y 1))")
          (is (.exists temp-file)))

        (testing "When Claude sends Edit payload with extra closing paren via stdin"
          (let [payload (create-edit-payload
                         (.getAbsolutePath temp-file)
                         "(defn bar [y] (- y 1))"
                         "(defn bar [y] (- y 1)))")
                result (run-hook-via-stdin "validate" payload)]

            (testing "Then hook returns allow with complete tool_input and correction"
              (is (= 0 (:exit result))
                  "Should exit with code 0")
              (is (= "allow" (get-in result [:response :hookSpecificOutput :permissionDecision]))
                  "Should use permissionDecision field")

              (testing "And updatedInput includes ALL tool_input fields"
                (is (= (.getAbsolutePath temp-file) (get-in result [:response :hookSpecificOutput :updatedInput :file_path]))
                    "Should include file_path")
                (is (= "(defn bar [y] (- y 1))" (get-in result [:response :hookSpecificOutput :updatedInput :old_string]))
                    "Should include old_string for Edit tool")
                (is (= "(defn bar [y] (- y 1))" (get-in result [:response :hookSpecificOutput :updatedInput :new_string]))
                    "Should provide corrected new_string"))

              (is (= "Auto-fixed bracket errors" (get-in result [:response :hookSpecificOutput :permissionDecisionReason]))
                  "Should include reason for correction"))))

        (finally
          (.delete temp-file))))))

(deftest validate-hook-write-with-syntax-error-test
  (testing "Scenario: Write hook receives code with syntax error via stdin"
    (let [temp-file (java.io.File/createTempFile "test-write-" ".clj")]
      (try
        (testing "Given a new Clojure file"
          (.delete temp-file)
          (is (not (.exists temp-file))))

        (testing "When Claude sends Write payload with missing closing bracket via stdin"
          (let [payload (create-write-payload
                         (.getAbsolutePath temp-file)
                         "(defn test [] (let [x 10] x)")
                result (run-hook-via-stdin "validate" payload)]

            (testing "Then hook returns allow with complete tool_input and correction"
              (is (= 0 (:exit result))
                  "Should exit with code 0")
              (is (= "allow" (get-in result [:response :hookSpecificOutput :permissionDecision]))
                  "Should use permissionDecision field")

              (testing "And updatedInput includes ALL tool_input fields"
                (is (= (.getAbsolutePath temp-file) (get-in result [:response :hookSpecificOutput :updatedInput :file_path]))
                    "Should include file_path")
                (is (some? (get-in result [:response :hookSpecificOutput :updatedInput :content]))
                    "Should provide corrected content for Write tool")
                (is (str/includes? (get-in result [:response :hookSpecificOutput :updatedInput :content] "") "))")
                    "Should add missing closing paren"))

              (is (= "Auto-fixed bracket errors" (get-in result [:response :hookSpecificOutput :permissionDecisionReason]))
                  "Should include reason for correction"))))

        (finally
          (when (.exists temp-file)
            (.delete temp-file)))))))

(deftest validate-hook-non-clojure-file-test
  (testing "Scenario: Hook receives non-Clojure file via stdin"
    (let [temp-file (java.io.File/createTempFile "test-txt-" ".txt")]
      (try
        (testing "Given a .txt file"
          (spit temp-file "some text")
          (is (.exists temp-file)))

        (testing "When Claude sends Edit payload for .txt file via stdin"
          (let [payload (create-edit-payload
                         (.getAbsolutePath temp-file)
                         "some text"
                         "new text")
                result (run-hook-via-stdin "validate" payload)]

            (testing "Then hook allows without validation in correct format"
              (is (= 0 (:exit result))
                  "Should exit with code 0")
              (is (= "allow" (get-in result [:response :hookSpecificOutput :permissionDecision]))
                  "Should use permissionDecision field")
              (is (nil? (get-in result [:response :hookSpecificOutput :updatedInput]))
                  "Should not provide updatedInput for non-Clojure files"))))

        (finally
          (.delete temp-file))))))

(deftest validate-hook-update-with-valid-code-test
  (testing "Scenario: Update hook receives valid Clojure code via stdin"
    (let [temp-file (java.io.File/createTempFile "test-update-valid-" ".clj")]
      (try
        (testing "Given a Clojure file"
          (spit temp-file "(defn old [] 1)")
          (is (.exists temp-file)))

        (testing "When Claude sends Update payload with valid new code via stdin"
          (let [payload (create-update-payload
                         (.getAbsolutePath temp-file)
                         "(defn new [] 2)")
                result (run-hook-via-stdin "validate" payload)]

            (testing "Then hook returns allow decision in correct format"
              (is (= 0 (:exit result))
                  "Should exit with code 0")
              (is (= "allow" (get-in result [:response :hookSpecificOutput :permissionDecision]))
                  "Should use permissionDecision field")
              (is (nil? (get-in result [:response :hookSpecificOutput :updatedInput]))
                  "Should not include updatedInput for valid code"))))

        (finally
          (.delete temp-file))))))

(deftest validate-hook-update-with-syntax-error-test
  (testing "Scenario: Update hook receives code with syntax error via stdin"
    (let [temp-file (java.io.File/createTempFile "test-update-error-" ".clj")]
      (try
        (testing "Given a Clojure file"
          (spit temp-file "(defn old [] 1)")
          (is (.exists temp-file)))

        (testing "When Claude sends Update payload with missing closing paren via stdin"
          (let [payload (create-update-payload
                         (.getAbsolutePath temp-file)
                         "(defn new [] (+ 1 2)")
                result (run-hook-via-stdin "validate" payload)]

            (testing "Then hook returns allow with complete tool_input and correction"
              (is (= 0 (:exit result))
                  "Should exit with code 0")
              (is (= "allow" (get-in result [:response :hookSpecificOutput :permissionDecision]))
                  "Should use permissionDecision field")

              (testing "And updatedInput includes ALL tool_input fields"
                (is (= (.getAbsolutePath temp-file) (get-in result [:response :hookSpecificOutput :updatedInput :file_path]))
                    "Should include file_path")
                (is (= "(defn new [] (+ 1 2))" (get-in result [:response :hookSpecificOutput :updatedInput :content]))
                    "Should provide corrected content for Update tool"))

              (is (= "Auto-fixed bracket errors" (get-in result [:response :hookSpecificOutput :permissionDecisionReason]))
                  "Should include reason for auto-fix"))))

        (finally
          (.delete temp-file))))))

(deftest validate-hook-json-response-format-test
  (testing "Scenario: Validate hook returns Claude Code hookSpecificOutput format"
    (testing "Given any valid hook payload"
      (let [payload (create-write-payload
                     "/tmp/test.clj"
                     "(def x 1)")
            result (run-hook-via-stdin "validate" payload)]

        (testing "When hook processes the request"
          (testing "Then response is valid JSON with hookSpecificOutput wrapper"
            (is (map? (:response result))
                "Response should parse as JSON map")
            (is (not (contains? (:response result) :parse-error))
                "Response should not have parse errors")
            (is (contains? (:response result) :hookSpecificOutput)
                "Must have :hookSpecificOutput wrapper"))

          (testing "And hookSpecificOutput contains required fields"
            (let [hook-output (get-in result [:response :hookSpecificOutput])]
              (is (= "PreToolUse" (:hookEventName hook-output))
                  "Must have hookEventName set to PreToolUse")
              (is (contains? hook-output :permissionDecision)
                  "Must have :permissionDecision field")
              (is (contains? #{"allow" "deny"} (:permissionDecision hook-output))
                  "permissionDecision must be 'allow' or 'deny'")))

          (testing "And when denying, includes reason"
            (let [hook-output (get-in result [:response :hookSpecificOutput])]
              (when (= "deny" (:permissionDecision hook-output))
                (is (some? (:permissionDecisionReason hook-output))
                    "Deny decision must include permissionDecisionReason"))))

          (testing "And when allowing with correction, includes updatedInput"
            (let [hook-output (get-in result [:response :hookSpecificOutput])]
              (when (and (= "allow" (:permissionDecision hook-output))
                         (contains? hook-output :updatedInput))
                (is (map? (:updatedInput hook-output))
                    "updatedInput should be a map")
                (is (some? (:permissionDecisionReason hook-output))
                    "Corrections should include permissionDecisionReason")))))))))
