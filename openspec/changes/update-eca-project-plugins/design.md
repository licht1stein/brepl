## Decisions
Only project `.eca/config.json` is changed, and only its `hooks` key. This installer never reads, writes, or validates `plugins`, `plugins.install`, or any plugin source. The brepl skill is not installed, copied, or removed by this installer at all; it already exists as a skill-only plugin in ECA's official marketplace (`editor-code-assistant/eca-plugins`, installable with `/plugin-install brepl`), and `resources/skills/brepl/SKILL.md` in this repository can also be copied manually into a project's `.eca/skills/brepl/SKILL.md`.

Balance-only writes `brepl-validate` with the original validate definition and `brepl-eval` with shell command `brepl eca eval --balance-only`. With-eval writes the original validate and eval definitions. No new sessionEnd hook is registered; no session state is needed.

Read and validate the project before one write. Skip the write if values are unchanged. Match known hooks by key and complete definition, including old session-end and the balance-only post hook. A customized known key aborts the operation without edits. Unrelated brepl-prefixed keys are not owned.

The post hook validates flags before reading input or changing files. No flags retains full evaluation. Balance-only runs the same input checks and `balance-file!`, reports repairs through additionalContext, and skips resolve-port, resolve-host, and eval-file entirely.

A path safety check still prevents writing through a symlinked project config path, or to a project path that resolves to the same file as the global config. This check is independent of plugin management; it protects any write to the project file, hooks included.

Reject malformed project JSON or a non-map `hooks` value. Unsupported inputs require manual review. JSON comments are accepted, but project writes remove comments and change formatting.

## Menu and removal
Install offers balance-only and with-eval. There is no default and no inference from existing hooks. Blank Enter or EOF cancels without writing config. A number or mode name selects the exact hook set. The full evaluation warning explains possible file or database side effects. Nonterminal calls need an explicit mode flag. Help and invalid flags cause no config reads or writes.

Uninstall takes no mode and no menu: it is a single deterministic action, since without plugin management there is no longer a meaningful choice between "remove hooks" and "remove hooks and something else". It removes exact known direct hooks and preserves everything else. No startup migration occurs.

## Compatibility and limits
This installer has no compatibility requirement on ECA's plugin/marketplace system, since it never uses it. Current installed commands still work, including safe session-end cleanup and existing deny/eval-error feedback.

Getting the brepl skill into ECA, and keeping its content in sync with `resources/skills/brepl/SKILL.md`, is the user's choice and outside this installer's scope. Review editor options, custom config files, `pureConfig`, and multiple workspace roots separately. Restart ECA after changes.
