# Decisions log

Newest first. Each entry: date, decision, where it is reflected. Planning happens in the claude.ai project
"RMA Inspection app"; this file is the repo's copy of what was decided.

## 2026-11-18 — Milestone A approved; work-list blank product type (Jacob)
- **Milestone A approved** after Jacob's device test (phone): everything passed, no issues found. Report: issue #33.
- **Question 5 answered:** add a **"(blank)" product-type chip, on by default**, so Received RMAs with no product
  type never drop off the work list (fix PR; api.md defines how the work-list filter asks for blank).
- **Idea logged, not scheduled:** pre-filled checklist on the Findings tab as an alternative to guided inspection —
  spec §15.1, issue #34 (milestone D, label `idea`). Decide later.

## 2026-11-18 — Upload queue and sign-out (Jacob, for #9)
1. **Wording:** sign-out clears the **session and screen history** but **never the upload queue**.
2. **Held uploads:** queued uploads of a signed-out inspector are held on the tablet until **that same inspector**
   signs in again. Never sent under another user's token, never re-attributed.
3. **Sign out with waiting uploads:** "You have N uploads waiting. Send now before signing out?" — buttons
   **Send now** / **Sign out anyway**. Send now uploads first (shows progress), then signs out; if offline it says
   so and offers Sign out anyway.
4. **Sign in with held uploads:** show "N uploads waiting from your earlier session" and send them automatically.
5. **Settings → Upload queue:** held items grouped by inspector; read-only for other users (they see that items
   are waiting, but cannot open or send them).
- Reflected in: spec §2 and §6.6; acceptance criteria of #9 (incl. an emulator test for user A / user B).

## 2026-11-18 — Sign-in answers (Jacob, from docs/questions.md #1–2)
- **Token storage (approved):** the session token is encrypted with AES-256-GCM using a non-exportable Android
  Keystore key, no extra library (`data/AuthStore.kt`). **Only the token (and the user's name/initials) is stored —
  never the password.** The password lives only in the sign-in form's memory and in the login request.
- **Token rejected mid-session (`401`) (approved with a change):** sign-in is shown on top of the current screen;
  after signing in again the user returns to the **screen and RMA they were on** (fallback: Home if that isn't
  possible). Back on the sign-in screen leaves the app rather than skipping sign-in. Explicit Sign out still clears
  the screen history.
- **Queued work keeps its inspector:** if a different user signs in, work already queued keeps the original
  inspector's identity (user, initials) and is **never re-attributed** (refined in the next entry).
- Reflected in: spec §2, `AuthStore.expired`, `RmaInspectorApp.kt` navigation, `AuthStoreTest`, emulator
  `SmokeTest.reSignInReturnsToTheSameRma`.

## 2026-11-18 — Build order, milestones and process (Jacob)
- Core loop first: sign-in → code lists/pickers → home/work list → workspace shell → findings → photos → submit
  inspection → queue/conflicts → report tab → submit report → camera search → guided inspection.
- GitHub milestones: **A Navigate** (#15 tests setup, #16 sign-in, #4 pickers, #17 home, #18 shell), **B Inspect**
  (#5, #7, #8, #9), **C Report** (#10, #11), **D Extras** (#19 camera, #20 guided). #6 closed (folded into #10).
- **Server track** (label `server`, local session on Jacob's work laptop, parallel): #21 S1 read-only real server
  (sign-in with server-side users — refuses to start without them — search, work list incl. `rma_queries.py`
  extraction and `status_since` rules, detail, code lists) starts when the workspace shell is merged; #22 S2 write
  side after the matching tablet issues (submit, photo filing, report build/spell check, 20-photo cap lift).
- The token-less mock bypass in `run_mock_server.bat` is removed in the sign-in issue.
- Process: up to 3 issues in parallel; **standing approval to merge** when Actions is green and acceptance criteria
  are met; uncovered decisions go to `docs/questions.md` while work continues; screenshot tests (Roborazzi) +
  emulator tests in Actions first, every PR comment shows changed screens + a 3-line summary; Jacob tests on a
  tablet at the end of each milestone, with a milestone report; `docs/decisions.md` and the dev plan kept current.

## 2026-11-18 — Layout and behaviour decisions (Jacob) — spec rev 7, api rev 7
Layout/behaviour only; built in later issues. **Supersedes:** "no sign-in in v1", "no barcode in v1", the inspector
picker, the separate Summary screen/tab, the status-chip work list and its old defaults (Received / Rework /
Inspected / Report Complete), "status stays manual after a report build", and "add to dictionary is a desk action".

**Goal**
- The inspector does the whole inspection **and** report in the app; report writing follows right after the
  inspection. Windows workbench updates come later.
- All sign-ins have all functions in v1 (`roles: ["all"]`); roles later.

**Sign-in**
- First screen: username + password checked by the server (`POST /auth/login` → token). v1 credentials are a server
  setting (mock: fake demo user). No credentials in the app or repo. Replaces the inspector picker. Stays signed in
  until the user signs out. `GET /inspectors` removed (mock keeps it only until the sign-in screen ships).

**Find an RMA**
- One search box (RMA, serial or SAP no.; server decides) with a camera icon: ML Kit on-device barcode/QR scanning
  plus text recognition for serials; the result fills the box and runs the search. One match → open; several → list.
- Results and work list show RMA, model, product type, return code, age (`status_since` + "estimated"), status,
  core-teardown and lock badges. Work list sits below the search box.

**Work list**
- Inspection status **Received** only.
- Filter chips for product type and return code; the tablet remembers the last selection.
- Defaults: product types Recip, Scroll, Screw, Part; return codes Inspection Only, Repair & Return, Warranty.
- Defaults and allowed values are server settings (`work_list_statuses`, `work_list_product_types`,
  `work_list_return_codes`; chips offer `product_types` / `return_codes`), matched trimmed/uppercase, so they can
  change at the SAP rollout without an app update.
- Scroll RMAs appear but open read-only with the "Handled in the repair database" banner (scroll capability off).
- Sort: oldest `status_since` first, then RMA number.
- `status_since` counts only real status changes (`Old_Status <> New_Status`), tiebreak `A4glidentity`; no audit
  rows → `rma_dt_entered` with `status_since_estimated = true`.
- Open = combined view `status='O'` JOIN `OERMAHDR_iPro` (same as the Trend Tracker). An RMA with a draft on this
  tablet stays on its list even if it no longer matches the filters, with a "Return code / status changed" flag.
- Phase 4 (local session): extract the Trend Tracker queries into a side-effect-free `rma_queries.py` shared by
  `daily_snapshot_V4.py`, `refresh_aging_data.py` and the app server.

**Inspection workspace**
- Persistent header: RMA, model, product type, return code, status, customer, serial; expandable failure info.
- Tabs: Findings, Photos, Report. Landscape two panes (list + detail); portrait list → detail. Target 11"+ tablets.
- Part detail opens as a sheet over Findings. "Add part" always visible. (Replaces the separate RMA overview screen.)

**Guided inspection**
- Header button: Start / Continue (step x of y) / Complete / disabled "No guided list for <type> yet" (v1: no
  checklists).
- Server-served checklists per product type in `/codelists` (`checklists` keyed by `product_type_key`; empty in v1):
  part code + photo rules (`required_when`: always / non_normal / never; `normal_issue_codes` = `50`). Future grid:
  required part + 50 Normal → overall photo required; non-normal → overall + detail required.
- Each step: part code, issue code as a searchable scrollable dropdown defaulting to 50 Normal (no chip grid),
  optional note, photo slots with Required/Optional badges. Skip disabled for required photos; Next disabled until
  required items are done. Steps write into Findings; resumable; existing rows pre-filled.
- Finish → "Inspection complete" dialog → back to the workspace.
- Required items apply only in guided mode; manual entry has no requirements.

**Submit inspection** (Findings / Photos tabs; review screen first; `status_roles` from the server)
- Status before Inspected → save findings/photos, set Inspected.
- Status Report Complete → dialog "Report already complete" with "Update inspection findings": saves, keeps the
  status, regenerates the report.
- Status Sent to RSM or Issued to Customer → same, with warning "This report was already sent. Updating will
  regenerate it; you may need to resend it."

**Report tab**
- Landscape: left = parts list (part, issue, note) + photo strip filtered by the selected part (Show all, tap to
  enlarge, long-press to add to report); right = General Defect Code dropdown, summary (standard wording button,
  counter), Inspected by / Summary by, "Report photos: n picked, n pages" → Arrange (captions, caption order). Must
  stay usable with the on-screen keyboard open.
- Submit report: automatic spell check (flagged words with suggestions: fix, ignore, add to dictionary — new
  `POST /spellcheck/dictionary`) → build PDF/XLSX → preview. First time sets Report Complete; afterwards it only
  regenerates (same warnings as above when past Report Complete).
- Photo list carries `seq` / `part_code` / `row_id` so the strip can filter by part.

**References** (check licenses; reference first): android/architecture-samples (main template),
android/nowinandroid, android/camera-samples, googlesamples/mlkit, android/compose-samples,
ReactiveCircus/android-emulator-runner. Field Report Pro for ideas only unless licensed.

**Mock server:** fake demo sign-in (`demo`/`demo`, overridable via `MOCK_USERS`); `MOCK_AUTH_OPTIONAL=1` keeps
pre-sign-in app builds working (set in `run_mock_server.bat` until the sign-in issue merges); new fake RMAs
912354–912359 for the filters, badges and report-status paths.

## 2026-11-17 — One report summary
- The app has **one** summary field, "Report summary" (≤ 4000). It is a server setting (`summary_blocks` in
  `/codelists`), not app code. Macola: stored in `oermahdr_extended_ipro.summary_1`; `summary_2` / `summary_3`
  are not shown or written by the app (the desk can still use them; they stay in the change check).

## 2026-11-17 — SAP-ready contract (api.md rev 6, spec rev 6, `docs/sap_mapping.md`)
Goal: the SAP S/4HANA cutover changes only the server's backend connector — not the API or the tablet.
1. **RMA identity:** the tablet treats an RMA as an opaque `rma_id` (URLs) + `display_number` (shown). The
   1–8 digit rule is removed from the app; the server validates. Search sends whatever was typed.
2. **Parts rows:** `orig_line_no` / `line_no` are gone from the contract. Each row has an opaque server-assigned
   `row_id` (Macola: line number + record signature; SAP later: notification item number). New rows send
   `row_id: null`; a save returns new `row_id`s.
3. **Codes:** every code is a code object `{group, code}` (+ `description` in reads); `group` is `null` for
   Macola. The tablet stores and sends codes back exactly as given. Statuses are code objects too.
4. **Cause:** optional `cause_code` on every parts row — `null` and unused in v1; `/codelists` `causes` is empty.
   The cause picker appears automatically when the server sends causes.
5. **Backend-neutral names:** `MACOLA_UNAVAILABLE` → `BACKEND_UNAVAILABLE`, `macola_reachable` →
   `backend_reachable`, `macola_open` → `is_open`, `has_inspection_record` → `inspectable`. Same idea applied to
   the photo store: `fileshare_reachable` → `photo_store_reachable`, `FILESHARE_UNAVAILABLE` →
   `PHOTO_STORE_UNAVAILABLE`; header `failure_cd` → `failure_code`.
6. **Summaries:** an ordered list of text blocks with per-block labels and limits from `/codelists`
   `summary_blocks` (Macola: 3 × 4000). Writes send only changed blocks.
7. **Identity:** the reserved sign-in path stays. SAP will need per-inspector identity for its audit trail
   (changed-by, status history) — expect sign-in or a per-inspector SAP user mapping before SAP go-live.
8. **`docs/sap_mapping.md`** maps every API field to its Macola source and the expected SAP object/field, with
   unknowns marked for the SAP team.

## 2026-11-17 — Issue #2 (settings, inspector, logo)
- Official BITZER logo supplied by Jacob → `app/src/main/res/drawable-nodpi/bitzer_logo.png`. Only change: the
  near-black background made transparent; the mark is unaltered. Shown in the black top bar (style guide:
  black or white backgrounds only, proportional scaling).
- The app runs on phones (Android 8.0+) for previewing progress; the tablet stays the target layout.
- Server address is editable in Settings and saved on the device; typed addresses without `http(s)://` get
  `http://` (dev / mock server on the local network). The real server address will be https.
- Inspector: chosen from `GET /inspectors`; one entry → auto-selected; several → "Who's inspecting?".
- Phase 3 backlog = GitHub issues #2–#11.

## 2026-11-17 — Phase 2 scaffolding
- Android: Kotlin + Jetpack Compose, minSdk 26, target/compile SDK 35, package `com.bitzer.rmainspector`.
- Builds run only in GitHub Actions (cloud sessions can't download the Android SDK). APKs are published on a
  GitHub Release named `latest` on every push to `main`; PRs build and upload an artifact.
- Theme follows the BITZER Style Guide (v04/2018): BITZER Green `#3AAA35` (primary), Dark green `#005B23`,
  Green 50/25/10, BITZER Black and grey shades. App font: Source Sans (the guide's digital running-text font)
  where available, system sans-serif fallback. AF Diwa is licensed and not bundled. The BITZER logo is **not**
  drawn or bundled; add the official asset later if wanted.
- Mock server (`mock-server/`, FastAPI) implements `docs/api.md` with fake data only.

## 2026-11-17 — Spec / API round 5 (`docs/spec.md` §9, `docs/api.md` §12)
- Close / reopen are RMA-team functions — not in the app.
- Report photo order follows caption names; reorder options maybe later.
- Work list: filter chips for any inspection status; default Received, Rework in Progress, Inspected, Report Complete.
- No device key / API protection in v1 (internal network only); maybe later with sign-in.
- Changes made elsewhere during an inspection are always reviewed, never overwritten. Change check covers the
  whole RMA record (incl. RMA-team fields) and `Report Pics `; no auto-merge.
- Sent photos stay on the tablet 30 days; evaluate storage later.

## 2026-11-17 — Round 4: report in the app
- The app covers the inspection **and** the report: report-photo selection (copies into `Report Pics `,
  captions editable) and report building (workbench report code, PDF + XLSX), PDF preview, spell check.
- Report photo count is up to the inspector (~20 typical, no limit). Lift the old 20 cap in the shared report module.
- Status after a report build stays manual; automate Report Complete later.
- Credit status, Failure Code, Failure Description, Comments are RMA-team fields — read-only in the app.
- Server creates `Report Pics` (without trailing space) when missing.

## 2026-11-17 — Round 3
- `summary_by` is independent of `inspected_by`.
- RMA numbers are digits only. No barcode/QR in v1.
- Photos: 2048 px / JPEG q85 as a server setting; confirm in the pilot.

## 2026-11-17 — Round 2
- No sign-in in v1: single inspector profile; inspector list (3 now, up to 10) and real sign-in later.
- Tablet may set **any** inspection status.
- Photo naming `SS-n <Part> - <Issue code> <Issue>.jpg`, RMA-level `00-n <Tag>.jpg` (approved).
- Server creates missing RMA / `All Pics` folders.
- Blank / unknown / System-Unit product types: full access. Scroll: read-only.
- Tablet has full control of the parts grid, including desk-entered rows.
- Defect code, summaries 1–3, `inspected_by`, `summary_by` are on the tablet; the workbench is a companion.
