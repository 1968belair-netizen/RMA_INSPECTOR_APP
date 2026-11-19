# Status — 2026-11-19 07:10 ET (coordinator session 3)

Read this first when resuming, then each in-progress issue's latest comment ("where I left off").

## 1. Milestones and open work
**Merged this session (2026-11-18/19):** #63 emulator test for guided inspection (closes #56, #20) · #69 camera hardening +
`docs/real-tablet-test.md` (#68) · #70 upload queue core (#64) · #74 fix for a native crash when Done closed the PDF preview
mid-render (the intermittent `ReportSubmitFlowTest.secondSubmit…` "Process crashed") · #73 lock handling that fails closed (#66).

### Open PRs
| PR | Issue | State |
|---|---|---|
| #72 | #65 B4b conflict screen (409 RMA_CHANGED / REPORT_PICS_CHANGED) | emulator job failed; thread fixing |
| #71 | #67 B4d queue UI, sign-out prompt, earlier-session banner | emulator job failed; thread fixing |

### Open issues
| Issue | Milestone | State |
|---|---|---|
| #9 B4 Queue, conflicts, lock/retry | B | split into #64 ✅ #65 (PR #72) #66 ✅ #67 (PR #71); close when #65 and #67 merge |
| #34 Idea: pre-filled checklist | D | idea — do not implement |
| #21 S1 Real server, read-only | server | local on Jacob's laptop — not for cloud sessions |
| #22 S2 Real server, write side | server | local, later |

## 2. Next steps
1. Merge #72 and #71 when green; close #9.
2. Jacob runs `docs/real-tablet-test.md` on a real tablet and phone; each finding becomes an issue.
3. Known gaps: camera paths still never run on real hardware; B3 emulator test covers Received → Inspected only (912358 rebuild /
   912359 resend warning screenshot-tested only); the open workspace is not refreshed when a background queue send finishes.

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
chip, on by default) and its row was removed by #37. Questions #7–10 came with B1 (#39); more may arrive with
#40 (C1) — check the file on main.
