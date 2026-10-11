---
name: brepl
description: "Evaluate Clojure code and use the REPL via brepl. Load this skill before using brepl."
---

## Requirements

Use an installed `brepl` binary on `PATH`. Evaluation requires a running nREPL
server; manual bracket repair does not.

## Command Reference

- `brepl -e EXPR` or `brepl EXPR`: evaluate one expression argument.
- `brepl` with stdin: evaluate code from a pipe or heredoc.
- `brepl -f FILE`: load and execute a file.
- `brepl -m MESSAGE`: send a raw nREPL message in EDN format.
- `-p PORT`, `-h HOST`: select the nREPL server. `-h` is not help.
- `--verbose`: show raw protocol messages instead of normal result output.
- `--help` or `-?`: show help. `--version`: show the installed version.
- `brepl balance FILE [--dry-run]`: repair brackets, or preview the repair.

## Evaluating Code

Prefer a quoted heredoc in a Bash-compatible shell. Use `<<'EOF'`, not
`<<EOF`, to prevent shell expansion. Put the closing `EOF` on its own line.
Write normal Clojure inside it without extra shell escaping; Clojure string
escaping still applies. If the shell tool cannot pass multiline input, use a
positional argument only for simple code that can be quoted safely.

```bash
brepl <<'EOF'
(require '[clojure.string :as str])
(str/join ", " ["a" "b" "c"])
EOF
```

A Clojure quote such as `'foo` breaks single-quoted shell arguments; use a
heredoc for code containing `'`. For simple expressions without it, this works:

```bash
brepl '(+ 1 2 3)'
```

`-e` is optional for positional expressions and stdin. Choose one input method:
`-e EXPR`, positional code, stdin, `-f`, or `-m`. An explicit or positional
expression causes stdin to be ignored. Do not use bare `-e` with a heredoc;
it can evaluate `true` instead of the supplied code. Put multiple forms in one
expression argument or heredoc, not separate positional arguments.

## Selecting the Server

Port priority is `-p`, then `.nrepl-port`, then `BREPL_PORT`, then process discovery.
Expression and raw-message modes check only the current directory's port file.
File mode searches upward from the file's directory, stopping at the current
working directory if it reaches it. Use explicit `-p` when working outside the
project root or when discovery fails. A stale port file overrides `BREPL_PORT`.
Select a host with `-h HOST`; the default is `localhost`. Do not rely on
`BREPL_HOST` in brepl 2.7.1: the CLI default overrides it.

Flags also work with stdin, without `-e`:

```bash
brepl -p 7888 <<'EOF'
(+ 1 2 3)
EOF
```

## Loading Files

```bash
brepl -f src/myapp/core.clj
```

`-f` sends a `load-file` expression, not file contents. The file must exist locally
and be accessible at that path on the server. Relative paths use the server's
working directory; prefer an absolute path accessible to both.

## Raw nREPL Operations

Use EDN (Clojure data notation) with string keys. Discover supported operations first:

```bash
brepl -m '{"op" "describe"}'
```

Only use operations advertised by that server. For example, if it supports `info`:

```bash
brepl -m '{"op" "info" "symbol" "map" "ns" "clojure.core"}'
```

Other useful operations include `lookup`, `complete`, and `ls-sessions`.
Add `--verbose` to inspect sent and received messages. Read response `status`
and error fields: raw-message mode can exit `0` even for `eval-error` or `unknown-op`.

## Checking Results

Normal evaluation and file loading return exit `2` for evaluation errors;
argument or connection failures return `1`. Inspect stderr as well as stdout.
Neither plain evaluation nor `-f` automatically repairs brackets. An evaluation
can change server state before failing; do not blindly repeat code with side effects.

## Manual Balance Recovery

Use `brepl balance` only when an error or file inspection shows unbalanced
delimiters (parentheses, brackets, or braces). Do not run it as a routine step
after a successful hook or without delimiter evidence. If a hook reports a
repair, respect that repair; inspect the current file before further changes.

Preview a repair without changing the file:

```bash
brepl balance src/myapp/core.clj --dry-run
```

Omit `--dry-run` to change the file in place. Review the resulting diff.
Do not treat silence as proof that hooks are installed or that evaluation
succeeded; check hook status and evaluation results separately.

## Resources

https://github.com/licht1stein/brepl (installation instructions if brepl is unavailable).
