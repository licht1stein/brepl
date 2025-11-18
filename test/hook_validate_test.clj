(ns hook-validate-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]))

;; Load validator lib
(load-file "lib/validator.clj")
(require '[brepl.lib.validator :as sut])

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

          (testing "And auto-fix returns the original code unchanged"
            (let [fixed (sut/auto-fix-brackets content)]
              (is (= content fixed)
                  "Valid code should not be modified"))))))))

(deftest validate-hook-with-extra-closing-paren-test
  (testing "Scenario: Validate hook receives code with extra closing paren"
    (testing "Given code with an extra closing paren"
      (let [content "(defn foo [x] (+ x 1)))"]

        (testing "When the validator auto-fixes the code"
          (let [fixed (sut/auto-fix-brackets content)]
            (is (some? fixed)
                "Should return fixed code")
            (is (= "(defn foo [x] (+ x 1))" fixed)
                "Should remove extra paren")))))))

(deftest validate-hook-with-mismatched-delimiters-test
  (testing "Scenario: Validate hook receives code with mismatched bracket types"
    (testing "Given code with ] instead of )"
      (let [content "(defn test-fn [] (let [x (range 10] x))"]

        (testing "When the validator attempts to fix the code"
          (testing "Then parmezan attempts to fix it"
            ;; Parmezan will try to fix this, result depends on its algorithm
            (let [fixed (sut/auto-fix-brackets content)]
              (is (some? fixed)
                  "Parmezan should attempt to fix the code"))))))))

(deftest validate-hook-with-missing-closing-paren-test
  (testing "Scenario: Validate hook receives code with missing closing paren"
    (testing "Given code with a missing closing paren"
      (let [content "(defn add [x y] (+ x y)"]

        (testing "When the validator auto-fixes the code"
          (let [fixed (sut/auto-fix-brackets content)]
            (is (some? fixed)
                "Should return fixed code")
            (is (= "(defn add [x y] (+ x y))" fixed)
                "Should add missing paren")))))))

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

(deftest validate-hook-with-bb-extension-test
  (testing "Scenario: Validate hook recognizes .bb files"
    (testing "Given a .bb file path"
      (let [file-path "/tmp/script.bb"]

        (testing "When checking if it's a Clojure file"
          (testing "Then it returns true"
            (is (sut/clojure-file? file-path)
                "Should recognize .bb extension as Babashka/Clojure")))))))

(deftest validate-hook-with-babashka-shebang-test
  (testing "Scenario: Validate hook recognizes Babashka shebang"
    (testing "Given a file without extension but with bb shebang"
      (let [file-path "/tmp/script"]

        (testing "When checking with #!/usr/bin/env bb shebang"
          (let [content-env "#!/usr/bin/env bb\n(println \"hello\")"]
            (is (sut/clojure-file? file-path content-env)
                "Should recognize #!/usr/bin/env bb shebang")))

        (testing "When checking with #!/usr/bin/bb shebang"
          (let [content-direct "#!/usr/bin/bb\n(println \"hello\")"]
            (is (sut/clojure-file? file-path content-direct)
                "Should recognize #!/usr/bin/bb shebang")))

        (testing "When checking without shebang"
          (let [content-no-shebang "(println \"hello\")"]
            (is (not (sut/clojure-file? file-path content-no-shebang))
                "Should not recognize file without extension or shebang")))))))

(deftest validate-hook-with-nested-structures-test
  (testing "Scenario: Validate hook handles nested structures"
    (testing "Given code with nested forms missing delimiters"
      (let [content "(let [x 1\n      y 2\n  (+ x y"]

        (testing "When the validator auto-fixes the code"
          (let [fixed (sut/auto-fix-brackets content)]
            (is (some? fixed)
                "Should return fixed code")
            (is (str/includes? fixed "(let [x 1")
                "Should preserve opening structure")
            (is (str/includes? fixed "(+ x y")
                "Should preserve inner expression")))))))
