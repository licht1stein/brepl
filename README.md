# brepl

[![GitHub release](https://img.shields.io/github/v/release/licht1stein/brepl?label=version)](https://github.com/licht1stein/brepl/releases/latest)
[![Run Tests](https://github.com/licht1stein/brepl/actions/workflows/test.yml/badge.svg)](https://github.com/licht1stein/brepl/actions/workflows/test.yml)

Lightweight REPL-driven development for Clojure with AI coding agents.

## What is brepl?

brepl enables AI-assisted Clojure development using your existing nREPL connection and Babashka's built-in parser. Integrates with [parinfer-rust](https://github.com/eraserhd/parinfer-rust) for automatic bracket fixing when available.

**Primary use case:** AI coding agents that automatically validate syntax, fix bracket errors, and keep your REPL synchronized with code changes.

**Also works as:** Fast command-line nREPL client for one-shot evaluations, scripts, and automation.

### Bracket Auto-Fix

brepl uses [parinfer-rust](https://github.com/eraserhd/parinfer-rust) for intelligent bracket correction:

- **With parinfer-rust installed**: Automatically fixes mismatched delimiters, missing brackets, and extra closing parens
- **Without parinfer-rust**: Blocks with detailed syntax errors for Claude to fix manually
- **Nix installation**: Includes parinfer-rust automatically

To install parinfer-rust separately:
```bash
# macOS
brew install parinfer-rust

# Nix
nix-env -iA nixpkgs.parinfer-rust

# Or build from source
git clone https://github.com/eraserhd/parinfer-rust
cd parinfer-rust && cargo install --path .
```

## Quick Start

### For AI-Assisted Development

**Using bbin:**
```bash
# Install brepl
bbin install io.github.licht1stein/brepl

# Install parinfer-rust for auto-fix (optional but recommended)
brew install parinfer-rust  # or see Bracket Auto-Fix section above

# Start your nREPL
bb nrepl-server

# Install hooks in your project
brepl hook install
```

**Using Nix (includes parinfer-rust automatically):**
```bash
nix-env -iA nixpkgs.brepl

# Or in a flake:
{
  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  # ... in your packages:
  brepl = pkgs.brepl;
}
```

Now AI agents automatically validate and evaluate Clojure code changes through your running REPL.

### For Command-Line Usage

```bash
# Evaluate expressions
brepl -e '(+ 1 2 3)'
# => 6

# Load files
brepl -f script.clj
```

## Features

### AI-Assisted Development (v2.0.2)
- 🤖 **Automatic syntax validation** - Pre-edit checking using Babashka's edamame parser
- 🔧 **Intelligent bracket auto-fix** - Uses parinfer-rust when available, detailed errors otherwise
- 💾 **Session-based backups** - Automatic backup/restore on errors
- 🔄 **REPL synchronization** - Evaluates file changes through your running nREPL
- 📦 **One-command setup** - `brepl hook install` in any project
- ⚡ **Project-aware** - Handles multiple REPLs via port discovery
- 🎯 **Graceful degradation** - Works with or without parinfer-rust

### nREPL Client Features
- 🚀 **Fast startup** - Built with Babashka for instant execution
- 💬 **Full nREPL protocol** - Access any nREPL operation, not just evaluation
- 📝 **Expression evaluation** - Evaluate Clojure expressions from command line
- 📁 **File loading** - Load and execute entire Clojure files
- 🔍 **Auto-discovery** - Automatically detects `.nrepl-port` files
- 📂 **Project-aware** (v1.3.0) - Finds `.nrepl-port` by walking up from file's directory
- ⚙️ **Flexible configuration** - Environment variables and CLI arguments
- 🐛 **Proper error handling** - Shows exceptions and stack traces
- 📊 **Verbose mode** - Debug nREPL communication with `--verbose`
- 🎯 **One-shot design** - Perfect for scripts and automation

## Installation

### Option 1: Install via bbin (recommended)

```bash
bbin install io.github.licht1stein/brepl
```

### Option 2: Install with Nix

```nix
{ pkgs ? import <nixpkgs> {} }:

let
  brepl = pkgs.callPackage (pkgs.fetchFromGitHub {
    owner = "licht1stein";
    repo = "brepl";
    rev = "v2.0.2";
    hash = "";
  } + "/package.nix") {};
in
pkgs.mkShell {
  buildInputs = [ brepl ];
}
```

Then run `nix-shell` to enter a shell with brepl available.

### Option 3: Manual Installation

```bash
git clone https://github.com/licht1stein/brepl.git
cd brepl

# Add to PATH
ln -s $(pwd)/brepl ~/.local/bin/brepl
```

## Usage

**Get help:** `brepl --help`

### Command Line Options

```
  -e, --e          <expr>     Expression to evaluate
  -f, --f          <file>     File to load and execute
  -m, --m, --message <message> Raw nREPL message (EDN format)
  -h, --h          <host>     nREPL host (default: localhost or BREPL_HOST)
  -p, --p          <port>     nREPL port (required - auto-detects from .nrepl-port or BREPL_PORT)
      --verbose              Show raw nREPL messages instead of parsed output
      --version              Show brepl version
  -?, --help                 Show help message
```

### Hook Subcommands

```bash
brepl hook install              # Install hooks to .claude/settings.local.json
brepl hook uninstall            # Remove hooks
brepl hook validate <file> <content>  # Pre-edit validation with auto-fix
brepl hook eval <file>          # Post-edit evaluation
brepl hook session-end <id>     # Cleanup session backups
```

### Basic Usage

```bash
# Evaluate an expression (auto-detects port from .nrepl-port)
brepl -e '(+ 1 2 3)'

# Load and execute a file
brepl -f script.clj

# Use single quotes to avoid escaping double quotes
brepl -e '(println "Hello, World!")'
```

### Port Configuration

The **port is required** and resolved in this order:

1. **Command line:** `-p 7888`
2. **Auto-detect:** `.nrepl-port` file
   - For `-f` flag: searches from the file's directory upward (v1.3.0+)
   - For `-e`/`-m` flags: uses current directory
3. **Environment:** `BREPL_PORT=7888`

```bash
# Explicit port
brepl -p 7888 -e '(+ 1 2)'

# Using environment variable
BREPL_PORT=7888 brepl -e '(+ 1 2)'

# Auto-detect from .nrepl-port (most common)
brepl -e '(+ 1 2)'

# NEW in v1.3.0: File-based project detection
# If you have multiple projects with different nREPL servers:
#   project1/.nrepl-port (port 7000)
#   project2/.nrepl-port (port 8000)
brepl -f project1/src/core.clj  # Uses port 7000
brepl -f project2/src/app.clj   # Uses port 8000
```

#### Project-Aware Port Discovery (v1.3.0+)

When using the `-f` flag, brepl now searches for `.nrepl-port` files starting from the file's directory and walking up the directory tree. This allows you to work with multiple projects simultaneously:

```bash
# Directory structure:
# ~/projects/
#   ├── backend/
#   │   ├── .nrepl-port (7000)
#   │   └── src/api/handler.clj
#   └── frontend/
#       ├── .nrepl-port (8000)
#       └── src/ui/core.cljs

# Automatically uses the correct nREPL server for each project:
brepl -f ~/projects/backend/src/api/handler.clj    # Connects to port 7000
brepl -f ~/projects/frontend/src/ui/core.cljs      # Connects to port 8000
```

This is especially useful when:
- Working with monorepos containing multiple services
- Switching between different projects frequently
- Using editor integrations that operate on individual files

### Remote Connections

```bash
# Connect to remote host with specific port
brepl -h remote-server -p 7888 -e '(+ 1 2)'

# Using environment variables
BREPL_HOST=remote-server BREPL_PORT=7888 brepl -e '(+ 1 2)'
```

## Environment Variables

Set these for default configuration:

```bash
export BREPL_HOST=localhost  # Default host
export BREPL_PORT=7888       # Default port
```

Or use them for one-off commands:
```bash
BREPL_PORT=7888 brepl -e '(+ 1 2 3)'
```

## Examples

```bash
# Start nREPL server (creates .nrepl-port file)
bb nrepl-server

# Basic evaluation
brepl -e '(+ 1 2 3)'
brepl -e '(require '[clojure.string :as str]) (str/upper-case "hello")'

# Load a script file  
brepl -f my-script.clj

# Send raw nREPL messages
brepl -m '{"op" "describe"}'
brepl -m '{"op" "ls-sessions"}'
brepl -m '{"op" "eval" "code" "(+ 1 2)"}'

# Multi-line expressions (single quotes make it easier)
brepl -e '(let [x 10 
               y 20] 
           (+ x y))'

# Quick math
brepl -e '(reduce + (range 100))'

# Check Babashka version
brepl -e '(System/getProperty "babashka.version")'

# Development workflow
brepl -f test/my_test.clj
brepl -e '(require '[my.namespace :refer :all]) (my-function 123)'
```

## Advanced Usage

### Raw nREPL Messages

The `-m/--message` option allows you to send raw nREPL messages in EDN format. This makes brepl a full-fledged nREPL client capable of accessing all operations supported by the nREPL server.

```bash
# Get server capabilities
brepl -m '{"op" "describe"}'

# List active sessions
brepl -m '{"op" "ls-sessions"}'

# Clone a session
brepl -m '{"op" "clone"}'

# Get completions
brepl -m '{"op" "complete" "prefix" "str/" "ns" "user"}'

# Look up symbol documentation
brepl -m '{"op" "info" "symbol" "map" "ns" "clojure.core"}'

# Evaluate with specific session
brepl -m '{"op" "eval" "code" "(+ 1 2)" "session" "your-session-id"}'
```

#### Common nREPL Operations

- `describe` - Returns server capabilities and supported operations
- `eval` - Evaluate code
- `load-file` - Load a file's contents
- `ls-sessions` - List active sessions
- `clone` - Create a new session
- `close` - Close a session
- `interrupt` - Interrupt an evaluation
- `complete` - Get code completions
- `info` - Get symbol information
- `lookup` - Look up symbol documentation
- `eldoc` - Get function signature information

For a complete list of standard nREPL operations, see the [nREPL documentation](https://nrepl.org/nrepl/ops.html).

#### Tips for Raw Messages

1. **Automatic ID generation**: If you don't provide an `id` field, brepl will add one automatically
2. **Response handling**: Some operations return multiple messages. Use `--verbose` to see the full conversation
3. **Session management**: Most operations work without a session, but some require one (like `interrupt`)
4. **Byte array conversion**: All byte arrays in responses are automatically converted to strings for readability
5. **Debugging**: Use `--verbose` with `-m` to see exactly what's sent and received

```bash
# Debug mode - see full message exchange
brepl -m '{"op" "describe"}' --verbose
```

### AI-Assisted Development

brepl provides lightweight hooks for REPL-driven development with AI coding agents.

**Two Approaches to AI-Assisted Clojure:**

1. **Protocol servers** - Run MCP servers, configure protocol bridges, manage multiple processes
2. **brepl hooks** - Direct integration with your REPL using Babashka, optionally enhanced with parinfer-rust

brepl uses direct REPL integration with minimal overhead. Syntax validation uses Babashka's built-in edamame parser, and bracket auto-fix delegates to parinfer-rust when available (included in Nix installs, optional for bbin).

#### Quick Setup

Install hooks in your project:

```bash
brepl hook install
```

This creates `.claude/settings.local.json` with validation and evaluation hooks that:
- **Pre-edit**: Validate syntax and auto-fix bracket errors before file writes
- **Post-edit**: Evaluate changed files via your existing nREPL connection
- **Session cleanup**: Remove temporary backups when done

#### Hook Commands

**`brepl hook install`**
Installs hooks to `.claude/settings.local.json` for the current project. Idempotent—safe to run multiple times.

**`brepl hook validate <file> <content>`**
Pre-edit syntax validation with automatic bracket correction. Recursively closes unclosed brackets and braces using the edamame parser. Returns corrected code or blocks with detailed error messages.

```bash
# Auto-fixes unclosed brackets
brepl hook validate src/core.clj "(defn foo ["
# => {"decision":"allow","correction":"(defn foo [])"}

# Blocks unfixable syntax errors
brepl hook validate src/core.clj "\"unclosed string"
# => {"decision":"block","reason":"Syntax error..."}
```

**`brepl hook eval <file>`**
Post-edit validation and optional nREPL evaluation. Validates syntax first, then evaluates via nREPL if available. Warnings don't block—development stays fluid while catching real errors.

```bash
# With nREPL running - evaluates and warns on errors
brepl hook eval src/core.clj
# => {"decision":"allow","warning":"Undefined symbol..."}

# Without nREPL - validates syntax only
brepl hook eval src/core.clj
# => {"decision":"allow"}  # Graceful degradation
```

**`brepl hook uninstall`**
Removes hooks from `.claude/settings.local.json` cleanly.

**`brepl hook session-end <session-id>`**
Cleanup command (called automatically by Claude Code) that removes session backup files.

#### How It Works

When active, brepl hooks run automatically during AI-assisted edits:

1. **Before edit**: Agent proposes code changes
2. **Validate**: brepl checks syntax, auto-fixes brackets if needed
3. **Write**: File is written with validated/corrected code
4. **Evaluate**: brepl loads file into your running REPL (if available)
5. **Feedback**: Agent sees warnings but continues unless syntax is invalid

This keeps your REPL state synchronized with file changes and catches errors early, without interrupting flow for recoverable issues like undefined symbols during incremental development.

#### Backup & Recovery

brepl automatically creates session-specific backups before validating edits. If syntax errors are detected post-write, the original file is restored from backup. Backups are stored in `/tmp/brepl-hooks-<session-id>/` and cleaned up automatically.

#### Project-Aware Integration

Hooks work with brepl's project-aware port discovery (v1.3.0+). When evaluating files, brepl walks up from the file's directory to find the correct `.nrepl-port`, so multi-project workflows just work:

```bash
# Directory structure:
# ~/projects/
#   ├── service-a/
#   │   ├── .nrepl-port (7000)
#   │   └── src/api.clj
#   └── service-b/
#       ├── .nrepl-port (8000)
#       └── src/handler.clj

# Each file evaluates against its own REPL
brepl hook eval ~/projects/service-a/src/api.clj      # port 7000
brepl hook eval ~/projects/service-b/src/handler.clj  # port 8000
```

#### Design Philosophy: Pragmatic Integration

brepl takes a pragmatic approach to AI-assisted Clojure development: use battle-tested tools when available, provide clear feedback when not.

**Minimal Core with Optional Enhancement:**
- ✅ Syntax validation uses edamame (built into Babashka)
- ✅ Bracket auto-fix delegates to parinfer-rust when available
- ✅ Works without parinfer-rust (blocks with detailed errors for Claude to fix)
- ✅ No protocol servers or separate processes required

**Direct REPL Integration:**
- ✅ Uses your running nREPL connection (no separate context)
- ✅ Works with any nREPL server (Babashka, Clojure, ClojureScript)
- ✅ Minimal overhead (fast Babashka startup)
- ✅ Graceful degradation (works without nREPL for syntax checking)
- ✅ Project-aware (handles multiple REPLs automatically)

**Installation Options:**
- **Nix**: Includes parinfer-rust automatically (zero configuration)
- **bbin**: Optionally install parinfer-rust for auto-fix (works without it)

Perfect for developers who want reliable AI assistance without managing multiple processes or protocol servers.

#### Implementation Details

See `BRACKET_AUTO_FIX_ANALYSIS.md` for detailed comparison with parinfer-rust showing **94.9% agreement** across 39 test cases. Both approaches:
- Fix extra closing brackets by removing from end
- Fix missing closing brackets by appending
- Handle nested structures with multiple errors
- Pragmatically fix "mismatched" delimiters as typos: `[1 2 3)` → `[1 2 3]`
- Give up on genuinely complex errors (deeply nested mismatches, strings)

**Known Limitations:**
- Can't fix anonymous function reader macros: `#(+ % 1` (edamame parser limitation)
- Multi-form with complex errors may fix only first form
- These edge cases occur rarely and degrade gracefully with clear error messages

## Troubleshooting

**Error: No port specified, no .nrepl-port file found, and BREPL_PORT not set**
- Start an nREPL server first: `bb nrepl-server`
- Or specify port manually: `brepl -p 7888 -e "(+ 1 2)"`

**Error connecting to nREPL server**
- Check if nREPL server is running
- Verify the port number is correct
- For remote connections, ensure host is reachable

**Get help anytime:** `brepl --help`

## Requirements

- [Babashka](https://babashka.org/) installed
- Running nREPL server (Babashka, Clojure, etc.)

## Development

### Running Tests

The project includes a comprehensive test suite. To run tests:

```bash
# Run all tests
bb test

# Run specific test namespace
bb test --nses brepl-test

# Run specific test
bb test --vars brepl-test/basic-evaluation-test
```

The test suite covers:
- Basic expression evaluation
- File loading and execution
- Error handling and exceptions
- Output handling (stdout/stderr)
- Port and host resolution
- Environment variable handling
- Verbose mode functionality
- CLI argument validation
- Edge cases and error conditions

### Verbose Mode

Use `--verbose` to debug nREPL communication:

```bash
brepl -p 1667 -e '(+ 1 2)' --verbose
# Shows the complete nREPL message exchange:
# {"op" "eval", "code" "(+ 1 2)", "id" "1749559876543"}
# {"id" "1749559876543", "ns" "user", "session" "none", "value" "3"}
# {"id" "1749559876543", "session" "none", "status" ["done"]}
```

## License

MPL-2.0 License

## Versioning

brepl follows [break versioning](https://www.taoensso.com/break-versioning):
- Version format: `<major>.<minor>.<non-breaking>`
- Breaking changes increment the minor version (e.g., 1.0.0 → 1.1.0)
- Non-breaking changes increment the patch version (e.g., 1.0.0 → 1.0.1)

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.

Before submitting a PR:
1. Ensure all tests pass: `bb test`
2. Add tests for any new functionality
3. Update documentation as needed
