# Proposal: Add Hook Install Command

## Summary
Add `brepl hook` subcommands that enable Claude Code hooks for automatic Clojure file validation during AI-assisted development. This provides:
- Auto-correction of bracket errors using edamame (replaces clojure-mcp for AI editing)
- Pre-edit syntax validation and fixing before writes
- Post-edit evaluation with warnings (not blocks by default)
- Idempotent local installation per project
- Zero external dependencies (pure Babashka-compatible Clojure)

## Motivation
When Claude edits Clojure files, syntax errors (mismatched brackets) and runtime errors can be introduced. Currently, these errors are only discovered when the code is run later, requiring additional back-and-forth with Claude to fix issues.

The hook system will:
1. **Auto-fix bracket errors** - Use edamame's `:edamame/expected-delimiter` to recursively fix incomplete expressions (replaces clojure-mcp for AI editing)
2. **Prevent invalid edits** - Block edits with unfixable syntax errors before they're written
3. **Warn about eval errors** - Evaluate edited files to detect runtime issues, but allow edits with valid justification
4. **Provide clear feedback** - Give Claude specific error messages to enable targeted fixes
5. **Zero external dependencies** - Pure Clojure solution using existing Babashka-compatible libraries

## User Experience

### Installation
```bash
# Install hooks in current project (idempotent, local to project)
brepl hook install

# Install with strict evaluation (blocks on eval errors)
brepl hook install --strict-eval

# Uninstall hooks
brepl hook uninstall
```

All hooks are installed locally in the project's `.claude/settings.local.json` and are idempotent - running install multiple times is safe.

### Hook Behavior
When Claude edits a Clojure file:

**Pre-Edit (Auto-Fix + Validation)**
- Parse new content with edamame
- If bracket errors detected: recursively auto-fix using `:edamame/expected-delimiter`
- Return corrected code to Claude
- If unfixable errors: block edit with detailed error message

**Post-Edit (Evaluation with Warnings)**
- Validate edited file has correct bracket syntax
- Evaluate the entire file via nREPL
- If eval errors: **warn Claude** but **don't block** by default
- Claude can proceed if there's a valid reason (writing tests, incremental updates, etc.)
- With `--strict-eval` flag: block on eval errors (optional strictness)

## Implementation Approach

### New Components
1. **Hook Subcommands** (`brepl hook`) - Claude Code integration commands
2. **Edamame Auto-Fix** - Recursive bracket correction using `:edamame/expected-delimiter`
3. **Evaluation with Warnings** - Non-blocking eval feedback by default
4. **Hook Installer** - Idempotent local installation in `.claude/settings.local.json`

### Integration Points
- Reuse existing nREPL client for file evaluation
- All commands go through `brepl hook` (may load from separate files for performance)
- Leverage brepl's port resolution for multi-project support
- Replace clojure-mcp with edamame-based auto-correction for AI editing
- Pure Clojure solution with zero external binary dependencies

## Success Criteria
1. `brepl hook install` idempotently installs hooks in local project
2. Pre-edit auto-fix corrects bracket errors using edamame
3. Post-edit evaluation warns about runtime errors without blocking
4. Clear, actionable error messages guide Claude to fixes
5. Zero external dependencies (no parinfer-rust binary required)
6. Replaces clojure-mcp with simpler edamame-based approach for AI editing

## Scope Boundaries

### In Scope
- Hook installation/uninstallation (idempotent, local to project)
- Pre-edit auto-fix using edamame's `:edamame/expected-delimiter`
- Pre-edit delimiter validation and error reporting
- Post-edit full-file evaluation with warnings
- Error message formatting for Claude
- Optional strict evaluation mode

### Out of Scope
- Post-edit formatting (cljfmt) - can be added later
- Statistics tracking - can be added later
- Multi-file evaluation - only edited file is evaluated
- REPL session management - uses one-shot evaluation
- External binary dependencies - pure Clojure only

## Dependencies
- **Required**: edamame (Babashka-compatible parser - already compatible)
- **Existing**: brepl nREPL client (for evaluation)
- **Zero external binaries**: No parinfer-rust or other external tools needed

## Risks & Mitigation
**Risk**: Edamame auto-fix might not handle all edge cases
- *Mitigation*: Fall back to error reporting for unfixable cases; clear error messages guide manual fix

**Risk**: Evaluation may be slow for large files
- *Mitigation*: Use warnings instead of blocking, add timeout with graceful skip

**Risk**: Evaluation failures block legitimate development workflows
- *Mitigation*: Default to warnings, not blocks; add `--strict-eval` for strictness

**Risk**: Port resolution may fail without .nrepl-port
- *Mitigation*: Skip evaluation gracefully, only validate syntax

**Risk**: Files intentionally don't eval during development (tests, incremental work)
- *Mitigation*: Evaluation errors are warnings that make Claude aware but don't block
