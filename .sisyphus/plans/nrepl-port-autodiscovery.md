# nREPL Port Auto-Discovery

## TL;DR

> **Quick Summary**: Add process-scanning fallback to `resolve-port` so brepl can auto-discover running nREPL servers when `.nrepl-port` file is absent. Uses `lsof` to find JVM/Babashka listeners, validates them via nREPL protocol, and selects the one whose working directory matches the user's CWD.
> 
> **Deliverables**:
> - New `src/brepl/lib/discovery.clj` namespace with process scanning, nREPL validation, and CWD matching
> - Updated `resolve-port` fallback chain in `src/brepl.clj`
> - Updated help text and error messages
> - Integration tests
> - Rebuilt uberscript
> 
> **Estimated Effort**: Medium
> **Parallel Execution**: YES - 2 waves
> **Critical Path**: Task 1 → Task 2 → Task 3 → Task 4

---

## Context

### Original Request
Implement nREPL port auto-discovery similar to clojure-mcp-light, with potential improvements. The user wants brepl to find running nREPL servers automatically when `.nrepl-port` file, `-p` flag, and `BREPL_PORT` env var are all absent.

### Interview Summary
**Key Discussions**:
- Integration: enhance `resolve-port` fallback chain only — no new subcommand
- Session persistence: not needed for this feature (brepl has state elsewhere but discovery is stateless)
- Environment detection: NOT needed — CWD matching is the selection criterion
- Multiple matches: first valid CWD-matching server wins
- No caching: scan every time, fresh results
- Process scanning only fires as last-resort fallback, never when `.nrepl-port` exists

**Research Findings**:
- clojure-mcp-light uses `lsof -nP -iTCP -sTCP:LISTEN | grep -Ei 'java|clojure|babashka|bb|nrepl'` with shell-side grep
- Its regex does NOT handle IPv6 (`[::1]:port`) — we should fix this
- `grep -Ei 'bb'` produces false positives (matches `dbus-broker`, `rabbitmq`, etc.) — we should parse in Clojure instead
- brepl already has patterns for shell execution (`stop_hooks.clj`), socket+bencode (`brepl.clj`), and module structure (`validator.clj`)

### Metis Review
**Identified Gaps** (addressed):
- Hook latency: process scanning should NOT fire from hook contexts — hooks already tolerate nil port gracefully. Resolved: only `resolve-port` changes; hook call sites that get nil just skip evaluation as they do today.
- `bb` grep false positives: parse lsof output in Clojure with proper word-boundary matching on the COMMAND column
- Path normalization: use `fs/canonicalize` for CWD comparison to handle symlinks and macOS `/private` prefix
- IPv6 support: regex must handle `[::]:port` and `[::1]:port` formats
- Race conditions: server dying between scan and connect handled by existing error handling
- `lsof` unavailability: return nil gracefully, no crash

---

## Work Objectives

### Core Objective
Add a process-scanning fallback as the last step in `resolve-port` so that brepl can discover nREPL servers without `.nrepl-port` files, `BREPL_PORT`, or explicit `-p` flags.

### Concrete Deliverables
- `src/brepl/lib/discovery.clj` — new namespace
- Modified `resolve-port` in `src/brepl.clj` — one new fallback
- Updated `print-help` in `src/brepl.clj` — step 4 in PORT RESOLUTION
- Updated error message at line 388 in `src/brepl.clj`
- Updated CLI spec description at line 33 in `src/brepl.clj`
- `test/discovery_test.clj` — new test file
- Regenerated `brepl` uberscript via `bb build`

### Definition of Done
- [ ] `bb test` passes with all existing + new tests
- [ ] `bb build` produces working uberscript
- [ ] `./brepl --help` shows 4-step port resolution including process scanning
- [ ] With running nREPL and no `.nrepl-port` file: `./brepl -e '(+ 1 2)'` outputs `3`
- [ ] With no nREPL running: error message mentions process scanning was attempted

### Must Have
- Process scanning via `lsof` that finds Java/Clojure/Babashka TCP listeners
- nREPL validation via bencode `describe` op before using a port
- CWD matching to select the right server among multiple candidates
- IPv6 support in port-parsing regex
- Proper process name filtering in Clojure (not shell grep)
- Graceful degradation when `lsof` is unavailable
- Path normalization with `fs/canonicalize` for CWD comparison
- Timeouts on all external operations (5s for lsof, 2s for TCP connections)
- Updated help text, error messages, and CLI spec description

### Must NOT Have (Guardrails)
- No new CLI flags (`--scan`, `--no-scan`, etc.)
- No caching of discovered ports
- No mutable state (atoms, dynamic vars) in discovery module
- No new dependencies in `deps.edn`
- No changes to `resolve-port` function signature
- No environment type detection (clj/bb/shadow labels)
- No changes to hook handler code (`handle-eval`, `handle-eca-eval`, `handle-eca-validate`) — hooks already handle nil port gracefully
- No `brepl discover` subcommand
- No AI slop: no excessive comments, no over-abstraction, no generic variable names

---

## Verification Strategy (MANDATORY)

> **ZERO HUMAN INTERVENTION** — ALL verification is agent-executed. No exceptions.

### Test Decision
- **Infrastructure exists**: YES
- **Automated tests**: Tests-after (not TDD)
- **Framework**: Babashka test runner via `bb test`

### QA Policy
Every task MUST include agent-executed QA scenarios.
Evidence saved to `.sisyphus/evidence/task-{N}-{scenario-slug}.{ext}`.

| Deliverable Type | Verification Tool | Method |
|------------------|-------------------|--------|
| Library/Module | Bash (bb) | Run `bb test`, check exit code and output |
| CLI behavior | Bash (shell) | Run `./brepl` with various flags, assert output |
| Help text | Bash (shell) | Run `./brepl --help`, grep for expected text |

---

## Execution Strategy

### Parallel Execution Waves

```
Wave 1 (Start Immediately — core implementation):
├── Task 1: Create discovery.clj with scanning, validation, CWD matching [deep]

Wave 2 (After Wave 1 — integration + tests + docs):
├── Task 2: Wire into resolve-port, update help/errors, bb build [quick]
├── Task 3: Integration tests [unspecified-high]
├── Task 4: Update README port resolution section [quick]

Wave FINAL (After ALL tasks — verification):
├── Task F1: Full QA — run all tests, verify CLI behavior end-to-end [unspecified-high]
```

### Dependency Matrix

| Task | Depends On | Blocks | Wave |
|------|------------|--------|------|
| 1 | — | 2, 3 | 1 |
| 2 | 1 | F1 | 2 |
| 3 | 1 | F1 | 2 |
| 4 | — | F1 | 2 |
| F1 | 2, 3, 4 | — | FINAL |

### Agent Dispatch Summary

| Wave | # Parallel | Tasks → Agent Category |
|------|------------|----------------------|
| 1 | 1 | T1 → `deep` |
| 2 | 3 | T2 → `quick`, T3 → `unspecified-high`, T4 → `quick` |
| FINAL | 1 | F1 → `unspecified-high` |

---

## TODOs

- [x] 1. Create `src/brepl/lib/discovery.clj` — process scanning, nREPL validation, CWD matching

  **What to do**:
  - Create new namespace `brepl.lib.discovery` with these functions:
    - `get-listening-ports` — run `lsof -nP -iTCP -sTCP:LISTEN 2>/dev/null` via `babashka.process/shell` with `:out :string :err :string :continue true :timeout 5000`. Return raw output string or nil on error/timeout.
    - `parse-lsof-output` — parse the raw lsof output. For EACH line: extract COMMAND (first column) and port. Filter by command name matching any of `#{"java" "clojure" "babashka" "bb"}` using case-insensitive exact match on the first whitespace-delimited token. Extract port from the `TCP ...:{port} (LISTEN)` pattern using regex `#"TCP\s+(?:\*|[\d.]+|\[[\da-fA-F:]+\]):(\d+)\s+\(LISTEN\)"` (handles IPv4, IPv6, wildcard). Return distinct vector of port integers.
    - `validate-nrepl-port` — given host and port, open a Socket with 2000ms connect timeout (`.setSoTimeout`), send bencode `{"op" "describe"}` message, read response, verify it contains `"ops"` key (confirms nREPL). Return true/false. Catch all exceptions → false. Close socket in finally block.
    - `get-nrepl-cwd` — given host and port, open a Socket, send bencode `{"op" "eval" "code" "(System/getProperty \"user.dir\")"}` message, read responses until `"done"`, extract `"value"` field, strip surrounding quotes. Return string or nil on failure. Close socket in finally block.
    - `discover-nrepl-port` — orchestrator function. Takes no args. Calls `get-listening-ports`, then `parse-lsof-output`. For each candidate port: call `validate-nrepl-port` on `"localhost"`; if valid, call `get-nrepl-cwd`; compare returned dir with `(fs/canonicalize (fs/cwd))` using `fs/canonicalize` on both sides. Return first matching port as Integer, or nil if none match. If no CWD matches but valid nREPL ports exist, still return nil (CWD match is required).
  - Follow module conventions from `validator.clj` (namespace docstring, pure functions, minimal side effects)
  - Follow shell execution pattern from `stop_hooks.clj:178-206` (`:continue true`, timeout, exception handling)
  - Follow socket+bencode pattern from `brepl.clj:186-214`

  **Must NOT do**:
  - Do NOT use shell-side `grep` — parse all lsof output in Clojure
  - Do NOT add global state (atoms, dynamic vars)
  - Do NOT cache results
  - Do NOT detect environment type (clj/bb/shadow)
  - Do NOT add dependencies to deps.edn
  - Do NOT use `pmap` or parallelism — sequential candidate checking is fine for a fallback

  **Recommended Agent Profile**:
  - **Category**: `deep`
    - Reason: Core algorithmic work requiring careful socket handling, regex design, and cross-platform considerations
  - **Skills**: [`clojure`, `brepl`]
    - `clojure`: Clojure idioms, Babashka specifics, bencode protocol
    - `brepl`: Project-specific patterns and conventions

  **Parallelization**:
  - **Can Run In Parallel**: NO
  - **Parallel Group**: Wave 1 (alone)
  - **Blocks**: Tasks 2, 3
  - **Blocked By**: None

  **References** (CRITICAL):

  **Pattern References** (existing code to follow):
  - `src/brepl.clj:186-214` — Socket + bencode pattern: how to open a Socket, send bencode, read responses, handle "done" status. Follow this exact pattern for `validate-nrepl-port` and `get-nrepl-cwd`.
  - `src/brepl/lib/stop_hooks.clj:178-206` — Shell execution with `babashka.process/shell`: how to run external commands with `:continue true`, `:timeout`, and `:out :string`. Follow for `get-listening-ports`.
  - `src/brepl/lib/validator.clj` — Module structure: namespace docstring, minimal requires, pure functions. Follow this layout for `discovery.clj`.
  - `src/brepl.clj:85-89` — `read-nrepl-port` — shows the simple pattern for a discovery function that returns Integer or nil.

  **API/Type References**:
  - `src/brepl.clj:7` — `bencode.core` is the bencode library, already required. Use `bencode/write-bencode` and `bencode/read-bencode`.
  - `src/brepl.clj:16-17` — `java.net.Socket` and `java.io.PushbackInputStream` imports. Follow the same import pattern.
  - `src/brepl.clj:177-183` — `->str` and `->str-deep` helpers for converting bencode byte arrays to strings. You'll need similar conversion in discovery.

  **External References**:
  - nREPL protocol ops: the `describe` op returns a map with `"ops"` key listing available operations — this is how to verify a port speaks nREPL without side effects.
  - `lsof` output format: `COMMAND  PID USER  FD TYPE DEVICE SIZE/OFF NODE NAME` — first column is process name, NAME column contains `TCP *:{port} (LISTEN)`.

  **WHY Each Reference Matters**:
  - `brepl.clj:186-214`: The socket/bencode dance is non-trivial (PushbackInputStream, flush, loop-until-done). Copy the pattern rather than reinventing it.
  - `stop_hooks.clj:178-206`: Shows the project's convention for safe shell execution with timeout and error handling.
  - `validator.clj`: Shows the minimal module structure the project expects — no boilerplate, just focused functions.

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Happy path — discover-nrepl-port finds a running server
    Tool: Bash
    Preconditions: nREPL server running on localhost with known port, no .nrepl-port file
    Steps:
      1. Start nREPL server via babashka.nrepl.server/start-server!
      2. Call discover-nrepl-port from a bb -e invocation
      3. Assert returned port matches the started server's port
    Expected Result: Returns the correct port as Integer
    Failure Indicators: Returns nil, throws exception, returns wrong port
    Evidence: .sisyphus/evidence/task-1-happy-path-discovery.txt

  Scenario: No nREPL running — graceful nil return
    Tool: Bash
    Preconditions: No nREPL server running, lsof available
    Steps:
      1. Call discover-nrepl-port
      2. Assert returns nil
    Expected Result: nil, no exceptions, no output
    Failure Indicators: Throws exception, hangs, prints to stderr
    Evidence: .sisyphus/evidence/task-1-no-server-nil.txt

  Scenario: lsof unavailable — graceful degradation
    Tool: Bash
    Preconditions: lsof not on PATH
    Steps:
      1. Call get-listening-ports with modified PATH
      2. Assert returns nil
    Expected Result: nil, no exceptions
    Failure Indicators: Throws exception
    Evidence: .sisyphus/evidence/task-1-no-lsof.txt

  Scenario: parse-lsof-output handles IPv6 and filters false positives
    Tool: Bash
    Preconditions: Sample lsof output strings
    Steps:
      1. Call parse-lsof-output with sample containing java on port 7888, dbus-broker on port 1234, bb on port 5555
      2. Assert returns [7888 5555], does NOT include 1234
      3. Call parse-lsof-output with IPv6 line "java 123 user 12u IPv6 0x0 0t0 TCP [::]:7888 (LISTEN)"
      4. Assert returns [7888]
    Expected Result: Correct ports extracted, false positives excluded, IPv6 handled
    Failure Indicators: Missing ports, includes non-JVM ports, crashes on IPv6
    Evidence: .sisyphus/evidence/task-1-parse-lsof.txt
  ```

  **Commit**: YES
  - Message: `feat(discovery): add nREPL port auto-discovery via process scanning`
  - Files: `src/brepl/lib/discovery.clj`
  - Pre-commit: `bb test`

---

- [ ] 2. Wire discovery into resolve-port, update help text and error messages, rebuild uberscript

  **What to do**:
  - In `src/brepl.clj`: add `[brepl.lib.discovery :as discovery]` to the ns require
  - Modify `resolve-port` (lines 122-138): after the `BREPL_PORT` env var check, add a final fallback calling `(discovery/discover-nrepl-port)`. The function still returns Integer or nil. No signature change.
  - Update `print-help` PORT RESOLUTION section (lines 63-71): add step 4 explaining process scanning. Something like: "4. Process scanning: finds Java/Clojure/Babashka nREPL servers matching current directory"
  - Update CLI spec description for `:p` (line 33): change to mention process scanning as final fallback
  - Update error message at line 388: change from "No port specified, no .nrepl-port file found, and BREPL_PORT not set" to something that also mentions "no running nREPL server found in current directory"
  - Run `bb build` to regenerate the uberscript
  - Verify `./brepl --help` shows updated text

  **Must NOT do**:
  - Do NOT change `resolve-port` signature (still 1-arity and 2-arity)
  - Do NOT modify hook handlers (`handle-eval`, `handle-eca-eval`)
  - Do NOT add CLI flags
  - Do NOT change any other functions

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Small, focused wiring changes — add a require, one fallback line, text updates
  - **Skills**: [`clojure`, `brepl`]
    - `clojure`: Clojure ns forms, require syntax
    - `brepl`: Project build process, uberscript generation

  **Parallelization**:
  - **Can Run In Parallel**: NO (must follow Task 1)
  - **Parallel Group**: Wave 2 (with Tasks 3, 4)
  - **Blocks**: Task F1
  - **Blocked By**: Task 1

  **References**:

  **Pattern References**:
  - `src/brepl.clj:122-138` — `resolve-port` function to modify: add one more `or` clause after the BREPL_PORT check
  - `src/brepl.clj:1-17` — ns form to add the new require
  - `src/brepl.clj:63-71` — `print-help` PORT RESOLUTION section to update
  - `src/brepl.clj:33` — CLI spec `:p` description to update
  - `src/brepl.clj:387-389` — Error message to update
  - `bb.edn:52-69` — Build task definition (shows how `bb build` works)

  **WHY Each Reference Matters**:
  - `resolve-port`: The exact function to change — must understand the `or` chain to insert the new fallback correctly
  - `print-help`: The user-visible documentation that must reflect the new 4-step resolution
  - `bb.edn` build task: Must know `bb build` generates `src/brepl/lib/skill_content.clj` then runs uberscript — new file must be included

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: Happy path — brepl discovers running nREPL without .nrepl-port
    Tool: Bash
    Preconditions: nREPL server running, no .nrepl-port file, BREPL_PORT unset
    Steps:
      1. Start nREPL server on a random port
      2. Ensure no .nrepl-port file exists in CWD
      3. Ensure BREPL_PORT is unset
      4. Run: ./brepl -e '(+ 1 2)'
      5. Assert exit code 0
      6. Assert stdout contains "3"
    Expected Result: Exit 0, stdout is "3\n"
    Failure Indicators: Exit 1, error message about no port
    Evidence: .sisyphus/evidence/task-2-happy-path.txt

  Scenario: Precedence — .nrepl-port wins over process scan
    Tool: Bash
    Preconditions: nREPL server running, .nrepl-port file exists with correct port
    Steps:
      1. Start nREPL server
      2. Create .nrepl-port file with correct port
      3. Run: ./brepl -e '(+ 1 2)'
      4. Assert works (exit 0)
    Expected Result: Uses .nrepl-port, not process scan
    Failure Indicators: Fails or uses wrong port
    Evidence: .sisyphus/evidence/task-2-precedence.txt

  Scenario: No server — updated error message
    Tool: Bash
    Preconditions: No nREPL server running, no .nrepl-port, BREPL_PORT unset
    Steps:
      1. Run: ./brepl -e '(+ 1 2)' 2>&1
      2. Assert exit code 1
      3. Assert stderr/stdout contains text about process scanning or "no running nREPL"
    Expected Result: Exit 1 with updated error message
    Failure Indicators: Old error message without mention of scanning
    Evidence: .sisyphus/evidence/task-2-error-message.txt

  Scenario: Help text shows 4-step resolution
    Tool: Bash
    Preconditions: Built uberscript
    Steps:
      1. Run: ./brepl --help
      2. Assert output contains "4." or "Process scan" in PORT RESOLUTION section
    Expected Result: Help text documents process scanning as step 4
    Failure Indicators: Only shows 3 steps
    Evidence: .sisyphus/evidence/task-2-help-text.txt
  ```

  **Commit**: YES
  - Message: `feat(discovery): wire auto-discovery into resolve-port fallback chain`
  - Files: `src/brepl.clj`, `brepl` (uberscript)
  - Pre-commit: `bb test`

---

- [ ] 3. Add integration tests for discovery

  **What to do**:
  - Create `test/discovery_test.clj` with tests that exercise the full discovery flow
  - Test functions:
    - `parse-lsof-output` with various input strings (Java, Babashka, IPv4, IPv6, false positives like dbus-broker)
    - `validate-nrepl-port` against a real nREPL server (using `with-nrepl-server` helper from brepl_test.clj — either copy or extract to a shared test-utils)
    - `discover-nrepl-port` end-to-end: start nREPL, delete .nrepl-port, verify discovery works
    - `discover-nrepl-port` with no server running: verify nil return
    - `get-listening-ports` when lsof is unavailable: verify nil return (modify PATH)
  - Use existing test patterns from `test/brepl_test.clj`:
    - `find-free-port` helper for port allocation
    - `with-nrepl-server` helper for spinning up real nREPL
    - `run-brepl` helper for CLI integration tests
  - Add at least one CLI-level integration test: start nREPL, remove .nrepl-port, run `./brepl -e '(+ 1 2)'`, assert output is "3"

  **Must NOT do**:
  - Do NOT mock lsof output for the integration test (unit tests for `parse-lsof-output` are fine with static strings)
  - Do NOT modify existing tests
  - Do NOT skip CWD matching in tests

  **Recommended Agent Profile**:
  - **Category**: `unspecified-high`
    - Reason: Tests require careful nREPL lifecycle management, port cleanup, and edge-case coverage
  - **Skills**: [`clojure`, `brepl`]
    - `clojure`: Clojure test patterns, deftest/is/testing
    - `brepl`: Project test conventions, with-nrepl-server helper

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Tasks 2, 4 — but depends on Task 1)
  - **Parallel Group**: Wave 2 (with Tasks 2, 4)
  - **Blocks**: Task F1
  - **Blocked By**: Task 1

  **References**:

  **Pattern References**:
  - `test/brepl_test.clj:1-45` — Test helpers: `find-free-port`, `run-brepl`, `with-nrepl-server`, `with-nrepl-port-file`. Copy the nREPL lifecycle pattern for discovery tests.
  - `test/brepl_test.clj:46+` — Test structure showing how existing tests exercise the CLI with real nREPL servers.
  - `test/stop_hooks_test.clj` — Shows tests for code that executes shell commands, relevant for testing lsof-based scanning.

  **API/Type References**:
  - `src/brepl/lib/discovery.clj` (from Task 1) — all functions to test: `parse-lsof-output`, `validate-nrepl-port`, `get-nrepl-cwd`, `discover-nrepl-port`, `get-listening-ports`

  **WHY Each Reference Matters**:
  - `brepl_test.clj` helpers: These handle nREPL lifecycle correctly (port allocation, server start/stop, cleanup). Reuse rather than reinvent.
  - `stop_hooks_test.clj`: Shows how to test code that depends on external processes.

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: All discovery tests pass
    Tool: Bash
    Preconditions: Task 1 complete
    Steps:
      1. Run: bb test --nses discovery-test
      2. Assert exit code 0
      3. Assert output shows all tests passing, 0 failures
    Expected Result: All tests pass
    Failure Indicators: Any test failure
    Evidence: .sisyphus/evidence/task-3-test-results.txt

  Scenario: Full test suite still passes
    Tool: Bash
    Preconditions: Tasks 1-2 complete
    Steps:
      1. Run: bb test
      2. Assert exit code 0
      3. Assert 0 failures across all test namespaces
    Expected Result: No regressions
    Failure Indicators: Any pre-existing test fails
    Evidence: .sisyphus/evidence/task-3-full-suite.txt
  ```

  **Commit**: YES
  - Message: `test(discovery): add integration tests for nREPL port auto-discovery`
  - Files: `test/discovery_test.clj`
  - Pre-commit: `bb test`

---

- [ ] 4. Update README port resolution documentation

  **What to do**:
  - In `README.md`, update the "Port Configuration" section to add step 4 (process scanning)
  - Update the "Project-Aware Port Discovery" section to mention that process scanning is the final fallback
  - Add a brief explanation of how process scanning works (finds JVM/Babashka processes, validates nREPL, matches CWD)
  - Keep it concise — 3-5 lines max for the new content

  **Must NOT do**:
  - Do NOT rewrite existing README sections
  - Do NOT add a separate "Auto-Discovery" section — integrate into existing port docs
  - Do NOT over-document internal implementation details

  **Recommended Agent Profile**:
  - **Category**: `quick`
    - Reason: Small text addition to existing documentation
  - **Skills**: [`clojure`]
    - `clojure`: Context for Clojure-specific terminology

  **Parallelization**:
  - **Can Run In Parallel**: YES (with Tasks 2, 3)
  - **Parallel Group**: Wave 2
  - **Blocks**: Task F1
  - **Blocked By**: None (can read Task 1's output but doesn't depend on it code-wise)

  **References**:

  **Pattern References**:
  - `README.md` "Port Configuration" section — the existing 3-step resolution docs to extend
  - `README.md` "Project-Aware Port Discovery" section — related docs for context

  **WHY Each Reference Matters**:
  - Must match existing documentation style and structure when adding the new step

  **Acceptance Criteria**:

  **QA Scenarios (MANDATORY):**

  ```
  Scenario: README documents 4-step port resolution
    Tool: Bash
    Preconditions: README updated
    Steps:
      1. Read README.md
      2. Find "Port Configuration" or "PORT RESOLUTION" section
      3. Assert it documents 4 steps including process scanning
    Expected Result: Step 4 mentions process scanning / auto-discovery
    Failure Indicators: Only 3 steps, or process scanning not mentioned
    Evidence: .sisyphus/evidence/task-4-readme-check.txt
  ```

  **Commit**: YES (groups with Task 2 if convenient)
  - Message: `docs: document nREPL process scanning in port resolution`
  - Files: `README.md`
  - Pre-commit: none

---

## Final Verification Wave (MANDATORY — after ALL implementation tasks)

- [ ] F1. **Full QA and Regression Check** — `unspecified-high`

  Read the plan end-to-end. Execute the following verification sequence:

  1. Run `bb test` — ALL tests must pass (existing + new discovery tests)
  2. Run `bb build` — uberscript must regenerate without errors
  3. Start a real nREPL server, delete `.nrepl-port`, run `./brepl -e '(+ 1 2)'` — must output `3`
  4. With no server running: `./brepl -e '(+ 1 2)' 2>&1` — must show updated error message mentioning scanning
  5. `./brepl --help` — must show 4-step port resolution
  6. Check README mentions process scanning in port docs
  7. Verify no changes to hook handlers (`handle-eval`, `handle-eca-eval`)
  8. Verify `deps.edn` has no new dependencies
  9. Check that `discovery.clj` has no atoms, dynamic vars, or mutable state

  Output: `Tests [PASS/FAIL] | Build [PASS/FAIL] | CLI Discovery [PASS/FAIL] | Error Msg [PASS/FAIL] | Help [PASS/FAIL] | README [PASS/FAIL] | Guardrails [PASS/FAIL] | VERDICT: APPROVE/REJECT`

---

## Commit Strategy

| After Task | Message | Files | Verification |
|------------|---------|-------|--------------|
| 1 | `feat(discovery): add nREPL port auto-discovery via process scanning` | `src/brepl/lib/discovery.clj` | `bb test` |
| 2 | `feat(discovery): wire auto-discovery into resolve-port fallback chain` | `src/brepl.clj`, `brepl` | `bb test && bb build` |
| 3 | `test(discovery): add integration tests for nREPL port auto-discovery` | `test/discovery_test.clj` | `bb test` |
| 4 | `docs: document nREPL process scanning in port resolution` | `README.md` | — |

---

## Success Criteria

### Verification Commands
```bash
bb test                              # Expected: 0 failures
bb build                             # Expected: "Done. Generated: brepl"
./brepl --help                       # Expected: shows 4-step port resolution
# With nREPL running, no .nrepl-port:
./brepl -e '(+ 1 2)'                # Expected: 3
# With no nREPL:
./brepl -e '(+ 1 2)' 2>&1           # Expected: error mentioning scanning
```

### Final Checklist
- [ ] All "Must Have" present
- [ ] All "Must NOT Have" absent
- [ ] All tests pass
- [ ] Uberscript rebuilt
- [ ] README updated
