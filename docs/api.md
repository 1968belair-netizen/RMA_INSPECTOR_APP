# RMA Inspection App — Tablet ↔ Server API (v1 DRAFT)

_Draft 2026-11-17 (rev 5 — no device key in v1, full-record change check, any-status work list. Rev 4 — adds report-photo selection, report building and spell check; earlier: no
sign-in in v1, any status, folder creation, defect code/summaries on the tablet, failure context read-only,
numeric RMAs). Companion to `docs/spec.md`. The server implements this by importing the RMA Tools
workbench core modules (`rma_tools_core.py`, `rma_flex_core.py`) — **no new Macola SQL**. The mock server
(Phase 2) returns the same shapes with fake data._

> Every value below is a **fake example** (RMA `912345`, customer "Example Refrigeration Co.", host
> `rma-server.example.invalid`). Never put real RMA/customer data, server names, IPs or credentials here.

---

## 1. Conventions

| Item | Rule |
|---|---|
| Base URL | `https://rma-server.example.invalid/api/v1` (real URL set in tablet Settings by IT) |
| Transport | HTTPS only (cert from IT). JSON UTF-8, except photo upload (multipart). |
| Auth (v1) | **None** (internal network only). Writes carry `X-Inspector: <username>` from `GET /inspectors`. Later: device key and/or `Authorization: Bearer <token>` (§3). |
| Client headers | `X-App-Version: 1.0.0`, `X-Device-Id: <uuid>` (random per install, not hardware ID) |
| RMA numbers | Sent and returned **unpadded and trimmed** (`"912345"`). Server pads for writes. |
| Codes | Always **strings** (`"48a"`, `"07"`, `"99 – JSIOC"`). Never numbers. |
| Text | Server returns trimmed values. Line breaks as `\n` (server stores `\r\n`). |
| Times | ISO 8601 with offset (`2026-11-17T10:42:00-05:00`). Audit time is the SQL server's `GETDATE()`. |
| Idempotency | Writes carry a client UUID (`submission_id`, `photo_id`). Repeating one returns the original result with `"replayed": true`. |
| Versioning | Breaking changes → `/api/v2`. Server can demand an app update with `426`. |

### 1.1 Error envelope

All non-2xx responses:

```json
{
  "error": {
    "code": "RMA_LOCKED",
    "message": "RMA 912345 is open in Macola by JDOE. Nothing was saved.",
    "retryable": true,
    "retry_after_seconds": 120,
    "details": { }
  }
}
```

`message` is safe to show the inspector as-is. `details` varies by code (shown per endpoint).

### 1.2 Error codes

| HTTP | `code` | Meaning | Tablet action |
|---|---|---|---|
| 400 | `BAD_REQUEST` | Malformed JSON / missing field | Bug — log, show message |
| 401 | `UNAUTHENTICATED` | Reserved for later sign-in (not returned in v1) | Go to sign-in; keep queue |
| 403 | `UNKNOWN_INSPECTOR` | `X-Inspector` not in the server's inspector list | Re-pick inspector |
| 403 | `FORBIDDEN` | User not allowed | Show message |
| 403 | `PRODUCT_TYPE_NOT_ENABLED` | Capability off for this product type (e.g. scroll) | Read-only banner |
| 404 | `RMA_NOT_FOUND` | No such RMA | Show message |
| 409 | `RMA_CHANGED` | Signature mismatch — someone changed the RMA since it was loaded | Conflict screen. **Never auto-retry.** |
| 409 | `REPORT_PICS_CHANGED` | `Report Pics ` changed (e.g. at the desk) since the tablet loaded it | Reload selection; never auto-retry |
| 409 | `REPORT_FILE_IN_USE` | Existing `<RMA> Report.pdf/.xlsx` is open on someone's PC, can't be replaced | Show message; retry when closed |
| 409 | `RMA_NOT_OPEN` | RMA is closed in Macola, or has no iPro inspection record | Read-only; ask office |
| 413 | `PAYLOAD_TOO_LARGE` | Photo over limit | Re-compress and retry |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Not a JPEG | Bug |
| 422 | `VALIDATION_FAILED` | Unknown code, text too long, bad status transition | Show field errors |
| 423 | `RMA_LOCKED` | Macola lock held by someone (or possibly stale) | Auto-retry with back-off; show holder |
| 426 | `APP_UPDATE_REQUIRED` | App older than `min_app_version` | Show update link |
| 500 | `SERVER_ERROR` | Unexpected; transaction rolled back | Retry later |
| 503 | `LOCK_CHECK_FAILED` | Lock table unreadable — **fail closed**, nothing written | Auto-retry |
| 503 | `MACOLA_UNAVAILABLE` / `FILESHARE_UNAVAILABLE` | SQL or G: unreachable | Auto-retry |
| 500 | `FOLDER_CREATE_FAILED` | Server couldn't create the RMA / `All Pics` folder (e.g. permissions) | Photos stay queued; show message |

---

## 2. Health

### `GET /health` (no auth)

```json
{
  "status": "ok",
  "api_version": "1.0",
  "server_version": "0.1.0",
  "min_app_version": "1.0.0",
  "backend": "macola",
  "macola_reachable": true,
  "fileshare_reachable": true,
  "server_time": "2026-11-17T10:40:00-05:00"
}
```

`backend` becomes `"sap"` after cutover; the tablet ignores it except for display.

---

## 3. Inspectors and auth

### v1 (now): no sign-in

- **No API key or token in v1** (decided 2026-11-17). The server listens only on BITZER's internal network.
  A device key (`X-Device-Key`) and/or real sign-in can be added later; the tablet and endpoints are built so
  adding a header is the only change.
- The tablet acts as an inspector from the server's list and sends `X-Inspector: <username>` on writes. The
  server builds the audit `UserID` (`<USERNAME>_SQL`) itself; the tablet never sends it.

### `GET /inspectors`

```json
{
  "inspectors": [
    { "username": "jdoe", "display_name": "J. Doe", "initials": "JD", "audit_user_id": "JDOE_SQL" }
  ],
  "max_inspectors": 10,
  "sign_in_required": false
}
```

One entry → tablet skips the picker. More than one → "Who's inspecting?" pick list (no password).
`initials` pre-fill `inspected_by` and `summary_by` (each only when empty; the two fields are independent).

### Later: real sign-in (reserved, not built in v1)

When `sign_in_required` becomes `true`, the tablet uses:

- `POST /auth/login` `{ "username": "jdoe", "password": "********", "device_id": "..." }` →
  `{ "token": "...", "expires_at": "...", "user": { <inspector object> } }`
- `POST /auth/logout` → `204`; `GET /me` → the inspector object.
- `Authorization: Bearer <token>` replaces `X-Inspector`. No other endpoint changes.

---

## 4. Code lists

### `GET /codelists`

Served live from `oermactl_ipro_parts`, `_issues`, `_failures`, `oermactl_ipro` plus server config.
Supports `If-None-Match: "<etag>"` → `304 Not Modified`.

Response `200` (lists shortened):
```json
{
  "version": "2026-11-17T06:00:00-05:00",
  "etag": "\"cl-5f2a9c\"",
  "source": "macola",
  "parts": [
    { "code": "01",  "description": "Compressor" },
    { "code": "48",  "description": "Winding to ground insulation" },
    { "code": "48a", "description": "Winding resistance" },
    { "code": "63",  "description": "Piston assy / skirt" },
    { "code": "100", "description": "Oil separator" }
  ],
  "issues": [
    { "code": "50",  "description": "Normal", "pin_first": true },
    { "code": "13",  "description": "Heavy wear" },
    { "code": "32a", "description": "Polished (Light wear)" },
    { "code": "34",  "description": "Scored / Scratched" },
    { "code": "38",  "description": "Ground fault" }
  ],
  "defect_codes": [
    { "code": "01", "description": "Cannot be clearly determined" },
    { "code": "10", "description": "Lack of lubrication" },
    { "code": "29", "description": "--" }
  ],
  "inspection_statuses": [
    "Open", "Entered", "En Route", "Received", "Inspected", "Rework in Progress",
    "Report Complete", "Sent to RSM", "Issued to Customer", "Non-Warranty Processed", "Closed"
  ],
  "status_rule": "any",
  "product_types": [
    { "value": "Recip",           "key": "recip",           "capabilities": { "findings": true,  "summary": true,  "photos": true,  "status": true,  "report": true  } },
    { "value": "Screw",           "key": "screw",           "capabilities": { "findings": true,  "summary": true,  "photos": true,  "status": true,  "report": true  } },
    { "value": "Scroll",          "key": "scroll",          "capabilities": { "findings": false, "summary": false, "photos": false, "status": false, "report": false } },
    { "value": "Part",            "key": "part",            "capabilities": { "findings": true,  "summary": true,  "photos": true,  "status": true,  "report": true  } },
    { "value": "Transport",       "key": "transport",       "capabilities": { "findings": true,  "summary": true,  "photos": true,  "status": true,  "report": true  } },
    { "value": "Pressure Vessel", "key": "pressure_vessel", "capabilities": { "findings": true,  "summary": true,  "photos": true,  "status": true,  "report": true  } },
    { "value": "System/Unit",     "key": "system_unit",     "capabilities": { "findings": true,  "summary": true,  "photos": true,  "status": true,  "report": true  } },
    { "value": "",                "key": "unknown",         "capabilities": { "findings": true,  "summary": true,  "photos": true,  "status": true,  "report": true  } }
  ],
  "hidden": { "parts": [], "issues": [] },
  "photo_tags": ["As received", "Nameplate", "Overview", "Packaging/Freight", "Other"],
  "work_list_statuses": ["Received", "Rework in Progress", "Inspected", "Report Complete"],
  "photo": { "max_edge_px": 2048, "jpeg_quality": 85 },
  "report": { "typical_photo_count": 20, "max_photo_count": null, "auto_status_after_report": null },
  "summary_template": "The primary failure mode is the [ ] of the [ ] likely due to [ ]. Failure progression resulted in [ ]. Common causes for [ ] include [ ]. No defects in materials or workmanship were found with this compressor.",
  "limits": {
    "part_no": 15, "issue_cd": 15, "note": 4000,
    "defect_cd": 10, "summary": 4000, "inspected_by": 255, "summary_by": 255,
    "photo_tag_text": 40, "photo_max_bytes": 10485760
  }
}
```

Notes:
- `defect_codes` (`oermactl_ipro_failures`) feed the General Defect Code picker.
- `status_rule: "any"` = the tablet may set any status in `inspection_statuses` (matches the workbench). The
  field exists so the server can later send a transition table instead without an app update.
- `report.max_photo_count: null` = no limit (the inspector decides; ~20 is typical). `auto_status_after_report`
  is `null` in v1 (status stays manual); later it can be `"Report Complete"`.
- `summary_template` is the standard wording for the "Insert standard wording" button.
- Order in `parts`/`issues` is not meaningful; the tablet natural-sorts and pins `pin_first` items.
- At SAP cutover the same shape carries SAP catalog codes; `source` becomes `"sap"`.
- Per-family checklists (later) will be added as an optional `checklists` object keyed by `product_type_key`.

---

## 5. RMA lookup

### `GET /rmas/search?q=<text>`

`q` is an RMA number (digits only, `^[0-9]{1,8}$`) or a serial. All digits → server tries RMA first, then
failed serial; anything else → serial only.

Response `200`:
```json
{
  "query": "25X0000001",
  "matched_on": "serial",
  "results": [
    {
      "rma": "912345",
      "model": "4FE-28Y",
      "failed_serial_no": "25X0000001",
      "product_type": "Recip",
      "product_type_key": "recip",
      "inspection_status": "Received",
      "macola_open": true,
      "has_inspection_record": true
    }
  ]
}
```

Empty `results` = no match (still `200`).

### `GET /rmas?limit=50` — work list

Without `inspection_status` the server uses its configured default (`work_list_statuses` in `/codelists`:
Received, Rework in Progress, Inspected, Report Complete). The tablet's filter chips can ask for **any**
inspection status, e.g. `?inspection_status=En%20Route,Report%20Complete`.

Response `200`: `{ "results": [ <same item shape as search>, ... ], "generated_at": "..." }`, newest first.
Only Macola-open RMAs with an iPro record.

---

## 6. RMA detail

### `GET /rmas/{rma}`

Response `200`:
```json
{
  "rma": "912345",
  "signature": "sig-3f9d0c7a1b",
  "loaded_at": "2026-11-17T10:41:12-05:00",
  "macola_open": true,
  "has_inspection_record": true,

  "product_type": "Recip",
  "product_type_key": "recip",
  "product_type_source": "header",
  "capabilities": { "findings": true, "summary": true, "photos": true, "status": true, "report": true },

  "header": {
    "customer_name": "Example Refrigeration Co.",
    "model": "4FE-28Y",
    "failed_serial_no": "25X0000001",
    "sales_order_no": "SO-000000",
    "return_code": "Warranty",
    "inspection_status": "Received",
    "credit_status": "",
    "failed_date": "2/17/2026",
    "rma_comment": "Example comment",
    "failure_cd": "9-Oil Issue",
    "failure_description": "Example: low oil pressure trips reported",
    "comments": "Example: customer reports repeated trips"
  },

  "lock": {
    "locked": false,
    "held_by": null,
    "host": null,
    "possibly_stale": false,
    "checked_at": "2026-11-17T10:41:12-05:00"
  },

  "parts": [
    { "line_no": 1, "part_no": "07a", "part_description": "Terminal box",        "issue_cd": "50", "issue_description": "Normal",     "note": "" },
    { "line_no": 2, "part_no": "48a", "part_description": "Winding resistance",  "issue_cd": "50", "issue_description": "Normal",     "note": "Within spec, all 3 phases" }
  ],

  "summary": {
    "exists": true,
    "defect_cd": "10",
    "defect_description": "Lack of lubrication",
    "summary_1": "The primary failure mode is ...",
    "summary_2": "",
    "summary_3": "",
    "inspected_by": "JD",
    "summary_by": "JD"
  },

  "photos": {
    "folder_found": true,
    "folder_display": "912xxx\\9123xx\\912345\\All Pics",
    "count": 2,
    "files": ["00-1 As received.jpg", "00-2 Nameplate.jpg"]
  }
}
```

Notes:
- `signature` is opaque to the tablet. It is the workbench's snapshot signature over the RMA's editable state
  (header fields + parts rows). The tablet stores it with the draft and sends it back as `base_signature`.
- `product_type_source`: `"header"` (from `Product_Type`) or `"model"` (derived from model prefix because the
  header was blank) — see spec §7.
- `lock` is **informational**; the authoritative check happens inside the write.
- `credit_status`, `return_code`, `failure_cd`, `failure_description` (`Failure_Mode`) and `comments` are
  **read-only** on the tablet (failure context). Sending them in a submit → `422 READ_ONLY_FIELD`.
- `summary.exists: false` means the RMA has no `oermahdr_extended_ipro` row yet; all fields are `""` and the
  server INSERTs the row on the first summary save.
- `photos.folder_found: false` is normal — the folder is created on the first photo upload.
- `folder_display` is relative to the picture root; the root path is never sent.

Errors: `404 RMA_NOT_FOUND`. A closed RMA still returns `200` with `macola_open: false` and all capabilities
`false`.

### `GET /rmas/{rma}/lock`

Same `lock` object as above. Used by the "Locked — will retry" screen to show live state.

---

## 7. Submit inspection (parts grid + status)

### `POST /rmas/{rma}/inspection`

The tablet sends the **full desired parts grid** in the inspector's order plus an optional status change.
Existing rows are referenced by the `line_no` they had in the loaded snapshot (`orig_line_no`); new rows have
`orig_line_no: null`. Because `oermactl_ipro_detail` has no key, `orig_line_no` is only meaningful together
with `base_signature`.

Request:
```json
{
  "submission_id": "4d3c2b1a-0000-4000-8000-00000000a001",
  "base_signature": "sig-3f9d0c7a1b",
  "product_type_key": "recip",
  "inspector": "jdoe",
  "captured_at": "2026-11-17T10:55:30-05:00",
  "device_id": "7b1e4c2a-0000-4000-8000-000000000001",

  "status_change": { "from": "Received", "to": "Inspected" },

  "summary": {
    "defect_cd": "10",
    "summary_1": "The primary failure mode is the scoring of the piston skirts likely due to lack of lubrication. ...",
    "inspected_by": "JD",
    "summary_by": "JD"
  },

  "parts": [
    { "orig_line_no": 1,    "part_no": "07a", "issue_cd": "50", "note": "" },
    { "orig_line_no": 2,    "part_no": "48a", "issue_cd": "39", "note": "Phase T1-T2 open" },
    { "orig_line_no": null, "part_no": "63",  "issue_cd": "34", "note": "Cyl 2 skirt scored\nlight on cyl 1" },
    { "orig_line_no": null, "part_no": "67",  "issue_cd": "13", "note": "" }
  ]
}
```

- `status_change` may be `null`. `to` may be any status in `inspection_statuses`; `from` must equal the status
  in the snapshot.
- `summary` may be `null` or contain **only changed fields** (`defect_cd`, `summary_1`–`summary_3`,
  `inspected_by`, `summary_by`). `""` clears a field. Unsent fields are left alone.
- `parts` may be `null` = parts grid unchanged. An empty list `[]` = delete every row (tablet asks to confirm).
- Omitting an existing row from `parts` = delete it. Order of `parts` = new `line_no` 1..n.
- `inspector` must match `X-Inspector` (server checks); the server builds the audit `UserID`
  (`JDOE_SQL`) itself — the tablet never sends it.

Server steps (= `apply_flex` logic): validate → Macola lock check (fail closed) → re-read with UPDLOCK +
signature compare → JSONL backup → status UPDATE + one `OERMAAUD_iPro` row (if status changed) →
summary row UPDATE by `rec_id` or INSERT (raw padded `cus_no`), no audit row → parts DELETE / UPDATE / two-pass renumber / INSERT → commit → re-read and verify. All or nothing.

#### `200 OK` — saved

```json
{
  "result": "saved",
  "submission_id": "4d3c2b1a-0000-4000-8000-00000000a001",
  "replayed": false,
  "rma": "912345",
  "new_signature": "sig-8a1e44d2c9",
  "verified": true,
  "changes": {
    "status": { "from": "Received", "to": "Inspected", "audited": true },
    "summary": { "row": "updated", "fields": ["defect_cd", "summary_1", "inspected_by", "summary_by"] },
    "parts": { "inserted": 2, "updated": 1, "deleted": 0, "renumbered": false }
  },
  "parts": [
    { "line_no": 1, "part_no": "07a", "issue_cd": "50" },
    { "line_no": 2, "part_no": "48a", "issue_cd": "39" },
    { "line_no": 3, "part_no": "63",  "issue_cd": "34" },
    { "line_no": 4, "part_no": "67",  "issue_cd": "13" }
  ],
  "warnings": []
}
```

If the post-commit verify finds a difference, it is still `200` with `"verified": false` and a warning
(the write committed; the office should check it in the workbench):

```json
"warnings": [ { "code": "VERIFY_MISMATCH", "message": "Saved, but the re-read didn't match. Please ask the office to check RMA 912345." } ]
```

A repeated `submission_id` returns the stored result with `"replayed": true` and writes nothing.

#### `409 RMA_CHANGED` — someone changed it since the tablet loaded it

```json
{
  "error": {
    "code": "RMA_CHANGED",
    "message": "Someone changed RMA 912345 after you loaded it. Nothing was saved.",
    "retryable": false,
    "details": {
      "base_signature": "sig-3f9d0c7a1b",
      "current_signature": "sig-77c0b1e5aa",
      "changed": ["inspection_status", "summary", "parts"],
      "current": {
        "inspection_status": "Rework in Progress",
        "summary": {
          "defect_cd": "10", "defect_description": "Lack of lubrication",
          "summary_1": "Typed at the desk ...", "summary_2": "", "summary_3": "",
          "inspected_by": "JD", "summary_by": "JD"
        },
        "parts": [
          { "line_no": 1, "part_no": "07a", "issue_cd": "50", "note": "" },
          { "line_no": 2, "part_no": "48a", "issue_cd": "50", "note": "Within spec, all 3 phases" },
          { "line_no": 3, "part_no": "34",  "issue_cd": "22", "note": "Added at desk" }
        ]
      }
    }
  }
}
```

The tablet uses `details.current` for the conflict screen and "Reload and keep my changes" (spec §3.12), then
resubmits with `base_signature = current_signature` and a **new** `submission_id`.

#### `423 RMA_LOCKED` — open in Macola

```json
{
  "error": {
    "code": "RMA_LOCKED",
    "message": "RMA 912345 is open in Macola by JDOE2 (EXAMPLE-PC). Nothing was saved.",
    "retryable": true,
    "retry_after_seconds": 120,
    "details": {
      "held_by": "JDOE2",
      "host": "EXAMPLE-PC",
      "possibly_stale": false
    }
  }
}
```

Stale lock (session no longer exists): same code, `"possibly_stale": true`, message
"RMA 912345 has a possible stale Macola lock. Nothing was saved. Ask IT to clear it." `held_by`/`host` are
`null` when the server lacks `VIEW SERVER STATE`. The server never clears locks.

#### `503 LOCK_CHECK_FAILED` — fail closed

```json
{
  "error": {
    "code": "LOCK_CHECK_FAILED",
    "message": "Couldn't check the Macola lock, so nothing was saved. Will retry.",
    "retryable": true,
    "retry_after_seconds": 60,
    "details": {}
  }
}
```

#### `422 VALIDATION_FAILED`

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "2 problems — nothing was saved.",
    "retryable": false,
    "details": {
      "fields": [
        { "path": "parts[2].issue_cd", "code": "UNKNOWN_CODE", "message": "Issue code '34b' is not in the issue list." },
        { "path": "summary.summary_2", "code": "TOO_LONG", "limit": 4000, "message": "Summary 2 is 4,212 characters; the limit is 4,000." }
      ]
    }
  }
}
```

Other field codes: `READ_ONLY_FIELD`, `UNKNOWN_STATUS`, `STATUS_FROM_MISMATCH`, `INSPECTOR_MISMATCH` (unknown `defect_cd` uses
`UNKNOWN_CODE`).

#### `409 RMA_NOT_OPEN` / `403 PRODUCT_TYPE_NOT_ENABLED`

```json
{ "error": { "code": "RMA_NOT_OPEN", "message": "RMA 912345 is closed in Macola. Nothing was saved.", "retryable": false, "details": { "macola_open": false, "has_inspection_record": true } } }
```
```json
{ "error": { "code": "PRODUCT_TYPE_NOT_ENABLED", "message": "Scroll inspections are recorded in the Access repair DB.", "retryable": false, "details": { "product_type_key": "scroll", "capability": "findings" } } }
```

---

## 8. Photos

New photos go to `<RMA>\All Pics`. Choosing report photos (§9) copies from there into `Report Pics `. Photo
and report endpoints do **not** need the Macola lock and write nothing to Macola.
The server builds the filename (spec §5) and never overwrites.

### `POST /rmas/{rma}/photos` (multipart/form-data)

Parts:
- `meta` — JSON:
```json
{
  "photo_id": "9e8d7c6b-0000-4000-8000-00000000b003",
  "kind": "part",
  "seq": 3,
  "part_no": "63",
  "issue_cd": "34",
  "tag": null,
  "tag_text": null,
  "captured_at": "2026-11-17T10:49:02-05:00",
  "sha256": "0f1e2d...example",
  "product_type_key": "recip"
}
```
  For an uncoded photo: `"kind": "rma", "seq": 0, "part_no": null, "issue_cd": null, "tag": "Nameplate"`
  (or `"tag": "Other", "tag_text": "Oil sample"`).
- `file` — the JPEG (`image/jpeg`, ≤ `limits.photo_max_bytes`).

Response `201 Created`:
```json
{
  "photo_id": "9e8d7c6b-0000-4000-8000-00000000b003",
  "replayed": false,
  "filename": "03-1 Piston assy, skirt - 34 Scored, Scratched.jpg",
  "folder_display": "912xxx\\9123xx\\912345\\All Pics",
  "folder_created": false,
  "bytes": 612344,
  "sha256_ok": true
}
```

- Same `photo_id` again → `200` with the original `filename` and `"replayed": true`.
- `sha256` mismatch → `400 BAD_REQUEST` (`details.reason: "checksum"`); tablet re-sends.
- Missing folders are **created** (only the missing levels: `<3>xxx`, `<4>xx`, `<RMA>`, `All Pics`);
  `folder_created: true` says so. An existing folder that starts with the RMA number is reused.
- Errors: `403 PRODUCT_TYPE_NOT_ENABLED` (capability `photos`), `413`, `415`, `422` (unknown code / bad tag),
  `500 FOLDER_CREATE_FAILED`, `503 FILESHARE_UNAVAILABLE`.

### `GET /rmas/{rma}/photos`

Both folders, natural-sorted, with a listing signature for `Report Pics ` (used by §9.2).

```json
{
  "folder_found": true,
  "folder_display": "912xxx\\9123xx\\912345",
  "all_pics": [
    { "filename": "00-1 As received.jpg", "bytes": 512000, "modified": "2026-11-17T10:47:00-05:00" },
    { "filename": "03-1 Piston assy, skirt - 34 Scored, Scratched.jpg", "bytes": 612344, "modified": "2026-11-17T10:49:10-05:00" }
  ],
  "report_pics": {
    "folder_found": false,
    "signature": "rp-empty",
    "files": []
  }
}
```

### `GET /rmas/{rma}/photos/{folder}/{filename}?size=thumb|full`

`folder` = `all` or `report`. Returns `image/jpeg`. `thumb` = 320 px long edge (server-made, cached);
`full` = the stored file. Needed for the selection screen, since desk photos aren't on the tablet.
`404` if the file is gone.

---

## 9. Report photos and report

### 9.1 How it works
- The inspector picks **any number** of photos from `All Pics` (~20 is typical; no limit), and edits captions.
- The server **copies** the picked photos into the RMA's `Report Pics ` folder (found with the workbench's
  case/blank-tolerant lookup). Filename of the copy = caption. The original in `All Pics` is never renamed.
- If no `Report Pics` folder exists, the server creates `Report Pics` (no trailing space — Windows can't make
  one); the workbench lookup ignores blanks, so it is found either way.
- Report order = natural order of the `Report Pics ` filenames (how `rma_report.py` works today). The caption's
  number prefix (`03-1 …`) therefore sets the order; editing it moves the photo.
- Removing a photo from the report **moves** its copy to the server's `backups\report_pics\<RMA>\<time>\`;
  nothing is hard-deleted.
- Files already in `Report Pics ` (e.g. picked at the desk) show as selected; they can be renamed or removed
  the same way.

### 9.2 `PUT /rmas/{rma}/report-pics`

Sends the **full desired** `Report Pics ` content. Server works out copy / rename / remove.

Request:
```json
{
  "submission_id": "5a4b3c2d-0000-4000-8000-00000000c001",
  "base_signature": "rp-empty",
  "photos": [
    { "source": "all_pics",    "filename": "00-1 As received.jpg",                              "caption": "00-1 As received" },
    { "source": "all_pics",    "filename": "03-1 Piston assy, skirt - 34 Scored, Scratched.jpg", "caption": "03-1 Piston skirt scoring, cylinder 2" },
    { "source": "report_pics", "filename": "05 Desk photo.jpg",                                 "caption": "05 Desk photo" }
  ]
}
```

- `caption` = new filename without extension; sanitised by the same rules as spec §5.2, ≤ 120 chars, unique.
- A `report_pics` file left out of `photos` is removed (moved to backup).

Response `200`:
```json
{
  "result": "saved",
  "replayed": false,
  "new_signature": "rp-7c21e0",
  "folder_created": true,
  "changes": { "copied": 2, "renamed": 0, "removed": 0, "kept": 1 },
  "report_pics": [
    "00-1 As received.jpg",
    "03-1 Piston skirt scoring, cylinder 2.jpg",
    "05 Desk photo.jpg"
  ]
}
```

Errors:
- `409 REPORT_PICS_CHANGED` — `details.current` = current file list + `current_signature`. Tablet reloads.
- `422 VALIDATION_FAILED` — field codes `CAPTION_EMPTY`, `CAPTION_DUPLICATE`, `CAPTION_TOO_LONG`,
  `SOURCE_NOT_FOUND` (file vanished from `All Pics`).
- `403 PRODUCT_TYPE_NOT_ENABLED` (capability `report`), `503 FILESHARE_UNAVAILABLE`.

### 9.3 `POST /rmas/{rma}/report` — build the report

Runs the workbench's report code (`rma_report.py`, `rma_report_xlsx.py`) against **current Macola data** and
the current `Report Pics `. The tablet must have no unsent findings for this RMA (it blocks the button
otherwise). Existing report files are copied to `backups\` first, as the workbench does. No status change in
v1.

Request:
```json
{ "submission_id": "6b5c4d3e-0000-4000-8000-00000000d001", "formats": ["pdf", "xlsx"] }
```

Response `200`:
```json
{
  "result": "built",
  "replayed": false,
  "generated_at": "2026-11-17T11:20:05-05:00",
  "files": [
    { "format": "pdf",  "filename": "912345 Report.pdf",  "bytes": 1543000, "pages": 6 },
    { "format": "xlsx", "filename": "912345 Report.xlsx", "bytes": 88000 }
  ],
  "photo_count": 3,
  "previous_backed_up": false,
  "status_changed": null,
  "warnings": [
    { "code": "NO_CREDIT_STATUS", "message": "No credit status yet — page 1 says \"Disposition Pending\"." }
  ]
}
```

Other warning codes: `NO_LETTER_TEXT` (credit status FD/NC/NR/RC has no disposition letter text),
`NO_REPORT_PICS`, `UNKNOWN_CODE_ROW` (a parts row's code isn't in a lookup table; row still printed),
`EMPTY_SUMMARY`.

Errors: `409 RMA_NOT_OPEN` (report needs an open RMA with an inspection record), `409 REPORT_FILE_IN_USE`
(`details.filename`), `403 PRODUCT_TYPE_NOT_ENABLED` (capability `report`), `503 MACOLA_UNAVAILABLE` /
`FILESHARE_UNAVAILABLE`, `500 SERVER_ERROR` (generator failed; old files untouched).

Building can take several seconds; the tablet waits up to 120 s with a progress indicator.

### 9.4 `GET /rmas/{rma}/report`

```json
{ "exists": true, "generated_at": "2026-11-17T11:20:05-05:00", "files": [ { "format": "pdf", "filename": "912345 Report.pdf", "bytes": 1543000 } ] }
```

### 9.5 `GET /rmas/{rma}/report/pdf`

Returns `application/pdf` for preview on the tablet (Android PDF viewer). `404` if no report exists.

### 9.6 `POST /spellcheck`

The workbench's spell check (`rma_spell.py`: trade-word list, skips CAPITALS, words next to digits, etc.) for
summaries and notes.

Request: `{ "text": "The primary failure mode is the scorring of the piston skirts" }`

Response `200`:
```json
{ "issues": [ { "word": "scorring", "start": 32, "end": 40, "suggestions": ["scoring"] } ] }
```

"Add to dictionary" is a desk action in v1 (the dictionary file lives with the workbench).

---

## 10. Endpoint summary

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Reachability, versions, `min_app_version` |
| GET | `/inspectors` | Inspector list (v1: one entry), initials, `sign_in_required` |
| POST | `/auth/login` · `/auth/logout`, GET `/me` | **Reserved** for later sign-in |
| GET | `/codelists` | Parts, issues, defect codes, statuses, product types + capabilities, report settings, summary template, hidden list, limits |
| GET | `/rmas/search?q=` | Lookup by RMA no. or serial |
| GET | `/rmas?inspection_status=` | Work list |
| GET | `/rmas/{rma}` | Header, product type, capabilities, lock, parts grid, summary row, photo list, signature |
| GET | `/rmas/{rma}/lock` | Live lock state |
| POST | `/rmas/{rma}/inspection` | Write parts grid + summary row + optional status (all or nothing) |
| POST | `/rmas/{rma}/photos` | File one photo into `All Pics` (creates folders if missing) |
| GET | `/rmas/{rma}/photos` | List `All Pics` and `Report Pics ` |
| GET | `/rmas/{rma}/photos/{folder}/{filename}` | Thumbnail or full image |
| PUT | `/rmas/{rma}/report-pics` | Set report photos + captions (copy / rename / remove) |
| POST | `/rmas/{rma}/report` | Build `<RMA> Report.pdf` + `.xlsx` |
| GET | `/rmas/{rma}/report` · `/report/pdf` | Report info / PDF preview |
| POST | `/spellcheck` | Spell check text |

Read-only on the tablet: failure code, failure description, comments, credit status, return code (RMA team
fields).
Not in the API: close/reopen (RMA-team functions, not for the inspector).

---

## 11. Server-side notes (for the Phase 4 build)

- Keep a small local store (SQLite, server-side, git-ignored) of `submission_id` / `photo_id` → result for
  replay, retained ≥ 30 days.
- JSONL backups go to the existing `backups\` convention (contains customer data → git-ignored). Report-photo
  removals and replaced report files go there too.
- Write connection autocommit off; lock-check connection autocommit on (as the workbench does).
- **Lift the 20-picture cap** in the shared report module (`rma_report.py`, and the XLSX copy if it has one) —
  a change in the workbench repo that the desk benefits from too. Layout stays 2 across × 3 down; extra
  photos add pages.
- Report generation runs in-process (ReportLab/openpyxl) — no `subprocess`, no Excel automation.
- No `subprocess.run()`; folder-style packaging; runs as a service with auto-restart.
- Server log records user, RMA, endpoint, result code — never note text or photo bytes.

---

## 12. Change-check rule (decided 2026-11-17)

Any change made elsewhere while an inspection is in progress is **reviewed on the tablet, never overwritten**:
- `signature` covers the **whole RMA record** the server loads: iPro header fields (including RMA-team fields
  the tablet can't edit — Comments, Failure Code/Description, credit status, return code), the summary row and
  the parts rows. `Report Pics ` has its own signature (§9.2).
- Any mismatch → `409 RMA_CHANGED` / `409 REPORT_PICS_CHANGED`. **No auto-merge**, even when the two sides
  changed different fields. `details.changed` lists every changed field so the tablet can show it.
- Phase 4 task: confirm the workbench's existing signature covers all of the above; extend it in the shared
  module if not.

## 13. Open questions (API-specific)

1. **Serial search** — should it also search `rma_cmt` (non-BITZER serials) like the Serial Lookup tool does?
2. **HTTPS certificate** — internal CA trusted by the tablets (Intune), or a public cert?
3. **Work-list sort** — "newest first" by `last_act_dt` right?
