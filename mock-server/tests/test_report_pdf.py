"""Report PDF (issue #45): built through the API with Pillow-generated JPEGs. FAKE data only."""
import io
import os
import uuid
from pathlib import Path

import pypdf
import pypdfium2 as pdfium
from PIL import Image, ImageDraw

API = "/api/v1"


def sid():
    return str(uuid.uuid4())


def jpeg(n: int, size=(640, 480)) -> bytes:
    im = Image.new("RGB", size, (40 + n * 30 % 200, 120, 90))
    ImageDraw.Draw(im).text((20, 20), f"Fake photo {n}", fill="white")
    out = io.BytesIO()
    im.save(out, "JPEG", quality=80)
    return out.getvalue()


def add_photos(client, rma, count):
    names = []
    for i in range(count):
        meta = {"photo_id": sid(), "kind": "rma", "seq": 0, "tag": "Overview"}
        import json
        r = client.post(f"{API}/rmas/{rma}/photos", data={"meta": json.dumps(meta)},
                        files={"file": ("p.jpg", jpeg(i), "image/jpeg")})
        assert r.status_code == 201, r.text
        names.append(r.json()["filename"])
    return names


def build(client, rma, names):
    sig = client.get(f"{API}/rmas/{rma}/photos").json()["report_pics"]["signature"]
    r = client.put(f"{API}/rmas/{rma}/report-pics", json={
        "submission_id": sid(), "base_signature": sig,
        "photos": [{"source": "all_pics", "filename": n, "caption": f"Fake caption {i + 1}"} for i, n in enumerate(names)]})
    assert r.status_code == 200, r.text
    b = client.post(f"{API}/rmas/{rma}/report", json={"submission_id": sid()})
    assert b.status_code == 200, b.text
    pdf = client.get(f"{API}/rmas/{rma}/report/pdf").content
    return b.json(), pypdf.PdfReader(io.BytesIO(pdf)), pdf


def jpeg_images(page):
    return [i for i in page.images]


def save_pngs(pdf: bytes, rma: str):
    out = os.environ.get("REPORT_PNG_DIR")
    if not out:
        return
    Path(out).mkdir(parents=True, exist_ok=True)
    doc = pdfium.PdfDocument(pdf)
    for i in range(len(doc)):
        doc[i].render(scale=1.3).to_pil().save(Path(out) / f"{rma}-page{i + 1}.png")


def test_report_has_letter_report_and_photo_pages(client):
    names = add_photos(client, "912345", 7)
    info, reader, pdf = build(client, "912345", names)
    assert info["photo_count"] == 7 and info["files"][0]["pages"] == 4 == len(reader.pages)
    text = [p.extract_text() for p in reader.pages]
    assert "Disposition Pending" in text[0] and "912345" in text[0]
    assert "912345" in text[1] and "DIAGNOSIS" in text[1]
    assert "Parts inspected" in text[1] and "Winding resistance" in text[1] and "Terminal box" in text[1]
    assert len(jpeg_images(reader.pages[2])) == 6 and len(jpeg_images(reader.pages[3])) == 1
    assert "Fake caption 1" in text[2] and "Fake caption 7" in text[3]
    save_pngs(pdf, "912345")


def test_report_uses_summary_and_diagnosis_and_letter(client):
    names = add_photos(client, "912347", 2)
    info, reader, pdf = build(client, "912347", names)
    assert len(reader.pages) == 3 and info["files"][0]["pages"] == 3
    text = [p.extract_text() for p in reader.pages]
    assert "Full Warranty Credit" in text[0]
    assert "Example summary text." in text[1] and "Motor break down" in text[1] and "JD" in text[1]
    assert "Stator" in text[1] and "Burnt" in text[1]
    assert len(jpeg_images(reader.pages[2])) == 2
    save_pngs(pdf, "912347")


def test_no_photos_gives_two_pages(client):
    info, reader, _ = build(client, "912357", [])
    assert len(reader.pages) == 2 and info["files"][0]["pages"] == 2
    assert "Dark oil" in reader.pages[1].extract_text()


def test_no_letter_text_credit_has_blank_letter_body(client):
    info, reader, _ = build(client, "912353", [])
    t = reader.pages[0].extract_text()
    assert "912353" in t and "Disposition Pending" not in t and "Example text" not in t
    assert len(reader.pages) == 2


def test_report_page_count_matches_photo_count(client):
    names = add_photos(client, "912345", 12)
    info, reader, _ = build(client, "912345", names)
    assert len(reader.pages) == 4 == info["files"][0]["pages"]
