---
name: brepl
description: Use when evaluating Clojure code via brepl or fixing bracket errors. Teaches the heredoc pattern for reliable code evaluation and parinfer integration for automatic bracket correction.
---

# brepl - Evaluating Clojure Code

## Overview

brepl is a REPL client for evaluating Clojure expressions with built-in bracket fixing capabilities. This skill teaches:
1. The heredoc pattern for reliable code evaluation
2. Using parinfer to automatically fix bracket errors

Use these patterns consistently for all Clojure code evaluation and error recovery.

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

## Fixing Bracket Errors with Parinfer

When encountering bracket mismatches or unclosed delimiters, use `brepl parinfer --mode smart` to automatically fix them:

### Fixing Code Before Evaluation

```bash
# Fix brackets in an expression, then evaluate
brepl -e "$(echo '(defn foo [' | brepl parinfer --mode smart)"

# Fix brackets in heredoc before evaluation
brepl -e "$(cat <<'EOF' | brepl parinfer --mode smart
(defn add [a b
  (+ a b
EOF
)"
```

### Fixing File Contents

```bash
# Fix brackets in-place - MUST use temp file
# (Cannot do: < file.clj > file.clj - shell truncates output file before reading input)
FILE=src/myapp/core.clj && brepl parinfer --mode smart < "$FILE" > /tmp/tmp.clj && mv /tmp/tmp.clj "$FILE"

# Or with explicit paths
brepl parinfer --mode smart < src/myapp/core.clj > /tmp/fixed.clj && mv /tmp/fixed.clj src/myapp/core.clj
```

**Always overwrite the original file using a temp file** - Cannot redirect to the same file because the shell truncates it before reading.

### When to Use Parinfer

**Use parinfer smart mode when:**
- Syntax errors mention "Unmatched delimiter" or "EOF while reading"
- Brackets don't seem balanced
- Code has missing closing brackets or parentheses
- You need to validate bracket structure before evaluation

**Pattern for error recovery:**
1. Encounter bracket error during evaluation
2. Pipe the code through `brepl parinfer --mode smart`
3. Evaluate the fixed code

### Parinfer Smart Mode

The `--mode smart` option intelligently fixes:
- Missing closing brackets: `(defn foo [` → `(defn foo [])`
- Extra closing brackets: `(+ 1 2))` → `(+ 1 2)`
- Mismatched delimiters: `[1 2 3)` → `[1 2 3]`
- Multiple nested issues

**Example workflow:**
```bash
# Original code with bracket error
CODE='(defn calculate [x y
  (let [sum (+ x y]
    (* sum 2'

# Fix and evaluate
brepl -e "$(echo "$CODE" | brepl parinfer --mode smart)"
```

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
5. **Fix brackets with parinfer** - When encountering bracket errors, pipe through `brepl parinfer --mode smart` before evaluation

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
