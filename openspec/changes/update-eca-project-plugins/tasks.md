## Implementation
- [x] Read AGENTS, OpenSpec guidance, prior approved proposal, and Karpathy guidelines.
- [x] Build the direct-hook design with three modes and plugin management (skill-only/balance-only/with-eval, `brepl@eca`).
- [x] Reduce scope: drop plugin management entirely after confirming (a) ECA supports project-level skill files at `<root>/.eca/skills` independent of any plugin, and (b) the brepl skill is already published in ECA's official marketplace as a skill-only plugin.
- [x] Drop the `--skill-only` install mode: it no longer installs anything once plugin management is gone, and is indistinguishable from plain uninstall.
- [x] Reduce install to two modes (`--balance-only`, `--with-eval`) and uninstall to a single flag-less action.
- [x] Remove mode inference. The install menu has no default; blank Enter and EOF cancel without writes. Require a flag without a terminal.
- [x] Write original direct validate/eval hooks or the balance-only post hook, with no new sessionEnd registration.
- [x] Remove only complete exact known hooks; abort without writes for customized known keys.
- [x] Remove all `plugins.install`/`plugins.installMode`/plugin-source validation, dynamic-ref rejection, `ECA_CONFIG`/`extraConfigs` rejection, and unqualified-ref ambiguity checks; none apply once plugins are out of scope.
- [x] Keep the project/global-config path safety check (symlink and same-file detection), since it protects any write, not just plugin writes.
- [x] Print a short tip after a successful install pointing to `/plugin-install brepl` in ECA or a manual copy of `resources/skills/brepl/SKILL.md`.
- [x] Reject malformed and trailing JSON without data loss; accept JSON comments.
- [x] Add balance-only post-hook execution that skips port discovery, host lookup, and evaluation.
- [x] Preserve no-argument full evaluation, deny/eval-error fixes, session-end compatibility, and Claude behavior.
- [x] Update README and CLI help text to match: two install modes, flag-less uninstall, no plugin/compatibility requirement, the skill tip.
- [x] Rewrite tests: drop all plugin-ref tests (dynamic refs, `ECA_CONFIG`, `extraConfigs`, unqualified refs, install-list preservation); update mode/menu/CLI tests for two install modes and flag-less uninstall.
- [x] Replace `resources/skills/brepl/SKILL.md` with an improved, more accurate version (precise exit codes, port-resolution order, a verified `BREPL_HOST` CLI-default bug, raw nREPL operations) carried over from the published marketplace plugin.
- [x] Rebuild the generated brepl executable with `bb build` after each source change, including once with the old skill text (to keep the amended commit's binary consistent with its own sources) and once with the new skill text.
- [x] Run the full test suite: `bb test`, 80 tests, 741 assertions, zero failures or errors, after the hook rework and again after the skill update.
- [x] Check editor diagnostics: no new diagnostics in changed ECA code or tests.

## Validation limits
- OpenSpec CLI validation is blocked because `openspec` is not installed.
- ECA's plugin/marketplace system itself was not changed or re-tested here; this installer simply stopped depending on it.
- Only the project's own test suite was run; cross-platform tests were not run (focused tests ran on Linux).
