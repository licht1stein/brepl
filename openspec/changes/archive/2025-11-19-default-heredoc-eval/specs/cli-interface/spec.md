## ADDED Requirements

### Requirement: Default to Eval Mode

When brepl receives positional arguments without explicit mode flags, it SHALL treat the input as eval mode.

#### Scenario: Heredoc pattern without -e flag

**Given** user invokes brepl with heredoc output:

```bash
brepl "$(cat <<'EOF'
(+ 1 2 3)
EOF
)"
```

**When** brepl receives the string as first positional argument

**Then** brepl should:

1. Detect no explicit `-e`, `-f`, or `-m` flags present
2. Treat input as eval mode (implicit `-e`)
3. Evaluate the expression and return result

**Output:**

```
6
```

The command succeeds without requiring explicit `-e` flag.

#### Scenario: Single-line expression without -e flag

**Given** user provides simple expression:

```bash
brepl "(inc 42)"
```

**When** brepl receives code as positional argument

**Then** brepl should treat it as eval mode

**Output:**

```
43
```

#### Scenario: Positional argument with other options

**Given** user provides positional argument with port or other options:

```bash
brepl "(+ 1 2 3)" -p 7777
brepl -p 7777 "(+ 1 2 3)"
brepl --hook "(inc 42)"
brepl --verbose "(println :hello)" -p 7888
```

**When** positional argument is combined with non-mode options

**Then** the positional argument is treated as eval mode (implicit `-e`)

**And** all other options are processed normally

**This ensures** options like `-p`, `-h`, `--hook`, `--verbose` compose with positional eval arguments just as they do with explicit `-e` flag.

#### Scenario: Explicit flags still work

**Given** user explicitly provides `-e` flag:

```bash
brepl -e "(+ 1 2)"
```

**When** brepl processes the arguments

**Then** existing behavior is preserved - no changes to explicit flag handling

**And** all current `-e`, `-f`, and `-m` usage patterns continue working exactly as before

### Requirement: Backward Compatibility

All existing invocation patterns SHALL continue working without changes.

#### Scenario: Current explicit usage unchanged

**Given** any existing script or workflow using explicit flags

**When** flags `-e`, `-f`, or `-m` are present

**Then** behavior is identical to current version - no breaking changes

**Examples that must continue working:**

```bash
brepl -e '(+ 1 2)'
brepl -f script.clj
brepl -m '{"op": "describe"}'
brepl -p 7888 -e '(println "hi")'
BREPL_PORT=7888 brepl -e '(+ 1 2)'
```

#### Scenario: Help and version commands unchanged

**Given** user runs utility commands:

```bash
brepl --help
brepl --version
```

**Then** these continue working exactly as before

#### Scenario: Hook and skill subcommands unchanged

**Given** user runs subcommands:

```bash
brepl hook install
brepl skill install
```

**Then** these continue working exactly as before

### Requirement: Clear Error Messages

When no input is provided, brepl SHALL show help message.

#### Scenario: No input provided

**Given** user runs `brepl` with no arguments

**When** no flags or positional arguments are present

**Then** show help message and exit with code 1

**Current behavior** (preserved):

```
Error: Must specify one of -e EXPR, -f FILE, or -m MESSAGE

USAGE:
    brepl [OPTIONS] -e <expr>
    ...
```

#### Scenario: Multiple conflicting modes detected

**Given** user provides conflicting inputs:

```bash
brepl -e "(+ 1 2)" -f script.clj
```

**When** multiple mode flags are specified

**Then** show error about conflicting flags and exit with code 1

**Current behavior** (preserved):

```
Error: Cannot specify multiple options (-e, -f, -m) together
```

