# Learnings

## 2026-02-17 Session Start

### Project Architecture
- brepl is a single-file Babashka CLI (`src/brepl.clj` 1194 lines) + lib modules in `src/brepl/lib/`
- Builds to uberscript via `bb build` — must rebuild after source changes
- All tests via `bb test` using cognitect test-runner

### Key Patterns
- Module structure: see `validator.clj` — ns docstring, minimal requires, pure functions, try/catch → nil
- Shell execution: see `stop_hooks.clj:178-206` — `process/shell {:out :string :err :string :continue true :timeout N}`, catch TimeoutException and Exception separately
- Socket+bencode: see `brepl.clj:186-214` — `Socket.`, `PushbackInputStream.`, `bencode/write-bencode` + `.flush`, loop-until-done on status "done"
- `->str` helper (brepl.clj:177) converts bencode byte arrays to strings

### resolve-port Call Sites
- `-main` (line 374): hard-fails on nil → prints error, exits 1
- `handle-eval` (line 694): tolerates nil → skips evaluation
- `handle-eca-eval` (line 1089): tolerates nil → skips evaluation
- `stop-hooks` via dynamic var: tolerates nil

### Discovery Design
- New fallback ONLY — fires when CLI -p, .nrepl-port, BREPL_PORT all absent
- CWD match required — use `fs/canonicalize` on both sides for symlink safety
- lsof parsing in Clojure — COMMAND column word-boundary exact match, not shell grep
- IPv6 regex: `#"TCP\s+(?:\*|[\d.]+|\[[\da-fA-F:]+\]):(\d+)\s+\(LISTEN\)"`
- Process name filter set: `#{"java" "clojure" "babashka" "bb"}` — exact, case-insensitive
- Timeouts: 5000ms for lsof, 2000ms for TCP socket connections

## [2026-02-17] Task 1 completed
- discovery.clj created at src/brepl/lib/discovery.clj
- Pre-commit hook auto-rebuilds uberscript when src/ changes — commit included rebuilt brepl binary
- `get-listening-ports` returns nil on non-zero exit (added `(when (zero? (:exit result)) ...)` guard)
- `validate-nrepl-port` uses `(into {} (map (fn [[k v]] [(->str k) (->str v)])) response)` for key conversion — simpler than postwalk for flat maps
- `discover-nrepl-port` uses `reduce`+`reduced` for early termination — idiomatic sequential scan with short-circuit
- `parse-lsof-output` uses `keep` with inline filtering — single pass, no intermediate collections
- PATH="" test can't work directly (bb itself not found) — used `with-redefs` on `process/shell` to simulate failure
