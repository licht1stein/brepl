# Adopt Parmezan for Delimiter Correction

## Overview

**Change ID:** `adopt-parmezan`

Replace the current parinfer-rust and edamame-based delimiter fixing approach with borkdude/parmezan, a pure-Clojure library that provides simpler, more reliable delimiter correction.

## Motivation

The current implementation has three problems:

1. **External dependency on parinfer-rust** - Requires separate binary installation, not available in all environments
2. **Complex error handling** - Edamame for detection + parinfer-rust for fixing creates two-stage complexity
3. **Limited control** - External parinfer-rust process limits customization and error recovery

Parmezan solves these by:

- Being pure Clojure/Babashka compatible (no external binaries)
- Using edamame internally for both detection and fixing (simpler flow)
- Providing programmatic control over the correction process
- Being maintained by @borkdude (same author as Babashka)

## Impact

### What Changes

- **lib/validator.clj** - Replace `delimiter-error?` and `auto-fix-brackets` with parmezan-based implementation
- **brepl** - Remove `parinfer-available?` check and `brepl parinfer` subcommand
- **package.nix** - Remove parinfer-rust dependency, add parmezan
- **README.md** - Update documentation to reference parmezan instead of parinfer-rust
- **bb.edn** - Add parmezan dependency

### What Stays the Same

- Hook behavior and API (still auto-fixes brackets before edits)
- Error message format for Claude Code
- File type filtering logic
- Test suite structure (update expectations, not approach)

### User Impact

**Breaking Changes:**
- `brepl parinfer` command removed (users must use parmezan CLI directly if needed)
- No longer requires parinfer-rust binary installation

**Improvements:**
- Simpler installation (one fewer external dependency)
- More reliable fixing (pure Clojure, no shell execution)
- Consistent behavior across all platforms

## Implementation Approach

1. Add parmezan dependency to bb.edn
2. Rewrite lib/validator.clj to use parmezan for both detection and fixing
3. Remove parinfer-rust code paths from brepl script
4. Update Nix packaging to replace parinfer-rust with parmezan
5. Update README and documentation
6. Update test expectations to match parmezan's behavior

## Related Changes

None - this is a standalone refactoring.

## Validation

- All existing tests in test/hook_validate_test.clj must pass
- Manual testing of hook mode with Claude Code
- Verify brepl works without parinfer-rust installed
- Confirm Nix build includes parmezan correctly
