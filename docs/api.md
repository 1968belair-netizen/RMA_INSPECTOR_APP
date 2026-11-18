# RMA Inspection App — Tablet ↔ Server API (v1 DRAFT)

_Rev 7 — 2026-11-18: **sign-in required** (username + password → token; replaces the inspector picker), **work
list = Received only**, filtered by product type and return code (server settings, oldest first), `status_since` +
estimated flag and badges on list items, server-served guided checklists (empty in v1), report build sets Report
Complete the first time, add-to-dictionary, photo ↔ part association. Rev 6 — 2026-11-17: **SAP-ready contract.** Opaque RMA and row identities, code objects with an optional
`group`, optional `cause_code`, backend-neutral names, summaries as ordered text blocks (v1: one "Report summary" block). Rev 5: no device key
in v1, full-record change check, any-status work list. Rev 4: report-photo selection, report building, spell
check. Companion to `docs/spec.md`; field-by-field backend mapping in `docs/sap_mapping.md`._

The server is the only part that knows the backend. Today it imports the RMA Tools workbench core modules
(`rma_tools_core.py`, `rma_flex_core.py`) — **no new Macola SQL**. At SAP cutover the server swaps its backend
connector; **this contract and the tablet stay the same.** The mock server returns the same shapes with fake data.

> Every value below is a **fake example** (RMA `912345`, customer "Example Refrigeration Co.", host
> `rma-server.example.invalid`). Never put real RMA/customer data, server names, IPs or credentials here.

---

## 1. Conventions

| Item | Rule |
|---|---|
| Base URL | `https://rma-server.example.invalid/api/v1` (real URL set in tablet Settings) |
| Transport | HTTPS in production. JSON UTF-8, except photo upload (multipart). |
| Auth | **Sign-in required** (§3): `Authorization: Bearer <token>` on every call except `GET /health` and `POST /auth/login`. Identity for audit comes from the token — the tablet never sends a user id. |
| Client headers | `X-App-Version: 0.1.12`, `X-Device-Id: <uuid>` (random per install, not hardware ID) |
| **RMA identity** | Every RMA has an opaque **`rma_id`** (used in URLs, URL-encoded) and a **`display_number`** (shown to people). The tablet never parses, pads, validates or builds either one — the server does. Macola: both are the unpadded RMA number. SAP: `rma_id` = whatever key the server uses (e.g. notification number), `display_number` = what people call the RMA. |
| **Codes** | Every code is a **code object** `{"group": …, "code": …}` plus `description` when the server sends it. `group` is `null` for Macola; SAP catalog codes use code group + code. Codes are **strings** (`"48a"`, `"07"`, `"99 – JSIOC"`), never numbers. The tablet **stores and sends codes back exactly as given** (same `group`, same `code`, same case/spaces) and never invents or reformats one. |
| **Row identity** | Parts rows have an opaque server-assigned **`row_id`**. The tablet only echoes it back. Macola: line number + record signature; SAP: notification item number. |
| Text | Server returns trimmed values. Line breaks as `\n` (server converts for the backend). |
| Times | ISO 8601 with offset (`2026-11-17T10:42:00-05:00`). Audit time comes from the backend, not the tablet. |
| Idempotency | Writes carry a client UUID (`submission_id`, `photo_id`). Repeating one returns the original result with `"replayed": true`. |
| Versioning | Breaking changes → `/api/v2`. `GET /health` → `min_app_version`; the app shows "update required" below it. |

### 1.1 Code object

```json
{ "group": null, "code": "48a", "description": "Winding resistance" }
```
- In **lists and reads** the server includes `description`.
- In **writes** the tablet sends `{ "group": …, "code": … }` copied from what it was given; `description` is
  ignored if present.
- Matching is exact on the pair (`group`, `code`). Display order: natural sort on `group`, then `code`
  (`48` < `48a` < `100`).
- SAP example (shape only — real catalogs come from the SAP team): `{ "group": "COMP-MECH", "code": "0063", "description": "Piston assy / skirt" }`.

### 1.2 Error envelope

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

`message` is safe to show the inspector as-is (it may name the backend). `details` varies by code.

### 1.3 Error codes

| HTTP | `code` | Meaning | Tablet action |
|---|---|---|---|
| 400 | `BAD_REQUEST` | Malformed JSON / missing field / bad checksum | Bug or re-send |
| 401 | `UNAUTHENTICATED` | Missing, unknown or signed-out token | Go to sign-in; keep the upload queue |
| 401 | `INVALID_CREDENTIALS` | Wrong username or password (`/auth/login` only) | Show on the sign-in screen |
| 403 | `PRODUCT_TYPE_NOT_ENABLED` | Capability off for this product type (e.g. scroll) | Read-only banner |
| 404 | `RMA_NOT_FOUND` / `PHOTO_NOT_FOUND` / `REPORT_NOT_FOUND` | No such item | Show message |
| 409 | `RMA_CHANGED` | Record changed since it was loaded | Conflict screen. **Never auto-retry.** |
| 409 | `REPORT_PICS_CHANGED` | `Report Pics ` changed since loaded | Reload selection; never auto-retry |
| 409 | `REPORT_FILE_IN_USE` | Existing report file is open on someone's PC | Show message; retry when closed |
| 409 | `RMA_NOT_OPEN` | RMA is not open (`is_open: false`) or not inspectable (`inspectable: false`) | Read-only; ask the RMA team |
| 413 | `PAYLOAD_TOO_LARGE` | Photo over limit | Re-compress and retry |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | Not a JPEG | Bug |
| 422 | `VALIDATION_FAILED` | Unknown code, unknown row, text too long, read-only field… | Show field errors |
| 423 | `RMA_LOCKED` | Record locked in the backend by someone else (or possibly stale) | Auto-retry with back-off; show holder |
| 500 | `SERVER_ERROR` / `FOLDER_CREATE_FAILED` | Unexpected; nothing half-written | Retry later |
| 503 | `LOCK_CHECK_FAILED` | Lock state unreadable — **fail closed**, nothing written | Auto-retry |
| 503 | `BACKEND_UNAVAILABLE` | ERP backend unreachable (Macola now, SAP later) | Auto-retry |
| 503 | `PHOTO_STORE_UNAVAILABLE` | Photo storage unreachable (G: now, SharePoint/SAP later) | Auto-retry |

---

## 2. Health

### `GET /health` (no auth)

```json
{
  "status": "ok",
  "api_version": "1.0",
  "server_version": "0.1.0",
  "min_app_version": "0.1.0",
  "backend": "macola",
  "backend_reachable": true,
  "photo_store_reachable": true,
  "server_time": "2026-11-17T10:40:00-05:00"
}
```

`backend` is `"macola"`, `"sap"` or `"mock"` — display only; the tablet has no backend-specific logic.

---

## 3. Sign-in and identity (decision 2026-11-18)

- The **first screen is sign-in**: username + password, checked by the server. It replaces the inspector picker.
- **v1 credentials are a server setting** (a small user list on the app server; mock: a fake demo user). No
  credentials live in the app or the repo. Real directory sign-in (AD/Entra) can replace the server's check later
  without changing this contract.
- The app **stays signed in until the user signs out** (`expires_at: null`); the server may still revoke a token,
  which the app sees as `401` → back to sign-in, queued work kept.
- **All sign-ins have all functions in v1** (`roles: ["all"]`); roles come later.
- The server maps the signed-in user to the backend audit user (`audit_user_id`; Macola: `<USERNAME>_SQL`).

### `POST /auth/login` (no auth)
Request: `{ "username": "jdoe", "password": "********", "device_id": "7b1e4c2a-…" }`

Response `200`:
```json
{
  "token": "opaque-token-example",
  "expires_at": null,
  "user": { "username": "jdoe", "display_name": "J. Doe", "initials": "JD", "audit_user_id": "JDOE_SQL", "roles": ["all"] }
}
```
Errors: `401 INVALID_CREDENTIALS` ("Wrong username or password.").

### `POST /auth/logout` → `204` · `GET /me` → the `user` object

The token is stored in the app's encrypted storage. `initials` pre-fill Inspected by / Summary by.

> **SAP note:** SAP needs **per-inspector identity** for its audit trail (changed-by, status history). Sign-in gives
> the server a real user per request; how the server authenticates to SAP per user (principal propagation, OAuth,
> named technical users) is still open for BITZER IT/SAP (`docs/sap_mapping.md`).

`GET /inspectors` (rev 5–6) is **removed**; the mock keeps it only until the app's sign-in screen ships.

---

## 4. Code lists

### `GET /codelists`

Served live from the backend (Macola lookup tables today; SAP catalogs and status profile later) plus server
config. Supports `If-None-Match: "<etag>"` → `304 Not Modified`.

Response `200` (lists shortened):
```json
{
  "version": "2026-11-17T06:00:00-05:00",
  "etag": "\"cl-5f2a9c\"",
  "source": "macola",
  "parts": [
    { "group": null, "code": "01",  "description": "Compressor" },
    { "group": null, "code": "48a", "description": "Winding resistance" },
    { "group": null, "code": "63",  "description": "Piston assy / skirt" },
    { "group": null, "code": "100", "description": "Oil separator" }
  ],
  "issues": [
    { "group": null, "code": "50",  "description": "Normal", "pin_first": true },
    { "group": null, "code": "13",  "description": "Heavy wear" },
    { "group": null, "code": "34",  "description": "Scored / Scratched" }
  ],
  "causes": [],
  "defect_codes": [
    { "group": null, "code": "10", "description": "Lack of lubrication" }
  ],
  "inspection_statuses": [
    { "group": null, "code": "Received",           "description": "Received" },
    { "group": null, "code": "Inspected",          "description": "Inspected" },
    { "group": null, "code": "Rework in Progress", "description": "Rework in Progress" },
    { "group": null, "code": "Report Complete",    "description": "Report Complete" }
  ],
  "status_rule": "any",
  "summary_blocks": [
    { "block_id": "1", "label": "Report summary", "max_length": 4000 }
  ],
  "product_types": [
    { "value": "Recip",  "key": "recip",   "capabilities": { "findings": true,  "summary": true,  "photos": true,  "status": true,  "report": true  } },
    { "value": "Scroll", "key": "scroll",  "capabilities": { "findings": false, "summary": false, "photos": false, "status": false, "report": false } },
    { "value": "",       "key": "unknown", "capabilities": { "findings": true,  "summary": true,  "photos": true,  "status": true,  "report": true  } }
  ],
  "hidden": { "parts": [], "issues": [], "causes": [] },
  "photo_tags": ["As received", "Nameplate", "Overview", "Packaging/Freight", "Other"],
  "return_codes": [
    { "group": null, "code": "Warranty", "description": "Warranty" },
    { "group": null, "code": "Inspection Only", "description": "Inspection Only" },
    { "group": null, "code": "Core&Inspection", "description": "Core&Inspection" }
  ],
  "work_list_statuses":      [ { "group": null, "code": "Received" } ],
  "work_list_product_types": [ "Recip", "Scroll", "Screw", "Part" ],
  "work_list_return_codes":  [ { "group": null, "code": "Inspection Only" }, { "group": null, "code": "Repair & Return" },
                               { "group": null, "code": "Warranty" } ],
  "status_roles": { "inspected": "Inspected", "report_complete": "Report Complete",
                    "report_sent": ["Sent to RSM", "Issued to Customer"] },
  "normal_issue_codes": [ { "group": null, "code": "50" } ],
  "checklists": {},
  "photo": { "max_edge_px": 2048, "jpeg_quality": 85 },
  "report": { "typical_photo_count": 20, "max_photo_count": null, "photos_per_page": 6, "fixed_pages": 2,
              "auto_status_after_report": "Report Complete" },
  "summary_template": "The primary failure mode is the [ ] of the [ ] likely due to [ ]. ...",
  "limits": { "note": 4000, "inspected_by": 255, "summary_by": 255, "photo_tag_text": 40, "photo_max_bytes": 10485760 }
}
```

Notes:
- **Every code list is a list of code objects** (§1.1). `parts` = where on the unit (SAP: defect location /
  object part), `issues` = what is wrong (SAP: defect type), `causes` = why (SAP: cause catalog),
  `defect_codes` = General Defect Code for the whole RMA, `inspection_statuses` = workflow status
  (Macola: the status text is the code; SAP: user status).
- **`causes` is empty in v1** (Macola has no cause per row). The tablet hides the cause picker while the list
  is empty and shows it automatically when the server sends causes.
- **`summary_blocks`** defines how many summary text blocks exist, their labels and per-block limits
  (v1: **one** "Report summary" block, ≤ 4000; Macola stores it in `summary_1`). The tablet renders whatever
  list it gets, so more blocks — or SAP's long-text layout — need no app change.
- `status_rule: "any"` = any status in `inspection_statuses` may be set (matches the workbench). The field
  exists so the server can later send a transition table without an app update.
- `hidden` lists code objects not offered in pickers (empty in v1); hidden codes still display on existing rows.
- **Work list settings** (decision 2026-11-18): `work_list_statuses` (v1: Received only — not a chip),
  `work_list_product_types` and `work_list_return_codes` are the **defaults** for the work-list filter chips; the
  chips offer every value in `product_types` and `return_codes`. The server matches them **trimmed and
  uppercase** (Macola `Return_Code` is CHAR(15)). All are server settings, so they can change at the SAP rollout
  without an app update. Core&Inspection is not a default.
- `status_roles` tells the app which statuses mean "inspected", "report complete" and "report already sent", for
  the submit and report dialogs (spec §6). Order of `inspection_statuses` = workflow order.
- `normal_issue_codes`: the issue code(s) meaning "normal" (Macola: `50`). Guided steps default to it; checklist
  photo rules use it.
- **`checklists`** (empty in v1): guided inspection lists keyed by `product_type_key`. Shape:
  ```json
  "checklists": {
    "recip": {
      "version": "2026-12-01",
      "steps": [
        { "part_code": { "group": null, "code": "63" }, "required": true,
          "photos": [
            { "slot": "overall", "label": "Overall", "required_when": "always" },
            { "slot": "detail",  "label": "Detail",  "required_when": "non_normal" }
          ] }
      ]
    }
  }
  ```
  `required_when`: `always` · `non_normal` (issue not in `normal_issue_codes`) · `never` (optional). Planned grid:
  required part + `50` Normal → overall photo required; non-normal → overall + detail required. Requirements apply
  only in guided mode.
- `report.max_photo_count: null` = no limit (~20 typical). Pages ≈ `fixed_pages` + ⌈photos ÷ `photos_per_page`⌉.
  `auto_status_after_report: "Report Complete"` — the first successful build sets it (§9.3).
- `summary_template` fills the first summary block via "Insert standard wording".
- At SAP cutover the same shape carries SAP catalog codes; `source` becomes `"sap"`.

---

## 5. RMA lookup

### `GET /rmas/search?q=<text>`

`q` is **whatever the inspector typed** (RMA number, serial, later maybe a notification number). The tablet does
not validate it beyond trimming; the server decides what it matches.

Response `200`:
```json
{
  "query": "25X0000001",
  "matched_on": "serial",
  "results": [
    {
      "rma_id": "912345",
      "display_number": "912345",
      "model": "4FE-28Y",
      "failed_serial_no": "25X0000001",
      "product_type": "Recip",
      "product_type_key": "recip",
      "return_code": { "group": null, "code": "Warranty", "description": "Warranty" },
      "inspection_status": { "group": null, "code": "Received", "description": "Received" },
      "status_since": "2026-10-26",
      "status_since_estimated": false,
      "flags": [ { "code": "CORE_TEARDOWN", "label": "In core teardown log" } ],
      "is_open": true,
      "inspectable": true,
      "locked": false
    }
  ]
}
```
- `status_since` = date of the last **real** status change (§11); `status_since_estimated: true` when there is
  no audit history and the date is the RMA entry date — the app shows "estimated". The app shows age from it.
- `flags` = badges the server wants shown (v1: `CORE_TEARDOWN`). Unknown flag codes are shown by `label`.

`matched_on`: `"rma"`, `"serial"` or `"none"`. Empty `results` = no match (still `200`).

### `GET /rmas?product_type=…&return_code=…` — work list (decision 2026-11-18)

- Shows **only** RMAs that are open (Macola: combined header view `status='O'` joined to `OERMAHDR_iPro`, same rule
  as the Trend Tracker), inspectable, and at a `work_list_statuses` status (v1: **Received**).
- `product_type` = comma-separated product type values; `return_code` = comma-separated return-code `code`s
  (`group:code` when a group exists). **Omitted parameter = the server default** (`work_list_*`); an empty value
  matches nothing. Matching is trimmed, uppercase.
- Sort: **oldest `status_since` first, then RMA number.**
- Scroll RMAs are listed (Scroll is a default product type) but open read-only (capabilities off).
- Response: `{ "results": [ <search item shape> ], "generated_at": "..." }`.

Example: `GET /rmas?product_type=Recip,Screw&return_code=Warranty,Repair%20%26%20Return`

**RMAs with a draft on this tablet** stay on that tablet's list even if they no longer match (return code or status
changed); the app flags them "Return code / status changed" (spec §3). That is app-side; the server just lists.

---

## 6. RMA detail

### `GET /rmas/{rma_id}`

Response `200`:
```json
{
  "rma_id": "912345",
  "display_number": "912345",
  "signature": "sig-3f9d0c7a1b",
  "loaded_at": "2026-11-17T10:41:12-05:00",
  "is_open": true,
  "inspectable": true,

  "product_type": "Recip",
  "product_type_key": "recip",
  "product_type_source": "header",
  "capabilities": { "findings": true, "summary": true, "photos": true, "status": true, "report": true },

  "header": {
    "customer_name": "Example Refrigeration Co.",
    "model": "4FE-28Y",
    "failed_serial_no": "25X0000001",
    "sales_order_no": "SO-000000",
    "return_code": { "group": null, "code": "Warranty", "description": "Warranty" },
    "inspection_status": { "group": null, "code": "Received", "description": "Received" },
    "credit_status": "",
    "failed_date": "2/17/2026",
    "rma_comment": "Example comment",
    "failure_code": "9-Oil Issue",
    "failure_description": "Example: low oil pressure trips reported",
    "comments": "Example: customer reports repeated trips"
  },

  "status_since": "2026-10-26",
  "status_since_estimated": false,
  "flags": [],
  "lock": { "locked": false, "held_by": null, "host": null, "possibly_stale": false,
            "checked_at": "2026-11-17T10:41:12-05:00" },

  "parts": [
    {
      "row_id": "L1.3f9d0c7a1b",
      "part_code":  { "group": null, "code": "07a", "description": "Terminal box" },
      "issue_code": { "group": null, "code": "50",  "description": "Normal" },
      "cause_code": null,
      "note": ""
    },
    {
      "row_id": "L2.3f9d0c7a1b",
      "part_code":  { "group": null, "code": "48a", "description": "Winding resistance" },
      "issue_code": { "group": null, "code": "50",  "description": "Normal" },
      "cause_code": null,
      "note": "Within spec, all 3 phases"
    }
  ],

  "summary": {
    "exists": true,
    "defect_code": { "group": null, "code": "10", "description": "Lack of lubrication" },
    "blocks": [
      { "block_id": "1", "text": "The primary failure mode is ..." }
    ],
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
- `signature` is opaque; it covers the **whole record** (§12). The tablet stores it with the draft and sends it
  back as `base_signature`.
- `row_id` is opaque and only valid with this `signature`. After a save the server returns new `row_id`s.
- `parts` are in display order. There is no line number in the contract; the server numbers rows for the backend.
- `cause_code` is optional on every row: `null` in v1 (Macola has no cause per row).
- `summary.blocks` follow `summary_blocks` from `/codelists`; `defect_code` is `null` when not set.
  `summary.exists: false` = no summary record yet (server creates it on the first save).
- `header.return_code` is a code object (display only). Read-only on the tablet (RMA-team fields): `credit_status`, `return_code`, `failure_code`,
  `failure_description`, `comments`. They are display text.
- `is_open: false` or `inspectable: false` → all capabilities `false`.
- `product_type_source`: `"header"` or `"model"` (derived from model prefix because the header was blank).
- `lock` is informational; the authoritative check happens inside the write.
- `folder_display` uses `display_number` and is relative to the photo root; the root is never sent.

Errors: `404 RMA_NOT_FOUND`.

### `GET /rmas/{rma_id}/lock`
Same `lock` object. Used by the "Locked — will retry" screen.

---

## 7. Submit inspection (parts + summary + status)

### `POST /rmas/{rma_id}/inspection`

The tablet sends the **full desired parts list** in the inspector's order, plus optional summary and status
changes. Existing rows carry their `row_id`; new rows have `row_id: null`. An existing `row_id` left out of
`parts` = delete that row. `parts: null` (or omitted) = parts unchanged.

Request:
```json
{
  "submission_id": "4d3c2b1a-0000-4000-8000-00000000a001",
  "base_signature": "sig-3f9d0c7a1b",
  "product_type_key": "recip",
  "captured_at": "2026-11-17T10:55:30-05:00",
  "device_id": "7b1e4c2a-0000-4000-8000-000000000001",

  "status_change": {
    "from": { "group": null, "code": "Received" },
    "to":   { "group": null, "code": "Inspected" }
  },

  "summary": {
    "defect_code": { "group": null, "code": "10" },
    "blocks": [ { "block_id": "1", "text": "The primary failure mode is the scoring of the piston skirts ..." } ],
    "inspected_by": "JD",
    "summary_by": "JD"
  },

  "parts": [
    { "row_id": "L1.3f9d0c7a1b", "part_code": { "group": null, "code": "07a" }, "issue_code": { "group": null, "code": "50" }, "cause_code": null, "note": "" },
    { "row_id": "L2.3f9d0c7a1b", "part_code": { "group": null, "code": "48a" }, "issue_code": { "group": null, "code": "39" }, "cause_code": null, "note": "Phase T1-T2 open" },
    { "row_id": null,            "part_code": { "group": null, "code": "63"  }, "issue_code": { "group": null, "code": "34" }, "cause_code": null, "note": "Cyl 2 skirt scored\nlight on cyl 1" }
  ]
}
```

- `status_change` may be `null`. `to` may be any status in `inspection_statuses`; `from` must equal the status
  in the snapshot.
- `summary` may be `null`, or contain **only changed parts**: an omitted key = unchanged; `"defect_code": null`
  = clear it; `blocks` lists only changed blocks (`"text": ""` clears a block); each block ≤ its `max_length`.
- Codes are sent exactly as received (§1.1). `cause_code` must be `null` while `/codelists` `causes` is empty.
- Who did it comes from the sign-in token; the server builds the backend audit user itself.
- **Status the app sends on submit** (decision 2026-11-18, spec §6): before `status_roles.inspected` → `to:
  Inspected`; at or after `report_complete` → no status change, and the app rebuilds the report (§9.3) after a
  successful save ("Update inspection findings").

Server steps (Macola = `apply_flex` logic): validate → backend lock check (fail closed) → re-read with lock +
signature compare → backup → status (+ audit row) → summary record (update or create) → parts
(delete / update / renumber / insert) → commit → re-read and verify. All or nothing.

#### `200 OK` — saved

```json
{
  "result": "saved",
  "submission_id": "4d3c2b1a-0000-4000-8000-00000000a001",
  "replayed": false,
  "rma_id": "912345",
  "new_signature": "sig-8a1e44d2c9",
  "verified": true,
  "changes": {
    "status": { "from": { "group": null, "code": "Received" }, "to": { "group": null, "code": "Inspected" }, "audited": true },
    "summary": { "record": "updated", "fields": ["defect_code", "blocks.1", "inspected_by", "summary_by"] },
    "parts": { "inserted": 1, "updated": 1, "deleted": 0, "reordered": false }
  },
  "parts": [
    { "row_id": "L1.8a1e44d2c9", "part_code": { "group": null, "code": "07a" }, "issue_code": { "group": null, "code": "50" }, "cause_code": null },
    { "row_id": "L2.8a1e44d2c9", "part_code": { "group": null, "code": "48a" }, "issue_code": { "group": null, "code": "39" }, "cause_code": null },
    { "row_id": "L3.8a1e44d2c9", "part_code": { "group": null, "code": "63"  }, "issue_code": { "group": null, "code": "34" }, "cause_code": null }
  ],
  "warnings": []
}
```

`parts` in the response are in saved order with **new `row_id`s** — the tablet replaces its row ids with these.
If the post-commit verify finds a difference: still `200`, `"verified": false` and a `VERIFY_MISMATCH` warning.
A repeated `submission_id` returns the stored result with `"replayed": true` and writes nothing.

#### `409 RMA_CHANGED` — changed elsewhere since loaded

```json
{
  "error": {
    "code": "RMA_CHANGED",
    "message": "Someone changed RMA 912345 after you loaded it. Nothing was saved.",
    "retryable": false,
    "details": {
      "base_signature": "sig-3f9d0c7a1b",
      "current_signature": "sig-77c0b1e5aa",
      "changed": ["header.inspection_status", "header.credit_status", "summary", "parts"],
      "current": { "<same shape as GET /rmas/{rma_id}>": "…" }
    }
  }
}
```

`details.current` is the full current record (same shape as §6, including new `row_id`s) for the conflict
screen. The tablet resubmits with `base_signature = current_signature`, the new `row_id`s and a **new**
`submission_id`. Never auto-retried.

#### `423 RMA_LOCKED`, `503 LOCK_CHECK_FAILED`

```json
{ "error": { "code": "RMA_LOCKED", "message": "RMA 912345 is open in Macola by JDOE2 (EXAMPLE-PC). Nothing was saved.",
  "retryable": true, "retry_after_seconds": 120, "details": { "held_by": "JDOE2", "host": "EXAMPLE-PC", "possibly_stale": false } } }
```
```json
{ "error": { "code": "LOCK_CHECK_FAILED", "message": "Couldn't check the lock, so nothing was saved. Will retry.",
  "retryable": true, "retry_after_seconds": 60, "details": {} } }
```
Stale lock: same `RMA_LOCKED` with `"possibly_stale": true`. The server never clears locks.

#### `422 VALIDATION_FAILED`

```json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "3 problems — nothing was saved.",
    "retryable": false,
    "details": {
      "fields": [
        { "path": "parts[2].issue_code", "code": "UNKNOWN_CODE", "message": "Issue code '34b' is not in the issue list." },
        { "path": "parts[0].row_id", "code": "UNKNOWN_ROW", "message": "That row isn't in the loaded RMA." },
        { "path": "summary.blocks.1", "code": "TOO_LONG", "limit": 4000, "message": "Report summary is 4,212 characters; the limit is 4,000." }
      ]
    }
  }
}
```
Other field codes: `READ_ONLY_FIELD`, `UNKNOWN_STATUS`, `STATUS_FROM_MISMATCH`, `UNKNOWN_BLOCK`,
`CAUSE_NOT_SUPPORTED`, `DUPLICATE_ROW`.

#### `409 RMA_NOT_OPEN` / `403 PRODUCT_TYPE_NOT_ENABLED`
```json
{ "error": { "code": "RMA_NOT_OPEN", "message": "RMA 912345 is closed. Nothing was saved.", "retryable": false, "details": { "is_open": false, "inspectable": true } } }
```
```json
{ "error": { "code": "PRODUCT_TYPE_NOT_ENABLED", "message": "Scroll inspections are recorded in the Access repair DB.", "retryable": false, "details": { "product_type_key": "scroll", "capability": "findings" } } }
```

---

## 8. Photos

New photos go to the RMA's `All Pics`. Choosing report photos (§9) copies from there into `Report Pics `. Photo
and report endpoints never touch the ERP backend and need no lock. Storage today: G: share; later SharePoint
or SAP attachments (server-side change only).

### `POST /rmas/{rma_id}/photos` (multipart/form-data)

- `meta` — JSON:
```json
{
  "photo_id": "9e8d7c6b-0000-4000-8000-00000000b003",
  "kind": "part",
  "seq": 3,
  "row_id": null,
  "part_code":  { "group": null, "code": "63" },
  "issue_code": { "group": null, "code": "34" },
  "tag": null,
  "tag_text": null,
  "captured_at": "2026-11-17T10:49:02-05:00",
  "sha256": "0f1e2d...example"
}
```
  - `seq` = row position at upload time (names the file, spec §5). `row_id` = the saved row if known (lets an SAP
    server attach the photo to the item later); `null` for rows not saved yet.
  - Uncoded photo: `"kind": "rma", "seq": 0, "part_code": null, "issue_code": null, "tag": "Nameplate"`
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
- Same `photo_id` again → `200` with the original `filename` and `"replayed": true`. The server never overwrites.
- Missing folders are **created**; `folder_created: true` says so.
- Errors: `400` (checksum), `403 PRODUCT_TYPE_NOT_ENABLED`, `413`, `415`, `422` (unknown code / bad tag),
  `500 FOLDER_CREATE_FAILED`, `503 PHOTO_STORE_UNAVAILABLE`.

### `GET /rmas/{rma_id}/photos`
```json
{
  "folder_found": true,
  "folder_display": "912xxx\\9123xx\\912345",
  "all_pics": [
    { "filename": "00-1 As received.jpg", "bytes": 512000, "modified": "2026-11-17T10:47:00-05:00",
      "seq": 0, "part_code": null, "row_id": null },
    { "filename": "03-1 Piston assy, skirt - 34 Scored, Scratched.jpg", "bytes": 612344, "modified": "…",
      "seq": 3, "part_code": { "group": null, "code": "63" }, "row_id": "L3.8a1e44d2c9" }
  ],
  "report_pics": { "folder_found": false, "signature": "rp-empty", "files": [] }
}
```
`seq` / `part_code` / `row_id` link a photo to its part (from the upload record; `seq` from the filename for older
photos, `part_code` null when unknown). The Report tab uses them to filter the photo strip by the selected part.

### `GET /rmas/{rma_id}/photos/{folder}/{filename}?size=thumb|full`
`folder` = `all` or `report`. Returns `image/jpeg` (`thumb` = 320 px long edge). `404 PHOTO_NOT_FOUND` if gone.

---

## 9. Report photos and report

### 9.1 How it works
- The inspector picks **any number** of photos from `All Pics` (~20 typical) and edits captions.
- The server **copies** picks into `Report Pics ` (workbench lookup; creates `Report Pics` without the trailing
  space if missing). Copy filename = caption. Originals in `All Pics` are never renamed.
- Report order = natural order of the `Report Pics ` filenames.
- Removing a pick **moves** its copy to the server's backup folder; nothing is hard-deleted.

### 9.2 `PUT /rmas/{rma_id}/report-pics`
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
Response `200`: `{ "result": "saved", "replayed": false, "new_signature": "rp-7c21e0", "folder_created": true,
"changes": { "copied": 2, "renamed": 0, "removed": 0, "kept": 1 }, "report_pics": [ … ] }`.
Errors: `409 REPORT_PICS_CHANGED` (`details.current`, `details.current_signature`), `422` (`CAPTION_EMPTY`,
`CAPTION_DUPLICATE`, `CAPTION_TOO_LONG`, `SOURCE_NOT_FOUND`), `403`, `503 PHOTO_STORE_UNAVAILABLE`.

### 9.3 `POST /rmas/{rma_id}/report` — build the report
Runs the report generator (Macola era: the workbench's `rma_report.py` / `rma_report_xlsx.py`) on current
backend data + `Report Pics `. The tablet must have no unsent findings for this RMA. Existing report files are
backed up first.
**Status (decision 2026-11-18):** if the RMA is before `status_roles.report_complete`, the first successful build
sets **Report Complete** (audited, `status_changed` in the response). Later builds only regenerate. At a
`report_sent` status the build still regenerates and adds warning `REPORT_ALREADY_SENT`.

Request: `{ "submission_id": "6b5c4d3e-0000-4000-8000-00000000d001", "formats": ["pdf", "xlsx"] }`

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
  "status_changed": { "from": { "group": null, "code": "Inspected" }, "to": { "group": null, "code": "Report Complete" }, "audited": true },
  "warnings": [ { "code": "NO_CREDIT_STATUS", "message": "No credit status yet — page 1 says \"Disposition Pending\"." } ]
}
```
Warning codes: `NO_CREDIT_STATUS`, `NO_LETTER_TEXT`, `NO_REPORT_PICS`, `UNKNOWN_CODE_ROW`, `EMPTY_SUMMARY`,
`REPORT_ALREADY_SENT`.
Errors: `409 RMA_NOT_OPEN`, `409 REPORT_FILE_IN_USE` (`details.filename`), `403`, `503 BACKEND_UNAVAILABLE` /
`PHOTO_STORE_UNAVAILABLE`, `500 SERVER_ERROR` (old files untouched). The tablet waits up to 120 s.

### 9.4 `GET /rmas/{rma_id}/report`
`{ "exists": true, "generated_at": "...", "files": [ { "format": "pdf", "filename": "912345 Report.pdf", "bytes": 1543000 } ] }`

### 9.5 `GET /rmas/{rma_id}/report/pdf`
`application/pdf` for preview. `404 REPORT_NOT_FOUND` if none.

### 9.6 `POST /spellcheck`
Request `{ "text": "..." }` → `{ "issues": [ { "word": "scorring", "start": 32, "end": 40, "suggestions": ["scoring"] } ] }`
(workbench `rma_spell.py` trade-word list). The app runs it **automatically** before every report build.

### 9.7 `POST /spellcheck/dictionary`
Request `{ "word": "slinger" }` → `201 { "word": "slinger", "added": true }`. Adds one word to the shared dictionary
(Macola era: the workbench's `rma_tools_dictionary.txt`), so it is never flagged again for anyone. `422` if not one word.

---

## 10. Endpoint summary

| Method | Path | Purpose |
|---|---|---|
| GET | `/health` | Reachability, versions, `min_app_version` |
| POST | `/auth/login` · `/auth/logout`, GET `/me` | Sign-in (required), sign-out, current user |
| GET | `/codelists` | Code objects (parts, issues, causes, defect codes, statuses), summary blocks, product types + capabilities, settings |
| GET | `/rmas/search?q=` | Lookup by anything the inspector types |
| GET | `/rmas?product_type=&return_code=` | Work list (Received only; oldest first) |
| GET | `/rmas/{rma_id}` | Header, capabilities, lock, parts (row ids), summary blocks, photos, signature |
| GET | `/rmas/{rma_id}/lock` | Live lock state |
| POST | `/rmas/{rma_id}/inspection` | Write parts + summary + optional status (all or nothing) |
| POST | `/rmas/{rma_id}/photos` | File one photo into `All Pics` |
| GET | `/rmas/{rma_id}/photos` | List `All Pics` and `Report Pics ` |
| GET | `/rmas/{rma_id}/photos/{folder}/{filename}` | Thumbnail or full image |
| PUT | `/rmas/{rma_id}/report-pics` | Set report photos + captions |
| POST | `/rmas/{rma_id}/report` | Build report PDF + XLSX |
| GET | `/rmas/{rma_id}/report` · `/report/pdf` | Report info / PDF preview |
| POST | `/spellcheck` · `/spellcheck/dictionary` | Spell check text · add a word to the dictionary |

Read-only on the tablet: credit status, return code, failure code, failure description, comments (RMA-team fields).
Not in the API: close/reopen (RMA-team functions).

---

## 11. Server-side notes (Phase 4 and SAP)

- The server has a **backend connector** (Macola now: workbench modules; SAP later: OData/BAPI) behind this
  contract. Everything backend-specific — padding, line numbers, lock tables, audit user names, catalog
  groups — lives in the connector.
- Macola `row_id` = `L<line_no>.<signature>`: the line number is only trusted together with the signature.
- Idempotency store (SQLite, server-side, git-ignored) of `submission_id` / `photo_id` → result, ≥ 30 days.
- Backups go to the existing `backups\` convention (git-ignored). Report-photo removals and replaced report files too.
- Lift the 20-picture cap in the shared report module (workbench repo change).
- Report generation runs in-process — no `subprocess`, no Excel automation. Folder-style packaging; service with auto-restart.
- Server log: user, RMA, endpoint, result code — never note text or photo bytes.
- **Work-list queries come from the Trend Tracker** (decision 2026-11-18). Phase 4 (local session): extract the
  Trend Tracker's inline SQL into a side-effect-free `rma_queries.py` shared by `daily_snapshot_V4.py`,
  `refresh_aging_data.py` and the app server. Rules: open = combined view `status='O'` JOIN `OERMAHDR_iPro`;
  return code read `RTRIM(ISNULL(Return_Code,''))`, matched trimmed/uppercase.
- **`status_since`** = latest `OERMAAUD_iPro.DateTimeOfChange` for `Field_Name='RMA_Inspection_Status'` where
  `Old_Status <> New_Status` (real changes only — same-value re-saves don't reset it), tiebreak `A4glidentity`;
  no audit rows → `rma_dt_entered` with `status_since_estimated = true`. (The Trend Tracker's current
  Status_Since counts same-value re-saves and has no tiebreak; the shared module should adopt this rule.)
- Core-teardown flag from the existing Core Teardown Log cache.
- v1 users (username, password hash, display name, initials, backend audit user) live in a server-side setting
  outside the repo.

---

## 12. Change-check rule (decided 2026-11-17)

Any change made elsewhere while an inspection is in progress is **reviewed on the tablet, never overwritten**:
- `signature` covers the **whole record**: header (including RMA-team fields), summary (defect code, all blocks,
  initials) and parts (including `cause_code`). `Report Pics ` has its own signature (§9.2).
- Any mismatch → `409 RMA_CHANGED` / `409 REPORT_PICS_CHANGED`. **No auto-merge.** `details.changed` lists
  what changed.
- Phase 4: confirm the workbench's signature covers all of the above; extend it if not. SAP: the connector
  derives the signature from the notification's change timestamp/ETag plus content.

---

## 13. Open questions (API-specific)

1. **Serial search** — also search `rma_cmt` (non-BITZER serials) like the Serial Lookup tool?
2. **HTTPS certificate** — internal CA trusted by the tablets (Intune), or a public cert?
3. **Work-list sort** — "newest first" by last activity right?
4. SAP-side unknowns are tracked in `docs/sap_mapping.md` §5.
