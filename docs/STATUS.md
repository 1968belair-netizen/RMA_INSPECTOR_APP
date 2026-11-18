# Status — 2026-11-18 13:40 ET (coordinator session 2, updated after #39 merged)

Read this first when resuming, then each in-progress issue's latest comment ("where I left off").

## 1. Milestones and open work — DEMO PRIORITY (2026-11-18 13:58)
**Goal:** full loop working against the mock server for a manager demo (see decisions.md "Demo plan"). No in-app demo
mode. Offline-queue edge cases, conflict screens and lock handling beyond the banner wait until after the demo.
**Milestones:** A done · B/C in progress · **E Demo** (new, #45, #46). Report "DEMO READY" + APK link + `docs/demo.md` when done.

### Demo critical path
`#44 B2 Photos` → `#8 B3 Submit inspection` → `#11 C2 Submit report` (needs `#40 C1` too) → `docs/demo.md`.
In parallel: `#45 M1` mock PDF, `#46 M2` demo dataset + checklist; then `#20 D2` guided inspection if time allows.

### Open PRs
| PR | Issue | Branch | State |
|---|---|---|---|
| #44 | #7 B2 Photos | `feature/b2-photos` | build ✅; first emulator run killed by a runner shutdown (infra). **Conflicts with merged C1** (ApiClient, ServiceLocator, WorkspaceScreen/State) — B2 thread merging main now (14:45 ET). |

**Merged this session:** #39 B1 · #37 A6 · #43 D1 camera search (needs a real-tablet test) · #42 docs · #49 M1 mock report PDF · #48 M2 demo dataset + checklist + run script · #47 demo-plan docs · #50 docs · **#40 C1 Report tab (26fd4e1, 14:41 ET)**.
**Emulator tests on main (A6 + B1 together): ✅ passed** (run on PR #47, 2026-11-18 14:12 ET).

### Open issues
| Issue | Milestone | State | Depends on |
|---|---|---|---|
| #7 B2 Photos | B | PR #44 (conflicts with main; thread rebasing) | B1 ✅ |
| #10 C1 Report tab | C | **done** (#40) | — |
| #45 M1 Mock report PDF | E Demo | **done** (#49) | — |
| #46 M2 Demo dataset + checklist + run script | E Demo | **done** (#48) | — |
| #8 B3 Submit inspection | B | not started (banner only for offline; no queue edge cases) | B2 |
| #11 C2 Submit report | C | not started | B3, C1 |
| #20 D2 Guided inspection | D | not started — if time allows | B2, #46 checklist |
| #9 B4 Queue, conflicts, lock/retry | B | **deferred until after the demo** | B3 |
| #34 Idea: pre-filled checklist | D | **idea — do not implement** | — |
| #21 S1 Real server, read-only | server | ready, local on Jacob's laptop. Acceptance now explicitly includes `(blank)` in `work_list_product_types` defaults + the `(blank)` token | — |
| #22 S2 Real server, write side | server | later, local | matching tablet issues |

## 2. Next steps
1. When #44 is green and acceptance met → attempt the merge (Jacob approves); if blocked post "READY TO MERGE: #n".
2. After B2 merges start #8 B3 (and #20 D2 once #46 merges). After B3 + C1 merge start #11 C2.
3. When the loop works: write `docs/demo.md` (setup + 5-minute tap-by-tap script), then post DEMO READY.
4. Worktrees used by threads: `/home/claude/wt-b2`, `/home/claude/wt-c1` (session-local).

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
