# Implementation Tasks

## Phase 1: Add Dependency and Update Validator (Core Logic)

- [x] 1. **Add parmezan dependency to bb.edn**
  - Added `io.github.borkdude/parmezan {:git/sha "39a978daffc025aceeed6a8ea2acaabbba901154"}` to deps
  - Verified bb.edn is valid with `bb --version`
  - **Validation:** `bb -e "(require '[borkdude.parmezan :as p])"` ✓

- [x] 2. **Rewrite lib/validator.clj to use parmezan**
  - Replaced `edamame.core` require with `borkdude.parmezan`
  - Removed `delimiter-error?` function (no longer needed for detection)
  - Replaced `auto-fix-brackets` implementation with parmezan call
  - Removed `parinfer-available?` function
  - Removed `format-error-message` function (simpler error handling)
  - Updated `clojure-file?` function with `.bb` extension and shebang detection
  - **Validation:** Unit tests pass ✓

- [x] 3. **Update test/hook_validate_test.clj**
  - Updated test expectations to match parmezan behavior
  - Removed parinfer-rust availability checks
  - Simplified test assertions
  - Added tests for `.bb` extension validation
  - Added tests for Babashka shebang detection
  - **Validation:** `bb test` passes ✓

## Phase 2: Remove Parinfer Command and References

- [x] 4. **Update brepl script**
  - Removed `parinfer-available?` function
  - Removed `brepl parinfer` subcommand handling
  - Removed parinfer references from help text
  - Updated hook logic to use parmezan directly
  - **Validation:** `./brepl --help` shows correct commands ✓

- [x] 5. **Update README.md**
  - Replaced parinfer-rust references with parmezan
  - Updated "Bracket Fixing" capability description
  - Removed `brepl parinfer` command examples
  - Updated installation instructions
  - **Validation:** Documentation is consistent ✓

## Phase 3: Update Packaging

- [x] 6. **Update package.nix**
  - Removed `parinfer-rust` from function parameters
  - Removed `parinfer-rust` from `lib.makeBinPath`
  - Updated `longDescription` to reference parmezan
  - **Validation:** Nix file updated ✓

- [x] 7. **Update shell.nix (if exists)**
  - Removed parinfer-rust from buildInputs
  - Updated shellHook
  - **Validation:** Nix shell config updated ✓

## Phase 4: Final Validation

- [x] 8. **Run full test suite**
  - Executed `bb test`
  - All 26 tests passed, 162 assertions
  - **Validation:** Zero test failures ✓

- [x] 9. **Manual hook testing**
  - Tested `brepl hook validate` with valid Clojure code ✓
  - Tested with code missing closing brackets - auto-fixed ✓
  - Tested with non-Clojure files - skipped validation ✓
  - Verified JSON output matches expected format ✓
  - **Validation:** Hook behaves correctly for all cases ✓

- [x] 10. **Integration testing**
  - Basic brepl functionality verified ✓
  - Hook mode tested manually ✓
  - All functionality works without parinfer-rust ✓
  - **Validation:** Parmezan integration complete ✓

## Summary

All tasks completed successfully. The brepl project has been successfully migrated from parinfer-rust to parmezan for delimiter correction. Key improvements:

- Pure Clojure solution (no external binary dependencies)
- Simpler implementation (unified detection and fixing)
- Added support for `.bb` files and Babashka shebangs
- All tests passing
- Hook mode working correctly with automatic bracket fixing
