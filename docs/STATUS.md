# Status — 2026-11-18 13:40 ET (coordinator session 2, updated after #39 merged)

Read this first when resuming, then each in-progress issue's latest comment ("where I left off").

## 1. Milestones and open work

**Milestone A Navigate:** approved by Jacob (device test, no issues). Report: issue #33 (closed). One follow-up:
#36 (A6).
**Current:** milestone **B Inspect**, running in parallel with **C Report** (rapid mode).

### Open PRs
| PR | Issue | Branch | State |
|---|---|---|---|
| #37 | #36 A6 "(blank)" product-type chip | `feature/a6-blank-product-type` | build ✅ test ✅, emulator running. Merge when green. |
| #40 | #10 C1 Report tab | `feature/c1-report-tab` | build ✅, emulator running. Merge when green and acceptance met. |
| #42 | — | `docs/approve-q3-q4-q6` | Docs: Q3/4/6 approved; rebased on main after #39. |
| (coming) | #19 D1 Camera search | `feature/d1-camera-search` | thread started 2026-11-18 ~13:35 |
| (coming) | #7 B2 Photos | `feature/b2-photos` | thread started 2026-11-18 ~13:35 (B1 merged) |

**Merged this session:** #39 B1 Findings tab (merged by Jacob).

### Open issues
| Issue | Milestone | State | Branch | Depends on |
|---|---|---|---|---|
| #36 A6 blank product-type chip | A | PR #37 | `feature/a6-blank-product-type` | — |
| #10 C1 Report tab | C | PR #40 | `feature/c1-report-tab` | — |
| #7 B2 Photos tab | B | **in progress** | `feature/b2-photos` | B1 ✅ |
| #19 D1 Camera search | D | **in progress** | `feature/d1-camera-search` | — |
| #8 B3 Submit inspection | B | not started | — | B2 (B1 ✅) |
| #9 B4 Offline queue, conflicts, lock/retry | B | not started | — | B3. Acceptance includes the 2026-11-18 queue/sign-out rules |
| #11 C2 Submit report | C | not started | — | B3 (and C1) |
| #20 D2 Guided inspection | D | not started | — | B2 (B1 ✅) |
| #34 Idea: pre-filled checklist on Findings | D | **idea — do not implement** | — | decide later |
| #21 S1 Real server, read-only | server | **ready**, local on Jacob's laptop | — | — |
| #22 S2 Real server, write side | server | later, local | — | matching tablet issues |

(#5 B1 is closed by the merge.)

## 2. Work in progress and exact next steps
1. **#37 / #40:** when green and acceptance met → attempt the squash-merge (Jacob approves the prompt). If it is
   still blocked, post "READY TO MERGE: #n" in chat and keep working.
2. **#7 B2 and #19 D1** are running in Sonnet threads (worktrees `/home/claude/wt-b2`, `/home/claude/wt-d1`).
3. **Keep 3 issues in progress:** next candidates are #8 B3 (after B2) → #9 B4, #11 C2 (after B3 + C1) and #20 D2.
4. **After B and C are done**, post one short milestone report covering both (APK link, screenshots, tests, gaps,
   questions). Jacob tests B and C together on his phone.
5. Worktrees used by threads: `/home/claude/wt-b2`, `/home/claude/wt-d1` (earlier ones are gone). These are
   session-local; a new session starts from a fresh clone.
6. Thread rules file (session-local copy): the same content is in CLAUDE.md "Rapid development mode" and below.

## 3. Rapid-mode rules in force (CLAUDE.md → Workflow → Rapid development mode)
1. Keep **3 issues in progress at all times**. Start independent work now (C against the mock); only truly
   dependent work waits.
2. **No pause between milestones.** Short milestone report, keep going; Jacob tests B and C together on his phone.
3. **Emulator tests only on PRs that change navigation, sign-in, submit or the upload queue.** The `changes` job in
   `android.yml` decides from paths (`ui/RmaInspectorApp.kt`, `MainActivity.kt`, `ui/signin/`, `AuthStore.kt`,
   `ApiClient.kt`, `*Submit*`/`*Queue*`/`*Upload*`, `app/src/androidTest/`, the workflow). The label `emulator`
   forces it. Unit and screenshot tests always run.
4. **Implementation threads use Sonnet**; Opus only for a thread stuck after two attempts.
5. **PR summaries: 3 lines max.** Milestone reports are the only detailed reports.
6. **Never wait on Jacob.** Undecided items go to `docs/questions.md` with the recommended option built.
7. **Stop only for:** a failed check you can't fix after two attempts, a hard-rule conflict, or a usage limit
   (leave a "where I left off" comment).

Also still in force: standing approval to merge (green + acceptance met), one issue → one branch → PR, commit and
push after every meaningful step, "where I left off" comments before stopping.

Known CI/session quirks:
- GraphQL is blocked in cloud sessions, so use `gh api` REST for PRs and merges.
- Push with `git -c push.negotiate=true push`. Force-push only with `--force-with-lease=<branch>:<sha>`.
- Branch deletion is blocked.
- In emulator tests:
  - Espresso `pressBack` hangs; use `onBackPressedDispatcher`.
  - Press shiftable buttons with `performSemanticsAction(SemanticsActions.OnClick)`.
  - Each test starts signed out.

## 4. Open items in `docs/questions.md` (recommended option already built; Jacob may override)
Q3, Q4 and Q6 were **approved as built on 2026-11-18** and moved to `docs/decisions.md`. Q5 was answered ("(blank)"
chip, on by default); its row goes when PR #37 merges. Questions #7–10 are added by PR #39 (B1) when it merges, and
more by #40 (C1) — check the file on main.
