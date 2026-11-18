# Open questions for Jacob

Decisions the docs don't cover. Each entry: date, issue, the question, options with a recommendation, and what
was done meanwhile (work continues on other issues). Jacob answers here or in chat; answered items move to
`docs/decisions.md`.

| # | Date | Issue | Question | Options (recommended first) | Meanwhile |
|---|---|---|---|---|---|
| 3 | 2026-11-18 | #4 | How often should code lists refresh, and how many "recently used" codes per picker? | **Recommended:** refresh on app start / sign-in (always, with ETag → usually `304`) and when the device copy is older than **4 h** (checked every 15 min); keep the last **8** used codes per picker on the device, show up to 6. Alt: make both server settings in `/codelists`. | Built with the recommended values (`CodeListRepository.DEFAULT_MAX_AGE_MS`, `RecentCodesStore`). |
| 4 | 2026-11-18 | #17 | Should the remembered work-list chips be per tablet or per inspector, and survive sign-out? | **Recommended:** per tablet, kept across sign-out and app restarts (one shared shop-floor tablet, same filters for everyone); Settings could get "Reset work-list filters" later. Alt: per inspector (keyed by username). | Built per tablet, kept across sign-out (`SettingsStore.workListFilter`). |
| 6 | 2026-11-18 | #18 | When does the workspace switch from list → detail to two panes? | **Recommended:** by screen width — **≥ 840 dp** shows two panes (11" tablet landscape ≈ 1280 dp); narrower (11" tablet portrait ≈ 800 dp, phones) shows list → detail. Alt: strictly by orientation (landscape = two panes even on phones). | Built with the recommended width rule (`TWO_PANE_MIN_WIDTH` in `WorkspaceScreen.kt`). |

_#1–2 and #5 answered 2026-11-18 — see `docs/decisions.md`._
