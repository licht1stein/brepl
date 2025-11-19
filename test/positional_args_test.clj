(ns positional-args-test
  (:require [babashka.nrepl.server :as srv]
            [babashka.process :refer [shell]]
            [clojure.test :refer [deftest is testing]]
            [cheshire.core :as json]))

;; Test helpers

(defn find-free-port
  "Find an available port by creating and immediately closing a ServerSocket"
  []
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

;; Positional argument tests (implicit -e mode)

(deftest positional-argument-test
  (with-nrepl-server
    (fn [port]
      (testing "Simple expression as positional argument"
        (let [result (run-brepl "-p" port "(+ 1 2)")]
          (is (= 0 (:exit result)))
          (is (clojure.string/includes? (:out result) "3"))))

      (testing "Positional argument with port flag after"
        (let [result (run-brepl "(+ 10 20)" "-p" port)]
          (is (= 0 (:exit result)))
          (is (clojure.string/includes? (:out result) "30"))))

      (testing "Positional argument with hook mode"
        (let [result (run-brepl "-p" port "--hook" "(inc 42)")]
          (is (= 0 (:exit result)))
          (let [output (json/parse-string (:out result) true)]
            (is (= true (:continue output)))
            (is (= true (:suppressOutput output)))
            (is (not (contains? output :decision))))))

      (testing "Positional argument with verbose mode"
        (let [result (run-brepl "-p" port "--verbose" "(+ 5 5)")]
          (is (= 0 (:exit result)))
          (is (clojure.string/includes? (:out result) "10"))))

      (testing "Multiline code as positional argument"
        (let [result (run-brepl "-p" port "(do\n  (+ 1 2)\n  (* 3 4))")]
          (is (= 0 (:exit result)))
          (is (clojure.string/includes? (:out result) "12"))))

      (testing "Explicit -e flag still works"
        (let [result (run-brepl "-p" port "-e" "(+ 100 200)")]
          (is (= 0 (:exit result)))
          (is (clojure.string/includes? (:out result) "300"))))

      (testing "Explicit -f flag still works"
        (with-temp-file "test" ".clj"
          "(+ 7 8)"
          (fn [filepath]
            (let [result (run-brepl "-p" port "-f" filepath)]
              (is (= 0 (:exit result)))
              (is (clojure.string/includes? (:out result) "15")))))))))
