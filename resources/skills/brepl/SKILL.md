---
name: brepl
description: Use when evaluating Clojure code via brepl. Teaches the heredoc pattern for reliable code evaluation. Bracket errors are automatically fixed in hook mode.
---

# brepl - Evaluating Clojure Code

## Overview

brepl is a REPL client for evaluating Clojure expressions with built-in automatic bracket fixing capabilities. This skill teaches:
1. The heredoc pattern for reliable code evaluation
2. Understanding that bracket errors are automatically fixed in hook mode

Use the heredoc pattern consistently for all Clojure code evaluation.

## The Heredoc Pattern - Default Approach

**Always use heredoc for brepl evaluation.** This eliminates quoting issues, works for all cases, and provides a consistent, reliable pattern.

### Syntax

```bash
brepl -e "$(cat <<'EOF'
(your clojure code here)
EOF
)"
```

**Important**: Use `<<'EOF'` (with quotes) not `<<EOF` to prevent shell variable expansion.

### Examples

**Multi-line expressions**:
```bash
brepl -e "$(cat <<'EOF'
(require '[clojure.string :as str])
(str/join ", " ["a" "b" "c"])
EOF
)"
```

**Code with quotes**:
```bash
brepl -e "$(cat <<'EOF'
(println "String with 'single' and \"double\" quotes")
EOF
)"
```

**Reloading and testing**:
```bash
brepl -e "$(cat <<'EOF'
(require '[myapp.core] :reload)
(myapp.core/some-function "test" 123)
EOF
)"
```

**Complex data structures**:
```bash
brepl -e "$(cat <<'EOF'
(def config
  {:database {:host "localhost"
              :port 5432
              :name "mydb"}
   :api {:key "secret-key"
         :endpoint "https://api.example.com"}})
(println (:database config))
EOF
)"
```

**Running tests**:
```bash
brepl -e "$(cat <<'EOF'
(require '[clojure.test :refer [run-tests]])
(require '[myapp.core-test] :reload)
(run-tests 'myapp.core-test)
EOF
)"
```

## Alternative: Direct `-e` Flag

While you can use the direct `-e` flag for simple expressions, the heredoc pattern is recommended as the default to maintain consistency:

```bash
# Works, but heredoc is preferred
brepl -e '(inc 1)'

# Same with heredoc (consistent approach)
brepl -e "$(cat <<'EOF'
(inc 1)
EOF
)"
```

**Why prefer heredoc:** No mental overhead deciding which pattern to use, no risk of quoting issues, easy to extend.

## Loading Files

To load an entire file into the REPL:

```bash
brepl -f src/myapp/core.clj
```

After loading, you can evaluate functions from that namespace using either pattern.

## Automatic Bracket Fixing

When using brepl with Claude Code hooks installed, bracket errors are **automatically fixed** before code is written to files. You don't need to manually fix brackets.

### How It Works

brepl uses [parmezan](https://github.com/borkdude/parmezan) to automatically fix:
- Missing closing brackets: `(defn foo [` → `(defn foo [])`
- Extra closing brackets: `(+ 1 2))` → `(+ 1 2)`
- Mismatched delimiters in many cases
- Multiple nested issues

### In Hook Mode

When Claude Code hooks are installed (`brepl hook install`):
1. You write Clojure code (even with bracket errors)
2. brepl automatically fixes brackets before writing
3. The corrected code is saved and evaluated
4. You see any evaluation errors immediately

**No manual intervention needed** - just write code naturally and let brepl handle the brackets.

### Manual Evaluation

When evaluating code manually with `brepl -e`, syntax errors will be reported if brackets are invalid. The automatic fixing only applies in hook mode.

## Common Patterns

### Namespace reloading
```bash
brepl -e "$(cat <<'EOF'
(require '[myapp.core] :reload-all)
EOF
)"
```

### Documentation lookup
```bash
brepl -e "$(cat <<'EOF'
(require '[clojure.repl :refer [doc source]])
(doc map)
(source filter)
EOF
)"
```

### Error inspection
```bash
brepl -e "$(cat <<'EOF'
*e
(require '[clojure.repl :refer [pst]])
(pst)
EOF
)"
```

## Critical Rules

1. **Always use heredoc** - Use the heredoc pattern for all brepl evaluations
2. **Quote the delimiter** - Always use `<<'EOF'` not `<<EOF` to prevent shell expansion
3. **No escaping needed** - Inside heredoc, write Clojure code naturally
4. **Multi-step operations** - Combine multiple forms in one heredoc block
5. **Trust automatic fixing** - In hook mode, brackets are fixed automatically; focus on writing correct logic

## Why Always Use Heredoc

**Consistency over optimization.** While direct `-e` works for simple cases, using heredoc everywhere means:

1. **No decision fatigue** - One pattern for everything
2. **No quoting errors** - Everything between `<<'EOF'` and `EOF` is literal
3. **Easy to extend** - Add more lines without changing syntax
4. **Readable** - Clear where the code starts and ends
5. **Safe** - No shell interpretation of Clojure code

Shell quoting with Clojure is error-prone: Clojure uses both single and double quotes, nested quotes require escaping, and reader macros can confuse the shell. Heredoc eliminates all these issues.

## Resources

brepl documentation: https://github.com/licht1stein/brepl
