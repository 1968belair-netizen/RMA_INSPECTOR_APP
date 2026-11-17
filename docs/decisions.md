# Decisions log

Newest first. Each entry: date, decision, where it is reflected. Planning happens in the claude.ai project
"RMA Inspection app"; this file is the repo's copy of what was decided.

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
