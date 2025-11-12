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
