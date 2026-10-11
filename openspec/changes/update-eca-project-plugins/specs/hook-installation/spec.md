## ADDED Requirements

### Requirement: ECA Project Hook Installation Modes
The system SHALL install project hooks with `--balance-only` or `--with-eval`. It SHALL reject unknown, repeated, or conflicting flags before side effects. Help SHALL cause no config reads or writes. It SHALL NOT read, write, or validate any ECA plugin reference or the brepl skill.

#### Scenario: Terminal install
- **WHEN** install runs in a terminal without flags
- **THEN** the menu offers balance-only and with-eval with no default
- **AND** it explains both modes and warns that full evaluation can have file or database side effects
- **AND** it never infers a mode from existing hooks

#### Scenario: Cancel without writes
- **WHEN** the user enters blank input or EOF in the install menu
- **THEN** the operation cancels without writing config or creating a config directory
- **AND** existing config remains byte-for-byte unchanged, including validation-only, full, or balance-only hooks

#### Scenario: Explicit evaluation from validation-only
- **WHEN** only the exact known validation hook exists and the user selects with-eval by flag, number, or mode name
- **THEN** the project receives the exact validate and full eval hooks

#### Scenario: Flag-less uninstall
- **WHEN** uninstall runs with no flags
- **THEN** it removes exact known direct hooks if present
- **AND** it changes nothing else
- **AND** any flag other than `--help` fails before reading config or writing

#### Scenario: Nonterminal invocation
- **WHEN** install runs without a mode outside a terminal
- **THEN** it fails without reading stdin or writing config

#### Scenario: Direct full hooks
- **WHEN** with-eval is selected
- **THEN** the project receives the original brepl-validate and brepl-eval definitions
- **AND** no new sessionEnd hook is installed

#### Scenario: Direct balance hooks
- **WHEN** balance-only is selected
- **THEN** the project receives the original validate hook and a post hook with shell command `brepl eca eval --balance-only`

#### Scenario: Skill guidance
- **WHEN** an install completes successfully
- **THEN** a short tip is printed pointing to `/plugin-install brepl` in ECA, or a manual copy of `resources/skills/brepl/SKILL.md`
- **AND** no skill file or plugin reference is read, written, or removed by this command

### Requirement: ECA Balance-only Post Hook
The system SHALL support `eca eval --balance-only` with the existing post-hook JSON contract. It SHALL reject invalid flags before reading input or changing files.

#### Scenario: Bracket repair without REPL access
- **WHEN** balance-only receives a successful Clojure file write or edit
- **THEN** balance-file! repairs brackets and reports changes through additionalContext
- **AND** resolve-port, resolve-host, and eval-file are never called, even with a live or stale port file

#### Scenario: No repair needed
- **WHEN** the file needs no repair or the input is not applicable
- **THEN** the hook silently allows the result with suppressOutput true

#### Scenario: Full compatibility
- **WHEN** eca eval runs without flags
- **THEN** it retains full evaluation and existing evaluation-error feedback
- **AND** validate denial reasons and session-end compatibility remain unchanged

### Requirement: Safe ECA Project Hook Migration and Removal
The system SHALL validate the project before at most one write and SHALL skip writes when unchanged. It SHALL never modify global config, Claude hooks, or temporary state during installation or removal.

#### Scenario: Exact generated hooks
- **WHEN** known hook keys match complete original definitions or the generated balance-only definition
- **THEN** a selected mode may replace or remove them, including old brepl-session-end
- **AND** unrelated brepl-prefixed keys are preserved
- **AND** no automatic startup migration occurs

#### Scenario: Customized known hook
- **WHEN** a known key has any customized definition
- **THEN** every install and uninstall mode aborts without writes and requests manual review

#### Scenario: Invalid project input
- **WHEN** project JSON is malformed or `hooks` is not an object, or a path safety check fails (symlinked project path, or project path resolving to the global config)
- **THEN** the operation fails without edits
- **AND** commented valid JSON is accepted, with a warning that writes remove comments and change formatting

#### Scenario: Compatibility limit
- **WHEN** the helper reports its scope
- **THEN** it does not depend on ECA's plugin or marketplace system in any way
- **AND** it requests a restart after changes
