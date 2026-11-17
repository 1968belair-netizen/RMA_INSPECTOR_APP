"""Contract tests for docs/api.md rev 6 (SAP-ready). Fake data only."""
import hashlib
import json
import uuid

from mock_server.main import FAKE_JPEG


def sid():
    return str(uuid.uuid4())


def C(code, group=None):
    return {"group": group, "code": code}


def load(client, rma):
    return client.get(f"/api/v1/rmas/{rma}").json()


def test_health_uses_backend_neutral_names(client):
    h = client.get("/api/v1/health").json()
    assert h["backend_reachable"] is True and h["photo_store_reachable"] is True
    assert "macola_reachable" not in h and "fileshare_reachable" not in h


def test_codelists_are_code_objects_with_group(client):
    r = client.get("/api/v1/codelists")
    body = r.json()
    for kind in ("parts", "issues", "defect_codes", "inspection_statuses"):
        assert body[kind], kind
        for c in body[kind]:
            assert set(c) >= {"group", "code", "description"} and c["group"] is None and isinstance(c["code"], str)
    assert body["causes"] == []
    assert [b["max_length"] for b in body["summary_blocks"]] == [4000]
    assert body["summary_blocks"][0]["label"] == "Report summary"
    assert body["work_list_statuses"][0] == {"group": None, "code": "Received"}
    assert client.get("/api/v1/codelists", headers={"If-None-Match": r.headers["ETag"]}).status_code == 304


def test_search_takes_any_text_server_decides(client):
    assert client.get("/api/v1/rmas/search", params={"q": "912345"}).json()["matched_on"] == "rma"
    s = client.get("/api/v1/rmas/search", params={"q": "25X0000003"}).json()
    assert s["matched_on"] == "serial"
    item = s["results"][0]
    assert item["rma_id"] == "912347" and item["display_number"] == "912347"
    assert item["inspection_status"]["code"] == "Inspected" and item["is_open"] is True and item["inspectable"] is True
    # No number-format rule: letters, long strings, spaces are simply "no match".
    for q in ("RMA-ABC", "123456789012", "QN 4000123"):
        r = client.get("/api/v1/rmas/search", params={"q": q})
        assert r.status_code == 200 and r.json()["results"] == []


def test_work_list_default_and_any_status(client):
    default = {r["rma_id"] for r in client.get("/api/v1/rmas").json()["results"]}
    assert "912345" in default and "912353" in default and "912349" not in default
    only = client.get("/api/v1/rmas", params={"inspection_status": "Report Complete"}).json()["results"]
    assert [r["rma_id"] for r in only] == ["912353"]


def test_detail_shape(client):
    d = load(client, "912345")
    assert d["rma_id"] == "912345" and d["display_number"] == "912345"
    assert d["is_open"] is True and d["inspectable"] is True
    assert "macola_open" not in d and "has_inspection_record" not in d
    assert d["header"]["inspection_status"] == {"group": None, "code": "Received", "description": "Received"}
    assert d["header"]["failure_code"] == "9-Oil Issue"
    row = d["parts"][0]
    assert set(row) == {"row_id", "part_code", "issue_code", "cause_code", "note"}
    assert row["part_code"] == {"group": None, "code": "07a", "description": "Terminal box"}
    assert row["cause_code"] is None and "line_no" not in row
    assert [b["block_id"] for b in d["summary"]["blocks"]] == ["1"]
    assert d["summary"]["defect_code"] is None


def test_product_types(client):
    assert load(client, "912348")["capabilities"]["findings"] is False
    d = load(client, "912350")
    assert d["product_type_key"] == "scroll" and d["product_type_source"] == "model"
    d = load(client, "912351")
    assert d["product_type_key"] == "unknown" and d["capabilities"]["report"] is True


def base_body(d, **kw):
    return {"submission_id": sid(), "base_signature": d["signature"], "inspector": "jdoe", **kw}


def test_submit_with_row_ids_codes_and_blocks(client, H):
    d = load(client, "912345")
    r1, r2 = d["parts"][0]["row_id"], d["parts"][1]["row_id"]
    body = base_body(d,
        status_change={"from": C("Received"), "to": C("Inspected")},
        summary={"defect_code": C("10"), "blocks": [{"block_id": "1", "text": "The primary failure mode is ..."}],
                 "inspected_by": "JD", "summary_by": "JD"},
        parts=[
            {"row_id": r2, "part_code": C("48a"), "issue_code": C("39"), "cause_code": None, "note": "Phase T1-T2 open"},
            {"row_id": r1, "part_code": C("07a"), "issue_code": C("50"), "cause_code": None, "note": ""},
            {"row_id": None, "part_code": C("63"), "issue_code": C("34"), "cause_code": None, "note": "Cyl 2 skirt scored"},
        ])
    r = client.post("/api/v1/rmas/912345/inspection", json=body, headers=H)
    assert r.status_code == 200, r.text
    res = r.json()
    assert res["changes"]["parts"] == {"inserted": 1, "updated": 1, "deleted": 0, "reordered": True}
    assert res["changes"]["summary"] == {"record": "created", "fields": ["defect_code", "blocks.1", "inspected_by", "summary_by"]}
    assert res["changes"]["status"]["to"] == C("Inspected")
    # New opaque row ids, codes echoed exactly as sent.
    assert all(p["row_id"] not in (r1, r2) for p in res["parts"])
    assert [p["part_code"] for p in res["parts"]] == [C("48a"), C("07a"), C("63")]
    after = load(client, "912345")
    assert [p["row_id"] for p in after["parts"]] == [p["row_id"] for p in res["parts"]]
    assert after["summary"]["blocks"][0]["text"].startswith("The primary") and after["summary"]["defect_code"]["code"] == "10"
    again = client.post("/api/v1/rmas/912345/inspection", json=body, headers=H).json()
    assert again["replayed"] is True and again["new_signature"] == res["new_signature"]


def test_delete_row_by_omitting_it(client, H):
    d = load(client, "912345")
    keep = d["parts"][1]
    r = client.post("/api/v1/rmas/912345/inspection", headers=H, json=base_body(d, parts=[
        {"row_id": keep["row_id"], "part_code": C("48a"), "issue_code": C("50"), "cause_code": None, "note": keep["note"]}]))
    assert r.json()["changes"]["parts"]["deleted"] == 1
    assert [p["part_code"]["code"] for p in load(client, "912345")["parts"]] == ["48a"]


def test_codes_must_match_exactly_including_group(client, H):
    d = load(client, "912345")
    r = client.post("/api/v1/rmas/912345/inspection", headers=H, json=base_body(d, parts=[
        {"row_id": None, "part_code": C("63", group="COMP"), "issue_code": C("34 "), "cause_code": None, "note": ""}]))
    assert r.status_code == 422
    paths = {f["path"]: f["code"] for f in r.json()["error"]["details"]["fields"]}
    assert paths == {"parts[0].part_code": "UNKNOWN_CODE", "parts[0].issue_code": "UNKNOWN_CODE"}


def test_cause_not_supported_while_list_empty(client, H):
    d = load(client, "912345")
    r = client.post("/api/v1/rmas/912345/inspection", headers=H, json=base_body(d, parts=[
        {"row_id": None, "part_code": C("63"), "issue_code": C("34"), "cause_code": C("01"), "note": ""}]))
    assert r.json()["error"]["details"]["fields"][0]["code"] == "CAUSE_NOT_SUPPORTED"


def test_unknown_row_vs_stale_row(client, H):
    d = load(client, "912345")
    bad = base_body(d, parts=[{"row_id": "L9.nope", "part_code": C("63"), "issue_code": C("34"), "cause_code": None, "note": ""}])
    r = client.post("/api/v1/rmas/912345/inspection", headers=H, json=bad)
    assert r.json()["error"]["details"]["fields"][0]["code"] == "UNKNOWN_ROW"
    # Stale base: old row ids are not reported as unknown — the inspector gets the conflict screen instead.
    client.post("/api/v1/_mock/rmas/912345/desk-edit", json={"summary": {"blocks": {"1": "typed at the desk"}}})
    stale = base_body(d, parts=[{"row_id": d["parts"][0]["row_id"], "part_code": C("07a"), "issue_code": C("50"), "cause_code": None, "note": ""}])
    r = client.post("/api/v1/rmas/912345/inspection", headers=H, json=stale)
    assert r.status_code == 409 and r.json()["error"]["code"] == "RMA_CHANGED"


def test_summary_block_limits_and_unknown_block(client, H):
    d = load(client, "912345")
    r = client.post("/api/v1/rmas/912345/inspection", headers=H, json=base_body(d, summary={
        "blocks": [{"block_id": "1", "text": "x" * 4001}, {"block_id": "9", "text": "?"}], "comments": "nope"}))
    codes = {f["path"]: f["code"] for f in r.json()["error"]["details"]["fields"]}
    assert codes["summary.blocks.1"] == "TOO_LONG" and codes["summary.blocks.9"] == "UNKNOWN_BLOCK"
    assert codes["comments"] == "READ_ONLY_FIELD"


def test_change_elsewhere_is_never_overwritten(client, H):
    d = load(client, "912347")
    client.post("/api/v1/_mock/rmas/912347/desk-edit", json={"header": {"credit_status": "P - Partial Warranty Credit"}})
    r = client.post("/api/v1/rmas/912347/inspection", headers=H, json=base_body(d, summary={"blocks": [{"block_id": "1", "text": "tablet"}]}))
    assert r.status_code == 409
    err = r.json()["error"]
    assert err["code"] == "RMA_CHANGED" and err["retryable"] is False
    assert "header.credit_status" in err["details"]["changed"]
    current = err["details"]["current"]
    assert current["header"]["credit_status"].startswith("P") and current["parts"][0]["row_id"]
    assert load(client, "912347")["summary"]["blocks"][0]["text"] == "Example summary text."


def test_lock_lock_failure_closed_and_inspector(client, H):
    d = load(client, "912346")
    r = client.post("/api/v1/rmas/912346/inspection", headers=H, json=base_body(d, parts=[]))
    assert r.status_code == 423 and r.json()["error"]["details"]["held_by"] == "JDOE2"
    d = load(client, "912352")
    r = client.post("/api/v1/rmas/912352/inspection", headers=H, json=base_body(d, parts=[]))
    assert r.status_code == 503 and r.json()["error"]["code"] == "LOCK_CHECK_FAILED"
    d = load(client, "912349")
    r = client.post("/api/v1/rmas/912349/inspection", headers=H, json=base_body(d, parts=[]))
    err = r.json()["error"]
    assert err["code"] == "RMA_NOT_OPEN" and err["details"] == {"is_open": False, "inspectable": True}
    r = client.post("/api/v1/rmas/912345/inspection", headers={"X-Inspector": "nobody"}, json={"submission_id": sid()})
    assert r.json()["error"]["code"] == "UNKNOWN_INSPECTOR"


def upload(client, H, rma, meta):
    meta = {"photo_id": sid(), "sha256": hashlib.sha256(FAKE_JPEG).hexdigest(), **meta}
    return client.post(f"/api/v1/rmas/{rma}/photos", headers=H, data={"meta": json.dumps(meta)},
                       files={"file": ("p.jpg", FAKE_JPEG, "image/jpeg")})


def test_photo_naming_folder_creation_and_collisions(client, H):
    meta = {"kind": "part", "seq": 3, "row_id": None, "part_code": C("63"), "issue_code": C("34")}
    r = upload(client, H, "912345", meta)
    assert r.status_code == 201, r.text
    assert r.json()["filename"] == "03-1 Piston assy, skirt - 34 Scored, Scratched.jpg"
    assert r.json()["folder_created"] is True
    assert upload(client, H, "912345", meta).json()["filename"].startswith("03-2 ")
    assert upload(client, H, "912345", {"kind": "rma", "seq": 0, "tag": "Nameplate"}).json()["filename"] == "00-1 Nameplate.jpg"
    assert upload(client, H, "912345", {**meta, "part_code": C("63", "X")}).status_code == 422
    assert upload(client, H, "912348", {"kind": "rma", "seq": 0, "tag": "Nameplate"}).status_code == 403


def test_report_pics_and_report(client, H):
    sig = client.get("/api/v1/rmas/912347/photos").json()["report_pics"]["signature"]
    r = client.put("/api/v1/rmas/912347/report-pics", headers=H, json={
        "submission_id": sid(), "base_signature": sig, "photos": [
            {"source": "all_pics", "filename": "00-1 As received.jpg", "caption": "00-1 As received"},
            {"source": "report_pics", "filename": "01-1 Stator - 46 Burnt.jpg", "caption": "01-1 Stator burnt / overheated"},
        ]})
    assert r.status_code == 200, r.text
    assert r.json()["changes"] == {"copied": 1, "renamed": 1, "removed": 0, "kept": 0}
    assert "01-1 Stator burnt, overheated.jpg" in r.json()["report_pics"]
    stale = client.put("/api/v1/rmas/912347/report-pics", headers=H, json={"submission_id": sid(), "base_signature": sig, "photos": []})
    assert stale.json()["error"]["code"] == "REPORT_PICS_CHANGED"
    b = client.post("/api/v1/rmas/912347/report", headers=H, json={"submission_id": sid(), "formats": ["pdf", "xlsx"]})
    assert b.status_code == 200 and b.json()["photo_count"] == 2 and b.json()["status_changed"] is None
    pdf = client.get("/api/v1/rmas/912347/report/pdf")
    assert pdf.headers["content-type"] == "application/pdf" and pdf.content.startswith(b"%PDF")
    client.post("/api/v1/_mock/rmas/912347/report-in-use", json={"in_use": True})
    assert client.post("/api/v1/rmas/912347/report", headers=H, json={"submission_id": sid()}).json()["error"]["code"] == "REPORT_FILE_IN_USE"
    warn = client.post("/api/v1/rmas/912353/report", headers=H, json={"submission_id": sid()}).json()["warnings"]
    assert {"NO_LETTER_TEXT", "EMPTY_SUMMARY"} <= {w["code"] for w in warn}


def test_spellcheck(client):
    r = client.post("/api/v1/spellcheck", json={"text": "The primary failure mode is the scorring of the skirts"})
    assert r.json()["issues"][0]["suggestions"] == ["scoring"]
