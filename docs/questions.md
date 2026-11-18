# Open questions for Jacob

Decisions the docs don't cover. Each entry: date, issue, the question, options with a recommendation, and what
was done meanwhile (work continues on other issues). Jacob answers here or in chat; answered items move to
`docs/decisions.md`.

| # | Date | Issue | Question | Options (recommended first) | Meanwhile |
|---|---|---|---|---|---|
| 3 | 2026-11-18 | #4 | How often should code lists refresh, and how many "recently used" codes per picker? | **Recommended:** refresh on app start / sign-in (always, with ETag → usually `304`) and when the device copy is older than **4 h** (checked every 15 min); keep the last **8** used codes per picker on the device, show up to 6. Alt: make both server settings in `/codelists`. | Built with the recommended values (`CodeListRepository.DEFAULT_MAX_AGE_MS`, `RecentCodesStore`). |

_#1–2 answered 2026-11-18 — see `docs/decisions.md`._
