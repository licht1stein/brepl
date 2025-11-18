# delimiter-validation Specification

## Purpose

TBD - created by archiving change add-hook-install. Update Purpose after archive.

## MODIFIED Requirements

### Requirement: Pre-Edit Auto-Fix

The hook MUST automatically fix unbalanced or mismatched delimiters in Clojure code before writing to disk, enabling Claude to work with Clojure without manual bracket management.

**Inputs**:

- Clojure source code (may contain delimiter errors)
- File path for context

**Process**:

1. Detect if file is Clojure source (by extension)
2. Attempt automatic delimiter correction
3. Validate corrected code is syntactically valid
4. Return corrected code or error message

**Outputs**:

- **Success**: Corrected Clojure code with balanced delimiters
- **Failure**: Block edit with actionable error message

**Constraints**:

- Must handle all Clojure delimiter types: `()`, `[]`, `{}`, `#{}`
- Must preserve code semantics (correction only adds/removes delimiters)
- Must complete in < 500ms for typical files (< 1000 lines)
- Must not require external binary dependencies

#### Scenario: Incomplete expression is auto-corrected

Given Claude edits a Clojure function with missing closing delimiters
And the function has correct opening delimiters
When the pre-edit hook processes the file
Then the hook should add the missing closing delimiters
And the hook should return the corrected code
And Claude's edit should proceed with corrected code

#### Scenario: Nested structure delimiters are balanced

Given Claude edits code with nested forms
And the nested forms have mismatched delimiter counts
When the pre-edit hook processes the file
Then the hook should balance all delimiter levels
And the hook should maintain proper nesting order
And the hook should return syntactically valid code

#### Scenario: Unfixable syntax errors block the edit

Given Claude edits code with mismatched delimiter types
And the delimiters cannot be automatically corrected
When the pre-edit hook attempts correction
Then the hook should detect the unfixable error
And the hook should block the edit
And the hook should provide an error message explaining the issue

### Requirement: Post-Edit Quick Validation

The hook SHALL verify code is syntactically valid after edits are written, as a safety check.

**Inputs**: File path of edited Clojure file

**Process**:

1. Read file contents
2. Validate syntax (parsing without execution)
3. Proceed to evaluation if valid, block if invalid

**Outputs**:

- **Valid**: Continue to evaluation
- **Invalid**: Block with error details

**Constraints**:

- Must complete in < 100ms for typical files
- Should rarely catch errors (pre-edit auto-fix handles most cases)

#### Scenario: Valid code passes post-edit check

Given a Clojure file was edited by Claude
And pre-edit auto-fix corrected any delimiter errors
When the post-edit validation runs
Then the validation should pass
And the file should proceed to evaluation

#### Scenario: Invalid code is detected post-edit

Given a Clojure file was edited
And an error was introduced that pre-edit missed
When the post-edit validation runs
Then the validation should detect the error
And the hook should block evaluation
And the hook should report the specific error

### Requirement: File Type Filtering

The hook SHALL apply validation only to Clojure and Babashka source files, allowing other file types to edit without validation overhead.

**Inputs**: File path and file contents

**Process**:

1. Check file extension for Clojure/Babashka extensions
2. If no recognized extension, check file contents for Babashka shebang
3. If Clojure/Babashka file: apply validation
4. If other file type: skip validation, return success

**Outputs**: Boolean decision (validate or skip)

**Constraints**:

- Must recognize extensions: `.clj`, `.cljs`, `.cljc`, `.cljx`, `.bb`
- Must recognize shebangs: `#!/usr/bin/env bb`, `#!/usr/bin/bb`, or any shebang containing `bb`
- Must complete in < 5ms (extension check + optional first line read)

#### Scenario: Non-Clojure files skip validation

Given Claude edits a markdown file
When the hook processes the edit
Then the hook should check the file extension
And the hook should skip all validation
And the hook should return immediate success

#### Scenario: All Clojure extensions are validated

Given Claude edits a file with Clojure extension
When the hook processes the edit
Then the hook should apply full delimiter validation
And the hook should auto-correct any errors
And the hook should validate the result

Valid Clojure extensions: `.clj`, `.cljs`, `.cljc`, `.cljx`

#### Scenario: Babashka files with .bb extension are validated

Given Claude edits a file with `.bb` extension
When the hook processes the edit
Then the hook should apply full delimiter validation
And the hook should auto-correct any errors
And the hook should validate the result

#### Scenario: Files with Babashka shebang are validated

Given Claude edits a file without a recognized extension
And the file starts with a Babashka shebang
When the hook processes the edit
Then the hook should detect the Babashka shebang
And the hook should apply full delimiter validation
And the hook should auto-correct any errors
And the hook should validate the result

Valid Babashka shebangs include: `#!/usr/bin/env bb`, `#!/usr/bin/bb`, or any shebang line containing `bb`

### Requirement: Clear Error Messages

The hook MUST provide actionable guidance to Claude when delimiter errors cannot be automatically fixed.

**Inputs**: Error details from validation failure

**Outputs**: Human-readable error message with:

- What went wrong
- Where the error occurred (if known)
- Suggested action to resolve

**Constraints**:

- Message must be understandable by AI agent
- Message must suggest specific remediation
- Message must not include internal implementation details

#### Scenario: Error message guides resolution

Given delimiter correction failed
And the hook needs to block the edit
When the hook generates the error message
Then the message should explain what error was detected
And the message should suggest how to fix the issue
And the message should be clear enough for Claude to understand

## REMOVED Requirements

### ~~Requirement: Pre-Edit Auto-Fix~~ (old implementation)

**Removed:** The old implementation using edamame's `:edamame/expected-delimiter` for recursive fixing is replaced by a simpler unified approach.

The following implementation-specific scenarios no longer apply:

- Recursive appending of delimiters based on edamame exceptions
- Checking for `:edamame/expected-delimiter` to determine fixability
- Multi-stage detection and fixing

### ~~Requirement: Clear Error Messages~~ (old detailed version)

**Removed:** The old detailed error message formatting using edamame's exception data is simplified.

The following scenarios no longer apply:

- Error message includes specific line/column from edamame
- Error message explains context with delimiter location details
- Custom formatting of edamame's `:opened-delimiter` and `:expected-delimiter` data
