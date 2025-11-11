# Tasks: Add Hook Install Command

## Overview
Implementation tasks for adding Claude Code hook support to brepl. Tasks are ordered for incremental delivery with verification at each step.

## Phase 1: Foundation (Validation Module)

### Task 1.1: Add edamame dependency
- Add `edamame/edamame` to bb.edn dependencies
- Verify dependency resolution with `bb classpath`
- Create basic test that imports edamame

**Validation**: `bb -e "(require '[edamame.core :as e]) (e/parse-string \"(+ 1 2)\")"` succeeds

### Task 1.2: Create delimiter validator module
- Create `lib/validator.clj` with namespace
- Implement `delimiter-error?` function using edamame
- Test with valid/invalid Clojure code samples
- Return structured error map with line/column info

**Files**: `lib/validator.clj`, `test/validator_test.clj`

**Validation**: Unit tests pass for:
- Valid code returns `nil`
- Unclosed delimiter returns error map
- Mismatched delimiter returns error map with position

### Task 1.3: Add Clojure file extension check
- Implement `clojure-file?` function
- Check against `.clj`, `.cljs`, `.cljc`, `.cljx` extensions
- Add tests for various file paths

**Validation**: Tests confirm correct file type detection

## Phase 2: Hook Validation Subcommand

### Task 2.1: Add hook subcommand CLI structure
- Extend brepl CLI spec with `hook` command
- Add subcommand parsing for `validate`, `eval`, `install`, `uninstall`, `session-end`
- Wire up basic command routing
- Return placeholder success for now

**Files**: brepl (main script)

**Validation**: `brepl hook validate foo.clj ""` runs without error (returns success)

### Task 2.2: Implement `brepl hook validate`
- Load validator module
- Parse arguments: file-path, new-content
- Check file extension (skip non-Clojure)
- Validate new-content with edamame
- Return Claude Code JSON response

**Files**: brepl, lib/validator.clj

**Validation**:
```bash
# Valid content
$ brepl hook validate test.clj "(defn foo [])"
{"continue": true, "decision": "allow", "suppressOutput": true}

# Invalid content
$ brepl hook validate test.clj "(defn foo ["
{"continue": true, "decision": "block", "stopReason": "...", "reason": "..."}
```

### Task 2.3: Improve error messages
- Extract line/column from edamame errors
- Identify delimiter type (unclosed, mismatched, unexpected)
- Format clear, actionable error messages for Claude
- Include code context when available

**Validation**: Error messages are clear and include:
- Specific error location
- Type of delimiter problem
- Suggested fix

## Phase 3: Backup System

### Task 3.1: Create backup module
- Create `lib/backup.clj`
- Implement session directory creation in `/tmp/brepl-hooks-<session-id>/`
- Implement `create-backup` function
- Implement `restore-backup` function
- Add file permission preservation

**Files**: `lib/backup.clj`, `test/backup_test.clj`

**Validation**: Unit tests for:
- Backup creates file in session directory
- Restore recovers exact content
- File permissions preserved

### Task 3.2: Add session-end subcommand
- Implement `brepl hook session-end <session-id>`
- Delete all files in session backup directory
- Remove session directory
- Handle missing/already-cleaned sessions gracefully

**Validation**:
```bash
$ brepl hook session-end test-session-123
# Session directory removed
```

### Task 3.3: Integrate backup with validate command
- Create backup before returning allow decision (pre-edit)
- Use `$SESSION_ID` environment variable from Claude Code
- Skip backup for non-Clojure files
- Handle backup errors gracefully (warn but allow)

**Files**: brepl, lib/backup.clj

**Validation**: After running validate, backup file exists in session directory

## Phase 4: Post-Edit Evaluation

### Task 4.1: Implement `brepl hook eval`
- Add `eval` subcommand
- Load file from disk
- Validate delimiters (reuse validator module)
- If invalid: restore backup, block with error
- If valid: proceed to evaluation

**Files**: brepl

**Validation**:
```bash
# Create test file with valid syntax
$ echo "(defn test [])" > /tmp/test.clj
$ brepl hook eval /tmp/test.clj
# Returns success (may warn about no REPL)
```

### Task 4.2: Add file evaluation via nREPL
- Resolve nREPL port using existing `resolve-port` logic
- Use existing `eval-file` function with `--hook` flag
- Parse JSON response from eval
- Check for errors in response
- If eval error: restore backup, block with details

**Validation**:
- Valid file with running nREPL: evaluation succeeds
- Invalid code (undefined symbol): evaluation fails, backup restored
- No nREPL: warning but success (graceful degradation)

### Task 4.3: Format evaluation errors for Claude
- Extract exception type and message
- Include stack trace information
- Show relevant line numbers
- Provide contextual suggestions
- Format as Claude Code JSON

**Validation**: Eval errors include:
- Exception type
- Error location
- Stack trace (when available)
- Helpful remediation suggestion

## Phase 5: Optional Parinfer Integration

### Task 5.1: Create parinfer module
- Create `lib/parinfer.clj`
- Implement `parinfer-available?` check (binary on PATH)
- Implement `parinfer-repair` function
- Invoke `parinfer-rust --mode indent` with stdin
- Return repaired content or nil if failed

**Files**: `lib/parinfer.clj`, `test/parinfer_test.clj`

**Validation**:
- When parinfer-rust installed: repair works
- When not installed: returns nil gracefully

### Task 5.2: Integrate parinfer with eval subcommand
- After delimiter validation fails in `hook eval`
- If `--parinfer` flag enabled: attempt repair
- Validate repaired content
- If valid: write repaired content, success
- If invalid: restore backup, block

**Files**: brepl, lib/parinfer.clj

**Validation**:
- Simple unclosed delimiter: auto-fixed
- Complex unfixable error: restored, blocked
- Parinfer disabled: skips repair, restores

### Task 5.3: Add parinfer flag to install command
- Parse `--parinfer` flag in `brepl hook install`
- Check for parinfer-rust availability
- Warn if requested but unavailable
- Store preference in generated hook config

**Validation**: `brepl hook install --parinfer` checks for binary and configures appropriately

## Phase 6: Hook Installation

### Task 6.1: Create installer module
- Create `lib/installer.clj`
- Implement `.claude/settings.local.json` reading
- Implement JSON merging with existing settings
- Preserve user settings (model, other hooks, etc.)
- Implement hook configuration generation

**Files**: `lib/installer.clj`, `test/installer_test.clj`

**Validation**: Unit tests for:
- New settings file creation
- Merging with existing settings
- Hook deduplication

### Task 6.2: Implement `brepl hook install`
- Add `install` subcommand
- Create/read `.claude/settings.local.json`
- Generate hook configuration (PreToolUse, PostToolUse, SessionEnd)
- Write updated settings file
- Report installed features

**Files**: brepl, lib/installer.clj

**Validation**:
```bash
$ brepl hook install
Installing Claude Code hooks...
Hooks installed successfully.
# .claude/settings.local.json contains correct config
```

### Task 6.3: Implement `brepl hook uninstall`
- Add `uninstall` subcommand
- Read `.claude/settings.local.json`
- Remove brepl-specific hooks
- Preserve other hooks and settings
- Report success

**Validation**:
```bash
$ brepl hook uninstall
Removing Claude Code hooks...
Hooks uninstalled successfully.
# brepl hooks removed from settings
```

### Task 6.4: Add installation status reporting
- Check parinfer-rust availability
- List enabled features (validation, eval, auto-repair)
- Show clear status with checkmarks/crosses
- Provide help for missing dependencies

**Validation**: Install command shows clear feature status

## Phase 7: Testing & Documentation

### Task 7.1: Integration tests
- Test full pre-edit → edit → post-edit flow
- Test with mock nREPL server
- Test backup/restore scenarios
- Test parinfer integration (when available)
- Test error recovery paths

**Files**: `test/hook_integration_test.clj`

**Validation**: All integration tests pass

### Task 7.2: Update README
- Add "Claude Code Integration" section
- Document `brepl hook install` command
- Explain pre-edit and post-edit validation
- List optional parinfer dependency
- Show example workflow

**Files**: README.md

**Validation**: README clearly documents hook features

### Task 7.3: Add help text
- Update `print-help` to include hook subcommands
- Add `brepl hook --help` output
- Document each subcommand's purpose
- Include example commands

**Files**: brepl

**Validation**: `brepl hook --help` shows clear documentation

### Task 7.4: Manual testing with Claude Code
- Install hooks in test project
- Verify pre-edit validation blocks invalid syntax
- Verify post-edit evaluation catches errors
- Test backup/restore on failures
- Test with/without parinfer
- Test multi-project port resolution

**Validation**: Hooks work correctly in real Claude Code sessions

## Phase 8: Polish & Release

### Task 8.1: Performance optimization
- Lazy-load hook modules (only when hook commands used)
- Cache parinfer availability check
- Optimize edamame parsing for large files
- Profile hook latency (target <500ms for typical files)

**Validation**: Hook commands remain fast, don't slow down main brepl

### Task 8.2: Error handling audit
- Review all error paths
- Ensure graceful degradation everywhere
- Clear error messages for all failure modes
- Never leave files in corrupt state

**Validation**: Error scenarios handled gracefully

### Task 8.3: Update CHANGELOG
- Document new `brepl hook` commands
- Note optional parinfer-rust dependency
- Add upgrade instructions
- Credit clojure-mcp-light inspiration

**Files**: CHANGELOG.md

### Task 8.4: Update version and release
- Bump version to 1.4.0 (new feature)
- Update version string in script
- Create git tag
- Update Nix hash if needed
- Create GitHub release

**Validation**: Release artifacts ready

## Dependencies Between Tasks

**Sequential Dependencies**:
- 1.2 depends on 1.1 (edamame)
- 2.2 depends on 1.2 (validator)
- 2.3 depends on 2.2 (validate command)
- 3.3 depends on 3.1, 2.2 (backup + validate)
- 4.1 depends on 3.1, 1.2 (backup + validator)
- 4.2 depends on 4.1 (eval command)
- 5.2 depends on 5.1, 4.1 (parinfer + eval)
- 6.2 depends on 6.1 (installer module)
- 7.1 depends on all implementation tasks

**Parallelizable**:
- Phase 3 (backup) can be done in parallel with Phase 2
- Phase 5 (parinfer) can be started after Phase 1
- Documentation (7.2, 7.3) can be written during implementation

## Verification Checklist

Before considering the feature complete:

- [x] All unit tests pass
- [ ] Integration tests pass (deferred - manual testing completed)
- [x] `brepl hook install` works
- [x] `brepl hook uninstall` works
- [x] `brepl hook validate` blocks invalid syntax
- [x] `brepl hook eval` catches eval errors
- [x] Backup/restore works correctly
- [ ] Parinfer integration works (when available) - skipped, edamame auto-fix used instead
- [x] Graceful degradation without nREPL
- [x] Multi-project port resolution works
- [x] No regression in existing brepl functionality
- [x] Documentation complete and accurate
- [x] Manually tested with Claude Code
- [x] Performance acceptable (<500ms hook latency)
