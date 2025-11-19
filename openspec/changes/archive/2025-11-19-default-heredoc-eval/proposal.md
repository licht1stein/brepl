# Change: Default Heredoc to Eval Mode

## Why

Claude and other AI agents frequently use heredoc patterns for evaluating Clojure code but often forget to add the `-e` flag, resulting in confusing errors. The heredoc pattern (`brepl "$(cat <<'EOF' ... EOF)"`) is already the recommended approach for all code evaluation, so making it default to eval mode removes friction and aligns with actual usage patterns.

## What Changes

- When brepl receives input without any explicit flags (`-e`, `-f`, `-m`), treat it as `-e` (eval mode)
- All existing explicit flag usage (`-e`, `-f`, `-m`) remains unchanged
- This makes the heredoc pattern work without requiring `-e` flag
- Users who want file loading still use `-f` explicitly

## Impact

- Affected specs: cli-interface (new spec)
- Affected code: `brepl` main script - argument parsing (~5 line change)
- User impact: Positive - heredoc pattern just works, no change to explicit flag usage
- Breaking changes: **None** - currently `brepl <arg>` without flags errors, now it evals
