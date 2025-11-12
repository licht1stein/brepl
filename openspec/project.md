# Project Context

## Purpose
brepl (Bracket-fixing REPL) is a fast nREPL client built with Babashka that solves the parenthesis problem for AI-assisted Clojure development. It enables:
- Quick evaluation of Clojure expressions from the command line
- Loading and executing Clojure files in a running REPL
- Sending raw nREPL protocol messages for advanced operations
- Integration with editor tooling and automation workflows
- Claude Code hook support for automatic file validation during AI-assisted development

## Tech Stack
- **Babashka** - Fast-starting Clojure scripting environment (primary runtime)
- **Clojure** - Core language for implementation
- **nREPL Protocol** - Network REPL protocol for client-server communication
- **bencode.core** - Bencode encoding/decoding for nREPL messages
- **cheshire.core** - JSON handling for hook mode output
- **cognitect.test-runner** - Test framework
- **Nix** - Optional packaging and distribution

## Project Conventions

### Code Style
- Standard Clojure conventions and idioms
- Functional programming approach
- Descriptive function names using kebab-case
- Namespace organization: single namespace in main script
- Comment style: Use docstrings and inline comments for complex logic
- Prefer `let` bindings for clarity over nested expressions

### Architecture Patterns
- **Single executable script** - All code in `brepl` file for easy distribution
- **CLI-first design** - babashka.cli for argument parsing and validation
- **Socket-based I/O** - Direct Java Socket API for nREPL communication
- **Streaming response handling** - Process nREPL responses incrementally
- **Port resolution hierarchy** - CLI args > .nrepl-port file > env vars
- **Project-aware discovery** - Walk directory tree from file location to find correct .nrepl-port
- **Stateless operation** - One-shot execution model, no persistent state

### Testing Strategy
- Comprehensive test suite in `test/brepl_test.clj`
- Uses cognitect.test-runner via bb tasks
- Tests cover: basic evaluation, file loading, error handling, port resolution, CLI validation
- Run with: `bb test`
- CI/CD via GitHub Actions on every push
- Focus on edge cases and error conditions

### Git Workflow
- **Versioning**: Break versioning (e.g., 1.0.0 → 1.1.0 for breaking changes)
- **Main branch**: `master`
- **No force push or hook skipping** without explicit approval
- **Commit conventions**: Clear, concise messages describing the change
- **Release process**: Tag with version, update version string in script, update Nix hash
- **CI**: GitHub Actions runs tests on all branches

## Domain Context

### nREPL Protocol
- Network REPL protocol used by Clojure tooling
- Message-based communication using bencode encoding
- Common operations: eval, load-file, describe, clone, ls-sessions
- Response streams may contain multiple messages before "done" status
- Sessions are optional but required for some operations

### Babashka Ecosystem
- Fast-starting Clojure scripting runtime (sub-second startup)
- Limited to Babashka-compatible libraries
- CLI tool integration via babashka.cli
- Compatible with bb.edn task definitions
- Can be distributed via bbin package manager

### Editor/Tool Integration
- Designed for editor plugins and automation scripts
- Hook mode provides JSON output for Claude Code integration
- Port auto-discovery simplifies multi-project workflows
- Verbose mode for debugging protocol communication

## Important Constraints

### Performance Requirements
- **Fast startup time** - Must leverage Babashka's instant startup (critical for CLI UX)
- **Minimal dependencies** - Only include Babashka-compatible libraries
- **Efficient I/O** - Stream responses, don't accumulate unnecessary data

### Technical Constraints
- **Single file distribution** - All code in one executable script for portability
- **Babashka compatibility** - Cannot use libraries requiring full JVM Clojure
- **nREPL protocol compliance** - Must correctly implement bencode and message flow
- **Cross-platform support** - Works on Linux, macOS, Windows (where Babashka runs)

### Compatibility Requirements
- **Any nREPL server** - Must work with Clojure, ClojureScript, Babashka nREPL servers
- **Multiple projects** - Support working with different nREPL ports simultaneously
- **Environment flexibility** - CLI args, env vars, and file-based configuration

## External Dependencies

### Required Runtime
- Babashka (bb) must be installed and on PATH
- Running nREPL server on target host/port

### Protocol Dependencies
- **bencode** - Binary encoding format for nREPL messages
- **nREPL server** - External process (not managed by brepl)
- **Socket connection** - TCP/IP connectivity to nREPL server

### Optional Integrations
- **Claude Code** - AI coding assistant with hook support
- **bbin** - Babashka package manager for installation
- **Nix** - Package manager for declarative installation
- **.nrepl-port file** - Convention for port auto-discovery (created by REPL servers)

### Development Dependencies
- Git for version control
- GitHub Actions for CI/CD
- cognitect.test-runner for testing
