# Proposal: Add Stop Hook Customization

## Problem Statement

Developers using brepl with Claude Code need automated test runs and validation after Claude finishes making changes. Currently brepl handles PreToolUse (bracket validation) and PostToolUse (file evaluation), but there's no way to run custom code when Claude completes a response cycle.

## Proposed Solution

Add support for user-configurable stop hooks that run when Claude Code fires the Stop event. Hooks can execute Clojure code via nREPL or bash commands, with configurable behavior for blocking (making Claude continue working if tests fail).

### Key Features

1. **Configuration file** at `.brepl/hooks.edn` with `:stop` key containing hook definitions
2. **Two hook types**: `:repl` (execute Clojure via nREPL) and `:bash` (shell commands)
3. **Blocking behavior**: Hooks can block Claude from stopping if validation fails
4. **Loop-on-failure**: Configurable per-hook to make Claude keep trying until fixed
5. **Schema validation**: Use `clojure.spec.alpha` for config validation
6. **Template generation**: `brepl hook install` creates example `.brepl/hooks.edn`

## Impact Analysis

### Files Modified

- `brepl` - Add `handle-stop` function and stop hook execution logic
- `lib/installer.clj` - Update to generate Stop hook config and template `.brepl/hooks.edn`

### Files Added

- `lib/stop_hooks.clj` - Stop hook loading, validation, and execution
- Template content for `.brepl/hooks.edn`

### Dependencies

- No new external dependencies
- Uses existing nREPL infrastructure from brepl
- Uses babashka.process for bash execution (already available in bb)

## Alternatives Considered

1. **Extend existing PostToolUse hooks** - Rejected because Stop event fires once per response cycle, not per tool use
2. **Separate config file per hook type** - Rejected for simplicity; single `.brepl/hooks.edn` is cleaner
3. **YAML/JSON config format** - Rejected; EDN is idiomatic for Clojure projects

## Success Criteria

- User can define stop hooks in `.brepl/hooks.edn`
- REPL hooks execute Clojure code and report results
- Bash hooks execute shell commands and report results
- Failed blocking hooks with `:loop-on-failure? true` cause Claude to continue working
- `brepl hook install` generates template config file
- Spec validation catches invalid hook configurations
