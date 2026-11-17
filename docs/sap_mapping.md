# SAP mapping — API field → Macola source → expected SAP object/field

_Draft 2026-11-17, written with `docs/api.md` rev 6. Purpose: show the SAP team exactly what the tablet needs, so
the SAP S/4HANA cutover changes only the server's backend connector. Nothing here has been checked against
BITZER's SAP design — no SAP system, field mapping or OData service has been touched yet (context pack 03 §7)._

**Status column:** **Known** = Macola source confirmed (RMA_DATA_MAP) and the SAP object is standard ·
**Proposed** = a reasonable SAP home, needs SAP-team agreement · **Unknown** = depends on BITZER's SAP design.

SAP names below use classic table/field names (QMEL, QMFE, QMUR…) because they are stable and documented; the
S/4HANA OData entity/property names the server would actually call must be confirmed by the SAP team.

---

## 1. Assumptions to confirm first

| # | Assumption | Why it matters |
|---|---|---|
| A1 | Teardown findings live in a **QM quality notification** (customer complaint origin, e.g. type Q1 or a BITZER type), linked to the SD **returns order** that replaces the Macola RMA. | Decides where rows, codes, texts and status go. |
| A2 | Parts / issues / causes become **QM catalogs**: defect location (part), defect type (issue), cause — each **code group + code**. | This is why every code object has `group`. |
| A3 | Inspection workflow status becomes a **user status** in the notification's status profile. | Status changes then have SAP change history. |
| A4 | RMA-team fields (credit status, return code) live on the returns order or as custom fields. | Read-only on the tablet either way. |
| A5 | The app server talks to SAP through released **OData APIs** (or BAPIs such as `BAPI_QUALNOT_CREATE` / `BAPI_QUALNOT_ADD_DATA` / `BAPI_QUALNOT_SAVE` if no suitable OData service exists). Exact service names: **verify on the SAP Business Accelerator Hub for BITZER's S/4HANA edition.** | Determines connector work and how locking/ETags behave. |
| A6 | Photos and report files stay on a file store (G: now, SharePoint later) unless the SAP team wants SAP attachments. | Photo endpoints are already backend-neutral. |

---

## 2. Identity, lookup and status

| API field | Macola source today | Expected SAP object / field | Status |
|---|---|---|---|
| `rma_id` | RMA number, unpadded (`oerhdfil_sql.rma_no`, stored right-justified in 8 chars) | Key the connector uses: QM notification number (QMEL-QMNUM) or returns order number (VBAK-VBELN) | **Unknown** (A1) |
| `display_number` | Same unpadded RMA number | What people will call "the RMA" in SAP (returns order no.?) | **Unknown** |
| `is_open` | Row in `oerhdfil_sql` (open) vs `oerhdhis_sql` (closed) | Notification system status not completed (NOCO) and/or returns order not closed | Proposed |
| `inspectable` | `OERMAHDR_iPro` row exists | Quality notification exists for the RMA | Proposed |
| `lock` (`locked`, `held_by`, `possibly_stale`) | `MSLLockDB.dbo.MacLocks` + `sys.dm_exec_sessions` | SAP enqueue lock on the notification; OData optimistic concurrency (ETag / `If-Match`) | Proposed |
| `signature` | Workbench snapshot signature over header + summary + parts | OData ETag / notification last-changed timestamp, plus a content hash | Proposed |
| `header.inspection_status` (code object) | `OERMAHDR_iPro.RMA_Inspection_Status` (text; list from `oermactl_ipro`) | User status (status profile on the notification type, table JEST/TJ30T) — `group` could carry the status profile | Proposed (A3) |
| `status_change` audit | `OERMAAUD_iPro` row (`UserID` = `<USER>_SQL`) | SAP status change history (JCDS) / change documents (CDHDR/CDPOS) under the inspector's own SAP user | Proposed — needs per-inspector identity |
| Search by serial (`q`) | `OERMAHDR_iPro.Failed_Serial_No`, `rma_cmt` | Notification serial number field / returns order item serial | Proposed |
| Work list filter | `RMA_Inspection_Status` values | User status selection | Proposed |

## 3. Header (read-only context) and product type

| API field | Macola source today | Expected SAP object / field | Status |
|---|---|---|---|
| `header.customer_name` | `ARCUSFIL_SQL.cus_name` via `cus_no` | Business partner (sold-to) name on returns order / notification customer (QMEL-KUNUM) | Known (standard) |
| `header.model` | `OERMAHDR_iPro.Model` (fallback `Failed_Model_No`) | Material on the notification (QMEL-MATNR) | Proposed |
| `header.failed_serial_no` | `OERMAHDR_iPro.Failed_Serial_No` | Serial number on notification / returns item | Proposed |
| `header.sales_order_no` | `OERMAHDR_iPro.Sales_Order_No` | Reference sales order on the notification (QMEL-VBELN / KDAUF) or returns order reference | Proposed |
| `header.return_code` (RMA team) | `OERMAHDR_iPro.Return_Code` | Returns order reason (order reason AUGRU) or custom field | **Unknown** |
| `header.credit_status` (RMA team) | `OERMAHDR_iPro.Inspection_Credit_Status` (`"F - Full Warranty Credit"`) | Returns decision / credit memo request status, or custom field | **Unknown** |
| `header.failed_date` | `OERMAHDR_iPro.Failed_Date` (text) | Notification malfunction start date or custom field | Proposed |
| `header.rma_comment` | `oerhdfil_sql.rma_cmt` | Returns order header text | Proposed |
| `header.failure_code` (RMA team) | `OERMAHDR_iPro.Failure_Cd` (drop-down from `oermactl_ipro`) | Notification header coding (catalog "coding", QMEL-QMGRP/QMCOD) | **Unknown** |
| `header.failure_description` (RMA team) | `OERMAHDR_iPro.Failure_Mode` (from customer's RMA request) | Notification short text (QMTXT) or header long text | **Unknown** |
| `header.comments` (RMA team) | `OERMAHDR_iPro.Comments` (500) | Notification header long text (separate text ID) | **Unknown** |
| `product_type` / `product_type_key` | `OERMAHDR_iPro.Product_Type`, else model prefix | Material group / product hierarchy of the material, or custom field | **Unknown** |
| `capabilities` | Server config per product type | Server config (unchanged) | Known |

## 4. Findings (parts rows, summary)

| API field | Macola source today | Expected SAP object / field | Status |
|---|---|---|---|
| `parts[]` (one row) | `oermactl_ipro_detail` row | **Notification item** (defect item, QMFE) | Proposed (A2) |
| `parts[].row_id` | `L<line_no>.<signature>` (`line_no` 1..n, no key) | Item number (QMFE-FENUM), possibly with ETag | Proposed |
| row order | `line_no` renumbered 1..n | Item sort number (QMFE-POSNR) | Proposed |
| `parts[].part_code` `{group, code}` | `part_no` → `oermactl_ipro_parts` (group `null`) | Defect location catalog: code group QMFE-OTGRP + code QMFE-OTEIN (or object part catalog) | Proposed |
| `parts[].issue_code` `{group, code}` | `issue_cd` → `oermactl_ipro_issues` (group `null`) | Defect type catalog (catalog type 9): QMFE-FEGRP + QMFE-FECOD | Proposed |
| `parts[].cause_code` `{group, code}` | — (none; always `null`) | Cause catalog: QMUR-URGRP + QMUR-URCOD under the item | Proposed — unused in v1 |
| `parts[].note` (≤ 4000) | `oermactl_ipro_detail.note` | Item text (QMFE-FETXT, short) + item long text | Proposed — short-text length limit needs a rule |
| `summary.defect_code` `{group, code}` | `oermahdr_extended_ipro.defect_cd` → `oermactl_ipro_failures` | Header coding, or a cause on the notification, or custom field | **Unknown** |
| `summary.blocks[]` (v1: one "Report summary") | `summary_1` (varchar 4000); `summary_2/3` unused by the app | Notification long text(s) — one text with sections, or several text IDs. `summary_blocks` in `/codelists` lets SAP send a different block layout | **Unknown** |
| `summary.inspected_by` | `oermahdr_extended_ipro.inspected_by` (initials) | Partner function on the notification (e.g. person responsible) or custom field | **Unknown** |
| `summary.summary_by` | `oermahdr_extended_ipro.summary_by` | Partner function or custom field | **Unknown** |
| `summary.exists` | `oermahdr_extended_ipro` row present | Always true once the notification exists | Known |
| Audit of findings edits | None (Macola writes no audit for parts/summary) | SAP change documents (automatic) | Proposed |

## 5. Code lists

| API list | Macola source today | Expected SAP source | Status |
|---|---|---|---|
| `parts` | `oermactl_ipro_parts` (160) | Catalog: defect location / object part, via the notification type's catalog profile | Proposed |
| `issues` | `oermactl_ipro_issues` (58) | Catalog type 9 (defect types) | Proposed |
| `causes` | — (empty) | Cause catalog | Proposed |
| `defect_codes` | `oermactl_ipro_failures` (26) | Coding catalog or cause catalog | **Unknown** |
| `inspection_statuses` | `oermactl_ipro` `Rma_Inspection_Status` rows | User statuses of the status profile | Proposed |
| `summary_blocks` | One block, 4000 (server config) | Long-text layout chosen by the SAP team | **Unknown** |
| code `description` | `*_description` columns | Code / code group short texts (QPCT) | Known (standard) |
| `hidden` | Server config (empty) | Inactive codes / codes outside the catalog profile | Proposed |

## 6. Photos, report, people

| API field | Macola-era source | Expected SAP-era home | Status |
|---|---|---|---|
| Photos (`All Pics`), report photos (`Report Pics `) | G: folders `<3>xxx\<4>xx\<RMA>` | SharePoint folder, or SAP attachments on the notification | **Unknown** (A6) |
| Photo ↔ row link (`row_id` in photo meta) | Filename prefix `SS-n` only | Attachment on the notification item | Proposed |
| Report PDF / XLSX | Workbench `rma_report.py` into the RMA folder | Same generator reading SAP data; file store as above | Proposed |
| Disposition letter text by credit status | `rma_report.py` `DISPOSITIONS` | Same (server-side) | Known |
| Inspector (`X-Inspector`, `audit_user_id`) | Windows user + `_SQL` | The inspector's own SAP user (needs sign-in or per-user mapping) | **Unknown** — see §7 |
| Spell check | `rma_spell.py` | Same (server-side) | Known |

## 7. Open questions for the SAP team

1. Is the "RMA" in SAP the returns order, the quality notification, or both? Which number do people use (`display_number`)?
2. Which notification type, catalog profile and catalogs hold BITZER's parts / issues / causes? Will today's 160 / 58 / 26 codes be migrated 1:1, regrouped, or redesigned?
3. Which user status profile replaces the 11 inspection statuses?
4. Where do General Defect Code, summaries, inspected-by and summary-by live (coding, causes, long texts, partner functions, custom fields)? Are custom fields readable and writable through the chosen API? (Some custom fields on quality notifications are reported as not exposed by the standard API — needs checking.)
5. How does the app server authenticate to SAP **per inspector** (principal propagation, OAuth user tokens, named technical users)? A shared technical user would lose who-did-what in SAP.
6. Which API: released OData service for quality notifications (name and version for BITZER's edition) vs BAPIs? How does it expose locks and ETags?
7. Item short text vs long text: how should a note longer than the item short text be split?
8. Do photos and reports move into SAP attachments, SharePoint, or stay on a file share?
9. Scroll: does the Access repair-DB data move into SAP too?

## 8. What the tablet already does to be ready

- Never parses or builds RMA numbers; uses `rma_id` / `display_number` from the server.
- Never uses line numbers; echoes `row_id`.
- Stores and returns codes as `{group, code}` exactly as received; shows groups when present.
- Shows a cause picker only when the server sends causes.
- Renders as many summary blocks as the server defines, with the server's limits.
- Has no backend-specific names or rules; capabilities and limits come from the server.
