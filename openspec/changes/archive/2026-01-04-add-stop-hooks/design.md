# Design: Stop Hooks Implementation

## Idempotent Settings Sync

### Problem

When running `brepl hook install`, we need to:

1. Add/update brepl hooks in `.claude/settings.local.json`
2. Preserve non-brepl hooks added by user or other tools
3. Be idempotent (running multiple times produces same result)

### Solution: Hook Identification and Merge

**Identify brepl hooks** by command prefix:

- All brepl hooks have commands starting with `brepl hook`
- Filter out existing brepl hooks before adding new ones

**Merge strategy**:

```
1. Read existing settings
2. For each hook event (PreToolUse, PostToolUse, Stop, SessionEnd):
   a. Filter out entries where any hook command starts with "brepl hook"
   b. Add new brepl hook entries
3. Write merged settings
```

**Implementation** (in `lib/installer.clj`):

```clojure
(defn brepl-hook? [hook-entry]
  "Check if a hook entry belongs to brepl."
  (some #(str/starts-with? (:command %) "brepl hook")
        (:hooks hook-entry)))

(defn merge-hook-event [existing-entries new-entries]
  "Merge new brepl entries with existing non-brepl entries."
  (let [non-brepl (remove brepl-hook? existing-entries)]
    (into (vec non-brepl) new-entries)))

(defn merge-hooks [existing-hooks new-hooks]
  "Merge brepl hooks with existing hooks, preserving non-brepl hooks."
  (reduce-kv
    (fn [acc event-name new-entries]
      (let [existing-entries (get acc event-name [])]
        (assoc acc event-name (merge-hook-event existing-entries new-entries))))
    existing-hooks
    new-hooks))
```

### Example

**Before** (existing settings):

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Write",
        "hooks": [{ "type": "command", "command": "prettier --write" }]
      },
      {
        "matcher": "Edit",
        "hooks": [{ "type": "command", "command": "brepl hook eval" }]
      }
    ]
  }
}
```

**After** `brepl hook install`:

```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Edit|Write",
        "hooks": [{ "type": "command", "command": "brepl hook validate" }]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Write",
        "hooks": [{ "type": "command", "command": "prettier --write" }]
      },
      {
        "matcher": "Edit|Write",
        "hooks": [{ "type": "command", "command": "brepl hook eval" }]
      }
    ],
    "Stop": [
      {
        "matcher": "",
        "hooks": [{ "type": "command", "command": "brepl hook stop" }]
      }
    ],
    "SessionEnd": [
      {
        "matcher": "*",
        "hooks": [{ "type": "command", "command": "brepl hook session-end" }]
      }
    ]
  }
}
```

Note: The old brepl PostToolUse entry (matching only "Edit") was replaced with the new one (matching "Edit|Write"), while the prettier hook was preserved.

## Template Generation

### `.brepl/hooks.edn` Template

```clojure
;; brepl stop hooks configuration

{:stop
 [;; Example: Run tests via nREPL after Claude stops
  ;; {:type :repl
  ;;  :name "run-tests"
  ;;  :code "(clojure.test/run-tests)"
  ;;  :retry-on-failure? true  ; Claude keeps trying until tests pass
  ;;  :max-retries 10          ; Give up after 10 attempts (0 = infinite)
  ;;  :required? true          ; Inform user if no nREPL connection
  ;;  :timeout 120}

  ;; Example: Run linter via bash
  ;; {:type :bash
  ;;  :name "lint"
  ;;  :command "clj-kondo --lint src"
  ;;  :retry-on-failure? false  ; Report failure but don't retry
  ;;  :timeout 30
  ;;  :cwd "."
  ;;  :env {"CI" "true"}}
  ]}

;; Hook fields:
;;   :type             - :repl or :bash (required)
;;   :name             - identifier for error messages (required)
;;   :retry-on-failure? - if true and fails, Claude retries (default: false)
;;   :max-retries       - max retry attempts, 0 = infinite (default: 10)
;;   :required?         - if true and can't run, inform user (default: false)
;;   :timeout           - seconds before timeout (default: 60)
;;
;; REPL-specific:
;;   :code             - Clojure code to evaluate (required for :repl)
;;
;; Bash-specific:
;;   :command          - shell command to run (required for :bash)
;;   :cwd              - working directory (default: ".")
;;   :env              - environment variables map (default: {})
```

## Exit Code Semantics

Per Claude Code Stop hook behavior:

- **Exit 0** = Success, Claude can stop
- **Exit 1** = Informational error (Claude sees stderr, can stop)
- **Exit 2** = Blocking error (Claude must continue working)

**All hooks pass**:

```
exit 0
```

**Hook fails with :loop-on-failure? true (retry count < max)**:

```
stderr: "Hook 'run-tests' failed: 3 tests failed"
exit 2
```

Claude continues working to fix the issue.

**Hook fails with :loop-on-failure? true (retry limit reached)**:

```
stderr: "Hook 'run-tests' failed after 10 retries. Giving up."
exit 1
```

Claude is informed but can stop.

**Hook fails with :loop-on-failure? false**:

```
stderr: "Hook 'lint' failed: 2 warnings found"
exit 1
```

Claude is informed but can stop.

**Required hook can't run (no nREPL)**:

```
stderr: "Hook 'run-tests' requires nREPL but none available. Please start REPL and retry."
exit 1
```

Claude is informed, should pause and notify user.

## State Persistence

Retry counts tracked in `/tmp/brepl-stop-hook-{session_id}.edn`:

```clojure
{"run-tests" 3
 "lint" 0}
```

- Created on first hook failure
- Updated on each retry
- Reset to 0 on success
- Deleted by SessionEnd hook or when all hooks pass
