## 1. Implementation

- [x] Modify argument parser to treat first positional argument as implicit `-e` when no mode flags present
- [x] Update validation logic to allow positional arguments
- [x] Preserve all existing explicit flag behavior (no changes to `-e`, `-f`, `-m` code paths)

## 2. Testing

- [x] Add test for heredoc without `-e`: `brepl "$(cat <<'EOF' (+ 1 2) EOF)"`
- [x] Add test for simple expression: `brepl "(+ 1 2)"`
- [x] Verify all existing tests pass (backward compatibility)
- [x] Test with hook mode: `brepl --hook "(+ 1 2)"`

## 3. Documentation

- [x] Update help text to mention positional argument support
- [x] Update SKILL.md to reflect that `-e` is now optional
- [x] Add examples of implicit eval to help text

## 4. Validation

- [x] Run full test suite: `bb test`
- [x] Manually test: `brepl "$(cat <<'EOF' (+ 1 2) EOF)"`
- [x] Manually test: `brepl "(+ 1 2)"`
- [x] Verify no regression in existing workflows
