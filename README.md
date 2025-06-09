# brepl

A fast, lightweight Babashka nREPL client for one-shot code evaluation and file loading.

## Quick Start

1. **Start a Babashka nREPL server** in your project:
   ```bash
   bb nrepl-server
   # Starts on default port 1667
   ```

2. **Use brepl** to evaluate expressions:
   ```bash
   brepl -p 1667 -e "(+ 1 2 3)"
   # => 6
   ```

You need to specify the port with `-p 1667` since Babashka doesn't create a `.nrepl-port` file by default.

## Features

- 🚀 **Fast startup** - Built with Babashka for instant execution
- 📝 **Expression evaluation** - Evaluate Clojure expressions directly from command line
- 📁 **File loading** - Load and execute entire Clojure files
- 🔍 **Auto-discovery** - Automatically detects `.nrepl-port` files
- ⚙️ **Flexible configuration** - Support for environment variables and CLI arguments
- 🛠️ **Easy installation** - Install via bbin or manual setup

## Installation

### Option 1: Install via bbin (Recommended)

```bash
bbin install io.github.licht1stein/brepl
```

### Option 2: Manual Installation

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

### Basic Usage

```bash
# Evaluate an expression (auto-detects port from .nrepl-port)
brepl -e "(+ 1 2 3)"

# Load and execute a file
brepl -f script.clj
```

### Port Configuration

The **port is required** and resolved in this order:

1. **Command line:** `-p 7888`
2. **Auto-detect:** `.nrepl-port` file in current directory
3. **Environment:** `BREPL_PORT=7888`

```bash
# Explicit port
brepl -p 7888 -e "(+ 1 2)"

# Using environment variable
BREPL_PORT=7888 brepl -e "(+ 1 2)"

# Auto-detect from .nrepl-port (most common)
brepl -e "(+ 1 2)"
```

### Remote Connections

```bash
# Connect to remote host with specific port
brepl -h remote-server -p 7888 -e "(+ 1 2)"

# Using environment variables
BREPL_HOST=remote-server BREPL_PORT=7888 brepl -e "(+ 1 2)"
```

## Environment Variables

Set these for default configuration:

```bash
export BREPL_HOST=localhost  # Default host
export BREPL_PORT=7888       # Default port
```

Or use them for one-off commands:
```bash
BREPL_PORT=7888 brepl -e "(+ 1 2 3)"
```

## Examples

```bash
# Start nREPL server (creates .nrepl-port file)
bb nrepl-server

# Basic evaluation
brepl -e "(+ 1 2 3)"
brepl -e "(require '[clojure.string :as str]) (str/upper-case \"hello\")"

# Load a script file  
brepl -f my-script.clj

# Multi-line expressions (use quotes)
brepl -e "(let [x 10 y 20] (+ x y))"

# Quick math
brepl -e "(reduce + (range 100))"

# Check Babashka version
brepl -e "(System/getProperty \"babashka.version\")"

# Development workflow
brepl -f test/my_test.clj
brepl -e "(require '[my.namespace :refer :all]) (my-function 123)"
```

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

## License

MPL-2.0 License

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.
