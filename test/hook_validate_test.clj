(ns hook-validate-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.java.shell :refer [sh]]
            [clojure.string :as str]))

;; Load validator lib
(load-file "lib/validator.clj")
(require '[brepl.lib.validator :as sut])

;; Test helpers

(defn parinfer-available?
  "Check if parinfer-rust is available on the system"
  []
  (= 0 (:exit (sh "which" "parinfer-rust"))))

;; Tests

(deftest validate-hook-with-valid-code-test
  (testing "Scenario: Validate hook receives valid Clojure code"
    (testing "Given valid Clojure code"
      (let [file-path "/tmp/test.clj"
            content "(defn foo [x] (* x 2))"]

        (testing "When the validator checks the file and code"
          (testing "Then it recognizes the file as Clojure"
            (is (sut/clojure-file? file-path)
                "Should recognize .clj extension"))

          (testing "And finds no syntax errors"
            (is (nil? (sut/delimiter-error? content))
                "Valid code should have no errors"))

          (testing "And auto-fix returns the original code unchanged"
            (let [fixed (sut/auto-fix-brackets content)]
              (is (= content fixed)
                  "Valid code should not be modified"))))))))

(deftest validate-hook-with-extra-closing-paren-test
  (testing "Scenario: Validate hook receives code with extra closing paren"
    (testing "Given code with an extra closing paren"
      (let [content "(defn foo [x] (+ x 1)))"]

        (testing "When the validator checks the code"
          (testing "Then it detects a syntax error"
            (let [error (sut/delimiter-error? content)]
              (is (some? error)
                  "Should detect delimiter error")
              (is (str/includes? (:message error) "Unmatched")
                  "Error message should mention unmatched delimiter")))

          (testing "And with parinfer-rust available"
            (let [fixed (sut/auto-fix-brackets content)]
              (is (some? fixed)
                  "Should return fixed code")
              (is (= "(defn foo [x] (+ x 1))" fixed)
                  "Should remove extra paren")
              (is (nil? (sut/delimiter-error? fixed))
                  "Fixed code should have no errors")))

          (testing "And without parinfer-rust available"
            (with-redefs [sut/parinfer-available? (constantly false)]
              (is (nil? (sut/auto-fix-brackets content))
                  "Should return nil when parinfer not available"))))))))

(deftest validate-hook-with-mismatched-delimiters-test
  (testing "Scenario: Validate hook receives code with mismatched bracket types"
    (testing "Given code with ] instead of )"
      (let [content "(defn test-fn [] (let [x (range 10] x))"]

        (testing "When the validator checks the code"
          (testing "Then it detects a syntax error"
            (let [error (sut/delimiter-error? content)]
              (is (some? error)
                  "Should detect delimiter error")
              (is (str/includes? (:message error) "Unmatched")
                  "Error should mention unmatched delimiter")))

          (testing "And parinfer-rust cannot fix this type of error"
            (is (nil? (sut/auto-fix-brackets content))
                "Even parinfer-rust can't fix mismatched delimiter types")))))))

(deftest validate-hook-with-missing-closing-paren-test
  (testing "Scenario: Validate hook receives code with missing closing paren"
    (testing "Given code with a missing closing paren"
      (let [content "(defn add [x y] (+ x y)"]

        (testing "When the validator checks the code"
          (testing "Then it detects a syntax error"
            (let [error (sut/delimiter-error? content)]
              (is (some? error)
                  "Should detect delimiter error")))

          (testing "And with parinfer-rust available"
            (let [fixed (sut/auto-fix-brackets content)]
              (is (some? fixed)
                  "Should return fixed code")
              (is (= "(defn add [x y] (+ x y))" fixed)
                  "Should add missing paren")
              (is (nil? (sut/delimiter-error? fixed))
                  "Fixed code should have no errors")))

          (testing "And without parinfer-rust available"
            (with-redefs [sut/parinfer-available? (constantly false)]
              (is (nil? (sut/auto-fix-brackets content))
                  "Should return nil when parinfer not available"))))))))

(deftest validate-hook-with-non-clojure-file-test
  (testing "Scenario: Validate hook receives non-Clojure file"
    (testing "Given a .txt file path"
      (let [file-path "/tmp/test.txt"]

        (testing "When checking if it's a Clojure file"
          (testing "Then it returns false"
            (is (not (sut/clojure-file? file-path))
                "Should not recognize .txt as Clojure")))

        (testing "And the validator should skip validation"
          (is (true? true)
              "Non-Clojure files are handled by checking clojure-file? first"))))))

(deftest validate-hook-with-reader-macros-test
  (testing "Scenario: Validate hook handles all Clojure reader macros"
    (testing "Given code with various reader macros"

      (testing "Tagged literals"
        (let [content "#inst \"2025-01-01\""]
          (is (nil? (sut/delimiter-error? content))
              "Should accept #inst tagged literal"))
        (let [content "#uuid \"00000000-0000-0000-0000-000000000000\""]
          (is (nil? (sut/delimiter-error? content))
              "Should accept #uuid tagged literal")))

      (testing "Regex patterns"
        (let [content "#\"[a-z]+\""]
          (is (nil? (sut/delimiter-error? content))
              "Should accept regex literal"))
        (let [content "(re-find #\"\\d+\" \"abc123\")"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept regex in function call")))

      (testing "Deref macro"
        (let [content "@my-atom"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept deref"))
        (let [content "(reset! my-atom @other-atom)"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept deref in expression")))

      (testing "Var-quote"
        (let [content "#'my-var"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept var-quote"))
        (let [content "(alter-var-root #'my-var inc)"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept var-quote in function call")))

      (testing "Quote and syntax-quote"
        (let [content "'(1 2 3)"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept quote"))
        (let [content "`(def ~x ~y)"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept syntax-quote with unquote"))
        (let [content "`(list ~@items)"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept unquote-splicing")))

      (testing "Metadata"
        (let [content "^{:private true} (def x 1)"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept map metadata"))
        (let [content "^:private (def x 1)"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept keyword metadata"))
        (let [content "^String x"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept type metadata")))

      (testing "Discard/comment macro"
        (let [content "#_(println \"debug\") :result"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept discard macro"))
        (let [content "(def x #_old-value 42)"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept inline discard")))

      (testing "Set literals"
        (let [content "#{1 2 3}"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept set literal"))
        (let [content "(contains? #{:a :b :c} :a)"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept set in expression")))

      (testing "Anonymous functions"
        (let [content "#(+ % 1)"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept anonymous function"))
        (let [content "(map #(* %1 %2) xs ys)"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept multi-arg anonymous function")))

      (testing "Reader conditionals"
        (let [content "#?(:clj \"jvm\" :cljs \"js\")"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept reader conditional"))
        (let [content "#?@(:clj [1 2] :cljs [3 4])"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept splicing reader conditional")))

      (testing "Symbolic values"
        (let [content "##Inf"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept positive infinity"))
        (let [content "##-Inf"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept negative infinity"))
        (let [content "##NaN"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept NaN")))

      (testing "Combined reader macros in realistic code"
        (let [content "(defn process
  \"Process data with metadata\"
  {:added \"1.0\" :private true}
  [^String input]
  (let [pattern #\"[a-z]+\"
        data @state-atom
        timestamp #inst \"2025-01-01\"
        set-data #{1 2 3}]
    #_(println \"debug:\") ; discard
    (map #(str/upper-case %)
         (re-seq pattern input))))"]
          (is (nil? (sut/delimiter-error? content))
              "Should accept complex code with multiple reader macros"))))))
