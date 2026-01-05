# Change: Create Self-Contained Uberscript Build

## Why

brepl currently downloads the parmezan dependency at runtime via `deps/add-deps`, which breaks Nix's reproducibility model and fails in sandboxed/offline environments. GitHub issue #10 requests a self-contained package. Using `bb uberscript` will bundle all dependencies into a single file, eliminating runtime network access.

## What Changes

- Restructure source layout to use standard Clojure namespace paths (`src/brepl/lib/`)
- Add parmezan as static dependency in `bb.edn` instead of runtime loading
- Replace dynamic `load-file` calls with static `require` in ns form
- Add build task to generate uberscript from source
- **BREAKING**: `lib/` directory no longer exists at runtime (bundled into script)
- Update `package.nix` to use generated uberscript instead of copying source files

## Impact

- Affected specs: None (internal packaging change, no behavior changes)
- Affected code: `brepl`, `lib/*.clj`, `bb.edn`, `package.nix`, test files
- Benefits: Works in sandboxed Nix builds, offline environments, faster startup (no dep resolution)
