# Open questions for Jacob

Decisions the docs don't cover. Each entry: date, issue, the question, options with a recommendation, and what
was done meanwhile (work continues on other issues). Jacob answers here or in chat; answered items move to
`docs/decisions.md`.

| # | Date | Issue | Question | Options (recommended first) | Meanwhile |
|---|---|---|---|---|---|
| 7 | 2026-11-18 | #5 | How should rows be reordered on the shop floor (gloves)? | **Recommended:** both — a drag handle on each row **and** Move up / Move down buttons in the part sheet (56 dp). Alt: drag only (spec wording). | Built both (`FindingsTab.kt`). |
| 8 | 2026-11-18 | #5 | Drafts belong to the inspector who made them. What does **another** inspector see when opening an RMA that has someone else's draft on this tablet? | **Recommended:** the server's rows (their own, separate draft), plus — in a later issue — an info banner "<name> has an unsent draft of this RMA on this tablet". Alt: block editing until that draft is sent or discarded. | Each inspector has their own draft (`DraftStore`, one file per inspector + RMA); no banner yet. |
| 9 | 2026-11-18 | #5 | Manual "Add part" defaults: should the issue start as `50` Normal? | **Recommended:** no default in manual mode (guided mode defaults to `50`); `50` is listed first. A new row left completely empty is dropped when its sheet is closed, and the draft is removed automatically when the rows match the server again. Alt: default new rows to `50` Normal. | Built as recommended. |
| 10 | 2026-11-18 | #5 | When an RMA with a draft is opened and the server's signature differs from the draft's `base_signature`, when is the inspector told? | **Recommended:** straight away — the "changed elsewhere" banner shows on open (the draft is kept, nothing overwritten); the full review comes with Submit / conflicts (B3/B4). Alt: only at submit (`409 RMA_CHANGED`). | Banner shown on open. |
| 11 | 2026-11-18 | #19 | How should a scan fill the search box? | **Recommended:** barcode / QR reads automatically (first usable line, trimmed, "S/N:" style label removed) and runs the search; serial plates need a **Capture** tap, then the inspector picks one recognised line (only lines with a digit and 4+ characters are offered). Alt: always auto-run on the first plate line. | Built as recommended (`ScanParsing`, `ScannerScreen`). |
| 12 | 2026-11-18 | #45 | Should the demo report PDF show the official BITZER logo? | **Recommended:** yes, on a white background, using only the official asset already in the repo (`bitzer_logo.png`, scaled proportionally); text header if the file is missing. Alt: text-only header. | Built as recommended (`report_pdf.py`). |

_#1–6 answered 2026-11-18 — see `docs/decisions.md`. Open: #7–10 (B1), #11 (D1 camera search), #12 (report logo)._
