(ns brepl-test
  (:require [babashka.nrepl.server :as srv]
            [babashka.process :refer [shell]]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [clojure.java.io :as io]))

;; Test helpers

(defn find-free-port []
  "Find an available port by creating and immediately closing a ServerSocket"
  (let [socket (java.net.ServerSocket. 0)
        port (.getLocalPort socket)]
    (.close socket)
    port))

(defn run-brepl
  "Run brepl command and return output map with :exit, :out, and :err"
  [& args]
  (apply shell {:out :string :err :string :continue true :timeout 5000} 
         "./brepl" (map str args)))

(defn with-temp-file
  "Create a temp file with content, execute function, then cleanup"
  [prefix suffix content f]
  (let [file (java.io.File/createTempFile prefix suffix)]
    (try
      (spit file content)
      (f (.getAbsolutePath file))
      (finally
        (.delete file)))))

(defn with-nrepl-server
  "Start nREPL server, execute function with port, then stop server"
  [f]
  (let [port (find-free-port)
        server (srv/start-server! {:port port :quiet true})]
    (try
      ;; Give server a moment to start
      (Thread/sleep 100)
      (f port)
      (finally
        (srv/stop-server! server)))))

(defn with-nrepl-port-file
  "Create .nrepl-port file, execute function, then cleanup"
  [port f]
  (let [port-file (io/file ".nrepl-port")]
    (try
      (spit port-file (str port))
      (f)
      (finally
        (when (.exists port-file)
          (.delete port-file))))))

;; Basic functionality tests

(deftest basic-evaluation-test
  (with-nrepl-server
    (fn [port]
      (testing "Simple arithmetic evaluation"
        (let [result (run-brepl "-p" port "-e" "(+ 1 2 3)")]
          (is (= 0 (:exit result)))
          (is (= "6\n" (:out result)))
          (is (empty? (:err result)))))
      
      (testing "String operations"
        (let [result (run-brepl "-p" port "-e" "(str \"Hello\" \" \" \"World\")")]
          (is (= 0 (:exit result)))
          (is (= "\"Hello World\"\n" (:out result)))))
      
      (testing "Multiple forms with def"
        (let [result (run-brepl "-p" port "-e" "(do (def x 5) (* x 2))")]
          (is (= 0 (:exit result)))
          (is (str/includes? (:out result) "10")))))))

;; File evaluation tests

(deftest file-evaluation-test
  (with-nrepl-server
    (fn [port]
      (testing "Single expression file"
        (with-temp-file "test" ".clj" "(* 7 6)"
          (fn [filepath]
            (let [result (run-brepl "-p" port "-f" filepath)]
              (is (= 0 (:exit result)))
              (is (= "42\n" (:out result)))))))
      
      (testing "Multi-expression file"
        (with-temp-file "test" ".clj" 
          "(def x 10)\n(def y 20)\n(+ x y)"
          (fn [filepath]
            (let [result (run-brepl "-p" port "-f" filepath)]
              (is (= 0 (:exit result)))
              (is (str/includes? (:out result) "30"))))))
      
      (testing "File with println"
        (with-temp-file "test" ".clj" 
          "(println \"Starting...\")\n(+ 2 3)\n(println \"Done!\")"
          (fn [filepath]
            (let [result (run-brepl "-p" port "-f" filepath)]
              (is (= 0 (:exit result)))
              (is (str/includes? (:out result) "Starting..."))
              (is (str/includes? (:out result) "5"))
              (is (str/includes? (:out result) "Done!")))))))))

;; Error handling tests

(deftest error-handling-test
  (with-nrepl-server
    (fn [port]
      (testing "Division by zero"
        (let [result (run-brepl "-p" port "-e" "(/ 1 0)")]
          (is (= 0 (:exit result)))
          (is (str/includes? (:err result) "ArithmeticException"))
          (is (str/includes? (:err result) "Divide by zero"))))
      
      (testing "Undefined symbol"
        (let [result (run-brepl "-p" port "-e" "undefined-var")]
          (is (= 0 (:exit result)))
          (is (str/includes? (:err result) "Could not resolve symbol"))))
      
      (testing "Syntax error"
        (let [result (run-brepl "-p" port "-e" "(defn bad [)")]
          (is (= 0 (:exit result)))
          (is (or (str/includes? (:err result) "EOF")
                  (str/includes? (:err result) "Unmatched delimiter")))))
      
      (testing "File not found"
        (let [result (run-brepl "-p" port "-f" "nonexistent.clj")]
          (is (= 1 (:exit result)))
          (is (str/includes? (:out result) "File does not exist")))))))

;; Output handling tests

(deftest output-handling-test
  (with-nrepl-server
    (fn [port]
      (testing "stdout output"
        (let [result (run-brepl "-p" port "-e" "(println \"test output\")")]
          (is (= 0 (:exit result)))
          (is (str/includes? (:out result) "test output"))
          (is (str/includes? (:out result) "nil"))))
      
      (testing "Multiple println statements"
        (let [result (run-brepl "-p" port "-e" "(do (println \"Line 1\") (println \"Line 2\") :done)")]
          (is (= 0 (:exit result)))
          (is (str/includes? (:out result) "Line 1"))
          (is (str/includes? (:out result) "Line 2"))
          (is (str/includes? (:out result) ":done"))))
      
      (testing "Mixed output and return value"
        (let [result (run-brepl "-p" port "-e" "(do (println \"Computing...\") (+ 10 20))")]
          (is (= 0 (:exit result)))
          (is (str/includes? (:out result) "Computing..."))
          (is (str/includes? (:out result) "30")))))))

;; Port resolution tests

(deftest port-resolution-test
  (with-nrepl-server
    (fn [port]
      (testing "Direct port specification"
        (let [result (run-brepl "-p" port "-e" "(+ 1 1)")]
          (is (= 0 (:exit result)))
          (is (= "2\n" (:out result)))))
      
      (testing "Port from .nrepl-port file"
        (with-nrepl-port-file port
          (fn []
            (let [result (run-brepl "-e" "(+ 2 2)")]
              (is (= 0 (:exit result)))
              (is (= "4\n" (:out result)))))))
      
      (testing "Port from environment variable"
        (let [result (shell {:out :string :err :string :continue true
                            :extra-env {"BREPL_PORT" (str port)}}
                           "./brepl" "-e" "(+ 3 3)")]
          (is (= 0 (:exit result)))
          (is (= "6\n" (:out result)))))
      
      (testing "Port precedence: CLI > file > env"
        (let [other-port (find-free-port)]
          (with-nrepl-port-file other-port
            (fn []
              ;; CLI port should win over file
              (let [result (run-brepl "-p" port "-e" "(+ 4 4)")]
                (is (= 0 (:exit result)))
                (is (= "8\n" (:out result)))))))))))

;; Host resolution tests

(deftest host-resolution-test
  (with-nrepl-server
    (fn [port]
      (testing "Default localhost"
        (let [result (run-brepl "-p" port "-e" "\"connected\"")]
          (is (= 0 (:exit result)))
          (is (= "\"connected\"\n" (:out result)))))
      
      (testing "Custom host"
        (let [result (run-brepl "-h" "127.0.0.1" "-p" port "-e" "\"custom-host\"")]
          (is (= 0 (:exit result)))
          (is (= "\"custom-host\"\n" (:out result)))))
      
      (testing "Host from environment variable"
        (let [result (shell {:out :string :err :string :continue true
                            :extra-env {"BREPL_HOST" "localhost"}}
                           "./brepl" "-p" (str port) "-e" "\"env-host\"")]
          (is (= 0 (:exit result)))
          (is (= "\"env-host\"\n" (:out result))))))))

;; Verbose mode tests

(deftest verbose-mode-test
  (with-nrepl-server
    (fn [port]
      (testing "Verbose mode shows nREPL conversation"
        (let [result (run-brepl "-p" port "-e" "(+ 1 2)" "--verbose")]
          (is (= 0 (:exit result)))
          ;; Check for request
          (is (str/includes? (:out result) "\"op\" \"eval\""))
          (is (str/includes? (:out result) "\"code\" \"(+ 1 2)\""))
          ;; Check for response
          (is (str/includes? (:out result) "\"value\" \"3\""))
          (is (str/includes? (:out result) "\"status\" [\"done\"]"))))
      
      (testing "Verbose mode with error"
        (let [result (run-brepl "-p" port "-e" "(/ 1 0)" "--verbose")]
          (is (= 0 (:exit result)))
          (is (str/includes? (:out result) "\"status\" [\"eval-error\"]"))
          (is (str/includes? (:out result) "ArithmeticException")))))))

;; CLI validation tests

(deftest cli-validation-test
  (testing "Missing required arguments"
    (let [result (run-brepl)]
      (is (= 1 (:exit result)))
      (is (str/includes? (:out result) "Must specify either -e EXPR or -f FILE"))))
  
  (testing "Both -e and -f specified"
    (let [result (run-brepl "-e" "(+ 1 1)" "-f" "test.clj")]
      (is (= 1 (:exit result)))
      (is (str/includes? (:out result) "Cannot specify both -e and -f"))))
  
  (testing "Help display"
    (let [result (run-brepl "--help")]
      (is (= 0 (:exit result)))
      (is (str/includes? (:out result) "brepl - Fast Babashka nREPL client"))
      (is (str/includes? (:out result) "USAGE:"))
      (is (str/includes? (:out result) "OPTIONS:"))))
  
  (testing "No port available"
    (let [result (run-brepl "-e" "(+ 1 1)")]
      (is (= 1 (:exit result)))
      (is (str/includes? (:out result) "No port specified")))))

;; Connection error tests

(deftest connection-error-test
  (testing "Connection to non-existent server"
    (let [port (find-free-port)
          result (run-brepl "-p" port "-e" "(+ 1 1)")]
      (is (= 1 (:exit result)))
      (is (str/includes? (:out result) "Error connecting to nREPL server")))))

;; Edge cases tests

(deftest edge-cases-test
  (with-nrepl-server
    (fn [port]
      (testing "Nil value returns successfully with no output"
        (let [result (run-brepl "-p" port "-e" "nil")]
          (is (= 0 (:exit result)))
          (is (= "" (:out result)))))
      
      (testing "Very large number"
        (let [result (run-brepl "-p" port "-e" "123456789012345678901234567890N")]
          (is (= 0 (:exit result)))
          (is (str/includes? (:out result) "123456789012345678901234567890N"))))
      
      (testing "Special characters in strings"
        (let [result (run-brepl "-p" port "-e" "(str \"Hello\\nWorld\\t!\")")]
          (is (= 0 (:exit result)))
          (is (str/includes? (:out result) "Hello\\nWorld\\t!"))))
      
      (testing "Multiple expressions in do block"
        (let [result (run-brepl "-p" port "-e" "(do (println \"First\") (println \"Second\") (+ 1 2))")]
          (is (= 0 (:exit result)))
          (is (str/includes? (:out result) "First"))
          (is (str/includes? (:out result) "Second"))
          (is (str/includes? (:out result) "3")))))))