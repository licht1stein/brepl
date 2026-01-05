# packaging Specification

## ADDED Requirements

### Requirement: Self-Contained Distribution

brepl SHALL be distributable as a single self-contained script file with all dependencies bundled, requiring no network access at runtime.

#### Scenario: Uberscript contains all dependencies

**Given** the uberscript build task has been run
**When** a user executes the generated uberscript in an offline environment
**Then** all functionality works without network access
**And** parmezan bracket-fixing is available
**And** all lib modules (validator, backup, installer, hook-utils, stop-hooks) are available

#### Scenario: Nix build uses pre-built uberscript

**Given** the `brepl` file in the repository is a pre-built uberscript
**When** `nix-build` is run on the brepl package
**Then** the build completes successfully (no network needed)
**And** the resulting binary works correctly

### Requirement: Build Task for Uberscript Generation

brepl SHALL provide a build task to generate the self-contained uberscript from source.

#### Scenario: Generate uberscript with bb build task

**Given** the source files are present in `src/brepl/lib/`
**And** dependencies are defined in `bb.edn`
**When** developer runs `bb build`
**Then** the `brepl` file is regenerated as an uberscript
**And** all required namespaces are bundled inline
**And** the generated script is executable with `bb`

### Requirement: Static Dependency Resolution

All external dependencies SHALL be declared statically in `bb.edn` rather than loaded at runtime.

#### Scenario: Parmezan available without runtime download

**Given** bb.edn contains parmezan as a static dependency
**When** brepl validates or fixes Clojure syntax
**Then** parmezan is available without any runtime `deps/add-deps` calls
**And** no network requests are made during execution

### Requirement: Standard Source Layout

Source files SHALL follow standard Clojure namespace-to-path conventions under `src/`.

#### Scenario: Namespace paths match directory structure

**Given** namespace `brepl.lib.validator`
**When** locating the source file
**Then** it is found at `src/brepl/lib/validator.clj`

**Given** namespace `brepl.lib.hook-utils`
**When** locating the source file
**Then** it is found at `src/brepl/lib/hook_utils.clj` (hyphen to underscore mapping)

### Requirement: bbin Installation Compatibility

brepl SHALL remain installable via bbin with full functionality.

#### Scenario: bbin install from git repository

**Given** a user has bbin installed
**When** user runs `bbin install io.github.licht1stein/brepl`
**Then** brepl is installed successfully
**And** `brepl --version` returns the correct version
**And** `brepl '(+ 1 2)'` evaluates correctly (when nREPL available)
**And** `brepl balance <file>` works with parmezan bracket-fixing

#### Scenario: bbin runs self-contained uberscript

**Given** the `brepl` file is a self-contained uberscript
**When** bbin runs brepl via `bb -f brepl`
**Then** brepl executes without needing external dependencies
**And** parmezan is available (bundled in uberscript)

### Requirement: Nix Installation with Uberscript

The Nix package SHALL use the pre-built uberscript from the repository.

#### Scenario: Nix build produces working binary

**Given** the package.nix derivation
**And** the `brepl` file is a pre-built uberscript
**When** `nix-build` is executed
**Then** the resulting `$out/bin/brepl` is a single executable script
**And** running `brepl --version` returns the correct version
**And** bracket-fixing functionality works (parmezan bundled)
