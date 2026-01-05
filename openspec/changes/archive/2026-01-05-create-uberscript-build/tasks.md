# Tasks: Create Self-Contained Uberscript Build

## 1. Restructure Source Layout

- [ ] 1.1 Create `src/brepl/lib/` directory structure
- [ ] 1.2 Move `lib/*.clj` files to `src/brepl/lib/` with correct names for namespaces
- [ ] 1.3 Remove runtime `deps/add-deps` call from validator.clj
- [ ] 1.4 Update main `brepl` script to use static requires instead of `load-file`

## 2. Update Build Configuration

- [ ] 2.1 Add `:paths ["src"]` to `bb.edn`
- [ ] 2.2 Add parmezan as static dependency in `bb.edn`
- [ ] 2.3 Add `uberscript` build task to `bb.edn`

## 3. Update Tests

- [ ] 3.1 Update test files to use static requires instead of `load-file`
- [ ] 3.2 Verify all tests pass with new structure

## 4. Update Nix Package

- [ ] 4.1 Simplify `package.nix` - just copy `brepl` (no lib/ needed)
- [ ] 4.2 Remove lib/ directory copying from install phase

## 5. Verify Installation Methods

- [ ] 5.1 Test local: `bb uberscript brepl -m brepl && ./brepl --version`
- [ ] 5.2 Test bbin install: `bbin install .` from local repo
  - Verify brepl runs correctly
  - Verify bracket-fixing works (parmezan available)
  - Verify all subcommands work (hooks, balance, etc.)
- [ ] 5.3 Test Nix build: `nix-build -E 'with import <nixpkgs> {}; callPackage ./package.nix {}'`
  - Build should succeed
  - Resulting binary should work correctly
  - Verify bracket-fixing works

## 6. Documentation and Cleanup

- [ ] 6.1 Add `bb build` task to bb.edn for convenience
- [ ] 6.2 Update CLAUDE.md with new dev workflow
- [ ] 6.3 Remove old `lib/` directory
