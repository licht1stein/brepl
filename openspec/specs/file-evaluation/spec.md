# file-evaluation Specification

## Purpose
TBD - created by archiving change add-hook-install. Update Purpose after archive.
## Requirements
### Requirement: Post-Edit File Evaluation
After successful delimiter validation, the hook SHALL evaluate the entire edited file via nREPL and return warnings (not blocks) for errors by default.

#### Scenario: Successful evaluation
Given file `src/foo.clj` is edited and has valid syntax.
And an nREPL server is running with `.nrepl-port` file present.

The post-edit hook should:
1. Resolve nREPL port (project-aware)
2. Execute file evaluation via brepl
3. Parse response
4. Return success if no errors

Output:
```json
{
  "continue": true,
  "decision": "allow",
  "suppressOutput": true
}
```

The edit is finalized successfully.

#### Scenario: Evaluation error with warning (default mode)
Given file has valid syntax but undefined symbol:
```clojure
(defn bar []
  (undefined-fn 42))
```

And hooks were installed without `--strict-eval` flag.

The post-edit hook should:
1. Evaluate file via nREPL
2. Receive CompilerException
3. Return warning (not block)

Output:
```json
{
  "continue": true,
  "decision": "allow",
  "suppressOutput": false,
  "warnings": [
    "File evaluation failed:\nCompilerException: Unable to resolve symbol: undefined-fn\n  at line 2, column 3\n\nIf this is intentional (writing tests, incremental work), you can proceed.\nOtherwise, please define 'undefined-fn' or check for typos."
  ]
}
```

Claude sees the warning but can proceed if there's a valid reason.

#### Scenario: Evaluation error with strict mode
Given file has runtime error:
```clojure
(defn divide [x y]
  (/ x y))

(divide 10 0) ; evaluated at load time
```

And hooks were installed with `--strict-eval` flag.

The post-edit hook should block:
```json
{
  "continue": true,
  "decision": "block",
  "stopReason": "Evaluation failed: ArithmeticException",
  "reason": "ArithmeticException: Divide by zero\n  at line 4, column 1\n  in function 'divide' at line 2\n\nThe file attempted to divide by zero during evaluation. Consider:\n- Removing evaluation expressions from file body\n- Adding guard against zero divisor\n- Moving code to tests or REPL session"
}
```

In strict mode, eval errors block the edit.

### Requirement: Graceful Degradation Without nREPL
The hook MUST work in validation-only mode when no nREPL server is available.

#### Scenario: No nREPL server running
Given file is edited but no `.nrepl-port` file exists.
And no `BREPL_PORT` environment variable is set.

The post-edit hook should:
1. Complete delimiter validation
2. Attempt port resolution
3. Detect no nREPL server available
4. Skip evaluation step
5. Return success with warning

Output:
```json
{
  "continue": true,
  "decision": "allow",
  "suppressOutput": false,
  "warnings": ["No nREPL server found - skipped evaluation. Syntax validation passed."]
}
```

This allows hooks to function without requiring a running REPL, providing at minimum syntax checking.

#### Scenario: Connection timeout
Given nREPL server is unresponsive.

The post-edit hook should:
1. Attempt connection with timeout
2. Timeout after 5 seconds
3. Return success with warning (fail-safe)

Output:
```json
{
  "continue": true,
  "decision": "allow",
  "suppressOutput": false,
  "warnings": ["nREPL connection timeout - skipped evaluation. Syntax validation passed."]
}
```

This prevents hooks from blocking Claude indefinitely.

### Requirement: Project-Aware Port Resolution
Evaluation SHALL use brepl's existing port resolution to find the correct nREPL server.

#### Scenario: Find port from file's project
Given project structure:
```
/projects/
  app-a/
    .nrepl-port (contains 7001)
    src/foo.clj
  app-b/
    .nrepl-port (contains 7002)
    src/bar.clj
```

When editing `/projects/app-a/src/foo.clj`:
- Hook should find port 7001 from parent directory
- Evaluate against app-a's REPL

When editing `/projects/app-b/src/bar.clj`:
- Hook should find port 7002
- Evaluate against app-b's REPL

This reuses brepl's `-f` flag behavior that walks up directory tree.

#### Scenario: Explicit port via environment
Given `BREPL_PORT=9999` is set.

The hook should:
- Use port 9999 regardless of `.nrepl-port` files
- Allow user override for non-standard setups

### Requirement: Evaluation Error Formatting
Errors from evaluation MUST be formatted for Claude to understand and fix.

#### Scenario: Compiler error format
Given CompilerException with line/column info.

Error should include:
- Exception type
- Error message
- File location (line, column)
- Relevant code snippet if available
- Remediation suggestion

Example:
```
CompilerException: Unable to resolve symbol: foo
  at src/bar.clj:15:7

Code at error location:
  14:   (defn process [x]
  15:     (foo x))
           ^
           symbol not found

Please check:
- Is 'foo' defined earlier in the file?
- Should this be imported from another namespace?
- Is there a typo in the function name?
```

#### Scenario: Runtime error format
Given runtime exception during file loading.

Error should include:
- Exception type
- Stack trace showing call chain
- File locations for each frame
- Context about when error occurred

Example:
```
NullPointerException: Cannot invoke method on nil
  at src/utils.clj:42:5 in 'process-data'
  at src/utils.clj:18:3 in 'transform'
  at src/utils.clj:55:1 (file load)

The file failed to load because of a nil value at line 42.
This occurred during top-level evaluation of the file.

Consider:
- Adding nil check before calling methods
- Ensuring all required values are initialized
- Moving execution to REPL rather than file load
```

### Requirement: Evaluation Mode Configuration
Users SHALL be able to configure evaluation strictness at install time.

#### Scenario: Default mode (warnings)
```bash
$ brepl hook install
# Default: evaluation errors produce warnings
```

Evaluation errors allow edit with warning message.

#### Scenario: Strict mode (blocks)
```bash
$ brepl hook install --strict-eval
# Strict mode: evaluation errors block edits
```

Evaluation errors block the edit, requiring fixes.

#### Scenario: Skip evaluation entirely
```bash
$ brepl hook install --skip-eval
# No evaluation performed
```

Only structural editing and syntax validation, no eval.

This is useful for:
- Large files where evaluation is slow
- Projects where file evaluation has side effects
- Users who prefer manual REPL testing

