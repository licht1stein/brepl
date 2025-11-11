# Hook Installation

## ADDED Requirements

### Requirement: Install Claude Code Hooks
The system SHALL provide a command to install Claude Code hooks for automatic Clojure file validation.

#### Scenario: Install basic hooks
```bash
$ brepl hook install
Installing Claude Code hooks in .claude/settings.local.json...
Hooks installed successfully.

Pre-edit auto-fix (edamame): ✓
Post-edit evaluation (warnings): ✓
Strict evaluation mode: ✗ (warnings only)
```

The command should:
- Be idempotent (safe to run multiple times)
- Create `.claude/` directory in current project if missing
- Read existing `settings.local.json` or create new one
- Add PreToolUse and PostToolUse hook configurations
- Preserve existing settings
- Report what features are enabled
- No external dependencies required

#### Scenario: Install with strict evaluation mode
```bash
$ brepl hook install --strict-eval
Installing Claude Code hooks in .claude/settings.local.json...
Hooks installed successfully.

Pre-edit auto-fix (edamame): ✓
Post-edit evaluation (strict): ✓
Strict evaluation mode: ✓ (blocks on eval errors)
```

The command should:
- Enable strict evaluation mode where eval errors block the edit
- Pass `--strict` flag to `brepl hook eval` command
- Document that this prevents edits when files don't eval cleanly

#### Scenario: Install with skip evaluation
```bash
$ brepl hook install --skip-eval
Installing Claude Code hooks in .claude/settings.local.json...
Hooks installed successfully.

Pre-edit auto-fix (edamame): ✓
Post-edit evaluation: ✗ (skipped)
```

The command should:
- Skip post-edit evaluation entirely
- Only perform pre-edit auto-fix
- Useful for projects where evaluation is slow or has side effects

#### Scenario: Idempotent installation
```bash
$ brepl hook install
# ... installs hooks ...
Hooks installed successfully.

$ brepl hook install
Hooks already installed, configuration updated.
# Same result, no duplicate hooks
```

Running install multiple times should be safe and idempotent.

### Requirement: Uninstall Claude Code Hooks
The system SHALL provide a command to remove brepl hooks from Claude Code configuration.

#### Scenario: Uninstall hooks
```bash
$ brepl hook uninstall
Removing Claude Code hooks...
Updated .claude/settings.local.json
Hooks uninstalled successfully.
```

The command should:
- Remove brepl hook configurations from settings
- Preserve other existing hooks
- Leave empty hooks array if no other hooks exist
- Report success

### Requirement: Hook Configuration Format
Hook installation MUST create valid Claude Code configuration.

#### Scenario: Generated configuration structure
The installed hooks should produce JSON like:
```json
{
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Edit",
        "hooks": [
          {
            "type": "command",
            "command": "brepl hook validate \"$FILE_PATH\" \"$NEW_STRING\""
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Edit",
        "hooks": [
          {
            "type": "command",
            "command": "brepl hook eval \"$FILE_PATH\""
          }
        ]
      }
    ],
    "SessionEnd": [
      {
        "hooks": [
          {
            "type": "command",
            "command": "brepl hook session-end \"$SESSION_ID\""
          }
        ]
      }
    ]
  }
}
```

The configuration should:
- Use correct Claude Code hook event names
- Match only Edit operations
- Pass required parameters via command placeholders
- Be valid JSON that Claude Code can parse

### Requirement: Preserve Existing Configuration
Hook installation MUST NOT break existing Claude Code settings.

#### Scenario: Install with existing settings
Given `.claude/settings.local.json` contains:
```json
{
  "model": "claude-opus-4",
  "hooks": {
    "PostToolUse": [
      {
        "matcher": "Write",
        "hooks": [{"type": "command", "command": "prettier --write $FILE_PATH"}]
      }
    ]
  }
}
```

After `brepl hook install`, the file should contain:
```json
{
  "model": "claude-opus-4",
  "hooks": {
    "PreToolUse": [
      {
        "matcher": "Edit",
        "hooks": [
          {
            "type": "command",
            "command": "brepl-hook pre-edit \"$FILE_PATH\" \"$OLD_STRING\" \"$NEW_STRING\""
          }
        ]
      }
    ],
    "PostToolUse": [
      {
        "matcher": "Write",
        "hooks": [{"type": "command", "command": "prettier --write $FILE_PATH"}]
      },
      {
        "matcher": "Edit",
        "hooks": [
          {
            "type": "command",
            "command": "brepl-hook post-edit \"$FILE_PATH\""
          }
        ]
      }
    ]
  }
}
```

The command should:
- Merge with existing hooks arrays
- Preserve non-hook settings
- Maintain JSON structure and formatting
- Not duplicate brepl hooks if already present
