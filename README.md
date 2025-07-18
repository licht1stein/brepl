# brepl

[![GitHub release](https://img.shields.io/github/v/release/licht1stein/brepl?label=version)](https://github.com/licht1stein/brepl/releases/latest)
[![Run Tests](https://github.com/licht1stein/brepl/actions/workflows/test.yml/badge.svg)](https://github.com/licht1stein/brepl/actions/workflows/test.yml)

A fast, lightweight nREPL client for one-shot interactions with any nREPL server.

## Quick Start

1. **Start a Babashka nREPL server** in your project:
   ```bash
   bb nrepl-server
   # Starts on default port 1667
   ```

2. **Use brepl** to evaluate expressions:
   ```bash
   brepl -p 1667 -e '(+ 1 2 3)'
   # => 6
   ```

You need to specify the port with `-p 1667` since Babashka doesn't create a `.nrepl-port` file by default.

## Features

- 🚀 **Fast startup** - Built with Babashka for instant execution
- 💬 **Full nREPL protocol** - Access any nREPL operation, not just evaluation
- 📝 **Expression evaluation** - Evaluate Clojure expressions directly from command line
- 📁 **File loading** - Load and execute entire Clojure files
- 🔍 **Auto-discovery** - Automatically detects `.nrepl-port` files
- 📂 **Project-aware** (v1.3.0) - Finds `.nrepl-port` by walking up from file's directory
- ⚙️ **Flexible configuration** - Support for environment variables and CLI arguments
- 🐛 **Proper error handling** - Shows exceptions and stack traces
- 📊 **Verbose mode** - Debug nREPL communication with `--verbose`
- 🎯 **One-shot design** - Perfect for scripts, editor integration, and automation
- 🛠️ **Easy installation** - Install via bbin or manual setup
- ✅ **Well tested** - Comprehensive test suite included

## Installation

### Option 1: Install via bbin

```bash
bbin install io.github.licht1stein/brepl
```

### Option 2: Download with curl

```bash
# Download latest release (v1.3.1)
curl -sSL https://raw.githubusercontent.com/licht1stein/brepl/v1.3.1/brepl -o brepl
chmod +x brepl
# Move to a directory on your PATH
```

### Option 3: Install with Nix

Add to your project's `shell.nix`:

```nix
{ pkgs ? import <nixpkgs> {} }:

let
  brepl = pkgs.callPackage (pkgs.fetchFromGitHub {
    owner = "licht1stein";
    repo = "brepl";
    rev = "v1.3.1";
    hash = "sha256-ZPydBe1Wszy2URmposABdnpQiniG0ZMABRNW1UIPFgQ=";
  } + "/package.nix") {};
in
pkgs.mkShell {
  buildInputs = [ brepl ];
}
```

Then run `nix-shell` to enter a shell with brepl available.

### Option 4: Manual Installation

1. Clone or download the repository
2. Make the script executable:
   ```bash
   chmod +x brepl
   ```
3. Place it somewhere on your PATH:
   ```bash
   # Copy to a directory on your PATH
   cp brepl ~/.local/bin/
   # Or create a symlink
   ln -s /path/to/brepl/brepl ~/.local/bin/brepl
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
      --hook                 Output Claude Code hook-compatible JSON format
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

### Using as a Claude Code Hook

The `--hook` flag enables brepl to output JSON in a format compatible with Claude Code hooks. This allows automatic validation of Clojure files during AI-assisted development.

#### Setting up Claude Code Hook

Add this to your Claude Code settings (`~/.claude/settings.json`):

```json
{
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "(?i)edit|write|replace",
        "hooks": [
          {
            "type": "command",
            "command": "jq -r 'select(.tool_input.file_path // .tool_input.path) | (.tool_input.file_path // .tool_input.path) | select(test(\"\\\\.clj[sc]?$\"))' | while read f; do brepl --hook -f \"$f\"; done"
          }
        ]
      }
    ]
  }
}
```

This hook will:
1. Intercept file edits made by Claude
2. Filter for Clojure files (`.clj`, `.cljs`, `.cljc`)
3. Automatically load each edited file into your running REPL
4. Stop Claude if evaluation errors occur

#### Hook Output Format

When using `--hook`, brepl outputs JSON instead of plain text:

**Success:**
```json
{"continue": true, "suppressOutput": true}
```

**Error:**
```json
{
  "continue": false,
  "stopReason": "Exception: class java.lang.ArithmeticException | Error: Divide by zero",
  "suppressOutput": true,
  "decision": "block",
  "reason": "Code evaluation failed:\njava.lang.ArithmeticException: Divide by zero at line 1"
}
```

#### Example Usage

```bash
# Normal mode - human-readable output
brepl -f my-code.clj
# => Function loaded successfully

# Hook mode - JSON output for Claude
brepl --hook -f my-code.clj
# => {"continue": true, "suppressOutput": true}

# Hook mode with error
brepl --hook -e '(/ 1 0)'
# => {"continue": false, "stopReason": "Exception: ...", ...}
```

The hook ensures that Claude stops and alerts you when code changes introduce errors, maintaining a working REPL state throughout your development session.

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
