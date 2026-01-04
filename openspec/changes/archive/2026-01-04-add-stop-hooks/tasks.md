# Tasks: Add Stop Hooks

## Implementation Tasks

### 1. Create stop hooks library with spec validation

**File**: `lib/stop_hooks.clj`

- [x] Define clojure.spec.alpha specs for hook schema
- [x] Implement `load-hooks` to read and parse `.brepl/hooks.edn`
- [x] Implement `validate-hooks` to check against specs
- [x] Return helpful error messages for validation failures

**Verification**: Unit tests for spec validation with valid/invalid configs ✓

### 2. Implement REPL hook execution

**File**: `lib/stop_hooks.clj`

- [x] Implement `execute-repl-hook` using existing nREPL infrastructure
- [x] Handle timeout via future/deref with timeout
- [x] Handle missing nREPL based on `:required?` flag
- [x] Capture and return stdout, stderr, result, error

**Verification**: Unit tests with mock nREPL responses ✓

### 3. Implement bash hook execution

**File**: `lib/stop_hooks.clj`

- [x] Implement `execute-bash-hook` using babashka.process
- [x] Support `:cwd` and `:env` options
- [x] Handle timeout
- [x] Capture and return stdout, stderr, exit code

**Verification**: Unit tests with simple shell commands ✓

### 4. Implement state persistence for retry tracking

**File**: `lib/stop_hooks.clj`

- [x] Implement `read-state` to load `/tmp/brepl-stop-hook-{session_id}.edn`
- [x] Implement `write-state` to save retry counts
- [x] Implement `cleanup-state` to remove state file on success
- [x] Track retry count per hook name

**Verification**: Unit tests for state file read/write/cleanup ✓

### 5. Implement sequential execution with failure handling

**File**: `lib/stop_hooks.clj`

- [x] Implement `run-stop-hooks` orchestrating hook execution
- [x] Execute hooks in order
- [x] Check retry count against `:max-retries` for `:loop-on-failure?` hooks
- [x] Return appropriate exit code (0, 1, or 2)

**Verification**: Integration tests with mixed success/failure hooks ✓

### 6. Add handle-stop CLI handler

**File**: `brepl`

- [x] Add `handle-stop` function to parse stdin JSON (get session_id)
- [x] Wire up to `brepl hook stop` subcommand
- [x] Output errors to stderr
- [x] Exit with 0 (success), 1 (inform), or 2 (block)

**Verification**: Manual test with `echo '{"session_id":"test"}' | brepl hook stop` ✓

### 7. Implement idempotent hook merging

**File**: `lib/installer.clj`

- [x] Add `brepl-hook?` predicate to identify brepl hooks by command prefix
- [x] Add `merge-hook-event` to filter out old brepl hooks before adding new
- [x] Update `merge-hooks` to use new merge strategy
- [x] Add Stop hook to `brepl-hook-config`

**Verification**: Install with existing non-brepl hooks, verify they're preserved ✓

### 8. Add template generation for .brepl/hooks.edn

**File**: `lib/installer.clj`

- [x] Create `.brepl` directory if needed
- [x] Generate `.brepl/hooks.edn` template with commented examples
- [x] Skip template generation if file already exists

**Verification**: Run `brepl hook install` and check generated files ✓

### 9. Add hook help subcommand

**File**: `brepl`

- [x] Update `show-hook-help` with stop subcommand documentation

**Verification**: `brepl hook --help` shows stop command ✓

## Testing Tasks

### 10. Add stop hooks spec validation tests

**File**: `test/stop_hooks_test.clj`

- [x] Test valid REPL hook passes validation
- [x] Test valid bash hook passes validation
- [x] Test missing required fields fail
- [x] Test invalid field types fail
- [x] Test unknown hook type fails

### 11. Add stop hooks execution tests

**File**: `test/stop_hooks_test.clj`

- [x] Test successful REPL hook execution
- [x] Test REPL hook with eval error
- [x] Test successful bash hook execution
- [x] Test bash hook with non-zero exit
- [x] Test timeout handling
- [x] Test sequential execution order
- [x] Test blocking failure with loop-on-failure
- [x] Test non-blocking failure continues execution

### 12. Add integration test for brepl hook stop

**File**: `test/stop_hooks_test.clj` (combined)

- [x] Test end-to-end with sample config file
- [x] Test JSON output format
- [x] Test exit codes

### 13. Add idempotent merge tests

**File**: `test/installer_test.clj`

- [x] Test non-brepl hooks are preserved
- [x] Test brepl hooks are replaced
- [x] Test running install twice produces same result

## Documentation Tasks

### 14. Minimal README update (optional)

- [ ] Add brief mention of `brepl hook stop` in hook subcommands list (if any)
- [ ] No detailed documentation yet - feature is experimental

## Effort Estimation (CHAI)

| Dimension         | Score | Notes                                          |
| ----------------- | ----- | ---------------------------------------------- |
| Claude Complexity | 3     | New lib file, touches installer and main       |
| Error Probability | 2     | Clear spec from interview, good test patterns  |
| Human Attention   | 2     | Straightforward feature, well-defined behavior |
| Iteration Risk    | 2     | Clear acceptance criteria from interview       |

**Summary**: Medium complexity, low risk. Can proceed confidently.
