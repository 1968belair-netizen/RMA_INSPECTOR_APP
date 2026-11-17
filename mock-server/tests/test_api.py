import hashlib
import json
import uuid

from mock_server.main import FAKE_JPEG


def sid():
    return str(uuid.uuid4())


def test_health_and_codelists_etag(client):
    assert client.get("/api/v1/health").json()["status"] == "ok"
    r = client.get("/api/v1/codelists")
    body = r.json()
    assert body["status_rule"] == "any"
    assert body["issues"][0]["code"] in [i["code"] for i in body["issues"]]
    assert all(isinstance(p["code"], str) for p in body["parts"])
    r2 = client.get("/api/v1/codelists", headers={"If-None-Match": r.headers["ETag"]})
    assert r2.status_code == 304


def test_search_by_rma_and_serial(client):
    assert client.get("/api/v1/rmas/search", params={"q": "912345"}).json()["matched_on"] == "rma"
    s = client.get("/api/v1/rmas/search", params={"q": "25X0000003"}).json()
    assert s["matched_on"] == "serial" and s["results"][0]["rma"] == "912347"
    assert client.get("/api/v1/rmas/search", params={"q": "000"}).json()["results"] == []


def test_work_list_default_and_any_status(client):
    default = {r["rma"] for r in client.get("/api/v1/rmas").json()["results"]}
    assert "912345" in default and "912353" in default and "912349" not in default
    only = client.get("/api/v1/rmas", params={"inspection_status": "Report Complete"}).json()["results"]
    assert [r["rma"] for r in only] == ["912353"]


def test_product_types(client):
    assert client.get("/api/v1/rmas/912348").json()["capabilities"]["findings"] is False
    d = client.get("/api/v1/rmas/912350").json()
    assert d["product_type_key"] == "scroll" and d["product_type_source"] == "model"
    d = client.get("/api/v1/rmas/912351").json()
    assert d["product_type_key"] == "unknown" and d["capabilities"]["report"] is True


def load(client, rma):
    return client.get(f"/api/v1/rmas/{rma}").json()


def test_submit_inspection_happy_path_and_replay(client, H):
    d = load(client, "912345")
    body = {
        "submission_id": sid(), "base_signature": d["signature"], "product_type_key": "recip", "inspector": "jdoe",
        "status_change": {"from": "Received", "to": "Inspected"},
        "summary": {"defect_cd": "10", "summary_1": "The primary failure mode is ...", "inspected_by": "JD", "summary_by": "JD"},
        "parts": [
            {"orig_line_no": 1, "part_no": "07a", "issue_cd": "50", "note": ""},
            {"orig_line_no": 2, "part_no": "48a", "issue_cd": "39", "note": "Phase T1-T2 open"},
            {"orig_line_no": None, "part_no": "63", "issue_cd": "34", "note": "Cyl 2 skirt scored"},
        ],
    }
    r = client.post("/api/v1/rmas/912345/inspection", json=body, headers=H)
    assert r.status_code == 200, r.text
    res = r.json()
    assert res["changes"]["parts"] == {"inserted": 1, "updated": 1, "deleted": 0, "renumbered": False}
    assert res["changes"]["summary"]["row"] == "inserted"
    assert res["changes"]["status"]["audited"] is True
    assert [p["line_no"] for p in res["parts"]] == [1, 2, 3]
    again = client.post("/api/v1/rmas/912345/inspection", json=body, headers=H).json()
    assert again["replayed"] is True and again["new_signature"] == res["new_signature"]


def test_desk_edit_causes_conflict_never_overwrite(client, H):
    d = load(client, "912347")
    client.post("/api/v1/_mock/rmas/912347/desk-edit", json={"header": {"credit_status": "P - Partial Warranty Credit"}})
    body = {"submission_id": sid(), "base_signature": d["signature"], "inspector": "jdoe",
            "summary": {"summary_2": "added on tablet"}}
    r = client.post("/api/v1/rmas/912347/inspection", json=body, headers=H)
    assert r.status_code == 409
    err = r.json()["error"]
    assert err["code"] == "RMA_CHANGED" and err["retryable"] is False
    assert "credit_status" in err["details"]["changed"]
    # Nothing was written.
    assert load(client, "912347")["summary"]["summary_2"] == ""


def test_lock_and_lock_check_failure(client, H):
    d = load(client, "912346")
    r = client.post("/api/v1/rmas/912346/inspection", headers=H,
                    json={"submission_id": sid(), "base_signature": d["signature"], "inspector": "jdoe", "parts": []})
    assert r.status_code == 423 and r.json()["error"]["details"]["held_by"] == "JDOE2"
    d = load(client, "912352")
    r = client.post("/api/v1/rmas/912352/inspection", headers=H,
                    json={"submission_id": sid(), "base_signature": d["signature"], "inspector": "jdoe", "parts": []})
    assert r.status_code == 503 and r.json()["error"]["code"] == "LOCK_CHECK_FAILED"


def test_validation_read_only_unknown_code_and_closed(client, H):
    d = load(client, "912345")
    r = client.post("/api/v1/rmas/912345/inspection", headers=H, json={
        "submission_id": sid(), "base_signature": d["signature"], "inspector": "jdoe",
        "summary": {"comments": "nope", "summary_2": "x" * 4001},
        "parts": [{"orig_line_no": None, "part_no": "63", "issue_cd": "34b", "note": ""}]})
    assert r.status_code == 422
    codes = {f["code"] for f in r.json()["error"]["details"]["fields"]}
    assert {"READ_ONLY_FIELD", "TOO_LONG", "UNKNOWN_CODE"} <= codes
    d = load(client, "912349")
    r = client.post("/api/v1/rmas/912349/inspection", headers=H,
                    json={"submission_id": sid(), "base_signature": d["signature"], "inspector": "jdoe", "parts": []})
    assert r.json()["error"]["code"] == "RMA_NOT_OPEN"
    r = client.post("/api/v1/rmas/912345/inspection", headers={"X-Inspector": "nobody"},
                    json={"submission_id": sid(), "base_signature": "x", "inspector": "nobody"})
    assert r.json()["error"]["code"] == "UNKNOWN_INSPECTOR"


def upload(client, H, rma, meta):
    meta = {"photo_id": sid(), "sha256": hashlib.sha256(FAKE_JPEG).hexdigest(), **meta}
    return client.post(f"/api/v1/rmas/{rma}/photos", headers=H, data={"meta": json.dumps(meta)},
                       files={"file": ("p.jpg", FAKE_JPEG, "image/jpeg")})


def test_photo_naming_folder_creation_and_collisions(client, H):
    r = upload(client, H, "912345", {"kind": "part", "seq": 3, "part_no": "63", "issue_cd": "34"})
    assert r.status_code == 201, r.text
    assert r.json()["filename"] == "03-1 Piston assy, skirt - 34 Scored, Scratched.jpg"
    assert r.json()["folder_created"] is True
    r2 = upload(client, H, "912345", {"kind": "part", "seq": 3, "part_no": "63", "issue_cd": "34"})
    assert r2.json()["filename"].startswith("03-2 ") and r2.json()["folder_created"] is False
    r3 = upload(client, H, "912345", {"kind": "rma", "seq": 0, "tag": "Nameplate"})
    assert r3.json()["filename"] == "00-1 Nameplate.jpg"
    scroll = upload(client, H, "912348", {"kind": "rma", "seq": 0, "tag": "Nameplate"})
    assert scroll.status_code == 403


def test_report_pics_and_report(client, H):
    photos = client.get("/api/v1/rmas/912347/photos").json()
    sig = photos["report_pics"]["signature"]
    r = client.put("/api/v1/rmas/912347/report-pics", headers=H, json={
        "submission_id": sid(), "base_signature": sig, "photos": [
            {"source": "all_pics", "filename": "00-1 As received.jpg", "caption": "00-1 As received"},
            {"source": "report_pics", "filename": "01-1 Stator - 46 Burnt.jpg", "caption": "01-1 Stator burnt / overheated"},
        ]})
    assert r.status_code == 200, r.text
    assert r.json()["changes"] == {"copied": 1, "renamed": 1, "removed": 0, "kept": 0}
    assert "01-1 Stator burnt, overheated.jpg" in r.json()["report_pics"]
    stale = client.put("/api/v1/rmas/912347/report-pics", headers=H,
                       json={"submission_id": sid(), "base_signature": sig, "photos": []})
    assert stale.status_code == 409 and stale.json()["error"]["code"] == "REPORT_PICS_CHANGED"

    b = client.post("/api/v1/rmas/912347/report", headers=H, json={"submission_id": sid(), "formats": ["pdf", "xlsx"]})
    assert b.status_code == 200 and b.json()["photo_count"] == 2 and b.json()["status_changed"] is None
    pdf = client.get("/api/v1/rmas/912347/report/pdf")
    assert pdf.headers["content-type"] == "application/pdf" and pdf.content.startswith(b"%PDF")
    client.post("/api/v1/_mock/rmas/912347/report-in-use", json={"in_use": True})
    busy = client.post("/api/v1/rmas/912347/report", headers=H, json={"submission_id": sid()})
    assert busy.json()["error"]["code"] == "REPORT_FILE_IN_USE"
    fd_ = client.post("/api/v1/rmas/912353/report", headers=H, json={"submission_id": sid()}).json()
    assert "NO_LETTER_TEXT" in {w["code"] for w in fd_["warnings"]}


def test_spellcheck(client):
    r = client.post("/api/v1/spellcheck", json={"text": "The primary failure mode is the scorring of the skirts"})
    assert r.json()["issues"][0]["suggestions"] == ["scoring"]
