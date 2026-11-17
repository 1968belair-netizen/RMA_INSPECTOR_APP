# Mock RMA app server

Implements `docs/api.md` with **fake data only**, so the tablet app can be built and tested without
BITZER's network. The real server (Phase 4) replaces it and reuses `mock_server/naming.py`.

## Run it
```
cd mock-server
pip install -r requirements.txt
python -m pytest -q
python -m uvicorn mock_server.main:app --host 0.0.0.0 --port 8000
```
- Emulator: app default server URL is `http://10.0.2.2:8000/api/v1`.
- A tablet on the same Wi-Fi as the PC: `http://<PC address>:8000/api/v1` (debug builds allow plain HTTP).

## Fake RMAs
| RMA | What it tests |
|---|---|
| 912345 | Recip, Received — happy path (no summary row yet, no photo folder yet) |
| 912346 | Screw — open in Macola by another user → `423 RMA_LOCKED` |
| 912347 | Recip, Inspected — parts, summary, photos and one report photo already there |
| 912348 | Scroll → read-only (`403 PRODUCT_TYPE_NOT_ENABLED`) |
| 912349 | Closed in Macola → `409 RMA_NOT_OPEN` |
| 912350 | Blank product type, scroll model → derived scroll |
| 912351 | Blank product type, unknown model → full access |
| 912352 | Lock table unreadable → `503 LOCK_CHECK_FAILED` |
| 912353 | Transport, Report Complete, credit status FD (no letter text warning) |

Serial numbers are `25X0000001` … `25X0000008`. Inspector: `jdoe` (send `X-Inspector: jdoe` on writes).

## Test hooks (mock only)
- `POST /api/v1/_mock/reset`
- `POST /api/v1/_mock/rmas/{rma}/desk-edit` — body `{"header": {...}, "summary": {...}, "parts": [...], "report_pics": [...]}`
  simulates an edit at the workbench → the next tablet save gets `409`.
- `POST /api/v1/_mock/rmas/{rma}/lock` — body `{"locked": true, "held_by": "JDOE2", "possibly_stale": false}`
- `POST /api/v1/_mock/rmas/{rma}/report-in-use` — body `{"in_use": true}`

Mock differences from the real server: thumbnails are the original bytes; the report PDF is a placeholder;
spell check only knows a few sample misspellings.
