(ns eca-test
  (:require [clojure.test :refer [deftest is]]
            [babashka.fs :as fs]
            [babashka.process :as process]
            [brepl-test :as helper]
            [brepl :as brepl]
            [clojure.string :as str]
            [cheshire.core :as json]
            [brepl.lib.installer :as installer]))

(def commands
  [[(str (fs/absolutize "brepl"))]
   ["bb" "--config" (str (fs/absolutize "bb.edn"))
    (str (fs/absolutize "src/brepl.clj"))]])

(defn with-temp-dir [f]
  (let [dir (str (fs/create-temp-dir {:prefix "eca-test-"}))]
    (try (f dir) (finally (fs/delete-tree dir)))))

(defn run-command [command dir args payload]
  (apply process/shell
         {:dir dir :in (json/generate-string payload)
          :out :string :err :string :continue true :timeout 10000
          :env (assoc (dissoc (into {} (System/getenv)) "ECA_CONFIG")
                      "XDG_CONFIG_HOME" dir "BREPL_HOST" "127.0.0.1")}
         (concat command ["eca"] args)))

(defn response [result] (json/parse-string (:out result) true))

(defn with-project [f]
  (with-temp-dir
    (fn [dir]
      (with-redefs [installer/eca-config-path (constantly (str dir "/.eca/config.json"))
                    installer/eca-global-config-path (constantly (str dir "/global.json"))]
        (f dir)))))

(defn put-config [config]
  (fs/create-dirs (fs/parent (installer/eca-config-path)))
  (spit (installer/eca-config-path) (json/generate-string config)))

(defn configure
  ([operation] (configure operation nil))
  ([operation flag]
   (installer/configure-eca-project operation (if flag [flag] []) {:terminal? false})))

(def operation-modes
  [[:install "--balance-only"] [:install "--with-eval"] [:uninstall nil]])

(deftest exact-direct-hook-definitions-test
  (let [full {:brepl-validate {:type "preToolCall"
                               :matcher "eca__(write_file|edit_file)"
                               :actions [{:type "shell" :shell "brepl eca validate"}]}
              :brepl-eval {:type "postToolCall"
                           :matcher "eca__(write_file|edit_file)"
                           :actions [{:type "shell" :shell "brepl eca eval"}]}}]
    (is (= (-> full
               (assoc-in [:brepl-validate :visible] false)
               (assoc-in [:brepl-eval :visible] false))
           (installer/eca-mode-hooks :with-eval)))
    (is (= (-> full
               (assoc-in [:brepl-validate :visible] false)
               (assoc-in [:brepl-eval :visible] false)
               (assoc-in [:brepl-eval :actions 0 :shell] "brepl eca eval --balance-only"))
           (installer/eca-mode-hooks :balance-only)))
    (is (= {} (installer/eca-mode-hooks :uninstall)))
    (is (= (assoc full :brepl-session-end
                  {:type "sessionEnd" :actions [{:type "shell" :shell "brepl eca session-end"}]})
           (installer/brepl-eca-hook-config)))))

(deftest exact-generated-hook-recognition-test
  ;; Independent fixtures: do not derive historical definitions from the generator.
  (let [validate {:type "preToolCall" :matcher "eca__(write_file|edit_file)"
                  :actions [{:type "shell" :shell "brepl eca validate"}]}
        eval-hook {:type "postToolCall" :matcher "eca__(write_file|edit_file)"
                   :actions [{:type "shell" :shell "brepl eca eval"}]}
        balance {:type "postToolCall" :matcher "eca__(write_file|edit_file)"
                 :actions [{:type "shell" :shell "brepl eca eval --balance-only"}]}
        session {:type "sessionEnd"
                 :actions [{:type "shell" :shell "brepl eca session-end"}]}]
    (doseq [[key hook] [[:brepl-validate validate] [:brepl-eval eval-hook]
                       [:brepl-eval balance]]
            definition [hook (assoc hook :visible false)]]
      (is (= {:hooks {}} (installer/remove-legacy-eca-hooks {:hooks {key definition}})))
      (doseq [custom [(assoc definition :visible true)
                      (assoc definition :visible nil)
                      (assoc definition :visible "false")
                      (assoc definition :extra true)
                      (assoc-in definition [:actions 0 :visible] false)]]
        (is (thrown-with-msg? Exception #"Customized legacy hook"
                             (installer/remove-legacy-eca-hooks {:hooks {key custom}})))))
    (is (= {:hooks {}} (installer/remove-legacy-eca-hooks {:hooks {:brepl-session-end session}})))
    (is (thrown? Exception (installer/remove-legacy-eca-hooks
                           {:hooks {:brepl-session-end (assoc session :visible false)}})))))

(deftest short-mode-messages-test
  (doseq [[op flag changed unchanged needs-restart? has-tip?]
          [[:install "--balance-only"
            "Bracket repair hooks installed; evaluation disabled."
            "bracket repair hooks installed; evaluation disabled." true true]
           [:install "--with-eval"
            "Bracket repair and evaluation hooks installed."
            "bracket repair and evaluation hooks installed." true true]
           [:uninstall nil
            "Project brepl hooks removed."
            "no project brepl hooks found." false false]]]
    (with-project
      (fn [_]
        (when (= op :uninstall)
          (put-config {:hooks {:brepl-eval {:type "postToolCall"
                                           :matcher "eca__(write_file|edit_file)"
                                           :actions [{:type "shell" :shell "brepl eca eval"}]}}}))
        (with-out-str
          (let [expected (cond-> changed
                           needs-restart? (str " Restart ECA.")
                           has-tip? (str "\n" installer/eca-skill-tip))]
            (is (= expected (:message (configure op flag))))))
        (with-redefs [installer/write-eca-config (fn [_] (is false "No-op must not write"))]
          (is (= "" (with-out-str
                      (let [result (configure op flag)
                            expected (cond-> (str "Unchanged: " unchanged)
                                       has-tip? (str "\n" installer/eca-skill-tip))]
                        (is (false? (:changed? result)))
                        (is (= expected (:message result)))
                        (is (= (installer/eca-config-path) (:config-path result))))))))))))

(deftest warning-before-input-and-write-test
  (doseq [existing? [false true] menu? [false true]]
    (with-project
      (fn [_]
        (when existing? (put-config {}))
        (let [output (java.io.StringWriter.)
              write-config installer/write-eca-config
              check-output (fn []
                             (is (= (if existing? 1 0)
                                    (count (re-seq #"Saving changes removes JSON comments" (str output))))))]
          (binding [*out* output]
            (with-redefs [installer/write-eca-config
                          (fn [config]
                            (check-output)
                            (write-config config)
                            (println "WRITE"))]
              (let [result (installer/configure-eca-project :install
                             (if menu? [] ["--balance-only"])
                             {:terminal? menu?
                              :read-line-fn (fn []
                                              (check-output)
                                              (is (str/starts-with? (str output)
                                                    (str "Config file: " (installer/eca-config-path) "\n")))
                                              "1")})]
                (is (= menu? (:config-path-shown? result)))
                (is (not (str/includes? (:message result) "Warning:"))))))
          (check-output)
          (is (str/ends-with? (str output) "WRITE\n")))))))

(deftest updated-eca-config-test
  (let [full {:brepl-validate {:type "preToolCall"
                              :visible false
                              :matcher "eca__(write_file|edit_file)"
                              :actions [{:type "shell" :shell "brepl eca validate"}]}
              :brepl-eval {:type "postToolCall"
                           :visible false
                           :matcher "eca__(write_file|edit_file)"
                           :actions [{:type "shell" :shell "brepl eca eval"}]}}
        balance (assoc-in full [:brepl-eval :actions 0 :shell] "brepl eca eval --balance-only")
        cleaned {:setting true :hooks {:custom {:type "preRequest"}}}]
    (doseq [[mode hooks] [[:balance-only balance] [:with-eval full] [:uninstall {}]]]
      (is (= (update cleaned :hooks merge hooks)
             (installer/updated-eca-config {:cleaned cleaned} mode))))
    (doseq [[mode expected] [[:balance-only {:hooks balance}] [:with-eval {:hooks full}]
                            [:uninstall {}]]]
      (is (= expected (installer/updated-eca-config {:cleaned {}} mode))))))

(deftest flags-before-config-or-stdin-test
  (with-redefs [installer/eca-project-state (fn [] (is false "Must not read config"))]
    (doseq [op [:install :uninstall]]
      (is (:success (installer/configure-eca-project op ["--help"] {:terminal? false})))
      (doseq [args [["--bogus"] ["--help" "--bogus"]]]
        (is (thrown? Exception
                     (installer/configure-eca-project op args
                       {:terminal? false :read-line-fn #(is false "Must not read stdin")})))))
    (is (thrown? Exception
                 (installer/configure-eca-project :install ["--balance-only" "--with-eval"]
                   {:terminal? false :read-line-fn #(is false "Must not read stdin")})))
    (is (thrown? Exception
                 (installer/configure-eca-project :install []
                   {:terminal? false :read-line-fn #(is false "Must not read stdin")})))))

(deftest project-modes-preserve-unrelated-settings-test
  (doseq [[flag mode] [["--balance-only" :balance-only] ["--with-eval" :with-eval]]]
    (with-project
      (fn [_]
        (let [other {:brepl-custom {:type "preRequest"}}
              config {:setting true :hooks (merge other (installer/brepl-eca-hook-config))}]
          (put-config config)
          (let [result (configure :install flag)]
            (is (:changed? result)))
          (is (= (assoc config :hooks (merge other (installer/eca-mode-hooks mode)))
                 (installer/read-eca-config)))
          (with-redefs [installer/write-eca-config (fn [_] (is false "No second write"))]
            (is (false? (:changed? (configure :install flag))))))))))

(deftest transitions-and-uninstall-test
  (with-project
    (fn [_]
      (doseq [mode [:with-eval :balance-only :with-eval]]
        (configure :install (str "--" (name mode)))
        (is (= (installer/eca-mode-hooks mode) (:hooks (installer/read-eca-config)))))
      (configure :uninstall)
      (is (= {:hooks {}} (installer/read-eca-config)))))
  (with-project
    (fn [_]
      (let [config {:hooks (assoc (installer/brepl-eca-hook-config) :brepl-other {:custom true})}]
        (put-config config)
        (configure :uninstall)
        (is (= (assoc config :hooks {:brepl-other {:custom true}})
               (installer/read-eca-config))))))
  (with-project
    (fn [dir]
      (is (false? (:changed? (configure :uninstall))))
      (is (not (fs/exists? (str dir "/.eca")))))))

(deftest customized-known-hooks-abort-test
  (doseq [hooks [(installer/brepl-eca-hook-config) (installer/eca-mode-hooks :balance-only)]
          [key definition] hooks
          value [nil (assoc definition :extra true) (assoc definition :matcher "custom")
                 (update definition :actions conj {:type "shell" :shell "echo custom"})]]
    (with-project
      (fn [_]
        (put-config {:hooks (assoc hooks key value)})
        (let [before (slurp (installer/eca-config-path))]
          (doseq [[op flag] operation-modes]
            (is (thrown-with-msg? Exception #"Customized legacy hook" (configure op flag)))
            (is (= before (slurp (installer/eca-config-path))))))))))

(deftest invalid-config-no-writes-test
  (doseq [raw ["{" "null" "[]" "false" "{} {}" "{} garbage" "" "/* unterminated" "// comment\n{\"a\":"
               "{\"hooks\":null}"]]
    (with-project
      (fn [_]
        (put-config {})
        (spit (installer/eca-config-path) raw)
        (doseq [[op flag] operation-modes]
          (is (thrown? Exception (configure op flag)) raw)
          (is (= raw (slurp (installer/eca-config-path)))))))))

(deftest global-config-is-not-modified-test
  (with-project
    (fn [_]
      (let [global "{\"plugins\":{\"install\":[\"other@eca\"]}}"]
        (spit (installer/eca-global-config-path) global)
        (configure :install "--balance-only")
        (is (= (installer/eca-mode-hooks :balance-only) (:hooks (installer/read-eca-config))))
        (is (= global (slurp (installer/eca-global-config-path))))))))

(deftest commented-project-test
  (with-project
    (fn [_]
      (put-config {})
      (spit (installer/eca-config-path)
            "{/* note */\"setting\":true,// note\n\"url\":\"https://example.test/*path*/\",\"plugins\":{\"installMode\":\"append\"}}")
      (is (= (str installer/eca-json-warning "\n")
             (with-out-str (configure :install "--balance-only"))))
      (is (= {:setting true :url "https://example.test/*path*/"
              :plugins {:installMode "append"}
              :hooks (installer/eca-mode-hooks :balance-only)}
             (installer/read-eca-config))))))

(deftest explicit-menu-choices-test
  (doseq [[input expected] [["1" :balance-only] ["2" :with-eval]
                           ["balance-only" :balance-only] ["with-eval" :with-eval]]]
    (let [output (with-out-str
                   (is (= expected (installer/choose-eca-mode (constantly input)))))]
      (is (str/includes? output "no default"))
      (is (= 2 (count (filter #(re-find #"^[12]\) " %) (str/split-lines output)))))
      (doseq [text ["balance-only" "with-eval" "side effects"]]
        (is (str/includes? output text))))))

(deftest menu-errors-and-validation-order-test
  (doseq [[input message] [[nil "Menu cancelled at end of input."]
                           ["" "Menu cancelled."] ["  " "Menu cancelled."]
                           ["0" "Invalid menu choice."] ["03" "Invalid menu choice."]
                           ["--balance-only" "Invalid menu choice."]]]
    (with-out-str
      (is (= (str message " No config changed. Review and migrate the project config manually.")
             (try (installer/choose-eca-mode (constantly input))
                  (catch Exception e (.getMessage e)))))))
  (with-project
    (fn [_]
      (put-config {:hooks nil})
      (doseq [op [:install :uninstall]]
        (is (thrown-with-msg? Exception #"hooks must be an object"
                             (installer/configure-eca-project op []
                               {:terminal? true
                                :read-line-fn #(is false "Validation must precede menu")})))))))

(deftest menu-cancellation-no-writes-test
  (doseq [hooks [nil
                 (select-keys (installer/brepl-eca-hook-config) [:brepl-validate])
                 (installer/brepl-eca-hook-config)
                 (installer/eca-mode-hooks :balance-only)]
          input ["" "  " nil "invalid" "with-hooks"]]
    (with-project
      (fn [dir]
        (when hooks (put-config {:hooks hooks :setting true}))
        (let [before (when hooks (slurp (installer/eca-config-path)))]
          (with-redefs [installer/write-eca-config (fn [_] (is false "Must not write"))]
            (with-out-str
              (is (thrown-with-msg? Exception #"Menu cancelled|Invalid menu choice"
                                   (installer/configure-eca-project :install []
                                     {:terminal? true :read-line-fn (constantly input)})))))
          (if hooks
            (is (= before (slurp (installer/eca-config-path))))
            (is (not (fs/exists? (str dir "/.eca"))))))))))

(deftest validation-only-explicit-with-eval-test
  (doseq [input ["2" "with-eval" :flag]]
    (with-project
      (fn [_]
        (put-config {:hooks (select-keys (installer/brepl-eca-hook-config) [:brepl-validate])})
        (with-out-str
          (if (= :flag input)
            (configure :install "--with-eval")
            (installer/configure-eca-project :install []
              {:terminal? true :read-line-fn (constantly input)})))
        (let [config (installer/read-eca-config)]
          (is (= (installer/eca-mode-hooks :with-eval) (:hooks config)))
          (is (= "brepl eca eval" (get-in config [:hooks :brepl-eval :actions 0 :shell]))))))))

(deftest cli-flags-source-and-packaged-test
  (doseq [command commands]
    (with-temp-dir
      (fn [dir]
        (doseq [args [["install"] ["uninstall" "--balance-only"] ["install" "--all"]
                     ["install" "--with-hooks"] ["install" "--skill-only"]
                     ["install" "--balance-only" "--with-eval"] ["install" "--bogus"]
                     ["install" "--with-eval" "--with-eval"] ["uninstall" "--all" "--hooks-only"]
                     ["eval" "--bogus"] ["eval" "--balance-only" "--with-eval"]
                     ["eval" "--balance-only" "--balance-only"] ["eval" "--help" "--bogus"]]]
          (is (= 1 (:exit (run-command command dir args {}))) (str command args))
          (is (not (fs/exists? (str dir "/.eca")))))
        (doseq [op ["install" "uninstall" "eval"]]
          (is (= 0 (:exit (run-command command dir [op "--help"] {}))))
          (is (not (fs/exists? (str dir "/.eca")))))
        (doseq [mode ["balance-only" "with-eval"]]
          (is (= 0 (:exit (run-command command dir ["install" (str "--" mode)] {}))))
          (let [config (installer/read-eca-config (str dir "/.eca/config.json"))]
            (is (= (installer/eca-mode-hooks (keyword mode)) (get config :hooks {})))))
        (is (= 0 (:exit (run-command command dir ["uninstall"] {}))))))))

(deftest target-safety-test
  (with-project
    (fn [_]
      (with-redefs [installer/eca-config-path installer/eca-global-config-path]
        (is (thrown-with-msg? Exception #"global config" (configure :install "--balance-only")))))))

(deftest symbolic-target-safety-test
  (when-not (fs/windows?)
    (doseq [directory? [false true]]
      (with-project
        (fn [dir]
          (let [target (str dir "/target")]
            (if directory?
              (do (fs/create-dir target)
                  (fs/create-sym-link (str dir "/.eca") target))
              (do (spit target "{}")
                  (fs/create-dir (str dir "/.eca"))
                  (fs/create-sym-link (installer/eca-config-path) target)))
            (is (thrown-with-msg? Exception #"symbolic links" (configure :install "--balance-only")))
            (when-not directory? (is (= "{}" (slurp target))))))))
    (with-project
      (fn [_]
        (put-config {})
        (fs/create-sym-link (installer/eca-global-config-path) (installer/eca-config-path))
        (is (thrown-with-msg? Exception #"global config" (configure :install "--balance-only")))
        (is (= {} (installer/read-eca-config)))))))

(deftest eca-validate-command-feedback-test
  (with-temp-dir
    (fn [dir]
      (let [file (str dir "/example.clj")]
        (spit file "(def x 1)")
        (doseq [command commands]
          (let [result (run-command command dir ["validate"]
                        {:tool_name "eca__edit_file" :tool_input {:path file
                         :original_content "(def missing 1)" :new_content "(def x 2)"}})]
            (is (= 0 (:exit result)))
            (is (= {:approval "deny"
                    :additionalContext "Could not simulate edit - original_content not found in file"}
                   (response result))))
          (is (= {:suppressOutput true}
                 (response (run-command command dir ["validate"]
                            {:tool_name "eca__write_file" :tool_input {:path file :content "(def x 2)"}})))))))))

(deftest eca-eval-command-feedback-test
  (helper/with-nrepl-server
    (fn [port]
      (with-temp-dir
        (fn [dir]
          (let [file (str dir "/example.clj")]
            (spit (str dir "/.nrepl-port") (str port))
            (doseq [command commands]
              (spit file "(throw (ex-info \"ECA regression: inventory unavailable\" {}))")
              (let [result (run-command command dir ["eval"]
                            {:tool_name "eca__write_file" :tool_input {:path file}})
                    output (response result)]
                (is (= 0 (:exit result)))
                (is (false? (:suppressOutput output)))
                (is (nil? (:approval output)))
                (doseq [text ["Evaluation error:" "ExceptionInfo" "ECA regression: inventory unavailable"]]
                  (is (str/includes? (:additionalContext output "") text))))
              (spit file "(+ 1 2)")
              (is (= {:suppressOutput true}
                     (response (run-command command dir ["eval"]
                                {:tool_name "eca__write_file" :tool_input {:path file}})))))))))))

(deftest balance-only-never-discovers-or-evaluates-test
  (with-temp-dir
    (fn [dir]
      (let [file (str dir "/example.clj")
            result (atom nil)
            forbidden (fn [& _] (is false "Must not discover or evaluate"))]
        (spit file "(+ 1 2)")
        (with-redefs [brepl/get-eca-post-tool-input (constantly [file {}])
                      brepl/balance-file! (fn [path] (is (= file path)) "fixed brackets")
                      brepl/resolve-port forbidden
                      brepl/resolve-host forbidden
                      brepl/eval-file forbidden
                      brepl/eca-json-exit (fn [payload code]
                                           (reset! result [payload code]))]
          (brepl/handle-eca-eval ["--balance-only"])
          (is (= [{:suppressOutput false :additionalContext "fixed brackets"} 0] @result)))))))

(deftest balance-only-real-file-stale-and-live-port-test
  (helper/with-nrepl-server
    (fn [live-port]
      (doseq [port [1 live-port] command commands]
        (with-temp-dir
          (fn [dir]
            (let [file (str dir "/example.clj")
                  marker (str dir "/evaluated")
                  fixed (str "(spit " (pr-str marker) " \"BAD\")")]
              (spit (str dir "/.nrepl-port") (str port))
              (spit file (str fixed ")"))
              (let [result (run-command command dir ["eval" "--balance-only"]
                            {:tool_name "eca__write_file" :tool_input {:path file}})]
                (is (= 0 (:exit result)))
                (is (false? (:suppressOutput (response result))))
                (is (seq (:additionalContext (response result))))
                (is (= fixed (str/trim (slurp file))))
                (is (not (fs/exists? marker))))
              (is (= {:suppressOutput true}
                     (response (run-command command dir ["eval" "--balance-only"]
                                {:tool_name "eca__edit_file" :tool_input {:path file}}))))
              (is (not (fs/exists? marker))))))))))
