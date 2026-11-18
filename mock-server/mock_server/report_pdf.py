"""Demo report PDF for the mock server (ReportLab + Pillow). FAKE wording and FAKE data only.

Layout imitates the real RMA report: page 1 disposition letter, page 2 RMA report (header block, parts table,
DIAGNOSIS, summary), then photo pages (2 across x 3 down, caption = file name without extension, JPEG bytes
embedded as-is). Pages = 2 + ceil(photos / 6) unless the report text itself overflows page 2.
"""
from __future__ import annotations

import io
import math
from pathlib import Path
from typing import Optional
from xml.sax.saxutils import escape

from reportlab.lib import colors
from reportlab.lib.pagesizes import letter
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import inch
from reportlab.platypus import (Image, KeepTogether, PageBreak, Paragraph, SimpleDocTemplate, Spacer, Table,
                                TableStyle)

GREEN = colors.HexColor("#3AAA35")
DARK_GREEN = colors.HexColor("#005B23")
GREY = colors.HexColor("#6B6B6B")
LIGHT = colors.HexColor("#EEF6ED")
PER_PAGE = 6
LOGO = Path(__file__).resolve().parents[2] / "app/src/main/res/drawable-nodpi/bitzer_logo.png"

# FAKE placeholder letter wording per credit-status code. Real wording lives in the workbench, not here.
LETTER_TEXT = {
    "F": "Example text: the returned compressor was inspected and a full warranty credit has been approved.",
    "P": "Example text: the returned compressor was inspected and a partial warranty credit has been approved.",
    "I/N": "Example text: the returned compressor was inspected and no credit applies to this return.",
}
GENERIC_LETTER = "Example text: the returned compressor was inspected. The disposition is described below."
NO_LETTER_TEXT = ("FD", "NC", "NR", "RC")

_base = dict(fontName="Helvetica", fontSize=10, leading=13)
S_BODY = ParagraphStyle("body", **_base)
S_SMALL = ParagraphStyle("small", **{**_base, "fontSize": 8.5, "leading": 11})
S_CELL = ParagraphStyle("cell", **{**_base, "fontSize": 9, "leading": 11})
S_HEAD = ParagraphStyle("head", **{**_base, "fontSize": 9, "leading": 11, "fontName": "Helvetica-Bold",
                                   "textColor": colors.white})
S_TITLE = ParagraphStyle("title", **{**_base, "fontName": "Helvetica-Bold", "fontSize": 18, "leading": 22,
                                     "textColor": DARK_GREEN})
S_H2 = ParagraphStyle("h2", **{**_base, "fontName": "Helvetica-Bold", "fontSize": 11, "leading": 14,
                               "textColor": DARK_GREEN, "spaceBefore": 8, "spaceAfter": 3})
S_CAP = ParagraphStyle("cap", **{**_base, "fontSize": 8.5, "leading": 10.5, "alignment": 1})


def _p(text, style=S_CELL):
    return Paragraph(escape(str(text or "")).replace("\n", "<br/>"), style)


def _title_block(title: str, subtitle: str):
    """Plain header. The official logo is used only when the asset is present (on white, scaled proportionally)."""
    left = [Paragraph("BITZER US", S_TITLE), Paragraph(escape(subtitle), S_SMALL)]
    cells = [[left, ""]]
    if LOGO.exists():
        try:
            from PIL import Image as PILImage
            w, h = PILImage.open(LOGO).size
            cells[0][1] = Image(str(LOGO), width=1.4 * inch, height=1.4 * inch * h / w)
        except Exception:
            pass
    t = Table(cells, colWidths=[5.1 * inch, 1.9 * inch])
    t.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "MIDDLE"), ("ALIGN", (1, 0), (1, 0), "RIGHT"),
                           ("LINEBELOW", (0, 0), (-1, 0), 2, GREEN), ("BOTTOMPADDING", (0, 0), (-1, 0), 8)]))
    return [t, Spacer(1, 10), Paragraph(escape(title), S_H2)]


def _credit_code(credit: str) -> str:
    return (credit or "").split(" - ")[0].strip()


def _disposition_page(d: dict) -> list:
    credit, code = d.get("credit_status") or "", _credit_code(d.get("credit_status") or "")
    out = _title_block("Disposition", "Example Compressor Returns - FAKE demo document")
    out += [Paragraph(f"Date: {escape(d['generated_date'])}", S_BODY), Spacer(1, 8),
            Paragraph(escape(d["customer_name"]), S_BODY), Spacer(1, 8),
            Paragraph(f"<b>RMA {escape(d['rma'])}</b> &nbsp; Model {escape(d['model'])} &nbsp; "
                      f"Serial {escape(d['serial'] or '-')}", S_BODY), Spacer(1, 14)]
    if not credit:
        out.append(Paragraph("<b>Disposition Pending</b>", ParagraphStyle("dp", parent=S_BODY, fontSize=14, leading=18,
                                                                          textColor=DARK_GREEN)))
        out.append(Spacer(1, 6))
        out.append(Paragraph("Example text: this return is still being processed. A disposition will follow.", S_BODY))
    elif code in NO_LETTER_TEXT:
        pass  # blank body by design (FD / NC / NR / RC)
    else:
        out.append(Paragraph(f"<b>Credit status: {escape(credit)}</b>", S_BODY))
        out.append(Spacer(1, 6))
        out.append(Paragraph(LETTER_TEXT.get(code, GENERIC_LETTER), S_BODY))
        out.append(Spacer(1, 6))
        out.append(Paragraph("Example text: please see the attached report for the inspection findings. "
                             "Contact your sales representative with any questions.", S_BODY))
    out += [Spacer(1, 30), Paragraph("Example Compressor Returns Team", S_BODY)]
    return out


def _report_page(d: dict) -> list:
    out = _title_block("RMA Report", "Example Compressor Returns - FAKE demo document")
    rows = [("RMA", d["rma"], "Model", d["model"]),
            ("Serial No.", d["serial"] or "-", "Customer", d["customer_name"]),
            ("Failed date", d["failed_date"], "Report date", d["generated_date"]),
            ("Return code", d["return_code"], "Status", d["status"]),
            ("Inspected by", d["inspected_by"], "Summary by", d["summary_by"])]
    hdr = Table([[_p(a, S_SMALL), _p(b), _p(c, S_SMALL), _p(e)] for a, b, c, e in rows],
                colWidths=[0.95 * inch, 2.55 * inch, 0.95 * inch, 2.55 * inch])
    hdr.setStyle(TableStyle([("BACKGROUND", (0, 0), (0, -1), LIGHT), ("BACKGROUND", (2, 0), (2, -1), LIGHT),
                             ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#B9D6B7")),
                             ("VALIGN", (0, 0), (-1, -1), "TOP")]))
    out += [hdr, Spacer(1, 10), Paragraph("Parts inspected", S_H2)]
    data = [[Paragraph(h, S_HEAD) for h in ("Line", "Part", "Issue", "Note")]]
    for i, p in enumerate(d["parts"], 1):
        data.append([_p(i), _p(p["part"]), _p(p["issue"]), _p(p["note"])])
    if len(data) == 1:
        data.append([_p("-"), _p("No parts entered"), _p(""), _p("")])
    pt = Table(data, colWidths=[0.45 * inch, 2.45 * inch, 1.9 * inch, 2.2 * inch], repeatRows=1)
    pt.setStyle(TableStyle([("BACKGROUND", (0, 0), (-1, 0), DARK_GREEN),
                            ("ROWBACKGROUNDS", (0, 1), (-1, -1), [colors.white, LIGHT]),
                            ("GRID", (0, 0), (-1, -1), 0.4, colors.HexColor("#B9D6B7")),
                            ("VALIGN", (0, 0), (-1, -1), "TOP")]))
    out += [pt, Spacer(1, 10),
            Paragraph(f"<font color='#005B23'><b>DIAGNOSIS:</b></font> {escape(d['diagnosis'] or '-')}", S_BODY),
            Paragraph("Summary", S_H2), _p(d["summary"] or "(no summary entered)", S_BODY)]
    return out


def _photo_cell(caption: str, data: bytes):
    max_w, max_h = 3.3 * inch, 2.35 * inch
    try:
        from PIL import Image as PILImage
        with PILImage.open(io.BytesIO(data)) as im:
            if im.format != "JPEG":
                raise ValueError("not a JPEG")
            w, h = im.size
        scale = min(max_w / w, max_h / h)
        img = Image(io.BytesIO(data), width=w * scale, height=h * scale)  # JPEG bytes are embedded unchanged
    except Exception:
        img = Paragraph("[image could not be read]", S_CAP)
    return [img, Spacer(1, 3), Paragraph(escape(caption), S_CAP)]


def _photo_page(photos: list[tuple[str, bytes]]) -> list:
    cells = [_photo_cell(c, b) for c, b in photos] + [""] * (PER_PAGE - len(photos))
    t = Table([cells[i:i + 2] for i in range(0, PER_PAGE, 2)], colWidths=[3.5 * inch, 3.5 * inch],
              rowHeights=[2.9 * inch] * 3)
    t.setStyle(TableStyle([("VALIGN", (0, 0), (-1, -1), "MIDDLE"), ("ALIGN", (0, 0), (-1, -1), "CENTER")]))
    return [t]


def build_report_pdf(data: dict, photos: list[tuple[str, bytes]]) -> tuple[bytes, int]:
    """data: see main.build_report. photos: [(caption, jpeg_bytes)] in report order. Returns (pdf bytes, page count)."""
    buf = io.BytesIO()
    doc = SimpleDocTemplate(buf, pagesize=letter, leftMargin=0.75 * inch, rightMargin=0.75 * inch,
                            topMargin=0.6 * inch, bottomMargin=0.6 * inch,
                            title=f"RMA {data['rma']} Report (FAKE demo)", author="RMA mock server")

    def footer(canvas, d_):
        canvas.saveState()
        canvas.setStrokeColor(GREEN)
        canvas.line(0.75 * inch, 0.45 * inch, letter[0] - 0.75 * inch, 0.45 * inch)
        canvas.setFont("Helvetica", 8)
        canvas.setFillColor(GREY)
        canvas.drawString(0.75 * inch, 0.3 * inch, f"RMA {data['rma']} - FAKE demo report")
        canvas.drawRightString(letter[0] - 0.75 * inch, 0.3 * inch, f"Page {d_.page}")
        canvas.restoreState()

    story = _disposition_page(data) + [PageBreak()] + _report_page(data)
    for i in range(0, len(photos), PER_PAGE):
        story += [PageBreak()] + _photo_page(photos[i:i + PER_PAGE])
    doc.build(story, onFirstPage=footer, onLaterPages=footer)
    return buf.getvalue(), doc.page
