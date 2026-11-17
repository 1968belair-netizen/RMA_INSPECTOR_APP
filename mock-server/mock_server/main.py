"""Mock RMA app server — implements docs/api.md with in-memory FAKE data.

Run:  python -m uvicorn mock_server.main:app --host 0.0.0.0 --port 8000
The Android emulator reaches it at http://10.0.2.2:8000/api/v1

Test hooks (mock only, under /api/v1/_mock): reset, desk-edit (simulate a change at the workbench),
lock / unlock, report-file-in-use. They let the app exercise conflict and lock paths.
"""
from __future__ import annotations

import hashlib
import json
import math
import re
import threading
import uuid
from datetime import datetime, timezone, timedelta
from typing import Any, Optional

from fastapi import APIRouter, FastAPI, File, Form, Header, Request, Response, UploadFile
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from . import fake_data as fd
from . import naming

API_VERSION = "1.0"
SERVER_VERSION = "0.1.0-mock"
MIN_APP_VERSION = "0.1.0"
EASTERN = timezone(timedelta(hours=-5))
PHOTO_MAX_BYTES = 10 * 1024 * 1024
LIMITS = {"part_no": 15, "issue_cd": 15, "note": 4000, "defect_cd": 10, "summary": 4000,
          "inspected_by": 255, "summary_by": 255, "photo_tag_text": 40, "photo_max_bytes": PHOTO_MAX_BYTES}
SUMMARY_FIELDS = ("defect_cd", "summary_1", "summary_2", "summary_3", "inspected_by", "summary_by")
READ_ONLY_FIELDS = ("credit_status", "return_code", "failure_cd", "failure_description", "comments")
WORK_LIST_DEFAULT = ["Received", "Rework in Progress", "Inspected", "Report Complete"]

# Smallest valid JPEG-ish payload for seeded photos (starts with the JPEG SOI marker).
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


# ---------------------------------------------------------------- state

class Store:
    def __init__(self):
        self.lock = threading.RLock()
        self.reset()

    def reset(self):
        with self.lock:
            self.rmas = fd.initial_rmas()
            self.files: dict[tuple[str, str, str], bytes] = {}   # (rma, "all"|"report", filename) -> bytes
            self.replays: dict[str, tuple[int, dict]] = {}       # submission_id/photo_id -> (status, body)
            self.backups: list[dict] = []
            self.report_in_use: set[str] = set()
            # Seed 912347 with photos so the report-photo screens have something to show.
            r = self.rmas["912347"]
            for name in ("00-1 As received.jpg", "01-1 Stator - 46 Burnt.jpg", "02-1 Winding to ground insulation - 38 Ground fault.jpg"):
                r["all_pics"].append(name)
                self.files[("912347", "all", name)] = FAKE_JPEG
            r["report_pics"].append("01-1 Stator - 46 Burnt.jpg")
            self.files[("912347", "report", "01-1 Stator - 46 Burnt.jpg")] = FAKE_JPEG
            r["folder_exists"] = True
            for k, v in self.rmas.items():
                v.setdefault("folder_exists", False)
                v.setdefault("report_folder_exists", bool(v["report_pics"]))


store = Store()

# ---------------------------------------------------------------- helpers

CODE_PARTS = dict(fd.PARTS)
CODE_ISSUES = dict(fd.ISSUES)
CODE_DEFECTS = dict(fd.DEFECT_CODES)


def product_key(rma: dict) -> tuple[str, str]:
    raw = rma["product_type"]
    keys = dict(fd.PRODUCT_TYPES)
    if raw:
        return keys.get(raw, "unknown"), "header"
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
    if not rma["macola_open"]:
        caps = {k: False for k in caps}
    return caps


def folder_display(rma_no: str, sub: Optional[str] = None) -> str:
    base = f"{rma_no[:3]}xxx\\{rma_no[:4]}xx\\{rma_no}"
    return base + (f"\\{sub}" if sub else "")


def record_signature(rma: dict) -> str:
    """Change check over the WHOLE record: header (incl. RMA-team fields), summary row, parts (api.md §12)."""
    payload = {"header": rma["header"], "summary": rma["summary"], "parts": rma["parts"],
               "macola_open": rma["macola_open"]}
    return "sig-" + hashlib.sha256(json.dumps(payload, sort_keys=True).encode()).hexdigest()[:10]


def report_pics_signature(rma: dict) -> str:
    if not rma["report_pics"]:
        return "rp-empty"
    return "rp-" + hashlib.sha256("\n".join(naming.natural_sorted(rma["report_pics"])).encode()).hexdigest()[:6]


def get_rma(rma_no: str) -> dict:
    r = store.rmas.get(rma_no.strip())
    if not r:
        raise ApiError(404, "RMA_NOT_FOUND", f"RMA {rma_no} was not found.")
    return r


def parts_view(rma: dict) -> list[dict]:
    return [{"line_no": i + 1, "part_no": p["part_no"], "part_description": CODE_PARTS.get(p["part_no"], ""),
             "issue_cd": p["issue_cd"], "issue_description": CODE_ISSUES.get(p["issue_cd"], ""), "note": p["note"]}
            for i, p in enumerate(rma["parts"])]


def summary_view(rma: dict) -> dict:
    s = dict(rma["summary"])
    s["defect_description"] = CODE_DEFECTS.get(s.get("defect_cd", ""), "")
    return s


def list_item(rma: dict) -> dict:
    key, _ = product_key(rma)
    return {"rma": rma["rma"], "model": rma["header"]["model"], "failed_serial_no": rma["header"]["failed_serial_no"],
            "product_type": rma["product_type"], "product_type_key": key,
            "inspection_status": rma["header"]["inspection_status"], "macola_open": rma["macola_open"],
            "has_inspection_record": rma["has_inspection_record"], "lock_locked": rma["lock"]["locked"]}


def lock_view(rma: dict) -> dict:
    return {**rma["lock"], "checked_at": now()}


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
    if not rma["macola_open"] or not rma["has_inspection_record"]:
        raise ApiError(409, "RMA_NOT_OPEN", f"RMA {rma['rma']} is closed in Macola. Nothing was saved.",
                       details={"macola_open": rma["macola_open"], "has_inspection_record": rma["has_inspection_record"]})


def check_lock(rma: dict):
    """Fail closed, like the workbench (RMA_DATA_MAP §5)."""
    if rma["lock_check_fails"]:
        raise ApiError(503, "LOCK_CHECK_FAILED", "Couldn't check the Macola lock, so nothing was saved. Will retry.",
                       retryable=True, retry_after=60)
    lk = rma["lock"]
    if lk["locked"]:
        if lk["possibly_stale"]:
            msg = f"RMA {rma['rma']} has a possible stale Macola lock. Nothing was saved. Ask IT to clear it."
        else:
            msg = f"RMA {rma['rma']} is open in Macola by {lk['held_by'] or 'another user'}" \
                  f"{' (' + lk['host'] + ')' if lk['host'] else ''}. Nothing was saved."
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
    out = []
    for k in new["header"]:
        if old["header"].get(k) != new["header"][k]:
            out.append("inspection_status" if k == "inspection_status" else k)
    if old["summary"] != new["summary"]:
        out.append("summary")
    if old["parts"] != new["parts"]:
        out.append("parts")
    return out


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


# --- health / inspectors / code lists

@api.get("/health")
def health():
    return {"status": "ok", "api_version": API_VERSION, "server_version": SERVER_VERSION,
            "min_app_version": MIN_APP_VERSION, "backend": "mock", "macola_reachable": True,
            "fileshare_reachable": True, "server_time": now()}


@api.get("/inspectors")
def inspectors():
    return {"inspectors": fd.INSPECTORS, "max_inspectors": 10, "sign_in_required": False}


def _codelists() -> dict:
    return {
        "version": "2026-11-17T06:00:00-05:00",
        "source": "mock",
        "parts": [{"code": c, "description": d} for c, d in fd.PARTS],
        "issues": [{"code": c, "description": d, **({"pin_first": True} if c == "50" else {})} for c, d in fd.ISSUES],
        "defect_codes": [{"code": c, "description": d} for c, d in fd.DEFECT_CODES],
        "inspection_statuses": fd.INSPECTION_STATUSES,
        "status_rule": "any",
        "product_types": [{"value": v, "key": k, "capabilities": capabilities_for(k)} for v, k in fd.PRODUCT_TYPES],
        "hidden": {"parts": [], "issues": []},
        "photo_tags": fd.PHOTO_TAGS,
        "work_list_statuses": WORK_LIST_DEFAULT,
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


# --- lookup

@api.get("/rmas/search")
def search(q: str):
    q = q.strip()
    results, matched_on = [], "none"
    if re.fullmatch(r"[0-9]{1,8}", q) and q in store.rmas:
        results, matched_on = [list_item(store.rmas[q])], "rma"
    if not results:
        hits = [list_item(r) for r in store.rmas.values()
                if r["header"]["failed_serial_no"] and r["header"]["failed_serial_no"].lower() == q.lower()]
        if hits:
            results, matched_on = hits, "serial"
    return {"query": q, "matched_on": matched_on, "results": results}


@api.get("/rmas")
def work_list(inspection_status: Optional[str] = None, limit: int = 50):
    wanted = [s.strip() for s in inspection_status.split(",")] if inspection_status else WORK_LIST_DEFAULT
    rows = [r for r in store.rmas.values()
            if r["macola_open"] and r["has_inspection_record"] and r["header"]["inspection_status"] in wanted]
    rows.sort(key=lambda r: (r["last_act_dt"], r["rma"]), reverse=True)
    return {"results": [list_item(r) for r in rows[:limit]], "generated_at": now()}


# --- detail

def detail(rma: dict) -> dict:
    key, source = product_key(rma)
    return {
        "rma": rma["rma"], "signature": record_signature(rma), "loaded_at": now(),
        "macola_open": rma["macola_open"], "has_inspection_record": rma["has_inspection_record"],
        "product_type": rma["product_type"], "product_type_key": key, "product_type_source": source,
        "capabilities": rma_capabilities(rma),
        "header": rma["header"], "lock": lock_view(rma), "parts": parts_view(rma), "summary": summary_view(rma),
        "photos": {"folder_found": rma["folder_exists"], "folder_display": folder_display(rma["rma"], "All Pics"),
                   "count": len(rma["all_pics"]), "files": naming.natural_sorted(rma["all_pics"])},
    }


@api.get("/rmas/{rma_no}")
def rma_detail(rma_no: str):
    return detail(get_rma(rma_no))


@api.get("/rmas/{rma_no}/lock")
def rma_lock(rma_no: str):
    return lock_view(get_rma(rma_no))


# --- submit inspection

@api.post("/rmas/{rma_no}/inspection")
async def submit_inspection(rma_no: str, request: Request, x_inspector: Optional[str] = Header(default=None)):
    body: dict[str, Any] = await request.json()
    with store.lock:
        hit = replay(body.get("submission_id"))
        if hit:
            return JSONResponse(hit[1], status_code=hit[0])
        rma = get_rma(rma_no)
        inspector = require_inspector(x_inspector)

        errors = []
        if not body.get("submission_id"):
            errors.append(field_error("submission_id", "REQUIRED", "submission_id is required."))
        if body.get("inspector", "").lower() != inspector["username"].lower():
            errors.append(field_error("inspector", "INSPECTOR_MISMATCH", "Inspector doesn't match X-Inspector."))
        for f in READ_ONLY_FIELDS:
            if f in body or f in (body.get("summary") or {}):
                errors.append(field_error(f, "READ_ONLY_FIELD", f"{f} is an RMA-team field and can't be changed from the tablet."))

        status_change = body.get("status_change")
        summary = body.get("summary")
        parts = body.get("parts")
        uses = []
        if parts is not None:
            uses.append("findings")
        if summary:
            uses.append("summary")
        if status_change:
            uses.append("status")
        for cap in uses:
            require_capability(rma, cap)
        require_open(rma)

        if status_change:
            if status_change.get("to") not in fd.INSPECTION_STATUSES:
                errors.append(field_error("status_change.to", "UNKNOWN_STATUS", f"'{status_change.get('to')}' is not an inspection status."))
            if status_change.get("from") != rma["header"]["inspection_status"] and body.get("base_signature") == record_signature(rma):
                errors.append(field_error("status_change.from", "STATUS_FROM_MISMATCH", "status_change.from doesn't match the loaded status."))
        if summary:
            for k, v in summary.items():
                if k not in SUMMARY_FIELDS:
                    if k not in READ_ONLY_FIELDS:
                        errors.append(field_error(f"summary.{k}", "UNKNOWN_FIELD", f"'{k}' is not a summary field."))
                    continue
                limit = LIMITS["summary"] if k.startswith("summary_") else LIMITS[k]
                if len(v) > limit:
                    errors.append(field_error(f"summary.{k}", "TOO_LONG", f"{k} is {len(v):,} characters; the limit is {limit:,}.", limit=limit))
                if k == "defect_cd" and v and v not in CODE_DEFECTS:
                    errors.append(field_error("summary.defect_cd", "UNKNOWN_CODE", f"Defect code '{v}' is not in the defect code list."))
        if parts is not None:
            n_old = len(rma["parts"])
            for i, p in enumerate(parts):
                pn, ic, note = p.get("part_no", ""), p.get("issue_cd", ""), p.get("note", "")
                if pn not in CODE_PARTS:
                    errors.append(field_error(f"parts[{i}].part_no", "UNKNOWN_CODE", f"Part code '{pn}' is not in the part list."))
                if ic not in CODE_ISSUES:
                    errors.append(field_error(f"parts[{i}].issue_cd", "UNKNOWN_CODE", f"Issue code '{ic}' is not in the issue list."))
                if len(note) > LIMITS["note"]:
                    errors.append(field_error(f"parts[{i}].note", "TOO_LONG", f"Note is {len(note):,} characters; the limit is 4,000.", limit=4000))
                o = p.get("orig_line_no")
                if o is not None and not (1 <= o <= n_old):
                    errors.append(field_error(f"parts[{i}].orig_line_no", "UNKNOWN_LINE", f"Line {o} wasn't in the loaded RMA."))
        raise_validation(errors)

        # Workbench order: validate -> lock check (fail closed) -> signature compare -> write.
        check_lock(rma)
        current_sig = record_signature(rma)
        if body.get("base_signature") != current_sig:
            raise ApiError(409, "RMA_CHANGED", f"Someone changed RMA {rma['rma']} after you loaded it. Nothing was saved.",
                           details={"base_signature": body.get("base_signature"), "current_signature": current_sig,
                                    "changed": rma.get("_last_changed", []),
                                    "current": {"header": rma["header"], "inspection_status": rma["header"]["inspection_status"],
                                                "summary": summary_view(rma), "parts": parts_view(rma)}})

        changes: dict[str, Any] = {}
        if status_change and status_change["to"] != rma["header"]["inspection_status"]:
            changes["status"] = {"from": rma["header"]["inspection_status"], "to": status_change["to"], "audited": True}
            rma["header"]["inspection_status"] = status_change["to"]
        if summary:
            fields = [k for k in SUMMARY_FIELDS if k in summary and summary[k] != rma["summary"].get(k, "")]
            row = "inserted" if not rma["summary"]["exists"] else ("updated" if fields else "unchanged")
            for k in fields:
                rma["summary"][k] = summary[k]
            rma["summary"]["exists"] = True
            changes["summary"] = {"row": row, "fields": fields}
        if parts is not None:
            old = rma["parts"]
            kept = {p["orig_line_no"] for p in parts if p.get("orig_line_no")}
            inserted = sum(1 for p in parts if not p.get("orig_line_no"))
            deleted = sum(1 for i in range(1, len(old) + 1) if i not in kept)
            updated = sum(1 for p in parts if p.get("orig_line_no") and
                          {k: p.get(k, "") for k in ("part_no", "issue_cd", "note")} != old[p["orig_line_no"] - 1])
            renumbered = any(p.get("orig_line_no") and p["orig_line_no"] != i + 1 for i, p in enumerate(parts))
            rma["parts"] = [{"part_no": p["part_no"], "issue_cd": p["issue_cd"], "note": p.get("note", "")} for p in parts]
            changes["parts"] = {"inserted": inserted, "updated": updated, "deleted": deleted, "renumbered": renumbered}
        rma["last_act_dt"] = datetime.now(EASTERN).date().isoformat()
        rma["_last_changed"] = []

        result = {"result": "saved", "submission_id": body["submission_id"], "replayed": False, "rma": rma["rma"],
                  "new_signature": record_signature(rma), "verified": True, "changes": changes,
                  "parts": [{"line_no": p["line_no"], "part_no": p["part_no"], "issue_cd": p["issue_cd"]} for p in parts_view(rma)],
                  "warnings": []}
        remember(body["submission_id"], 200, result)
        return result


# --- photos

@api.post("/rmas/{rma_no}/photos", status_code=201)
async def upload_photo(rma_no: str, response: Response, meta: str = Form(...), file: UploadFile = File(...),
                       x_inspector: Optional[str] = Header(default=None)):
    m = json.loads(meta)
    with store.lock:
        hit = replay(m.get("photo_id"))
        if hit:
            response.status_code = 200
            return hit[1]
        rma = get_rma(rma_no)
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
        errors = []
        if m.get("kind") == "part":
            if m.get("part_no") not in CODE_PARTS:
                errors.append(field_error("meta.part_no", "UNKNOWN_CODE", f"Part code '{m.get('part_no')}' is not in the part list."))
            if m.get("issue_cd") not in CODE_ISSUES:
                errors.append(field_error("meta.issue_cd", "UNKNOWN_CODE", f"Issue code '{m.get('issue_cd')}' is not in the issue list."))
            seq = int(m.get("seq") or 0)
            if seq < 1:
                errors.append(field_error("meta.seq", "BAD_SEQ", "Part photos need a row position of 1 or more."))
        elif m.get("kind") == "rma":
            seq = 0
            if m.get("tag") not in fd.PHOTO_TAGS:
                errors.append(field_error("meta.tag", "BAD_TAG", f"'{m.get('tag')}' is not a photo tag."))
            if m.get("tag") == "Other" and not (m.get("tag_text") or "").strip():
                errors.append(field_error("meta.tag_text", "REQUIRED", "Describe the photo when the tag is Other."))
        else:
            errors.append(field_error("meta.kind", "BAD_KIND", "kind must be 'part' or 'rma'."))
            seq = 0
        raise_validation(errors)

        folder_created = not rma["folder_exists"]
        rma["folder_exists"] = True
        n = naming.next_photo_number(rma["all_pics"], seq)
        if m["kind"] == "part":
            name = naming.part_photo(seq, n, CODE_PARTS[m["part_no"]], m["issue_cd"], CODE_ISSUES[m["issue_cd"]])
        else:
            name = naming.rma_photo(n, m["tag"], m.get("tag_text"))
        rma["all_pics"].append(name)
        store.files[(rma["rma"], "all", name)] = data
        result = {"photo_id": m.get("photo_id"), "replayed": False, "filename": name,
                  "folder_display": folder_display(rma["rma"], "All Pics"), "folder_created": folder_created,
                  "bytes": len(data), "sha256_ok": bool(m.get("sha256"))}
        remember(m.get("photo_id"), 200, result)
        return result


@api.get("/rmas/{rma_no}/photos")
def list_photos(rma_no: str):
    rma = get_rma(rma_no)
    def entry(folder, name):
        return {"filename": name, "bytes": len(store.files.get((rma["rma"], folder, name), b"")), "modified": now()}
    return {"folder_found": rma["folder_exists"], "folder_display": folder_display(rma["rma"]),
            "all_pics": [entry("all", n) for n in naming.natural_sorted(rma["all_pics"])],
            "report_pics": {"folder_found": rma["report_folder_exists"], "signature": report_pics_signature(rma),
                            "files": naming.natural_sorted(rma["report_pics"])}}


@api.get("/rmas/{rma_no}/photos/{folder}/{filename}")
def get_photo(rma_no: str, folder: str, filename: str, size: str = "full"):
    rma = get_rma(rma_no)
    key = {"all": "all", "report": "report"}.get(folder)
    data = store.files.get((rma["rma"], key, filename)) if key else None
    if data is None:
        raise ApiError(404, "PHOTO_NOT_FOUND", "That photo is no longer in the folder.")
    # Mock: thumbnails are the original bytes (the real server makes 320 px thumbnails).
    return Response(content=data, media_type="image/jpeg")


# --- report photos

@api.put("/rmas/{rma_no}/report-pics")
async def put_report_pics(rma_no: str, request: Request, x_inspector: Optional[str] = Header(default=None)):
    body = await request.json()
    with store.lock:
        hit = replay(body.get("submission_id"))
        if hit:
            return JSONResponse(hit[1], status_code=hit[0])
        rma = get_rma(rma_no)
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
            new_files[target] = store.files[(rma["rma"], folder, fname)]
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
            store.backups.append({"rma": rma["rma"], "file": f, "to": f"backups\\report_pics\\{rma['rma']}\\{stamp}\\{f}"})
        for f in rma["report_pics"]:
            store.files.pop((rma["rma"], "report", f), None)
        for name, data in new_files.items():
            store.files[(rma["rma"], "report", name)] = data
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


@api.post("/rmas/{rma_no}/report")
async def build_report(rma_no: str, request: Request, x_inspector: Optional[str] = Header(default=None)):
    body = await request.json()
    with store.lock:
        hit = replay(body.get("submission_id"))
        if hit:
            return JSONResponse(hit[1], status_code=hit[0])
        rma = get_rma(rma_no)
        require_inspector(x_inspector)
        require_capability(rma, "report")
        require_open(rma)
        if rma["rma"] in store.report_in_use:
            raise ApiError(409, "REPORT_FILE_IN_USE", f"{rma['rma']} Report.pdf is open on someone's PC and can't be replaced.",
                           retryable=True, details={"filename": f"{rma['rma']} Report.pdf"})
        warnings = []
        credit = rma["header"]["credit_status"]
        if not credit:
            warnings.append({"code": "NO_CREDIT_STATUS", "message": 'No credit status yet — page 1 says "Disposition Pending".'})
        elif credit.split(" - ")[0] in fd.NO_LETTER_TEXT_CREDIT:
            warnings.append({"code": "NO_LETTER_TEXT", "message": f"Credit status '{credit}' has no disposition letter text."})
        if not rma["report_pics"]:
            warnings.append({"code": "NO_REPORT_PICS", "message": "No report photos were picked."})
        if not any(rma["summary"].get(f) for f in ("summary_1", "summary_2", "summary_3")):
            warnings.append({"code": "EMPTY_SUMMARY", "message": "The summaries are empty."})
        photo_count = len(rma["report_pics"])
        pages = 2 + math.ceil(photo_count / 6)  # letter + report page + photo pages (2 x 3 per page)
        pdf = _fake_pdf(f"MOCK REPORT RMA {rma['rma']}", pages)
        previous = rma["report"] is not None
        rma["report"] = {"generated_at": now(), "pdf": pdf, "pages": pages, "xlsx_bytes": 8800}
        result = {"result": "built", "replayed": False, "generated_at": rma["report"]["generated_at"],
                  "files": [{"format": "pdf", "filename": f"{rma['rma']} Report.pdf", "bytes": len(pdf), "pages": pages},
                            {"format": "xlsx", "filename": f"{rma['rma']} Report.xlsx", "bytes": 8800}],
                  "photo_count": photo_count, "previous_backed_up": previous, "status_changed": None,
                  "warnings": warnings}
        remember(body.get("submission_id"), 200, result)
        return result


@api.get("/rmas/{rma_no}/report")
def report_info(rma_no: str):
    rma = get_rma(rma_no)
    rep = rma["report"]
    if not rep:
        return {"exists": False, "generated_at": None, "files": []}
    return {"exists": True, "generated_at": rep["generated_at"],
            "files": [{"format": "pdf", "filename": f"{rma['rma']} Report.pdf", "bytes": len(rep["pdf"])},
                      {"format": "xlsx", "filename": f"{rma['rma']} Report.xlsx", "bytes": rep["xlsx_bytes"]}]}


@api.get("/rmas/{rma_no}/report/pdf")
def report_pdf(rma_no: str):
    rma = get_rma(rma_no)
    if not rma["report"]:
        raise ApiError(404, "REPORT_NOT_FOUND", "No report has been built for this RMA yet.")
    return Response(content=rma["report"]["pdf"], media_type="application/pdf")


# --- spell check (mock: tiny misspelling list; the real server uses rma_spell.py)

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


@api.post("/_mock/rmas/{rma_no}/desk-edit")
async def mock_desk_edit(rma_no: str, request: Request):
    """Simulate an edit at the workbench. Body: {"header": {...}, "summary": {...}, "parts": [...] } (any subset)."""
    body = await request.json()
    with store.lock:
        rma = get_rma(rma_no)
        before = json.loads(json.dumps({"header": rma["header"], "summary": rma["summary"], "parts": rma["parts"]}))
        rma["header"].update(body.get("header", {}))
        if "summary" in body:
            rma["summary"].update(body["summary"])
            rma["summary"]["exists"] = True
        if "parts" in body:
            rma["parts"] = body["parts"]
        if "report_pics" in body:
            for f in rma["report_pics"]:
                store.files.pop((rma["rma"], "report", f), None)
            rma["report_pics"] = list(body["report_pics"])
            for f in rma["report_pics"]:
                store.files[(rma["rma"], "report", f)] = FAKE_JPEG
            rma["report_folder_exists"] = True
        rma["_last_changed"] = changed_fields(before, {"header": rma["header"], "summary": rma["summary"], "parts": rma["parts"]})
        return {"ok": True, "signature": record_signature(rma), "changed": rma["_last_changed"]}


@api.post("/_mock/rmas/{rma_no}/lock")
async def mock_lock(rma_no: str, request: Request):
    """Body: {"locked": true, "held_by": "JDOE2", "host": "EXAMPLE-PC", "possibly_stale": false}"""
    body = await request.json()
    rma = get_rma(rma_no)
    rma["lock"] = {"locked": bool(body.get("locked")), "held_by": body.get("held_by"), "host": body.get("host"),
                   "possibly_stale": bool(body.get("possibly_stale"))}
    return {"ok": True, "lock": rma["lock"]}


@api.post("/_mock/rmas/{rma_no}/report-in-use")
async def mock_report_in_use(rma_no: str, request: Request):
    body = await request.json()
    rma = get_rma(rma_no)
    (store.report_in_use.add if body.get("in_use") else store.report_in_use.discard)(rma["rma"])
    return {"ok": True}


app.include_router(api)
