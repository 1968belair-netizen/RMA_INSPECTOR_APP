"""MOCK_DATASET=demo: curated demo dataset, checklists, reset. The default dataset is covered by test_api.py."""
import pytest
from fastapi.testclient import TestClient

from mock_server.main import app, store

DEMO_WL = ["912364", "912361", "912368", "912366", "912362", "912365", "912363", "912367"]  # oldest first


@pytest.fixture
def demo(monkeypatch):
    monkeypatch.setenv("MOCK_DATASET", "demo")
    store.reset()
    c = TestClient(app)
    r = c.post("/api/v1/auth/login", json={"username": "demo", "password": "demo", "device_id": "t"})
    c.headers["Authorization"] = "Bearer " + r.json()["token"]
    yield c
    monkeypatch.delenv("MOCK_DATASET")
    store.reset()


def test_work_list_has_eight_expected_rmas(demo):
    items = demo.get("/api/v1/rmas").json()["results"]
    assert [i["rma_id"] for i in items] == DEMO_WL
    by = {i["rma_id"]: i for i in items}
    keys = sorted(i["product_type_key"] for i in items)
    assert keys == ["part", "recip", "recip", "recip", "screw", "screw", "scroll", "transport"]
    assert any(f["code"] == "CORE_TEARDOWN" for f in by["912362"]["flags"])
    assert by["912364"]["status_since"] < by["912361"]["status_since"]
    from datetime import date
    assert (date.today() - date.fromisoformat(by["912364"]["status_since"])).days > 30


def test_scroll_is_read_only(demo):
    d = demo.get("/api/v1/rmas/912368").json()
    assert d["product_type_key"] == "scroll"
    assert not any(d["capabilities"].values())


def test_completed_example_has_built_report_and_photos(demo):
    d = demo.get("/api/v1/rmas/912369").json()
    assert d["header"]["inspection_status"]["code"] == "Report Complete"
    assert len(d["parts"]) >= 4 and d["summary"]["defect_code"]["code"] == "07"
    assert d["summary"]["inspected_by"] and d["summary"]["blocks"][0]["text"]
    photos = demo.get("/api/v1/rmas/912369/photos").json()
    assert len(photos["all_pics"]) >= 6 and photos["report_pics"]["files"]
    img = demo.get("/api/v1/rmas/912369/photos/all/" + photos["all_pics"][0]["filename"])
    assert img.content[:3] == b"\xff\xd8\xff" and len(img.content) > 1000
    assert demo.get("/api/v1/rmas/912369/report").json()["exists"] is True
    pdf = demo.get("/api/v1/rmas/912369/report/pdf").content
    assert pdf.startswith(b"%PDF")
    import io, pypdf
    pages = pypdf.PdfReader(io.BytesIO(pdf)).pages
    assert sum(len(p.images) for p in pages) >= 4   # the seeded report photos are in the PDF


def test_checklists_use_real_codes(demo):
    cl = demo.get("/api/v1/codelists").json()
    parts = {p["code"] for p in cl["parts"]}
    assert cl["normal_issue_codes"] == [{"group": None, "code": "50"}]
    assert "Transport" in cl["work_list_product_types"]
    recip, screw = cl["checklists"]["recip"], cl["checklists"]["screw"]
    codes = [s["part_code"]["code"] for s in recip["steps"]]
    assert {"07", "48a", "49", "34", "35", "37", "44", "46", "63"} <= set(codes)
    assert 0 < len(screw["steps"]) < len(recip["steps"])
    assert all(s["part_code"]["code"] in parts for c in (recip, screw) for s in c["steps"])
    assert recip["steps"][0]["photos"][0]["required_when"] == "always"


def test_spellcheck_misspellings(demo):
    words = [i["word"] for i in demo.post("/api/v1/spellcheck", json={"text": "scorring, compresser and bearring"}).json()["issues"]]
    assert words == ["scorring", "compresser", "bearring"]


def test_reset_restores_dataset(demo):
    demo.post("/api/v1/_mock/rmas/912361/desk-edit", json={"header": {"credit_status": "P - Partial Warranty Credit"}})
    assert demo.get("/api/v1/rmas/912361").json()["header"]["credit_status"]
    assert demo.post("/mock/reset").json() == {"ok": True}
    r = demo.post("/api/v1/auth/login", json={"username": "demo", "password": "demo", "device_id": "t"})   # reset signs out
    demo.headers["Authorization"] = "Bearer " + r.json()["token"]
    assert demo.get("/api/v1/rmas/912361").json()["header"]["credit_status"] == ""
    assert demo.get("/api/v1/rmas/912369").json()["header"]["inspection_status"]["code"] == "Report Complete"


def test_default_dataset_unchanged_without_env():
    store.reset()
    assert "912347" in store.rmas and "912369" not in store.rmas
    c = TestClient(app)
    r = c.post("/api/v1/auth/login", json={"username": "demo", "password": "demo", "device_id": "t"})
    c.headers["Authorization"] = "Bearer " + r.json()["token"]
    assert c.get("/api/v1/codelists").json()["checklists"] == {}
