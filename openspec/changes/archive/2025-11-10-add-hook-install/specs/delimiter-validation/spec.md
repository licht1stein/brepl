# Auto-Fix and Validation

## ADDED Requirements

### Requirement: Pre-Edit Auto-Fix
The hook MUST auto-correct bracket errors using edamame's `:edamame/expected-delimiter` before edits are written to disk.

#### Scenario: Auto-fix incomplete expression
Given Claude attempts to edit a file with incomplete brackets:
```clojure
(defn foo [x]
  (+ x 1
```

The pre-edit hook should:
- Parse with edamame
- Catch exception with `:edamame/expected-delimiter`
- Recursively append missing delimiters: `)` then `)`
- Return the corrected code

Output: Corrected code with proper brackets:
```clojure
(defn foo [x]
  (+ x 1))
```

The edit is applied with correct brackets automatically.

#### Scenario: Auto-fix nested structures
Given Claude edits with nested structures:
```clojure
(let [x 1
      y 2
  (+ x y
```

The pre-edit hook should:
- Parse with edamame, catch first error
- Recursively append missing delimiters: `)` then `]` then `)`
- Return properly structured code:
```clojure
(let [x 1
      y 2]
  (+ x y))
```

#### Scenario: Block on unfixable errors
Given Claude attempts edit with truly malformed code:
```clojure
(defn foo [x}
  (inc x))
```

The pre-edit hook should:
- Parse with edamame
- Detect mismatched delimiter (expects `]` got `}`)
- No `:edamame/expected-delimiter` in exception (unfixable)
- Return block decision with clear error

Output:
```json
{
  "continue": true,
  "decision": "block",
  "stopReason": "Syntax error: mismatched delimiter",
  "reason": "Expected ']' but found '}' at line 1.\n\nThe opening '[' at position 11 expects a matching ']', not '}'.\nPlease fix the delimiter mismatch."
}
```

### Requirement: Post-Edit Quick Validation
The hook SHALL perform quick syntax validation after edits are written (belt-and-suspenders check).

#### Scenario: Quick validation pass
Given file after edit contains valid syntax (should be normal after pre-edit auto-fix).

The post-edit hook should:
1. Quick parse check with edamame
2. Proceed to evaluation if valid

This is a sanity check that should rarely catch errors since pre-edit auto-fix handles corrections.

### Requirement: File Type Filtering
Validation SHALL only apply to Clojure source files.

#### Scenario: Skip validation for non-Clojure files
Given Claude edits `README.md` or `config.json`.

The hook should:
- Check file extension
- If not `.clj`, `.cljs`, `.cljc`, `.cljx`: skip validation
- Return immediate success

This ensures hooks don't slow down non-Clojure edits.

#### Scenario: Validate all Clojure extensions
The hook should validate files matching:
- `*.clj` - Clojure
- `*.cljs` - ClojureScript
- `*.cljc` - Clojure/ClojureScript cross-compatible
- `*.cljx` - Legacy cross-platform

All should receive full delimiter validation.

### Requirement: Clear Error Messages
Validation errors MUST provide actionable guidance for Claude.

#### Scenario: Error message includes location
Given syntax error at specific position.

Error message should include:
- Line number where error occurred
- Type of error (unclosed, mismatched, unexpected)
- Expected vs actual delimiter
- Suggestion for fix

Example:
```
Found unclosed '(' at line 23, column 5.

The opening parenthesis expects a matching ')'.
Please add the missing closing parenthesis.
```

#### Scenario: Error message explains context
Given complex nested structure with error.

Error message should:
- Identify the containing form (defn, let, if, etc.)
- Explain what delimiter is expected
- Provide clear remediation steps

Example:
```
Mismatched delimiter in 'let' binding at line 15.

Expected ']' to close vector binding, but found ')'.
The '[' at line 14 opened a vector that must be closed with ']'.

Please change ')' to ']' at line 15, column 8.
```
