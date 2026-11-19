# Status — 2026-11-19 09:55 ET (coordinator session 3)

Read this first when resuming, then each in-progress issue's latest comment ("where I left off").

## 1. Milestones and open work
**Merged 2026-11-18/19:** #63 guided-inspection emulator test (closes #56, #20) · #69 camera hardening + `docs/real-tablet-test.md` ·
#70 upload queue core · #74 PDF-preview crash fix · #73 lock handling (fails closed) · #72 conflict screen (409s) · #71 queue UI,
sign-out prompt, earlier-session banner (closes #67, #9) · #76 rename to **BITZER Inspect** + new icon · #80 status names from the
server's `status_roles` (closes #77) · #75/#79 docs. Latest APK: `BITZER-Inspect-0.1.149-debug.apk` (release `latest`).

### New feature in progress: Report tab compact header + photo viewer (Jacob, 2026-11-19; project doc `Report_Tab_Photo_Viewer_Plan_2026-11-19`)
| Issue | What | State |
|---|---|---|
| #81 C4a | one-line header, Details, "+n" overflow | thread building |
| #82 C4b | photo viewer: swipe, filmstrip, caption chips, All/Report toggle, zoom | thread building |
| #83 C4c | side-by-side / stacked layout, emulator test, spec §7 update | starts when #81 and #82 are merged; Jacob wants this in a debug release ASAP |

### Open issues (other)
| Issue | Milestone | State |
|---|---|---|
| #78 D4 rename applicationId / package / repo | D | deferred |
| #34 Idea: pre-filled checklist | D | idea — do not implement |
| #21 S1 Real server, read-only | server | local on Jacob's laptop — not for cloud sessions |
| #22 S2 Real server, write side | server | local, later |

## 2. Next steps
1. Merge #81, #82, then build and merge #83; send Jacob the APK link.
2. Jacob runs `docs/real-tablet-test.md` on the tablet and phone; each finding becomes an issue.
3. Known gaps: camera paths never run on real hardware; the open workspace is not refreshed when a background queue send finishes;
   the `SubmitModal` container test tag does not match in the emulator tree (match by text or button tags in tests).

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

## 4. Open questions
None — #1–43 are in `docs/decisions.md` (#26 changed: status names come from the server). New rows arrive with the report-viewer PRs.
