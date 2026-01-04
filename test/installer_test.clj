(ns installer-test
  "Tests for installer idempotent merge functionality.

  These tests verify that brepl hook install correctly merges hooks
  with existing Claude settings without destroying non-brepl hooks."
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [cheshire.core :as json]))

;; Load installer lib
(load-file "lib/installer.clj")
(require '[brepl.lib.installer :as sut])

;; =============================================================================
;; Test Helpers
;; =============================================================================

(defn with-temp-dir
  "Create a temp directory, execute function, then cleanup"
  [f]
  (let [dir (fs/create-temp-dir {:prefix "installer-test-"})]
    (try
      (f (str dir))
      (finally
        (fs/delete-tree dir)))))

(defn create-settings-file
  "Create .claude/settings.local.json with given content"
  [dir content]
  (let [claude-dir (io/file dir ".claude")]
    (.mkdirs claude-dir)
    (spit (io/file claude-dir "settings.local.json")
          (json/generate-string content {:pretty true}))))

(defn read-settings-file
  "Read .claude/settings.local.json as EDN"
  [dir]
  (let [settings-file (io/file dir ".claude" "settings.local.json")]
    (when (.exists settings-file)
      (json/parse-string (slurp settings-file) true))))

;; =============================================================================
;; Hook Identification Tests
;; =============================================================================

(deftest brepl-hook-identification-test
  (testing "brepl-hook? correctly identifies brepl hooks"
    (testing "Hook with brepl command is identified as brepl hook"
      (is (sut/brepl-hook? {:matcher "Edit"
                            :hooks [{:type "command"
                                     :command "brepl hook validate"}]})
          "Should identify brepl hook validate"))

    (testing "Hook with brepl hook eval is identified as brepl hook"
      (is (sut/brepl-hook? {:matcher "Edit|Write"
                            :hooks [{:type "command"
                                     :command "brepl hook eval --debug"}]})
          "Should identify brepl hook with flags"))

    (testing "Hook with non-brepl command is not identified as brepl hook"
      (is (not (sut/brepl-hook? {:matcher "Write"
                                 :hooks [{:type "command"
                                          :command "prettier --write"}]}))
          "Should not identify prettier as brepl hook"))

    (testing "Hook with eslint command is not identified as brepl hook"
      (is (not (sut/brepl-hook? {:matcher "*"
                                 :hooks [{:type "command"
                                          :command "eslint --fix"}]}))
          "Should not identify eslint as brepl hook"))))

;; =============================================================================
;; Merge Hook Event Tests
;; =============================================================================

(deftest merge-hook-event-test
  (testing "merge-hook-event preserves non-brepl hooks and adds new brepl hooks"
    (let [existing [{:matcher "Write"
                     :hooks [{:type "command"
                              :command "prettier --write"}]}
                    {:matcher "Edit"
                     :hooks [{:type "command"
                              :command "brepl hook validate"}]}]
          new-entries [{:matcher "Edit|Write"
                        :hooks [{:type "command"
                                 :command "brepl hook validate"}]}]
          result (sut/merge-hook-event existing new-entries)]

      (testing "Result contains prettier hook"
        (is (some #(= "prettier --write" (get-in % [:hooks 0 :command])) result)
            "Should preserve prettier hook"))

      (testing "Result contains new brepl hook"
        (is (some #(= "brepl hook validate" (get-in % [:hooks 0 :command])) result)
            "Should include new brepl hook"))

      (testing "Old brepl hook is removed"
        (is (= 1 (count (filter #(re-find #"brepl hook" (get-in % [:hooks 0 :command] "")) result)))
            "Should have exactly one brepl hook after merge")))))

;; =============================================================================
;; Full Merge Tests
;; =============================================================================

(deftest merge-hooks-preserves-non-brepl-test
  (testing "Scenario: Preserve non-brepl hooks during install"
    (testing "Given settings with a prettier PostToolUse hook"
      (let [existing-hooks {:PostToolUse [{:matcher "Write"
                                           :hooks [{:type "command"
                                                    :command "prettier --write"}]}
                                          {:matcher "Edit"
                                           :hooks [{:type "command"
                                                    :command "brepl hook eval"}]}]}
            new-hooks {:PreToolUse [{:matcher "Edit|Write"
                                     :hooks [{:type "command"
                                              :command "brepl hook validate"}]}]
                       :PostToolUse [{:matcher "Edit|Write"
                                      :hooks [{:type "command"
                                               :command "brepl hook eval"}]}]
                       :Stop [{:matcher ""
                               :hooks [{:type "command"
                                        :command "brepl hook stop"}]}]}
            result (sut/merge-hooks existing-hooks new-hooks)]

        (testing "When brepl hook install runs"
          (testing "Then prettier hook is preserved"
            (is (some #(= "prettier --write" (get-in % [:hooks 0 :command]))
                      (:PostToolUse result))
                "Prettier hook should be preserved in PostToolUse"))

          (testing "And brepl hooks are added"
            (is (some #(= "brepl hook validate" (get-in % [:hooks 0 :command]))
                      (:PreToolUse result))
                "PreToolUse should have brepl validate hook")
            (is (some #(= "brepl hook eval" (get-in % [:hooks 0 :command]))
                      (:PostToolUse result))
                "PostToolUse should have brepl eval hook")
            (is (some #(= "brepl hook stop" (get-in % [:hooks 0 :command]))
                      (:Stop result))
                "Stop should have brepl stop hook")))))))

(deftest merge-hooks-replaces-old-brepl-test
  (testing "Scenario: Replace old brepl hooks"
    (testing "Given settings with old brepl hook config"
      (let [existing-hooks {:PostToolUse [{:matcher "Edit"
                                           :hooks [{:type "command"
                                                    :command "brepl hook eval --old-flag"}]}]
                            :PreToolUse [{:matcher "Write"
                                          :hooks [{:type "command"
                                                   :command "brepl hook validate --old"}]}]}
            new-hooks {:PreToolUse [{:matcher "Edit|Write"
                                     :hooks [{:type "command"
                                              :command "brepl hook validate --new"}]}]
                       :PostToolUse [{:matcher "Edit|Write"
                                      :hooks [{:type "command"
                                               :command "brepl hook eval --new"}]}]}
            result (sut/merge-hooks existing-hooks new-hooks)]

        (testing "When brepl hook install runs"
          (testing "Then old brepl hooks are replaced"
            (is (not (some #(re-find #"--old" (get-in % [:hooks 0 :command] ""))
                           (:PreToolUse result)))
                "Should not have old PreToolUse brepl hook")
            (is (not (some #(re-find #"--old" (get-in % [:hooks 0 :command] ""))
                           (:PostToolUse result)))
                "Should not have old PostToolUse brepl hook"))

          (testing "And new brepl hooks are present"
            (is (some #(re-find #"--new" (get-in % [:hooks 0 :command] ""))
                      (:PreToolUse result))
                "Should have new PreToolUse brepl hook")
            (is (some #(re-find #"--new" (get-in % [:hooks 0 :command] ""))
                      (:PostToolUse result))
                "Should have new PostToolUse brepl hook")))))))

(deftest merge-hooks-idempotent-test
  (testing "Scenario: Install is idempotent"
    (testing "Given brepl hooks already installed"
      (let [brepl-hooks {:PreToolUse [{:matcher "Edit|Write"
                                       :hooks [{:type "command"
                                                :command "brepl hook validate"}]}]
                         :PostToolUse [{:matcher "Edit|Write"
                                        :hooks [{:type "command"
                                                 :command "brepl hook eval"}]}]
                         :Stop [{:matcher ""
                                 :hooks [{:type "command"
                                          :command "brepl hook stop"}]}]}
            result (sut/merge-hooks brepl-hooks brepl-hooks)]

        (testing "When brepl hook install runs again"
          (testing "Then settings are unchanged"
            (is (= brepl-hooks result)
                "Merged hooks should equal original when merging with same hooks"))

          (testing "And no duplicate hooks are created"
            (is (= 1 (count (:PreToolUse result)))
                "Should have exactly 1 PreToolUse entry")
            (is (= 1 (count (:PostToolUse result)))
                "Should have exactly 1 PostToolUse entry")
            (is (= 1 (count (:Stop result)))
                "Should have exactly 1 Stop entry")))))))

;; =============================================================================
;; Mixed Non-Brepl Hooks Tests
;; =============================================================================

(deftest preserve-multiple-non-brepl-hooks-test
  (testing "Preserve multiple non-brepl hooks across different events"
    (let [existing-hooks {:PreToolUse [{:matcher "*"
                                        :hooks [{:type "command"
                                                 :command "custom-validator"}]}]
                          :PostToolUse [{:matcher "Write"
                                         :hooks [{:type "command"
                                                  :command "prettier --write"}]}
                                        {:matcher "*.js"
                                         :hooks [{:type "command"
                                                  :command "eslint --fix"}]}
                                        {:matcher "Edit"
                                         :hooks [{:type "command"
                                                  :command "brepl hook eval"}]}]
                          :Stop [{:matcher ""
                                  :hooks [{:type "command"
                                           :command "cleanup-script"}]}]}
          new-hooks (sut/brepl-hook-config {})
          result (sut/merge-hooks existing-hooks new-hooks)]

      (testing "All non-brepl PreToolUse hooks preserved"
        (is (some #(= "custom-validator" (get-in % [:hooks 0 :command]))
                  (:PreToolUse result))
            "Custom validator should be preserved"))

      (testing "All non-brepl PostToolUse hooks preserved"
        (is (some #(= "prettier --write" (get-in % [:hooks 0 :command]))
                  (:PostToolUse result))
            "Prettier should be preserved")
        (is (some #(= "eslint --fix" (get-in % [:hooks 0 :command]))
                  (:PostToolUse result))
            "ESLint should be preserved"))

      (testing "All non-brepl Stop hooks preserved"
        (is (some #(= "cleanup-script" (get-in % [:hooks 0 :command]))
                  (:Stop result))
            "Cleanup script should be preserved"))

      (testing "Brepl hooks are also present"
        (is (some #(re-find #"brepl hook" (get-in % [:hooks 0 :command] ""))
                  (:PreToolUse result))
            "Brepl PreToolUse hook should be present")
        (is (some #(re-find #"brepl hook" (get-in % [:hooks 0 :command] ""))
                  (:PostToolUse result))
            "Brepl PostToolUse hook should be present")
        (is (some #(re-find #"brepl hook" (get-in % [:hooks 0 :command] ""))
                  (:Stop result))
            "Brepl Stop hook should be present")))))

;; =============================================================================
;; brepl-hook-config Tests
;; =============================================================================

(deftest brepl-hook-config-includes-stop-test
  (testing "brepl-hook-config generates Stop hook configuration"
    (let [config (sut/brepl-hook-config {})]
      (testing "Stop hook is present"
        (is (contains? config :Stop)
            "Should have :Stop key"))

      (testing "Stop hook has correct command"
        (is (= "brepl hook stop" (get-in config [:Stop 0 :hooks 0 :command]))
            "Stop hook should have 'brepl hook stop' command"))

      (testing "Stop hook has empty matcher"
        (is (= "" (get-in config [:Stop 0 :matcher]))
            "Stop hook should have empty matcher")))))

(deftest brepl-hook-config-includes-session-end-test
  (testing "brepl-hook-config generates SessionEnd hook configuration"
    (let [config (sut/brepl-hook-config {})]
      (testing "SessionEnd hook is present"
        (is (contains? config :SessionEnd)
            "Should have :SessionEnd key"))

      (testing "SessionEnd hook has correct command"
        (is (= "brepl hook session-end" (get-in config [:SessionEnd 0 :hooks 0 :command]))
            "SessionEnd hook should have 'brepl hook session-end' command")))))

(deftest brepl-hook-config-debug-flag-test
  (testing "brepl-hook-config adds debug flag when requested"
    (let [config (sut/brepl-hook-config {:debug true})]
      (testing "PreToolUse has debug flag"
        (is (re-find #"--debug" (get-in config [:PreToolUse 0 :hooks 0 :command]))
            "PreToolUse should have --debug flag"))

      (testing "PostToolUse has debug flag"
        (is (re-find #"--debug" (get-in config [:PostToolUse 0 :hooks 0 :command]))
            "PostToolUse should have --debug flag"))

      (testing "Stop has debug flag"
        (is (re-find #"--debug" (get-in config [:Stop 0 :hooks 0 :command]))
            "Stop should have --debug flag")))))
