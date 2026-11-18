# Mock RMA app server

Implements `docs/api.md` (rev 7) with **fake data only**, so the tablet app can be built and tested without
BITZER's network. The real server (Phase 4) replaces it and reuses `mock_server/naming.py`.

## Run it
**Windows, no typing:** double-click `run_mock_server.bat`. It installs the requirements, starts the **demo
dataset** (`MOCK_DATASET=demo`), shows this PC's IPv4 address (with a reminder to use the phone's hotspot) and
starts the server on port 8000. Allow it through Windows Firewall (Private networks) when asked.

**Command line:**
```
cd mock-server
pip install -r requirements.txt
python -m pytest -q
python -m uvicorn mock_server.main:app --host 0.0.0.0 --port 8000
```
- Emulator: app default server URL is `http://10.0.2.2:8000/api/v1`.
- A tablet on the same Wi-Fi as the PC: `http://<PC address>:8000/api/v1` (debug builds allow plain HTTP).

## Sign-in (fake demo user)
- `POST /api/v1/auth/login` with `{"username": "demo", "password": "demo"}` → token; send
  `Authorization: Bearer <token>` on every other call. These are **fake** test credentials for the mock only.
  Other users: set `MOCK_USERS` to JSON, e.g. `{"jdoe": {"password": "x", "display_name": "J. Doe", "initials": "JD"}}`.
- There is no token-less bypass: the app signs in like it will against the real server.

## Datasets (`MOCK_DATASET`)
| `MOCK_DATASET` | Used by | Contents |
|---|---|---|
| *(unset)* / anything else | pytest, CI, emulator tests | The test RMAs below (912345 … 912360), empty `checklists`. |
| `demo` | `run_mock_server.bat`, phone demos | 8 curated RMAs on the work list + 1 finished example, sample checklists, real small JPEGs (`mock_server/fake_images.py`). |

Run the demo by hand: `MOCK_DATASET=demo python -m uvicorn mock_server.main:app --host 0.0.0.0 --port 8000`
(Windows cmd: `set MOCK_DATASET=demo` first). `POST /mock/reset` (also `/api/v1/_mock/reset`) restores the dataset
and signs everyone out. Dates are relative to today, so ages stay realistic.

### Demo dataset (`MOCK_DATASET=demo`)
| RMA | What it shows | On work list |
|---|---|---|
| 912364 | Screw, Warranty, **41 days** old (aged) | yes (oldest) |
| 912361 | Recip, Warranty, 24 d, oil trips; misspellings "Compresser", "scorring", "Bearring" in the text | yes |
| 912368 | Scroll, Warranty — read only ("Handled in the repair database") | yes |
| 912366 | Part, Repair & Return | yes |
| 912362 | Recip, Repair & Return, **core-teardown badge**, "Termnal" misspelling | yes |
| 912365 | Screw, Repair & Return ("windng" misspelling) | yes |
| 912363 | Recip, Inspection Only ("Lubercation" misspelling) | yes |
| 912367 | Transport, Warranty (Transport is added to the demo work-list defaults) | yes |
| 912369 | **Finished example**: Report Complete, 5 parts, summary, defect code 07, inspected-by, 7 photos, 4 report photos, built report | no (status) |

`checklists` in `/codelists`: `recip` (07, 48a, 49, 34, 35, 37, 44, 46, 63) and a shorter `screw`
(07, 48a, 34, 35); overall photo always, detail photo when the issue is not `50` Normal.

## Test dataset (default)
Work list defaults (server settings): status **Received**, product types **Recip / Scroll / Screw / Part**,
return codes **Inspection Only / Repair & Return / Warranty**, oldest `status_since` first.

| RMA | What it tests | On default work list |
|---|---|---|
| 912346 | Screw, Warranty, Received 2026-10-08, open elsewhere → `423 RMA_LOCKED` | yes (oldest) |
| 912348 | Scroll, Warranty → read only ("Handled in the repair database") | yes |
| 912345 | Recip, Warranty — happy path (no summary, no photo folder yet) | yes |
| 912352 | Part, Repair & Return, date **estimated**, lock check fails → `503` | yes |
| 912355 | Screw, Repair & Return, core-teardown badge | yes |
| 912357 | Recip, Inspection Only, one part row | yes |
| 912347 | Recip, Inspection Only, Inspected — parts, summary, photos, a report photo | no (status) |
| 912358 | Report Complete — "report already complete" path | no (status) |
| 912359 | Sent to RSM — "report already sent" warning path | no (status) |
| 912353 | Transport, Freight Damage, Report Complete, credit FD (no letter text) | no |
| 912354 | Recip, Core&Inspection, core-teardown badge | no (return code) |
| 912356 | Pressure Vessel, Warranty | no (product type) |
| 912350 | Blank product type (scroll model), blank return code | no |
| 912351 | Blank product type, Core Credit | no |
| 912349 | Closed → `409 RMA_NOT_OPEN` | no |

Serial numbers are `25X0000001` … `25X0000014`.

## Test hooks (mock only)
- `POST /api/v1/_mock/reset`
- `POST /api/v1/_mock/rmas/{rma}/desk-edit` — simulates an edit made elsewhere (workbench, later SAP) → the next
  tablet save gets `409 RMA_CHANGED`. Body (any subset):
  `{"header": {"credit_status": "P - Partial Warranty Credit"}, "summary": {"blocks": {"2": "typed at the desk"}},
  "parts": [{"part_code": {"group": null, "code": "44"}, "issue_code": {"group": null, "code": "46"}, "cause_code": null, "note": ""}],
  "report_pics": ["01-1 Example.jpg"]}`
- `POST /api/v1/_mock/rmas/{rma}/lock` — body `{"locked": true, "held_by": "JDOE2", "possibly_stale": false}`
- `POST /api/v1/_mock/rmas/{rma}/report-in-use` — body `{"in_use": true}`

Behaves like the Macola-era server: code `group` is `null`, `row_id` = `L<n>.<signature>`, no causes,
three 4000-character summary blocks.

Mock differences from the real server: thumbnails are the original bytes; the report PDF is a demo layout with fake wording;
spell check only knows a few sample misspellings.

Report PDF: `POST /report` builds a real demo PDF (`mock_server/report_pdf.py`, ReportLab + Pillow): disposition letter, report page, photo pages from the report photos actually stored.
