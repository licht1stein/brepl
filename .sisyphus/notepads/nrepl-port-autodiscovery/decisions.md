# Decisions

## 2026-02-17 Session Start

- resolve-port only enhancement — no new subcommand
- No caching — scan fresh every time
- No environment type detection — CWD matching is the only selection criterion
- First CWD-matching valid nREPL wins — no scoring
- lsof only for now — ss deferred
- Sequential candidate checking — no pmap
- No changes to hook handlers — they already handle nil port gracefully
