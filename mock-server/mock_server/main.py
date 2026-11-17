"""Mock RMA app server — implements docs/api.md (rev 6, SAP-ready) with in-memory FAKE data.

Run:  python -m uvicorn mock_server.main:app --host 0.0.0.0 --port 8000
The Android emulator reaches it at http://10.0.2.2:8000/api/v1

The mock behaves like the Macola-era server: codes have group null, row ids are "L<n>.<signature>",
there are no causes and three 4000-character summary blocks. Everything backend-specific stays in here.

Test hooks (mock only, under /api/v1/_mock): reset, desk-edit (simulate a change elsewhere),
lock, report-file-in-use.
"""
from __future__ import annotations

import hashlib
import json
import math
import re
import threading
from datetime import datetime, timedelta, timezone
from typing import Any, Optional

from fastapi import APIRouter, FastAPI, File, Form, Header, Request, Response, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from . import fake_data as fd
from . import naming

API_VERSION = "1.0"
SERVER_VERSION = "0.2.0-mock"
MIN_APP_VERSION = "0.1.0"
EASTERN = timezone(timedelta(hours=-5))
PHOTO_MAX_BYTES = 10 * 1024 * 1024
LIMITS = {"note": 4000, "inspected_by": 255, "summary_by": 255, "photo_tag_text": 40, "photo_max_bytes": PHOTO_MAX_BYTES}
READ_ONLY_FIELDS = ("credit_status", "return_code", "failure_code", "failure_description", "comments")
WORK_LIST_DEFAULT = ["Received", "Rework in Progress", "Inspected", "Report Complete"]
FAKE_JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 64 + b"\xff\xd9"


def now() -> str:
    return datetime.now(EASTERN).replace(microsecond=0).isoformat()


# ---------------------------------------------------------------- errors

class ApiError(Exception):
    def __init__(self, status: int, code: str, message: str, *, retryable: bool = False,
                 details: Optional[dict] = None, retry_after: Optional[int] = None):
        self.status, self.code, self.message = status, code, message
        self.retryable, self.details, self.retry_after = retryable, details or {}, retry_after


def error_body(e: ApiError) -> dict:
    body = {"code": e.code, "message": e.message, "retryable": e.retryable, "details": e.details}
    if e.retry_after is not None:
        body["retry_after_seconds"] = e.retry_after
    return {"error": body}


# ---------------------------------------------------------------- code lists

def _list(pairs):
    return [{"group": None, "code": c, "description": d} for c, d in pairs]


CODES = {
    "parts": _list(fd.PARTS),
    "issues": _list(fd.ISSUES),
    "causes": list(fd.CAUSES),
    "defect_codes": _list(fd.DEFECT_CODES),
    "inspection_statuses": [{"group": None, "code": s, "description": s} for s in fd.INSPECTION_STATUSES],
}
BLOCKS = {b["block_id"]: b for b in fd.SUMMARY_BLOCKS}


def _key(obj) -> Optional[tuple]:
    if obj is None:
        return None
    return (obj.get("group"), obj.get("code"))


def lookup(kind: str, obj) -> Optional[dict]:
    """Exact match on (group, code) — codes are compared as given, never normalised."""
    if not isinstance(obj, dict) or "code" not in obj:
        return None
    k = _key(obj)
    return next((c for c in CODES[kind] if (c["group"], c["code"]) == k), None)


def with_desc(kind: str, obj) -> Optional[dict]:
    if obj is None:
        return None
    hit = lookup(kind, obj)
    return {"group": obj.get("group"), "code": obj.get("code"), "description": hit["description"] if hit else ""}


def status_obj(code_text: str) -> dict:
    return {"group": None, "code": code_text, "description": code_text}


# ---------------------------------------------------------------- state

class Store:
    def __init__(self):
        self.lock = threading.RLock()
        self.reset()

    def reset(self):
        with self.lock:
            self.rmas = fd.initial_rmas()
            self.files: dict[tuple[str, str, str], bytes] = {}
            self.replays: dict[str, tuple[int, dict]] = {}
            self.backups: list[dict] = []
            self.report_in_use: set[str] = set()
            r = self.rmas["912347"]
            for name in ("00-1 As received.jpg", "01-1 Stator - 46 Burnt.jpg",
                         "02-1 Winding to ground insulation - 38 Ground fault.jpg"):
                r["all_pics"].append(name)
                self.files[("912347", "all", name)] = FAKE_JPEG
            r["report_pics"].append("01-1 Stator - 46 Burnt.jpg")
            self.files[("912347", "report", "01-1 Stator - 46 Burnt.jpg")] = FAKE_JPEG
            for v in self.rmas.values():
                v["folder_exists"] = bool(v["all_pics"])
                v["report_folder_exists"] = bool(v["report_pics"])


store = Store()


# ---------------------------------------------------------------- helpers

def product_key(rma: dict) -> tuple[str, str]:
    raw = rma["product_type"]
    if raw:
        return dict(fd.PRODUCT_TYPES).get(raw, "unknown"), "header"
    model = rma["header"]["model"].upper()
    if model.startswith(fd.SCROLL_PREFIXES):
        return "scroll", "model"
    if model.startswith(fd.SCREW_PREFIXES):
        return "screw", "model"
    return "unknown", "model"


def capabilities_for(key: str) -> dict:
    on = key != "scroll"
    return {"findings": on, "summary": on, "photos": on, "status": on, "report": on}


def rma_capabilities(rma: dict) -> dict:
    caps = capabilities_for(product_key(rma)[0])
    if not rma["is_open"] or not rma["inspectable"]:
        caps = {k: False for k in caps}
    return caps


def folder_display(rma: dict, sub: Optional[str] = None) -> str:
    n = rma["display_number"]
    base = f"{n[:3]}xxx\\{n[:4]}xx\\{n}"
    return base + (f"\\{sub}" if sub else "")


def record_signature(rma: dict) -> str:
    """Change check over the WHOLE record (api.md §12)."""
    payload = {"header": rma["header"], "summary": rma["summary"], "parts": rma["parts"], "is_open": rma["is_open"]}
    return "sig-" + hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()[:10]


def row_ids(rma: dict) -> list[str]:
    """Macola connector row id: line number + record signature (opaque to the tablet)."""
    sig = record_signature(rma)[4:]
    return [f"L{i + 1}.{sig}" for i in range(len(rma["parts"]))]


def report_pics_signature(rma: dict) -> str:
    if not rma["report_pics"]:
        return "rp-empty"
    return "rp-" + hashlib.sha256("\n".join(naming.natural_sorted(rma["report_pics"])).encode()).hexdigest()[:6]


def get_rma(rma_id: str) -> dict:
    r = store.rmas.get(rma_id.strip())
    if not r:
        raise ApiError(404, "RMA_NOT_FOUND", f"RMA {rma_id} was not found.")
    return r


def parts_view(rma: dict) -> list[dict]:
    return [{"row_id": rid, "part_code": with_desc("parts", p["part_code"]), "issue_code": with_desc("issues", p["issue_code"]),
             "cause_code": with_desc("causes", p["cause_code"]), "note": p["note"]}
            for rid, p in zip(row_ids(rma), rma["parts"])]


def summary_view(rma: dict) -> dict:
    s = rma["summary"]
    return {"exists": s["exists"], "defect_code": with_desc("defect_codes", s["defect_code"]),
            "blocks": [{"block_id": b["block_id"], "text": s["blocks"].get(b["block_id"], "")} for b in fd.SUMMARY_BLOCKS],
            "inspected_by": s["inspected_by"], "summary_by": s["summary_by"]}


def header_view(rma: dict) -> dict:
    h = dict(rma["header"])
    h["inspection_status"] = status_obj(h["inspection_status"])
    return h


def list_item(rma: dict) -> dict:
    key, _ = product_key(rma)
    return {"rma_id": rma["rma_id"], "display_number": rma["display_number"], "model": rma["header"]["model"],
            "failed_serial_no": rma["header"]["failed_serial_no"], "product_type": rma["product_type"],
            "product_type_key": key, "inspection_status": status_obj(rma["header"]["inspection_status"]),
            "is_open": rma["is_open"], "inspectable": rma["inspectable"], "locked": rma["lock"]["locked"]}


def detail(rma: dict) -> dict:
    key, source = product_key(rma)
    return {
        "rma_id": rma["rma_id"], "display_number": rma["display_number"], "signature": record_signature(rma),
        "loaded_at": now(), "is_open": rma["is_open"], "inspectable": rma["inspectable"],
        "product_type": rma["product_type"], "product_type_key": key, "product_type_source": source,
        "capabilities": rma_capabilities(rma), "header": header_view(rma),
        "lock": {**rma["lock"], "checked_at": now()}, "parts": parts_view(rma), "summary": summary_view(rma),
        "photos": {"folder_found": rma["folder_exists"], "folder_display": folder_display(rma, "All Pics"),
                   "count": len(rma["all_pics"]), "files": naming.natural_sorted(rma["all_pics"])},
    }


def require_inspector(x_inspector: Optional[str]) -> dict:
    for i in fd.INSPECTORS:
        if x_inspector and i["username"].lower() == x_inspector.lower():
            return i
    raise ApiError(403, "UNKNOWN_INSPECTOR", "This inspector isn't on the server's list. Pick the inspector again.")


def require_capability(rma: dict, cap: str):
    key, _ = product_key(rma)
    if not capabilities_for(key)[cap]:
        raise ApiError(403, "PRODUCT_TYPE_NOT_ENABLED", "Scroll inspections are recorded in the Access repair DB.",
                       details={"product_type_key": key, "capability": cap})


def require_open(rma: dict):
    if not rma["is_open"] or not rma["inspectable"]:
        raise ApiError(409, "RMA_NOT_OPEN", f"RMA {rma['display_number']} is closed. Nothing was saved.",
                       details={"is_open": rma["is_open"], "inspectable": rma["inspectable"]})


def check_lock(rma: dict):
    if rma["lock_check_fails"]:
        raise ApiError(503, "LOCK_CHECK_FAILED", "Couldn't check the lock, so nothing was saved. Will retry.",
                       retryable=True, retry_after=60)
    lk = rma["lock"]
    if lk["locked"]:
        if lk["possibly_stale"]:
            msg = f"RMA {rma['display_number']} has a possible stale lock. Nothing was saved. Ask IT to clear it."
        else:
            who = lk["held_by"] or "another user"
            msg = f"RMA {rma['display_number']} is open in Macola by {who}{' (' + lk['host'] + ')' if lk['host'] else ''}. Nothing was saved."
        raise ApiError(423, "RMA_LOCKED", msg, retryable=True, retry_after=120,
                       details={"held_by": lk["held_by"], "host": lk["host"], "possibly_stale": lk["possibly_stale"]})


def replay(key: Optional[str]):
    if key and key in store.replays:
        status, body = store.replays[key]
        return status, {**body, "replayed": True}
    return None


def remember(key: Optional[str], status: int, body: dict):
    if key:
        store.replays[key] = (status, body)


def field_error(path: str, code: str, message: str, **extra) -> dict:
    return {"path": path, "code": code, "message": message, **extra}


def raise_validation(errors: list[dict]):
    if errors:
        n = len(errors)
        raise ApiError(422, "VALIDATION_FAILED", f"{n} problem{'s' if n != 1 else ''} — nothing was saved.",
                       details={"fields": errors})


def changed_fields(old: dict, new: dict) -> list[str]:
    out = [f"header.{k}" for k in new["header"] if old["header"].get(k) != new["header"][k]]
    if old["summary"] != new["summary"]:
        out.append("summary")
    if old["parts"] != new["parts"]:
        out.append("parts")
    return out


def code_label(obj) -> str:
    return f"{obj.get('group')}:{obj.get('code')}" if obj and obj.get("group") else str((obj or {}).get("code"))


# ---------------------------------------------------------------- app

app = FastAPI(title="RMA Inspector mock server", version=SERVER_VERSION)
api = APIRouter(prefix="/api/v1")


@app.exception_handler(ApiError)
async def _api_error(_: Request, e: ApiError):
    headers = {"Retry-After": str(e.retry_after)} if e.retry_after else None
    return JSONResponse(error_body(e), status_code=e.status, headers=headers)


@app.exception_handler(RequestValidationError)
async def _bad_request(_: Request, e: RequestValidationError):
    return JSONResponse(error_body(ApiError(400, "BAD_REQUEST", "The request was malformed.",
                                            details={"errors": json.loads(json.dumps(e.errors(), default=str))})),
                        status_code=400)


@api.get("/health")
def health():
    return {"status": "ok", "api_version": API_VERSION, "server_version": SERVER_VERSION,
            "min_app_version": MIN_APP_VERSION, "backend": "mock", "backend_reachable": True,
            "photo_store_reachable": True, "server_time": now()}


@api.get("/inspectors")
def inspectors():
    return {"inspectors": fd.INSPECTORS, "max_inspectors": 10, "sign_in_required": False}


def _codelists() -> dict:
    issues = [{**c, **({"pin_first": True} if c["code"] == "50" else {})} for c in CODES["issues"]]
    return {
        "version": "2026-11-17T06:00:00-05:00",
        "source": "mock",
        "parts": CODES["parts"],
        "issues": issues,
        "causes": CODES["causes"],
        "defect_codes": CODES["defect_codes"],
        "inspection_statuses": CODES["inspection_statuses"],
        "status_rule": "any",
        "summary_blocks": fd.SUMMARY_BLOCKS,
        "product_types": [{"value": v, "key": k, "capabilities": capabilities_for(k)} for v, k in fd.PRODUCT_TYPES],
        "hidden": {"parts": [], "issues": [], "causes": []},
        "photo_tags": fd.PHOTO_TAGS,
        "work_list_statuses": [{"group": None, "code": s} for s in WORK_LIST_DEFAULT],
        "photo": {"max_edge_px": 2048, "jpeg_quality": 85},
        "report": {"typical_photo_count": 20, "max_photo_count": None, "auto_status_after_report": None},
        "summary_template": fd.SUMMARY_TEMPLATE,
        "limits": LIMITS,
    }


@api.get("/codelists")
def codelists(if_none_match: Optional[str] = Header(default=None)):
    body = _codelists()
    etag = '"cl-' + hashlib.sha256(json.dumps(body, sort_keys=True).encode()).hexdigest()[:6] + '"'
    if if_none_match == etag:
        return Response(status_code=304, headers={"ETag": etag})
    return JSONResponse({**body, "etag": etag}, headers={"ETag": etag})


@api.get("/rmas/search")
def search(q: str):
    q = q.strip()
    results, matched_on = [], "none"
    hit = next((r for r in store.rmas.values() if r["display_number"] == q or r["rma_id"] == q), None)
    if hit:
        results, matched_on = [list_item(hit)], "rma"
    else:
        hits = [list_item(r) for r in store.rmas.values()
                if r["header"]["failed_serial_no"] and r["header"]["failed_serial_no"].lower() == q.lower()]
        if hits:
            results, matched_on = hits, "serial"
    return {"query": q, "matched_on": matched_on, "results": results}


@api.get("/rmas")
def work_list(inspection_status: Optional[str] = None, limit: int = 50):
    if inspection_status:
        wanted = [s.split(":", 1)[-1].strip() for s in inspection_status.split(",")]  # "group:code" or "code"
    else:
        wanted = WORK_LIST_DEFAULT
    rows = [r for r in store.rmas.values() if r["is_open"] and r["inspectable"] and r["header"]["inspection_status"] in wanted]
    rows.sort(key=lambda r: (r["last_act_dt"], r["rma_id"]), reverse=True)
    return {"results": [list_item(r) for r in rows[:limit]], "generated_at": now()}


@api.get("/rmas/{rma_id}")
def rma_detail(rma_id: str):
    return detail(get_rma(rma_id))


@api.get("/rmas/{rma_id}/lock")
def rma_lock(rma_id: str):
    rma = get_rma(rma_id)
    return {**rma["lock"], "checked_at": now()}


# --- submit inspection

def _validate_summary(summary: dict, errors: list):
    for k, v in summary.items():
        if k in READ_ONLY_FIELDS:
            continue  # reported separately
        if k == "defect_code":
            if v is not None and not lookup("defect_codes", v):
                errors.append(field_error("summary.defect_code", "UNKNOWN_CODE", f"Defect code '{code_label(v)}' is not in the defect code list."))
        elif k == "blocks":
            seen = set()
            for b in v or []:
                bid = str(b.get("block_id"))
                if bid not in BLOCKS:
                    errors.append(field_error(f"summary.blocks.{bid}", "UNKNOWN_BLOCK", f"Summary block '{bid}' doesn't exist."))
                    continue
                if bid in seen:
                    errors.append(field_error(f"summary.blocks.{bid}", "DUPLICATE_BLOCK", f"Summary block '{bid}' sent twice."))
                seen.add(bid)
                limit = BLOCKS[bid]["max_length"]
                text = b.get("text") or ""
                if len(text) > limit:
                    errors.append(field_error(f"summary.blocks.{bid}", "TOO_LONG",
                                              f"{BLOCKS[bid]['label']} is {len(text):,} characters; the limit is {limit:,}.", limit=limit))
        elif k in ("inspected_by", "summary_by"):
            if len(v or "") > LIMITS[k]:
                errors.append(field_error(f"summary.{k}", "TOO_LONG", f"{k} is too long.", limit=LIMITS[k]))
        else:
            errors.append(field_error(f"summary.{k}", "UNKNOWN_FIELD", f"'{k}' is not a summary field."))


def _validate_parts(parts: list, valid_ids: Optional[set], errors: list):
    used = set()
    for i, p in enumerate(parts):
        if not lookup("parts", p.get("part_code")):
            errors.append(field_error(f"parts[{i}].part_code", "UNKNOWN_CODE", f"Part code '{code_label(p.get('part_code'))}' is not in the part list."))
        if not lookup("issues", p.get("issue_code")):
            errors.append(field_error(f"parts[{i}].issue_code", "UNKNOWN_CODE", f"Issue code '{code_label(p.get('issue_code'))}' is not in the issue list."))
        cause = p.get("cause_code")
        if cause is not None:
            if not CODES["causes"]:
                errors.append(field_error(f"parts[{i}].cause_code", "CAUSE_NOT_SUPPORTED", "This server doesn't record causes yet."))
            elif not lookup("causes", cause):
                errors.append(field_error(f"parts[{i}].cause_code", "UNKNOWN_CODE", f"Cause code '{code_label(cause)}' is not in the cause list."))
        if len(p.get("note") or "") > LIMITS["note"]:
            errors.append(field_error(f"parts[{i}].note", "TOO_LONG", "Note is longer than 4,000 characters.", limit=LIMITS["note"]))
        rid = p.get("row_id")
        if rid is not None:
            if rid in used:
                errors.append(field_error(f"parts[{i}].row_id", "DUPLICATE_ROW", "The same row was sent twice."))
            used.add(rid)
            if valid_ids is not None and rid not in valid_ids:
                errors.append(field_error(f"parts[{i}].row_id", "UNKNOWN_ROW", "That row isn't in the loaded RMA."))


@api.post("/rmas/{rma_id}/inspection")
async def submit_inspection(rma_id: str, request: Request, x_inspector: Optional[str] = Header(default=None)):
    body: dict[str, Any] = await request.json()
    with store.lock:
        hit = replay(body.get("submission_id"))
        if hit:
            return JSONResponse(hit[1], status_code=hit[0])
        rma = get_rma(rma_id)
        inspector = require_inspector(x_inspector)
        current_sig = record_signature(rma)
        base_matches = body.get("base_signature") == current_sig

        errors: list[dict] = []
        if not body.get("submission_id"):
            errors.append(field_error("submission_id", "REQUIRED", "submission_id is required."))
        if (body.get("inspector") or "").lower() != inspector["username"].lower():
            errors.append(field_error("inspector", "INSPECTOR_MISMATCH", "Inspector doesn't match X-Inspector."))
        summary = body.get("summary")
        for f in READ_ONLY_FIELDS:
            if f in body or f in (summary or {}):
                errors.append(field_error(f, "READ_ONLY_FIELD", f"{f} is an RMA-team field and can't be changed from the tablet."))

        status_change, parts = body.get("status_change"), body.get("parts")
        for cap, used in (("findings", parts is not None), ("summary", bool(summary)), ("status", bool(status_change))):
            if used:
                require_capability(rma, cap)
        require_open(rma)

        if status_change:
            to = status_change.get("to")
            if not lookup("inspection_statuses", to):
                errors.append(field_error("status_change.to", "UNKNOWN_STATUS", f"'{code_label(to)}' is not an inspection status."))
            if base_matches and _key(status_change.get("from")) != (None, rma["header"]["inspection_status"]):
                errors.append(field_error("status_change.from", "STATUS_FROM_MISMATCH", "status_change.from doesn't match the loaded status."))
        if summary:
            _validate_summary(summary, errors)
        if parts is not None:
            # Row ids are only checkable against the record they came from; a stale base gets 409 below instead.
            _validate_parts(parts, set(row_ids(rma)) if base_matches else None, errors)
        raise_validation(errors)

        # Workbench order: validate -> lock check (fail closed) -> signature compare -> write.
        check_lock(rma)
        if not base_matches:
            raise ApiError(409, "RMA_CHANGED", f"Someone changed RMA {rma['display_number']} after you loaded it. Nothing was saved.",
                           details={"base_signature": body.get("base_signature"), "current_signature": current_sig,
                                    "changed": rma.get("_last_changed", []), "current": detail(rma)})

        old_ids = row_ids(rma)  # before any change: row ids include the record signature
        changes: dict[str, Any] = {}
        if status_change and status_change["to"]["code"] != rma["header"]["inspection_status"]:
            changes["status"] = {"from": {"group": None, "code": rma["header"]["inspection_status"]},
                                 "to": {"group": status_change["to"].get("group"), "code": status_change["to"]["code"]},
                                 "audited": True}
            rma["header"]["inspection_status"] = status_change["to"]["code"]
        if summary:
            s, fields = rma["summary"], []
            if "defect_code" in summary:
                new = None if summary["defect_code"] is None else {"group": summary["defect_code"].get("group"), "code": summary["defect_code"]["code"]}
                if new != s["defect_code"]:
                    s["defect_code"] = new
                    fields.append("defect_code")
            for b in summary.get("blocks") or []:
                bid = str(b["block_id"])
                if (b.get("text") or "") != s["blocks"].get(bid, ""):
                    s["blocks"][bid] = b.get("text") or ""
                    fields.append(f"blocks.{bid}")
            for k in ("inspected_by", "summary_by"):
                if k in summary and summary[k] != s[k]:
                    s[k] = summary[k]
                    fields.append(k)
            record = "created" if not s["exists"] else ("updated" if fields else "unchanged")
            s["exists"] = True
            changes["summary"] = {"record": record, "fields": fields}
        if parts is not None:
            old_by_id = dict(zip(old_ids, rma["parts"]))
            kept = [p["row_id"] for p in parts if p.get("row_id")]
            new_rows = [{"part_code": {"group": p["part_code"].get("group"), "code": p["part_code"]["code"]},
                         "issue_code": {"group": p["issue_code"].get("group"), "code": p["issue_code"]["code"]},
                         "cause_code": None if p.get("cause_code") is None else {"group": p["cause_code"].get("group"), "code": p["cause_code"]["code"]},
                         "note": p.get("note") or ""} for p in parts]
            changes["parts"] = {
                "inserted": sum(1 for p in parts if not p.get("row_id")),
                "updated": sum(1 for p, n in zip(parts, new_rows) if p.get("row_id") and old_by_id[p["row_id"]] != n),
                "deleted": sum(1 for rid in old_ids if rid not in kept),
                "reordered": kept != [rid for rid in old_ids if rid in kept],
            }
            rma["parts"] = new_rows
        rma["last_act_dt"] = datetime.now(EASTERN).date().isoformat()
        rma["_last_changed"] = []

        saved_parts = [{"row_id": rid, "part_code": p["part_code"], "issue_code": p["issue_code"], "cause_code": p["cause_code"]}
                       for rid, p in zip(row_ids(rma), rma["parts"])]
        result = {"result": "saved", "submission_id": body["submission_id"], "replayed": False, "rma_id": rma["rma_id"],
                  "new_signature": record_signature(rma), "verified": True, "changes": changes,
                  "parts": json.loads(json.dumps(saved_parts)), "warnings": []}
        remember(body["submission_id"], 200, result)
        return result


# --- photos

@api.post("/rmas/{rma_id}/photos", status_code=201)
async def upload_photo(rma_id: str, response: Response, meta: str = Form(...), file: UploadFile = File(...),
                       x_inspector: Optional[str] = Header(default=None)):
    m = json.loads(meta)
    with store.lock:
        hit = replay(m.get("photo_id"))
        if hit:
            response.status_code = 200
            return hit[1]
        rma = get_rma(rma_id)
        require_inspector(x_inspector)
        require_capability(rma, "photos")
        data = await file.read()
        if file.content_type != "image/jpeg" or not data.startswith(b"\xff\xd8"):
            raise ApiError(415, "UNSUPPORTED_MEDIA_TYPE", "Photos must be JPEG.")
        if len(data) > PHOTO_MAX_BYTES:
            raise ApiError(413, "PAYLOAD_TOO_LARGE", "Photo is larger than 10 MB.")
        if m.get("sha256") and hashlib.sha256(data).hexdigest() != m["sha256"]:
            raise ApiError(400, "BAD_REQUEST", "Photo checksum didn't match — please re-send.", retryable=True,
                           details={"reason": "checksum"})
        errors, seq = [], 0
        if m.get("kind") == "part":
            part, issue = lookup("parts", m.get("part_code")), lookup("issues", m.get("issue_code"))
            if not part:
                errors.append(field_error("meta.part_code", "UNKNOWN_CODE", f"Part code '{code_label(m.get('part_code'))}' is not in the part list."))
            if not issue:
                errors.append(field_error("meta.issue_code", "UNKNOWN_CODE", f"Issue code '{code_label(m.get('issue_code'))}' is not in the issue list."))
            seq = int(m.get("seq") or 0)
            if seq < 1:
                errors.append(field_error("meta.seq", "BAD_SEQ", "Part photos need a row position of 1 or more."))
        elif m.get("kind") == "rma":
            if m.get("tag") not in fd.PHOTO_TAGS:
                errors.append(field_error("meta.tag", "BAD_TAG", f"'{m.get('tag')}' is not a photo tag."))
            if m.get("tag") == "Other" and not (m.get("tag_text") or "").strip():
                errors.append(field_error("meta.tag_text", "REQUIRED", "Describe the photo when the tag is Other."))
        else:
            errors.append(field_error("meta.kind", "BAD_KIND", "kind must be 'part' or 'rma'."))
        raise_validation(errors)

        folder_created = not rma["folder_exists"]
        rma["folder_exists"] = True
        n = naming.next_photo_number(rma["all_pics"], seq)
        if m["kind"] == "part":
            name = naming.part_photo(seq, n, part["description"], issue["code"], issue["description"])
        else:
            name = naming.rma_photo(n, m["tag"], m.get("tag_text"))
        rma["all_pics"].append(name)
        store.files[(rma["rma_id"], "all", name)] = data
        result = {"photo_id": m.get("photo_id"), "replayed": False, "filename": name,
                  "folder_display": folder_display(rma, "All Pics"), "folder_created": folder_created,
                  "bytes": len(data), "sha256_ok": bool(m.get("sha256"))}
        remember(m.get("photo_id"), 200, result)
        return result


@api.get("/rmas/{rma_id}/photos")
def list_photos(rma_id: str):
    rma = get_rma(rma_id)

    def entry(folder, name):
        return {"filename": name, "bytes": len(store.files.get((rma["rma_id"], folder, name), b"")), "modified": now()}
    return {"folder_found": rma["folder_exists"], "folder_display": folder_display(rma),
            "all_pics": [entry("all", n) for n in naming.natural_sorted(rma["all_pics"])],
            "report_pics": {"folder_found": rma["report_folder_exists"], "signature": report_pics_signature(rma),
                            "files": naming.natural_sorted(rma["report_pics"])}}


@api.get("/rmas/{rma_id}/photos/{folder}/{filename}")
def get_photo(rma_id: str, folder: str, filename: str, size: str = "full"):
    rma = get_rma(rma_id)
    key = {"all": "all", "report": "report"}.get(folder)
    data = store.files.get((rma["rma_id"], key, filename)) if key else None
    if data is None:
        raise ApiError(404, "PHOTO_NOT_FOUND", "That photo is no longer in the folder.")
    return Response(content=data, media_type="image/jpeg")  # mock: thumbnails are the original bytes


# --- report photos

@api.put("/rmas/{rma_id}/report-pics")
async def put_report_pics(rma_id: str, request: Request, x_inspector: Optional[str] = Header(default=None)):
    body = await request.json()
    with store.lock:
        hit = replay(body.get("submission_id"))
        if hit:
            return JSONResponse(hit[1], status_code=hit[0])
        rma = get_rma(rma_id)
        require_inspector(x_inspector)
        require_capability(rma, "report")
        current = report_pics_signature(rma)
        if body.get("base_signature") != current:
            raise ApiError(409, "REPORT_PICS_CHANGED", "Report photos were changed elsewhere after you loaded them. Please reload.",
                           details={"current_signature": current, "current": naming.natural_sorted(rma["report_pics"])})
        errors, targets = [], []
        for i, p in enumerate(body.get("photos", [])):
            src, fname, cap = p.get("source"), p.get("filename", ""), (p.get("caption") or "")
            pool = rma["all_pics"] if src == "all_pics" else rma["report_pics"] if src == "report_pics" else None
            if pool is None or fname not in pool:
                errors.append(field_error(f"photos[{i}].filename", "SOURCE_NOT_FOUND", f"'{fname}' is no longer in the folder."))
                continue
            if not naming.sanitize(cap):
                errors.append(field_error(f"photos[{i}].caption", "CAPTION_EMPTY", "Caption can't be empty."))
                continue
            if len(naming.sanitize(cap)) + len(naming.EXT) > naming.NAME_MAX:
                errors.append(field_error(f"photos[{i}].caption", "CAPTION_TOO_LONG", "Caption is longer than 116 characters."))
            targets.append((src, fname, naming.caption_to_filename(cap)))
        names = [t[2].lower() for t in targets]
        for i, t in enumerate(targets):
            if names.count(t[2].lower()) > 1:
                errors.append(field_error(f"photos[{i}].caption", "CAPTION_DUPLICATE", f"Two photos would both be named '{t[2]}'."))
        raise_validation(errors)

        copied = renamed = kept = 0
        new_files: dict[str, bytes] = {}
        for src, fname, target in targets:
            folder = "all" if src == "all_pics" else "report"
            new_files[target] = store.files[(rma["rma_id"], folder, fname)]
            if src == "all_pics":
                copied += 1
            elif fname != target:
                renamed += 1
            else:
                kept += 1
        keep_sources = {f for s, f, _ in targets if s == "report_pics"}
        removed = [f for f in rma["report_pics"] if f not in keep_sources]
        stamp = datetime.now(EASTERN).strftime("%Y%m%d-%H%M%S")
        for f in removed:
            store.backups.append({"rma_id": rma["rma_id"], "file": f, "to": f"backups\\report_pics\\{rma['display_number']}\\{stamp}\\{f}"})
        for f in rma["report_pics"]:
            store.files.pop((rma["rma_id"], "report", f), None)
        for name, data in new_files.items():
            store.files[(rma["rma_id"], "report", name)] = data
        folder_created = not rma["report_folder_exists"] and bool(new_files)
        rma["report_folder_exists"] = rma["report_folder_exists"] or bool(new_files)
        rma["folder_exists"] = rma["folder_exists"] or bool(new_files)
        rma["report_pics"] = list(new_files.keys())
        result = {"result": "saved", "replayed": False, "new_signature": report_pics_signature(rma),
                  "folder_created": folder_created,
                  "changes": {"copied": copied, "renamed": renamed, "removed": len(removed), "kept": kept},
                  "report_pics": naming.natural_sorted(rma["report_pics"])}
        remember(body.get("submission_id"), 200, result)
        return result


# --- report

def _fake_pdf(title: str, pages: int) -> bytes:
    """Minimal valid PDF (mock only)."""
    objs = ["<< /Type /Catalog /Pages 2 0 R >>"]
    kids = " ".join(f"{3 + i * 2} 0 R" for i in range(pages))
    objs.append(f"<< /Type /Pages /Kids [{kids}] /Count {pages} >>")
    for i in range(pages):
        text = f"BT /F1 18 Tf 72 720 Td ({title} - page {i + 1} of {pages}) Tj ET".encode()
        objs.append(f"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents {4 + i * 2} 0 R "
                    f"/Resources << /Font << /F1 << /Type /Font /Subtype /Type1 /BaseFont /Helvetica >> >> >> >>")
        objs.append(f"<< /Length {len(text)} >>\nstream\n{text.decode()}\nendstream")
    out = bytearray(b"%PDF-1.4\n")
    offsets = []
    for n, o in enumerate(objs, start=1):
        offsets.append(len(out))
        out += f"{n} 0 obj\n{o}\nendobj\n".encode()
    xref = len(out)
    out += f"xref\n0 {len(objs) + 1}\n0000000000 65535 f \n".encode()
    for off in offsets:
        out += f"{off:010d} 00000 n \n".encode()
    out += f"trailer\n<< /Size {len(objs) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode()
    return bytes(out)


@api.post("/rmas/{rma_id}/report")
async def build_report(rma_id: str, request: Request, x_inspector: Optional[str] = Header(default=None)):
    body = await request.json()
    with store.lock:
        hit = replay(body.get("submission_id"))
        if hit:
            return JSONResponse(hit[1], status_code=hit[0])
        rma = get_rma(rma_id)
        require_inspector(x_inspector)
        require_capability(rma, "report")
        require_open(rma)
        num = rma["display_number"]
        if rma["rma_id"] in store.report_in_use:
            raise ApiError(409, "REPORT_FILE_IN_USE", f"{num} Report.pdf is open on someone's PC and can't be replaced.",
                           retryable=True, details={"filename": f"{num} Report.pdf"})
        warnings = []
        credit = rma["header"]["credit_status"]
        if not credit:
            warnings.append({"code": "NO_CREDIT_STATUS", "message": 'No credit status yet — page 1 says "Disposition Pending".'})
        elif credit.split(" - ")[0] in fd.NO_LETTER_TEXT_CREDIT:
            warnings.append({"code": "NO_LETTER_TEXT", "message": f"Credit status '{credit}' has no disposition letter text."})
        if not rma["report_pics"]:
            warnings.append({"code": "NO_REPORT_PICS", "message": "No report photos were picked."})
        if not any(rma["summary"]["blocks"].values()):
            warnings.append({"code": "EMPTY_SUMMARY", "message": "The summaries are empty."})
        photo_count = len(rma["report_pics"])
        pages = 2 + math.ceil(photo_count / 6)
        pdf = _fake_pdf(f"MOCK REPORT RMA {num}", pages)
        previous = rma["report"] is not None
        rma["report"] = {"generated_at": now(), "pdf": pdf, "pages": pages, "xlsx_bytes": 8800}
        result = {"result": "built", "replayed": False, "generated_at": rma["report"]["generated_at"],
                  "files": [{"format": "pdf", "filename": f"{num} Report.pdf", "bytes": len(pdf), "pages": pages},
                            {"format": "xlsx", "filename": f"{num} Report.xlsx", "bytes": 8800}],
                  "photo_count": photo_count, "previous_backed_up": previous, "status_changed": None,
                  "warnings": warnings}
        remember(body.get("submission_id"), 200, result)
        return result


@api.get("/rmas/{rma_id}/report")
def report_info(rma_id: str):
    rma = get_rma(rma_id)
    rep, num = rma["report"], rma["display_number"]
    if not rep:
        return {"exists": False, "generated_at": None, "files": []}
    return {"exists": True, "generated_at": rep["generated_at"],
            "files": [{"format": "pdf", "filename": f"{num} Report.pdf", "bytes": len(rep["pdf"])},
                      {"format": "xlsx", "filename": f"{num} Report.xlsx", "bytes": rep["xlsx_bytes"]}]}


@api.get("/rmas/{rma_id}/report/pdf")
def report_pdf(rma_id: str):
    rma = get_rma(rma_id)
    if not rma["report"]:
        raise ApiError(404, "REPORT_NOT_FOUND", "No report has been built for this RMA yet.")
    return Response(content=rma["report"]["pdf"], media_type="application/pdf")


MISSPELLINGS = {"scorring": ["scoring"], "compresser": ["compressor"], "bearring": ["bearing"],
                "lubercation": ["lubrication"], "termnal": ["terminal"], "windng": ["winding"]}


@api.post("/spellcheck")
async def spellcheck(request: Request):
    text = (await request.json()).get("text", "")
    issues = [{"word": m.group(0), "start": m.start(), "end": m.end(), "suggestions": MISSPELLINGS[m.group(0).lower()]}
              for m in re.finditer(r"[A-Za-z]+", text) if m.group(0).lower() in MISSPELLINGS]
    return {"issues": issues}


# --- mock-only test hooks

@api.post("/_mock/reset")
def mock_reset():
    store.reset()
    return {"ok": True}


@api.post("/_mock/rmas/{rma_id}/desk-edit")
async def mock_desk_edit(rma_id: str, request: Request):
    """Simulate an edit made elsewhere (workbench / SAP GUI).
    Body (any subset): {"header": {...}, "summary": {"blocks": {"1": "text"}, "defect_code": {...}},
                        "parts": [ {part_code, issue_code, cause_code, note} ], "report_pics": [names]}"""
    body = await request.json()
    with store.lock:
        rma = get_rma(rma_id)
        before = json.loads(json.dumps({"header": rma["header"], "summary": rma["summary"], "parts": rma["parts"]}))
        rma["header"].update(body.get("header", {}))
        if "summary" in body:
            sm = body["summary"]
            if "blocks" in sm:
                rma["summary"]["blocks"].update(sm["blocks"])
            for k in ("defect_code", "inspected_by", "summary_by"):
                if k in sm:
                    rma["summary"][k] = sm[k]
            rma["summary"]["exists"] = True
        if "parts" in body:
            rma["parts"] = body["parts"]
        if "report_pics" in body:
            for f in rma["report_pics"]:
                store.files.pop((rma["rma_id"], "report", f), None)
            rma["report_pics"] = list(body["report_pics"])
            for f in rma["report_pics"]:
                store.files[(rma["rma_id"], "report", f)] = FAKE_JPEG
            rma["report_folder_exists"] = True
        rma["_last_changed"] = changed_fields(before, {"header": rma["header"], "summary": rma["summary"], "parts": rma["parts"]})
        return {"ok": True, "signature": record_signature(rma), "changed": rma["_last_changed"]}


@api.post("/_mock/rmas/{rma_id}/lock")
async def mock_lock(rma_id: str, request: Request):
    body = await request.json()
    rma = get_rma(rma_id)
    rma["lock"] = {"locked": bool(body.get("locked")), "held_by": body.get("held_by"), "host": body.get("host"),
                   "possibly_stale": bool(body.get("possibly_stale"))}
    return {"ok": True, "lock": rma["lock"]}


@api.post("/_mock/rmas/{rma_id}/report-in-use")
async def mock_report_in_use(rma_id: str, request: Request):
    body = await request.json()
    rma = get_rma(rma_id)
    (store.report_in_use.add if body.get("in_use") else store.report_in_use.discard)(rma["rma_id"])
    return {"ok": True}


app.include_router(api)
