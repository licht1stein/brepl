# Change: Configure ECA project hooks safely, without managing plugins

## Why
Users need a safe choice between automatic bracket repair and automatic evaluation. Installation must not depend on ECA's plugin/marketplace system working, and must not manage the brepl skill at all.

## Approval
The user approved this final, reduced-scope design on 2026-10-11. It replaces the prior design, which had this installer also manage a `brepl@eca` plugin reference. That plugin management was dropped entirely: the brepl skill is already published in ECA's official marketplace and can be installed with `/plugin-install brepl` inside ECA, or by copying `resources/skills/brepl/SKILL.md` into a project's `.eca/skills/brepl/SKILL.md`. This installer never touches either path.

## What Changes
- Install automatic hooks directly in project config under the original names. Install offers `--balance-only` and `--with-eval`, with a terminal menu that has no default.
- Uninstall takes no flags: it removes exact known direct hooks if present, and nothing else.
- Blank Enter or EOF cancels without writes in the install menu. Non-terminal calls require an explicit flag.
- Add `eca eval --balance-only`, which repairs brackets but never discovers a port, resolves a host, or evaluates code.
- Match complete known hook definitions, including old session-end. Abort without writes for customized known keys. Preserve unrelated hooks and other config.
- Never read, write, or validate `plugins.install`, `plugins.installMode`, or any plugin source. Print a short tip after a successful install pointing to `/plugin-install brepl` or a manual copy of the skill file.
- Keep prior deny and evaluation-error feedback fixes, Claude behavior, and safe session-end compatibility.

## Impact
- Affected specs: hook-installation, packaging.
- Affected files: ECA installer and CLI, tests, README, generated executable, bundled skill content.
- ECA's plugin/marketplace system and runtime merge behavior are outside this repository and this implementation; this installer no longer depends on them.
