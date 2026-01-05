# Design: Create Self-Contained Uberscript Build

## Context

brepl is distributed as a Babashka script with supporting library files. Currently:

1. **Runtime dependency loading**: `lib/validator.clj` uses `deps/add-deps` to fetch parmezan from GitHub at runtime
2. **Dynamic file loading**: Main script uses `load-file` to load libraries based on script location at runtime

This approach breaks in sandboxed environments (Nix builds) and requires network access on first run.

**Stakeholders**: Users installing via Nix, users in offline/restricted environments, package maintainers.

## Goals / Non-Goals

**Goals**:

- Single self-contained script with all dependencies bundled
- Works in Nix sandbox (no network during build/install)
- Maintains current CLI behavior and functionality
- Backward-compatible for existing users

**Non-Goals**:

- Changing brepl's functionality or CLI interface
- Supporting uberjar (adds JVM dependency complexity)
- Modifying how brepl interacts with nREPL

## Decisions

### Decision 1: Use `bb uberscript` over `bb uberjar`

**Rationale**: Uberscript produces a single `.clj` file that can be run directly with `bb`. This preserves the current distribution model (single executable script) and avoids introducing JAR file handling. Uberjar would require different invocation (`bb -jar`) and complicates the Nix wrapper.

### Decision 2: Standard source layout (`src/brepl/lib/`)

**Rationale**: Babashka's uberscript requires namespaces to be resolvable at build time via classpath. Using standard Clojure layout (`src/` directory with namespace-matching paths) is the convention and works with bb.edn's `:paths` directive.

File mapping:

- `lib/validator.clj` (ns `brepl.lib.validator`) → `src/brepl/lib/validator.clj`
- `lib/backup.clj` (ns `brepl.lib.backup`) → `src/brepl/lib/backup.clj`
- `lib/hook_utils.clj` (ns `brepl.lib.hook-utils`) → `src/brepl/lib/hook_utils.clj`
- `lib/installer.clj` (ns `brepl.lib.installer`) → `src/brepl/lib/installer.clj`
- `lib/stop_hooks.clj` (ns `brepl.lib.stop-hooks`) → `src/brepl/lib/stop_hooks.clj`

Note: Clojure convention maps hyphens in namespaces to underscores in filenames.

### Decision 3: Dual distribution strategy (bbin vs Nix)

**Rationale**: With the uberscript approach, both installation methods use the same self-contained file:

- **bbin**: Runs `bb -f brepl` which executes the uberscript directly.
- **Nix**: Copies the uberscript to `$out/bin/brepl`.

**Solution**: Source files + generated uberscript in repo:

```
Source (for editing):
  src/brepl/main.clj  - Entry point source
  src/brepl/lib/      - Library modules
  bb.edn              - Paths + deps (for uberscript generation)

Distribution (committed to repo):
  brepl               - Generated uberscript (self-contained)
  package.nix         - Just copies brepl
```

**bbin install**: Works unchanged - bbin runs `bb -f brepl`. Since brepl is now a self-contained uberscript, no dependency resolution needed.

**Nix install**: Uses pre-built uberscript committed to repo. No build phase needed.

### Decision 4: Developer generates uberscript locally

**Rationale**: Developer runs `bb uberscript` locally before committing. The `brepl` file in repo IS the uberscript. Simple workflow, no CI complexity.

```
Development workflow:
  1. Edit source files in src/brepl/
  2. Run: bb uberscript brepl -m brepl
  3. Test with ./brepl
  4. Commit source + regenerated brepl together
```

Benefits:

- No CI workflow needed
- Nix package unchanged (just copies brepl)
- bbin works (uberscript is valid bb code)
- Developer controls when to regenerate

Optional: Add `bb build` task or pre-commit hook for convenience.

### Decision 5: Parmezan pinned in bb.edn

**Rationale**: Moving the dependency from runtime `deps/add-deps` to static `bb.edn` dependency makes it available at build time for uberscript bundling. The git SHA pins the exact version for reproducibility.

## Risks / Trade-offs

| Risk                                                    | Mitigation                                                          |
| ------------------------------------------------------- | ------------------------------------------------------------------- |
| Uberscript may be larger than current distributed files | Acceptable trade-off for self-containment; bb scripts compress well |
| Development workflow changes slightly                   | Source files still editable; only final distribution changes        |
| Tests need updating to work with new structure          | One-time migration; tests use same static requires                  |
| Developer must regenerate before commit                 | Add `bb build` task; document in CLAUDE.md                          |

## Migration Plan

1. **Phase 1**: Restructure source layout (non-breaking, lib/ still works)
2. **Phase 2**: Update brepl script to static requires
3. **Phase 3**: Update tests to use static requires
4. **Phase 4**: Add uberscript build task
5. **Phase 5**: Update Nix package to use uberscript
6. **Phase 6**: Remove old lib/ directory

**Rollback**: If issues arise, revert to previous commit. No database or external state changes.

## Open Questions

None - approach is straightforward given Babashka's documented uberscript behavior.
