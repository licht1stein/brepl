<!-- OPENSPEC:START -->

# OpenSpec Instructions

These instructions are for AI assistants working in this project.

Always open `@/openspec/AGENTS.md` when the request:

- Mentions planning or proposals (words like proposal, spec, change, plan)
- Introduces new capabilities, breaking changes, architecture shifts, or big performance/security work
- Sounds ambiguous and you need the authoritative spec before coding

Use `@/openspec/AGENTS.md` to learn:

- How to create and apply change proposals
- Spec format and conventions
- Project structure and guidelines

Keep this managed block so 'openspec update' can refresh the instructions.

<!-- OPENSPEC:END -->

## Development Workflow

brepl uses uberscript for distribution. The `brepl` file in the repo is a generated artifact containing all dependencies bundled.

### Source Structure

```
src/brepl.clj       - Main entry point
src/brepl/lib/      - Library modules (validator, installer, etc.)
bb.edn              - Paths + deps (parmezan for bracket-fixing)
brepl               - Generated uberscript (committed)
```

### Making Changes

1. Edit source files in `src/`
2. Run `bb build` to regenerate the uberscript
3. Test with `./brepl --version` or `bb test`
4. Commit both source changes and regenerated `brepl`

```bash
# Edit source
vim src/brepl/lib/validator.clj

# Rebuild
bb build

# Test
bb test
./brepl balance /tmp/test.clj --dry-run

# Commit
git add src/ brepl
git commit -m "Your changes"
```

## Version Management

### Bumping Versions

Use the `bb version-bump` task to update version across all files:

```bash
bb version-bump patch   # 2.1.0 -> 2.1.1
bb version-bump minor   # 2.1.0 -> 2.2.0
bb version-bump major   # 2.1.0 -> 3.0.0
```

This automatically updates:

- `src/brepl.clj` - source version string
- `package.nix` - Nix derivation version
- `README.md` - installation example version
- Regenerates `brepl` uberscript via `bb build`

### Release Procedure

When merging a PR that requires a new release:

1. **Merge PR to master**

   ```bash
   gh pr merge <number> --squash
   ```

2. **Pull latest master**

   ```bash
   git checkout master && git pull
   ```

3. **Tag the release**

   ```bash
   git tag v2.1.1
   git push github v2.1.1
   ```

4. **Create GitHub release**

   ```bash
   gh release create v2.1.1 --title "v2.1.1" --notes "Release notes here"
   ```

5. **Update Nix hash** (after tag is pushed)

   ```bash
   nix-prefetch-github licht1stein brepl --rev v2.1.1
   ```

6. **Update README.md with new hash**
   - Replace the `hash` value in the installation example with output from step 5
   - Commit and push:
   ```bash
   git add README.md
   git commit -m "Update Nix hash for v2.1.1 release"
   git push
   ```

### Version Numbering

- **Patch** (x.y.Z): Bug fixes, no breaking changes
- **Minor** (x.Y.0): New features, may include breaking changes
- **Major** (X.0.0): Major rewrites or significant API changes

Note: This project uses a modified semver where breaking changes increment minor version, not major.
