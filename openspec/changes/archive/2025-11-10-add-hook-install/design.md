# Design: Hook Installation and Validation System

## Architecture Overview

The hook system extends brepl with new subcommands:

```
┌─────────────────┐
│  Claude Code    │
└────────┬────────┘
         │ Pre/Post Edit Events
         ▼
┌─────────────────┐
│  brepl hook     │  ← New subcommands
│  validate/eval  │
└────────┬────────┘
         │
    ┌────┴─────┬──────────────┐
    ▼          ▼              ▼
┌────────┐ ┌────────┐ ┌─────────────┐
│Edamame │ │Auto-Fix│ │brepl eval   │
│Parser  │ │Module  │ │(existing)   │
└────────┘ └────────┘ └─────────────┘
```

## Component Design

### 1. Hook Installer (`brepl hook install`)

**Purpose**: Configure Claude Code to use brepl hooks for Clojure file validation.

**Behavior**:
- Idempotent - can be run multiple times safely
- Installs locally in project's `.claude/settings.local.json`
- Adds hook configuration for Pre/PostToolUse events
- Supports `--strict-eval` flag to block on eval errors (default: warnings only)
- Supports `--skip-eval` flag to skip evaluation entirely
- Validates configuration before writing
- No external binary dependencies required

**Hook Configuration Structure**:
```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Edit",
        "hooks": [
          {
            "type": "command",
            "command": "brepl hook validate \"$FILE_PATH\" \"$NEW_STRING\""
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Edit",
        "hooks": [
          {
            "type": "command",
            "command": "brepl hook eval \"$FILE_PATH\""
          }
        ]
      }
    ],
    "SessionEnd": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "brepl hook session-end \"$SESSION_ID\""
          }
        ]
      }
    ]
  }
}
```

### 2. Hook Validation Subcommands

**Purpose**: New brepl subcommands that validate and evaluate Clojure files.

**Commands**:
- `brepl hook validate <file> <new-content>` - Pre-edit validation with auto-fix
- `brepl hook eval <file>` - Post-edit evaluation with warnings

All commands go through `brepl hook` entry point.

**Output Format**: JSON compatible with Claude Code hook protocol
```json
{
  "continue": true,
  "decision": "allow" | "block",
  "stopReason": "Error message (for blocks)",
  "reason": "Detailed explanation",
  "suppressOutput": true,
  "warnings": ["Warning messages (non-blocking)"]
}
```

### 3. Edamame Auto-Fix Module

**Purpose**: Parse and auto-correct bracket errors using edamame.

**Implementation** (from edamame README):
```clojure
(require '[edamame.core :as e])

(defn fix-expression [expr]
  (try
    (when (e/parse-string expr)
      expr)
    (catch clojure.lang.ExceptionInfo ex
      (if-let [expected-delimiter (:edamame/expected-delimiter (ex-data ex))]
        ;; Recursively add missing delimiters
        (fix-expression (str expr expected-delimiter))
        ;; Unfixable error - rethrow
        (throw ex)))))

;; Example usage
(fix-expression "{:a (let [x 5")  ;; => "{:a (let [x 5])}"
```

**Features**:
- Pure Clojure solution (no external binaries)
- Recursive bracket correction using `:edamame/expected-delimiter`
- Clear error messages for unfixable cases
- Works with all Clojure delimiters: () [] {}

### 4. Delimiter Validation

**Purpose**: Detect delimiter errors and extract error information.

**Implementation**:
```clojure
(defn validate-delimiters [code-str]
  (try
    (e/parse-string-all code-str)
    {:valid true}
    (catch clojure.lang.ExceptionInfo ex
      (let [data (ex-data ex)]
        {:valid false
         :error (.getMessage ex)
         :line (:row data)
         :column (:col data)
         :expected-delimiter (:edamame/expected-delimiter data)}))))
```

**Features**:
- Full Clojure reader support
- Detailed error location (line, column)
- Structured error data for clear messaging

### 5. File Evaluation

**Purpose**: Evaluate edited file to catch runtime errors, but don't block legitimate workflows.

**Strategy**:
- Reuse existing `brepl -f` functionality
- Use `--hook` flag for JSON output
- Leverage project-aware port resolution
- Return warnings for eval errors by default (not blocks)
- Only block if `--strict-eval` flag was used during install

**Integration**:
```clojure
(defn eval-file [file-path strict?]
  ;; Reuse existing eval-file logic from brepl
  (let [result (eval-file host port file-path {:hook true})]
    (if (:has-error? result)
      (if strict?
        ;; Strict mode: block on error
        {:continue true
         :decision "block"
         :stopReason "Evaluation failed"
         :reason (:error-details result)}
        ;; Default mode: warn but allow
        {:continue true
         :decision "allow"
         :suppressOutput false
         :warnings [(str "File evaluation failed: " (:error-details result)
                        "\n\nIf this is intentional (writing tests, incremental work), you can proceed.")]})
      ;; Success
      {:continue true
       :decision "allow"
       :suppressOutput true})))
```

## Hook Event Flow

### Pre-Edit Flow (Auto-Fix)
```
1. Claude prepares to edit file
2. Hook receives: file-path, old-string, new-string
3. Check if file matches "*.clj{s,c,x}?"
4. Parse and auto-fix with edamame:
   a. Try to parse new-string
   b. If parse error with :edamame/expected-delimiter:
      - Recursively append missing delimiters
      - Return corrected code
   c. If unfixable error (no expected-delimiter):
      - Return block decision with error details
5. If valid or auto-fixed:
   a. Return allow decision with corrected code (if fixed)
   b. Edit proceeds with proper brackets
```

### Post-Edit Flow (Evaluation with Warnings)
```
1. Edit completed, file written
2. Hook receives: file-path
3. Quick syntax check (should pass after pre-edit auto-fix)
4. Evaluate entire file:
   a. Find nREPL port (project-aware)
   b. If no port: skip eval, warn
   c. Run file evaluation via existing brepl logic
   d. Check result for errors
5. If eval errors:
   a. Check if --strict-eval mode enabled
   b. If strict: block with error + stacktrace
   c. If not strict (default):
      - Return allow with warning
      - Message: "File doesn't eval cleanly. Proceed if intentional (tests, incremental work)."
6. If eval succeeds:
   a. Return success
```

**Key Change**: Evaluation errors produce **warnings** by default, not blocks. This allows legitimate development workflows where files don't eval cleanly (writing tests, incremental refactoring, etc.).

## File Organization

```
brepl                          # Main script - add subcommands
lib/
  autofix.clj                  # Edamame auto-fix implementation
  validator.clj                # Delimiter validation (edamame)
  evaluator.clj                # File evaluation with warnings
  installer.clj                # Hook installation module
test/
  hook_test.clj                # Hook functionality tests
  autofix_test.clj             # Auto-fix tests
  validator_test.clj
  evaluator_test.clj
bb.edn                         # Add deps: edamame, cheshire (already has)
```

Modules are loaded only when subcommands are invoked, keeping main brepl fast.

**Entry Points**:
- `brepl hook install` - Install hooks locally in project
- `brepl hook uninstall` - Remove hooks
- `brepl hook validate <file> <content>` - Pre-edit validation with auto-fix
- `brepl hook eval <file>` - Post-edit evaluation with warnings

## Configuration Options

### Hook Flags
- `--strict-eval` - Block on evaluation errors (default: warnings only)
- `--timeout N` - Evaluation timeout (seconds, default: 30)
- `--skip-eval` - Skip post-edit evaluation entirely

### Environment Variables
- `BREPL_HOOK_DEBUG=1` - Enable detailed logging
- `BREPL_HOOK_BACKUP_DIR=/path` - Custom backup directory

## Error Handling

### Validation Errors (Blocks)
```json
{
  "continue": true,
  "decision": "block",
  "stopReason": "Syntax error: unclosed delimiter",
  "reason": "Found unclosed '(' at line 42. Please add missing closing parenthesis."
}
```

### Evaluation Errors (Warnings - Default)
```json
{
  "continue": true,
  "decision": "allow",
  "suppressOutput": false,
  "warnings": [
    "File evaluation failed:\nCompilerException: Unable to resolve symbol: foo at line 15\n\nIf this is intentional (writing tests, incremental refactoring), you can proceed.\nOtherwise, please define 'foo' or check for typos."
  ]
}
```

### Evaluation Errors (Blocks - Strict Mode)
```json
{
  "continue": true,
  "decision": "block",
  "stopReason": "Evaluation failed",
  "reason": "CompilerException: Unable to resolve symbol: foo\n  at line 15\n\nPlease define 'foo' or check for typos."
}
```

### Connection Errors
```json
{
  "continue": true,
  "decision": "allow",
  "suppressOutput": true,
  "warnings": ["No nREPL server found, skipping evaluation. Syntax validation passed."]
}
```

## Performance Considerations

**Fast Path Optimization**:
- Skip validation for non-Clojure files (check extension)
- Cache parinfer-rust availability check
- Reuse edamame parser instance

**Timeout Strategy**:
- Pre-edit: 5 second timeout (parsing is fast)
- Post-edit eval: 30 second default (configurable)
- Fail safe: allow edit if timeout exceeded (with warning)

## Backward Compatibility

- Hooks are opt-in via `brepl hook install`
- Existing `brepl` functionality unchanged
- Hook tool is separate executable
- No breaking changes to existing CLI

## Testing Strategy

### Unit Tests
- Delimiter validation with various malformed inputs
- Backup/restore operations
- Parinfer integration (mock)
- JSON output formatting

### Integration Tests
- Full pre-edit → edit → post-edit flow
- Evaluation with mock nREPL server
- Hook installation/uninstallation
- Error recovery scenarios

### Manual Testing
- Test with actual Claude Code
- Various project structures
- Multi-project port resolution
- Performance with large files
