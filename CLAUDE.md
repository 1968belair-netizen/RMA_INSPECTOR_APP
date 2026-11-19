# CLAUDE.md — BITZER Inspect (formerly "RMA Inspector App")

BITZER Inspect: BITZER US RMA inspection tablet app: native Android (Kotlin / Jetpack Compose) + a Python app server that
reuses the RMA Tools workbench core modules. The app covers the whole inspection **and** report process.

## Read first
- `docs/spec.md` — screens, behaviour, photo naming (source of truth for the app)
- `docs/api.md` — tablet ↔ server API with example JSON (source of truth for the contract)
- `docs/decisions.md` — what was decided and when
- Planning lives in the claude.ai project "RMA Inspection app" (dev plan, RMA_DATA_MAP, context pack).
  If code needs a decision the docs don't cover, stop and ask; record the answer in `docs/decisions.md`.

## The owner does not hand-code
Jacob reviews and tests; Claude Code writes all code. When telling him to do something, give plain numbered
steps and exact commands. Prefer whole-file changes over fragments.

## Hard rules
1. **No real data in this repo or in cloud sessions**: no real RMA numbers, customer names, serials, photos,
   server names, IPs, paths to BITZER shares, or credentials. Examples use fake RMA `912345`, host
   `rma-server.example.invalid`, "Example Refrigeration Co.". `.gitignore` blocks data/backups/images — keep it.
2. **Never write new SQL for Macola.** The real server imports the workbench modules (`rma_tools_core.py`,
   `rma_flex_core.py`, `rma_report.py`, `rma_spell.py`). That work happens **locally** on BITZER's network,
   not in cloud sessions.
3. **Never silently overwrite.** Conflicts (`409 RMA_CHANGED`, `409 REPORT_PICS_CHANGED`) are always shown to
   the inspector for review. Locks fail closed.
4. Codes are **text** (`48a`, `07`, `99 – JSIOC`), never numbers; display in natural sort order.
5. Python on BITZER machines: no `subprocess.run()` (SentinelOne); folder-style packaging, not one-file exes.
6. Free tools only.

## Layout
```
app/            Android app (Kotlin, Compose)  — package com.bitzer.rmainspector
mock-server/    FastAPI mock of docs/api.md with fake data (used by the app in dev and in CI)
docs/           spec, api, decisions
.github/        Actions: APK build/release, mock-server tests
```
The real app server (`server/`) is added later in a local session (Phase 4).

## Building and testing
- Cloud sessions cannot download the Android SDK or Maven/Google repos — **do not try to build the app
  locally in a cloud session**. Push a branch; GitHub Actions builds, runs unit tests and uploads the APK.
  Check results with `gh run list` / `gh run view --log-failed`.
- Mock server (works anywhere Python + pip work):
  ```
  cd mock-server
  pip install -r requirements.txt
  python -m pytest -q
  python -m uvicorn mock_server.main:app --host 0.0.0.0 --port 8000
  ```
- The Android emulator reaches a mock server on the host at `http://10.0.2.2:8000/api/v1`.

## Workflow (decided 2026-11-18)
- GitHub milestones **A Navigate · B Inspect · C Report · D Extras**; label `server` = real-server work done
  **locally** on Jacob's laptop (never in a cloud session).
- One issue → one branch (from the latest `main`) → PR using the template (3-line **Summary**) → Actions green.
- **Standing approval:** merge (squash) when Actions is green **and** every acceptance item in the issue is met.
- Branch from `main` only after the PRs you depend on are merged.
- A decision the docs don't cover → add it to `docs/questions.md` (with a recommended option), keep working on
  other issues. Record answered decisions in `docs/decisions.md`.
- Commit messages: short imperative summary line.
- **Commit and push at least after every meaningful step**, so a usage-limit pause never strands work locally.
- **Before stopping, or when told a limit is near**, write a short "where I left off" comment on the issue: what's
  done, the next step, and the branch name.
- **When resuming**, read the issue's latest comment first.

### Rapid development mode (decided 2026-11-18, overrides the rules above where they differ)
1. Keep **3 issues in progress at all times**. Start work that doesn't depend on unfinished issues (e.g. milestone C
   Report tab against the mock while B runs); only work that truly depends on another issue waits for it.
2. **Don't pause between milestones.** Post a short milestone report and keep going; Jacob tests several milestones
   together on his phone.
3. **Emulator tests only on PRs that change navigation, sign-in, submit or the upload queue** (CI decides from the
   changed paths; label a PR `emulator` to force). Every PR still runs unit and screenshot tests.
4. **Implementation threads use Sonnet**; switch a thread to Opus only when it's stuck after two attempts.
5. **PR summaries: 3 lines max.** Milestone reports are the only detailed reports.
6. **Never wait on Jacob.** Undecided items go to `docs/questions.md` with the recommended option already built;
   keep working.
7. **Stop only for:** a failed check you can't fix after two attempts, a change that would break a hard rule above,
   or a usage limit (leave a "where I left off" comment on the issue).

## Screenshot tests (every PR)
- Roborazzi on Robolectric, JVM only: `app/src/test/java/com/bitzer/rmainspector/screenshots/`.
- Add a screen state: put a `@Test fun name() = shot("name") { ... }` in `ScreenCatalog` using a **stateless**
  composable (`XxxContent(state, callbacks)`) and fake data from `ScreenshotData` — tablet (1280×800 landscape) and
  phone (411×891 portrait) variants are produced automatically.
- CI runs `./gradlew assembleDebug recordRoborazziDebug`, publishes PNGs to the `screenshots` branch and comments on
  the PR with the screens that changed versus `main` plus the PR's Summary lines.
- Emulator tests: `app/src/androidTest/` (Compose UI tests), run in Actions on an API 34 tablet emulator against the
  mock server at `10.0.2.2:8000` (`connectedDebugAndroidTest`). PRs only, and only when the PR changes navigation, sign-in, submit, the upload
  queue, the emulator tests or the workflow (job `changes` decides; label `emulator` forces). Keep them few and focused.

## Style
- UI follows the BITZER Style Guide: BITZER Green `#3AAA35` primary, Dark green `#005B23`, greys; headlines in
  capitals; Source Sans / sans-serif. Logo: use only the official asset `res/drawable-nodpi/bitzer_logo.png`, on black or
  white backgrounds, scaled proportionally; never redraw or alter it.
- Large touch targets (shop floor, gloves): min 56 dp for primary actions.
