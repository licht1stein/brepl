(ns eca-test
  "Tests for ECA (Editor Code Assistant) hook integration.

  These tests verify:
  - ECA installer functions (install/uninstall to .eca/config.json)
  - ECA validate hook JSON parsing and response format"
  (:require [clojure.test :refer [deftest is testing]]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [cheshire.core :as json]
            [brepl.lib.installer :as installer]))

(defn with-temp-dir
  "Create a temp directory, execute function, then cleanup"
  [f]
  (let [dir (fs/create-temp-dir {:prefix "eca-test-"})]
    (try
      (f (str dir))
      (finally
        (fs/delete-tree dir)))))

(defn create-eca-config-file
  "Create .eca/config.json with given content"
  [dir content]
  (let [eca-dir (io/file dir ".eca")]
    (.mkdirs eca-dir)
    (spit (io/file eca-dir "config.json")
          (json/generate-string content {:pretty true}))))

(defn read-eca-config-file
  "Read .eca/config.json as EDN"
  [dir]
  (let [config-file (io/file dir ".eca" "config.json")]
    (when (.exists config-file)
      (json/parse-string (slurp config-file) true))))

(deftest eca-install-preserves-existing-test
  (testing "Scenario: Install ECA hooks preserves existing config"
    (with-temp-dir
      (fn [dir]
        (testing "Given existing .eca/config.json with other settings"
          (create-eca-config-file dir {:someOtherSetting true
                                       :hooks {:my-custom-hook {:type "preRequest"}}})
          (let [old-dir (System/getProperty "user.dir")]
            (try
              (System/setProperty "user.dir" dir)
              (with-redefs [installer/eca-config-path (constantly (str dir "/.eca/config.json"))]
                (testing "When brepl eca install runs"
                  (installer/install-eca-hooks)
                  (let [config (read-eca-config-file dir)]
                    (testing "Then other settings are preserved"
                      (is (true? (:someOtherSetting config))
                          "Should preserve someOtherSetting"))

                    (testing "And existing hooks are preserved"
                      (is (contains? (:hooks config) :my-custom-hook)
                          "Should preserve my-custom-hook"))

                    (testing "And brepl hooks are added"
                      (is (contains? (:hooks config) :brepl-validate)
                          "Should add brepl-validate hook")))))
              (finally
                (System/setProperty "user.dir" old-dir)))))))))

(deftest eca-install-replaces-old-brepl-hooks-test
  (testing "Scenario: Install replaces old brepl hooks"
    (with-temp-dir
      (fn [dir]
        (testing "Given existing brepl hooks with old config"
          (create-eca-config-file dir {:hooks {:brepl-validate {:type "preToolCall"
                                                                :matcher "old-matcher"}
                                               :my-custom-hook {:type "preRequest"}}})
          (let [old-dir (System/getProperty "user.dir")]
            (try
              (System/setProperty "user.dir" dir)
              (with-redefs [installer/eca-config-path (constantly (str dir "/.eca/config.json"))]
                (testing "When brepl eca install runs"
                  (installer/install-eca-hooks)
                  (let [config (read-eca-config-file dir)]
                    (testing "Then old brepl hook is replaced"
                      (is (not= "old-matcher" (get-in config [:hooks :brepl-validate :matcher]))
                          "Should not have old matcher"))

                    (testing "And new brepl hook is present"
                      (is (= "eca__(write_file|edit_file)" (get-in config [:hooks :brepl-validate :matcher]))
                          "Should have new matcher"))

                    (testing "And non-brepl hooks are preserved"
                      (is (contains? (:hooks config) :my-custom-hook)
                          "Should preserve my-custom-hook")))))
              (finally
                (System/setProperty "user.dir" old-dir)))))))))

(deftest eca-uninstall-removes-brepl-hooks-test
  (testing "Scenario: Uninstall removes only brepl hooks"
    (with-temp-dir
      (fn [dir]
        (testing "Given config with brepl and non-brepl hooks"
          (create-eca-config-file dir {:someOtherSetting true
                                       :hooks {:brepl-validate {:type "preToolCall"}
                                               :my-custom-hook {:type "preRequest"}}})
          (let [old-dir (System/getProperty "user.dir")]
            (try
              (System/setProperty "user.dir" dir)
              (with-redefs [installer/eca-config-path (constantly (str dir "/.eca/config.json"))]
                (testing "When brepl eca uninstall runs"
                  (installer/uninstall-eca-hooks)
                  (let [config (read-eca-config-file dir)]
                    (testing "Then brepl hooks are removed"
                      (is (not (contains? (:hooks config) :brepl-validate))
                          "Should not have brepl-validate hook"))

                    (testing "And non-brepl hooks are preserved"
                      (is (contains? (:hooks config) :my-custom-hook)
                          "Should preserve my-custom-hook"))

                    (testing "And other settings are preserved"
                      (is (true? (:someOtherSetting config))
                          "Should preserve someOtherSetting")))))
              (finally
                (System/setProperty "user.dir" old-dir)))))))))

(deftest eca-install-idempotent-test
  (testing "Scenario: Install is idempotent"
    (with-temp-dir
      (fn [dir]
        (let [old-dir (System/getProperty "user.dir")]
          (try
            (System/setProperty "user.dir" dir)
            (with-redefs [installer/eca-config-path (constantly (str dir "/.eca/config.json"))]
              (testing "When brepl eca install runs twice"
                (installer/install-eca-hooks)
                (let [config1 (read-eca-config-file dir)]
                  (installer/install-eca-hooks)
                  (let [config2 (read-eca-config-file dir)]
                    (testing "Then configs are identical"
                      (is (= config1 config2)
                          "Configs should be equal after second install"))

                    (testing "And only brepl hooks exist (no duplicates)"
                      (is (= 3 (count (filter #(installer/brepl-eca-hook? %) (keys (:hooks config2)))))
                          "Should have exactly 3 brepl hooks (validate, eval, session-end)"))))))
            (finally
              (System/setProperty "user.dir" old-dir))))))))

