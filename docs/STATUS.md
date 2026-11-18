# Status — 2026-11-18 13:20 ET (hand-over to a fresh session)

Read this first when resuming, then each in-progress issue's latest comment ("where I left off").

## 1. Milestones and open work

**Milestone A Navigate:** approved by Jacob (device test, no issues). Report: issue #33 (closed). One follow-up:
#36 (A6).
**Current:** milestone **B Inspect**, running in parallel with **C Report** (rapid mode).

### Open PRs
| PR | Issue | Branch | State at hand-over |
|---|---|---|---|
| #37 | #36 A6 "(blank)" product-type chip, on by default | `feature/a6-blank-product-type` | build ✅, emulator running. Merge when green. |
| #39 | #5 B1 Findings tab + local drafts | `feature/b1-findings` | build + emulator running. Merge when green and all acceptance items are met. |
| #40 | #10 C1 Report tab | `feature/c1-report-tab` | **build ❌** (first run). The C1 thread is fixing it. |

All three were started by threads in the old session; they may finish and merge on their own before the new session
starts. Check `gh api "repos/1968belair-netizen/rma_inspector_app/pulls?state=open"` first.

### Open issues
| Issue | Milestone | State | Branch | Depends on |
|---|---|---|---|---|
| #36 A6 blank product-type chip | A | PR #37 | `feature/a6-blank-product-type` | — |
| #5 B1 Findings tab | B | PR #39 | `feature/b1-findings` | — |
| #10 C1 Report tab | C | PR #40 | `feature/c1-report-tab` | — |
| #7 B2 Photos tab | B | not started | — | B1 (row-photo slot + DraftStore) |
| #8 B3 Submit inspection | B | not started | — | B1, B2 |
| #9 B4 Offline queue, conflicts, lock/retry | B | not started | — | B3. Acceptance now includes the 2026-11-18 queue/sign-out rules (held per inspector, sign-out prompt, earlier-session banner, per-inspector queue view, A/B emulator test, second fake mock user) |
| #11 C2 Submit report | C | not started | — | B3 (and C1) |
| #19 D1 Camera search | D | not started | — | — (can start any time) |
| #20 D2 Guided inspection | D | not started | — | B1, B2 |
| #34 Idea: pre-filled checklist on Findings | D | **idea — do not implement** | — | decide later |
| #21 S1 Real server, read-only | server | **ready**. Jacob runs it locally on his work laptop | — | — |
| #22 S2 Real server, write side | server | later, local | — | matching tablet issues |

## 2. Work in progress and exact next steps
1. **#37 / #39 / #40:** if a PR is still open, read its issue's latest comment. Then: green CI + acceptance met →
   rebase on main if needed → squash-merge (standing approval). C1 (#40) had a failing build on its first run; check
   `gh run view --log-failed` for its latest run.
2. **After B1 merges**, start **#7 B2 Photos**. It plugs into B1's row-photo slot and DraftStore; see B1's PR / final
   comment.
3. **Keep 3 issues in progress:** next candidates are #7 B2 (after B1), #19 D1 camera search (independent), then #8
   B3 → #9 B4 and #11 C2.
4. **After B and C are done**, post one short milestone report covering both (APK link, screenshots, tests, gaps,
   questions). Jacob tests B and C together on his phone.
5. Worktrees used by threads: `/home/claude/wt-a6`, `/home/claude/wt-b1`, `/home/claude/wt-c1`. These are
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
| # | Issue | Question | Built |
|---|---|---|---|
| 3 | #4 | Code-list refresh interval; recent codes kept | Refresh on start/sign-in + when > 4 h old; 8 recent per picker, 6 shown |
| 4 | #17 | Work-list chips per tablet or per inspector | Per tablet, kept across sign-out |
| 5 | #17 | Chip for blank product type | **Answered** by Jacob: add "(blank)", on by default. Row removed by PR #37 (#36) |
| 6 | #18 | When the workspace shows two panes | Width ≥ 840 dp; narrower = list → detail |

Rows added by #39 (B1) or #40 (C1) will appear when those PRs merge; check the file on main.
