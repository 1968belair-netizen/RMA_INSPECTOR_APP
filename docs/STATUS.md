# Status — 2026-11-18 16:10 ET (coordinator session 2)

Read this first when resuming, then each in-progress issue's latest comment ("where I left off").

## 1. Milestones and open work — DEMO PRIORITY
**Goal:** full loop against the mock server for a manager demo (see decisions.md "Demo plan"). No in-app demo mode.
Offline-queue edge cases, conflict screens and lock handling beyond the banner wait until after the demo.
**Milestones:** A done · B/C demo path done · **E Demo** in progress. Post "DEMO READY" + APK link + `docs/demo.md` when done.

### Open PRs
None. **DEMO READY** posted 2026-11-18 ~16:35 ET: APK `RMA-Inspector-0.1.101-debug.apk` (release `latest`, built from 8072f8c, includes guided inspection); guide: `docs/demo.md`.

**Merged this session:** #39 B1 · #37 A6 · #43 D1 camera search (needs a real-tablet test) · #42 · #47 · #50 docs · #49 M1 mock PDF · #48 M2 demo dataset · #40 C1 Report tab · #44 B2 Photos · #55 C2 Submit report · #57 C2 emulator acceptance checks · #54 B3 Submit inspection · **#53 D2 Guided inspection (8072f8c, merged without emulator test; revert if it causes problems)** · #58, #59 docs.
**Emulator tests on main:** A6 + B1 ✅ (2026-11-18 14:12 ET). #54 and #57 emulator ✅ (one `SmokeTest` flake, passed on re-run).

### Open issues
| Issue | Milestone | State |
|---|---|---|
| #20 D2 Guided inspection | D | PR #53 open; stays open until the emulator test lands (#56) |
| #56 D2b emulator test, guided inspection with demo dataset | E Demo | after the demo |
| #9 B4 Queue, conflicts, lock/retry | B | **deferred until after the demo** (B3 shows dialogs only for 409/423/offline) |
| #34 Idea: pre-filled checklist | D | idea — do not implement |
| #21 S1 Real server, read-only | server | local on Jacob's laptop; acceptance includes `(blank)` in `work_list_product_types` defaults |
| #22 S2 Real server, write side | server | later, local |

Done: #7 B2, #8 B3, #10 C1, #11 C2, #45 M1, #46 M2.

## 2. Next steps
1. Demo on the tablet; note anything that breaks.
2. After the demo: #56 (guided emulator test), #9 B4 queue/conflicts, server issues #21/#22.
3. Known gaps: camera features (D1, B2) never run on a real tablet; B3 emulator test covers Received → Inspected only
   (912358 rebuild / 912359 resend warning are screenshot-tested only).

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
