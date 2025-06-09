# brepl

A fast, lightweight Babashka nREPL client for one-shot code evaluation and file loading.

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

### Basic Usage

```bash
# Evaluate an expression
brepl -e "(+ 1 2 3)"

# Load and execute a file
brepl -f script.clj
```

### With Specific Host and Port

```bash
# Connect to specific port
brepl -e "(println \"Hello\")" -p 7888

# Connect to remote host
brepl -e "(+ 1 2)" -h remote-server -p 7888
```

### Command Line Options

- `-e EXPR` - Evaluate Clojure expression
- `-f FILE` - Load and execute Clojure file
- `-h HOST` - nREPL host (default: localhost)
- `-p PORT` - nREPL port (auto-detected if not specified)

**Note:** You must specify either `-e` or `-f`, but not both.

## Configuration

### Port Discovery

brepl automatically discovers nREPL ports in the following order:

1. **CLI argument** - `-p PORT`
2. **`.nrepl-port` file** - Reads port from file in current directory
3. **Environment variable** - `BREPL_PORT`
4. **Error** - Exit if no port found

### Host Resolution

brepl resolves hosts in the following order:

1. **CLI argument** - `-h HOST`
2. **Environment variable** - `BREPL_HOST`
3. **Default** - `localhost`

### Environment Variables

Set these environment variables for default configuration:

```bash
export BREPL_HOST=localhost
export BREPL_PORT=1667
```

Or use them for one-off commands:
```bash
BREPL_PORT=7888 brepl -e "(+ 1 2 3)"
```

## Examples

### Starting a Babashka nREPL Server

First, start a Babashka nREPL server in your project:

```bash
# Start server (creates .nrepl-port file)
bb nrepl-server

# Or with specific port
bb nrepl-server 7888
```

### Using brepl

```bash
# Auto-detect port from .nrepl-port file
brepl -e "(require '[clojure.string :as str]) (str/upper-case \"hello\")"

# Load a script file
brepl -f my-script.clj

# Quick math
brepl -e "(reduce + (range 100))"

# Check Babashka version
brepl -e "(System/getProperty \"babashka.version\")"

# Multi-line expressions (use quotes)
brepl -e "(let [x 10
               y 20]
           (+ x y))"
```

### Integration with Development Workflow

```bash
# Check if tests pass
brepl -f test/my_test.clj

# Quick REPL-style development
brepl -e "(require '[my.namespace :refer :all]) (my-function 123)"

# Evaluate with environment variables
BREPL_PORT=7888 brepl -e "(println \"Using port 7888\")"
```

## Error Handling

brepl provides clear error messages for common issues:

```bash
# Missing required argument
$ brepl
Error: Must specify either -e EXPR or -f FILE

# Both arguments provided
$ brepl -e "(+ 1 2)" -f script.clj
Error: Cannot specify both -e and -f

# File not found
$ brepl -f nonexistent.clj
Error: File does not exist: nonexistent.clj

# Connection issues
$ brepl -e "(+ 1 2)" -p 9999
Error connecting to nREPL server at localhost:9999
Connection refused

# No port configuration
$ brepl -e "(+ 1 2)"
Error: No port specified, no .nrepl-port file found, and BREPL_PORT not set
```

## Requirements

- [Babashka](https://babashka.org/) installed
- Running nREPL server (Babashka, Clojure, etc.)

## License

MIT License

## Contributing

Contributions are welcome! Please feel free to submit issues and pull requests.
