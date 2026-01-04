# stop-hooks Specification

## Purpose

Enable user-configurable hooks that execute when Claude Code fires the Stop event, allowing automated test runs, validation checks, and cleanup operations after Claude finishes responding.

## ADDED Requirements

### Requirement: Stop Hook Configuration File

The system SHALL load stop hook definitions from `.brepl/hooks.edn` in the current working directory.

#### Scenario: Load valid configuration

```gherkin
Given Maya has created .brepl/hooks.edn with valid hook definitions
And the file contains a :stop key with a vector of hook maps
When brepl hook stop executes
Then the hooks are loaded and executed in order
```

#### Scenario: No configuration file exists

```gherkin
Given no .brepl/hooks.edn file exists in the current directory
When brepl hook stop executes
Then brepl returns success with no hooks executed
And outputs {"decision": "approve"}
```

#### Scenario: Invalid configuration format

```gherkin
Given .brepl/hooks.edn contains malformed EDN or invalid hook structure
When brepl hook stop executes
Then brepl exits with code 1
And outputs error to stderr
And Claude is informed but can stop
```

The configuration file:

- Location: `.brepl/hooks.edn` (CWD only, no parent directory traversal)
- Format: EDN with `:stop` key containing vector of hook maps
- Validation: Uses `clojure.spec.alpha` to validate structure

### Requirement: Hook Schema Validation

The system SHALL validate hook definitions using clojure.spec.alpha before execution.

#### Scenario: Valid REPL hook

```gherkin
Given a hook map with :type :repl, :name "tests", and :code "(run-tests)"
When the schema is validated
Then the hook is accepted
```

#### Scenario: Valid bash hook

```gherkin
Given a hook map with :type :bash, :name "lint", and :command "clj-kondo --lint src"
When the schema is validated
Then the hook is accepted
```

#### Scenario: Missing required field

```gherkin
Given a hook map missing the :name field
When the schema is validated
Then validation fails with explanation of missing field
```

#### Scenario: Invalid field type

```gherkin
Given a hook map with :timeout "sixty" (string instead of integer)
When the schema is validated
Then validation fails with explanation of type mismatch
```

Common hook fields (all hooks):

- `:type` - keyword, `:repl` or `:bash` (required)
- `:name` - string, identifier for reporting (required)
- `:retry-on-failure?` - boolean, if true and hook fails, exit 2 to make Claude retry (default: false)
- `:max-retries` - non-negative integer, max retry attempts before giving up; 0 means infinite (default: 10)
- `:required?` - boolean, if true and hook can't run, inform Claude to pause and notify user (default: false)
  - For REPL hooks: REPL must be available
  - For bash hooks: command must be able to execute
- `:timeout` - positive integer, seconds before timeout (default: 60, no max - user decides based on their test suite)

REPL-specific fields:

- `:code` - string, Clojure code to evaluate (required for :repl)

Bash-specific fields:

- `:command` - string, shell command to execute (required for :bash)
- `:cwd` - string, working directory (default: ".")
- `:env` - map of string to string, environment variables (default: {})

### Requirement: REPL Hook Execution

The system SHALL execute REPL hooks by evaluating Clojure code via nREPL connection.

#### Scenario: Successful REPL hook execution

```gherkin
Given Dev has configured a REPL hook with :code "(+ 1 2)"
And an nREPL server is running
When the stop hook executes
Then the code is evaluated via nREPL
And the hook is marked as successful
```

#### Scenario: REPL hook with evaluation error

```gherkin
Given Dev has configured a REPL hook with :code "(/ 1 0)"
And an nREPL server is running
When the stop hook executes
Then the hook is marked as failed
And the error message is captured for reporting
```

#### Scenario: No nREPL available with required hook

```gherkin
Given Dev has configured a REPL hook with :required? true
And no nREPL server is running
When the stop hook executes
Then brepl exits with code 1
And outputs message asking Claude to pause and notify user
And Claude is informed but can stop
```

#### Scenario: No nREPL available with optional hook

```gherkin
Given Dev has configured a REPL hook with :required? false (or omitted)
And no nREPL server is running
When the stop hook executes
Then the hook is skipped silently
And execution continues with remaining hooks
```

REPL hook execution:

- Uses existing brepl nREPL infrastructure
- Port resolution follows existing priority (CLI > .nrepl-port > env var)
- Timeout enforced per hook
- Captures stdout, stderr, and evaluation result

### Requirement: Bash Hook Execution

The system SHALL execute bash hooks by running shell commands via babashka.process.

#### Scenario: Successful bash hook execution

```gherkin
Given Dev has configured a bash hook with :command "echo hello"
When the stop hook executes
Then the command runs in a shell
And exit code 0 marks the hook as successful
```

#### Scenario: Bash hook with non-zero exit

```gherkin
Given Dev has configured a bash hook with :command "exit 1"
When the stop hook executes
Then the hook is marked as failed
And stdout/stderr are captured for reporting
```

#### Scenario: Bash hook with custom working directory

```gherkin
Given Dev has configured a bash hook with :cwd "test"
When the stop hook executes
Then the command runs with working directory set to "test"
```

#### Scenario: Bash hook with environment variables

```gherkin
Given Dev has configured a bash hook with :env {"CI" "true", "DEBUG" "1"}
When the stop hook executes
Then the command runs with those environment variables set
```

Bash hook execution:

- Uses babashka.process/shell
- Inherits current environment, merges with :env
- Timeout enforced per hook
- Captures stdout, stderr, and exit code

### Requirement: Sequential Execution with Failure Handling

The system SHALL execute hooks sequentially in definition order with configurable failure behavior.

#### Scenario: All hooks succeed

```gherkin
Given Dev has configured three hooks in .brepl/hooks.edn
And all hooks execute successfully
When brepl hook stop runs
Then all hooks execute in order
And brepl outputs {"decision": "approve"}
```

#### Scenario: Blocking hook fails with loop-on-failure

```gherkin
Given Dev has configured a hook with :retry-on-failure? true
And the hook fails (test failure, non-zero exit, etc.)
And retry count is below :max-retries
When brepl hook stop runs
Then brepl exits with code 2 to force Claude to continue
And error details go to stderr
And subsequent hooks do not execute
```

#### Scenario: Blocking hook exhausts retry limit

```gherkin
Given Dev has configured a hook with :retry-on-failure? true and :max-retries 10
And the hook has failed 10 times
When brepl hook stop runs
Then brepl exits with code 1
And outputs message that retry limit reached
And Claude is informed but can stop
```

#### Scenario: Blocking hook with infinite retries

```gherkin
Given Dev has configured a hook with :retry-on-failure? true and :max-retries 0
And the hook fails
When brepl hook stop runs
Then brepl exits with code 2 to force Claude to continue
And this continues indefinitely until hook passes
```

#### Scenario: Non-looping hook fails

```gherkin
Given Dev has configured a hook with :retry-on-failure? false (or omitted)
And the hook fails
When brepl hook stop runs
Then brepl exits with code 1
And failure is reported to stderr
And Claude is informed but can stop
```

Execution behavior:

- Hooks execute in order defined in config
- Hook failure with `:retry-on-failure? true` exits 2 (Claude retries) until `:max-retries` reached
- Hook failure with `:retry-on-failure? false` exits 1 (Claude informed, can stop)
- Retry count tracked per hook per session in state file

### Requirement: State Persistence for Retry Tracking

The system SHALL persist retry counts across hook invocations using a state file in /tmp.

#### Scenario: Track retry count across invocations

```gherkin
Given Dev has configured a hook with :retry-on-failure? true
And the hook fails on first invocation
When Claude retries and brepl hook stop runs again
Then brepl reads the previous retry count from state file
And increments the count for this hook
```

#### Scenario: Reset retry count on success

```gherkin
Given a hook has failed 5 times previously
And the hook now succeeds
When brepl hook stop completes
Then the retry count for that hook is reset to 0
```

#### Scenario: Isolate state by session

```gherkin
Given two Claude sessions are running in the same directory
When each session runs brepl hook stop
Then each session has its own independent retry counts
```

State file:

- Location: `/tmp/brepl-stop-hook-{session_id}.edn`
- Format: EDN map of hook name to retry count
- Cleanup: Removed by SessionEnd hook or on success

### Requirement: Claude Code Stop Event Integration

The system SHALL integrate with Claude Code's Stop hook event via `brepl hook stop` command.

#### Scenario: Receive stop event input

```gherkin
Given Claude Code fires the Stop event
When brepl hook stop receives JSON input via stdin
Then it parses session_id, transcript_path, and other fields
And proceeds with hook execution
```

#### Scenario: All hooks pass

```gherkin
Given all hooks execute successfully
When brepl hook stop completes
Then brepl exits with code 0
And Claude can stop
```

#### Scenario: Hook fails with loop-on-failure

```gherkin
Given a hook with :retry-on-failure? true fails
And retry count is below :max-retries
When brepl hook stop completes
Then brepl exits with code 2
And error details go to stderr
And Claude must continue working
```

#### Scenario: Hook fails without loop-on-failure

```gherkin
Given a hook with :retry-on-failure? false fails
When brepl hook stop completes
Then brepl exits with code 1
And error details go to stderr
And Claude is informed but can stop
```

CLI interface:

- Command: `brepl hook stop`
- Input: Claude Code Stop event JSON via stdin
- Output: Error messages to stderr
- Exit codes:
  - 0 = success, Claude can stop
  - 1 = informational error, Claude is informed but can stop
  - 2 = blocking error, Claude must continue working

### Requirement: Install Command Enhancement

The system SHALL generate a template `.brepl/hooks.edn` file and register Stop hook in Claude settings.

#### Scenario: Install creates template config

```gherkin
Given no .brepl/hooks.edn exists
When Dev runs brepl hook install
Then .brepl/hooks.edn is created with commented examples
And the Stop hook is registered in .claude/settings.local.json
```

#### Scenario: Install preserves existing brepl config

```gherkin
Given .brepl/hooks.edn already exists with user configuration
When Dev runs brepl hook install
Then the existing .brepl/hooks.edn is not modified
And the Stop hook is registered in .claude/settings.local.json
```

Template content includes:

- Commented example REPL hook for running tests
- Commented example bash hook for linting
- Documentation of all available fields
- Explanation of blocking vs non-blocking behavior

### Requirement: Idempotent Claude Settings Merge

The system SHALL merge brepl hooks with existing Claude settings without replacing non-brepl hooks.

#### Scenario: Preserve non-brepl hooks during install

```gherkin
Given .claude/settings.local.json contains a PostToolUse hook for prettier
When Dev runs brepl hook install
Then the prettier hook is preserved
And brepl hooks are added alongside it
```

#### Scenario: Update brepl hooks idempotently

```gherkin
Given .claude/settings.local.json contains older brepl hook configuration
When Dev runs brepl hook install
Then the old brepl hooks are replaced with new configuration
And non-brepl hooks remain unchanged
```

#### Scenario: Install is idempotent

```gherkin
Given Dev has already run brepl hook install
When Dev runs brepl hook install again
Then the settings file contains the same configuration
And no duplicate hooks are created
```

Hook identification:

- Brepl hooks are identified by commands starting with "brepl hook"
- Non-brepl hooks (commands not starting with "brepl hook") are preserved
- Each install replaces all brepl hooks with current configuration
