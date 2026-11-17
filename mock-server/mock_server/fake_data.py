"""FAKE data for the mock server. Nothing here is a real RMA, customer, serial or person.

Code lists are a representative subset of the Macola lookup tables (reference text, not customer data);
the real server serves the full live lists.

Fake RMAs (each exercises a path in docs/api.md):
  912345  Recip, Received, normal happy path (no summary row yet, no folder yet)
  912346  Screw, Received, OPEN IN MACOLA by another user            -> 423 RMA_LOCKED
  912347  Recip, Inspected, has parts + summary + report photos       -> use /_mock hooks for conflicts
  912348  Scroll                                                      -> capabilities all false (403)
  912349  Recip, closed                                               -> 409 RMA_NOT_OPEN
  912350  Blank product type, scroll model prefix                     -> derived scroll (read-only)
  912351  Blank product type, unknown model                           -> "unknown", full access
  912352  Part, Received, lock table unreadable                       -> 503 LOCK_CHECK_FAILED
  912353  Transport, Report Complete, credit status FD (no letter text)
"""
from __future__ import annotations

import copy

PARTS = [
    ("01", "Compressor"), ("02", "Compressor housing"), ("04", "Bearing cap"),
    ("04b", "Housing cover needle bearing?"), ("07", "Terminal plate"), ("07a", "Terminal box"),
    ("08", "Sight glass"), ("11", "Cylinder head"), ("12", "Capacity control"), ("15", "Steel shaft ?"),
    ("21", "Crankshaft journals"), ("22", "Shaft seal"), ("24", "Valve plate"), ("25", "Suction reed"),
    ("26", "Discharge reed"), ("29", "Motor side bearing"), ("34", "Oil Condition"), ("35", "Oil level"),
    ("37", "Suction screen"), ("39a", "Cylinder head gasket"), ("44", "Stator"), ("46", "Rotor"),
    ("48", "Winding to ground insulation"), ("48a", "Winding resistance"), ("49", "Thermistors"),
    ("51", "Oil pump"), ("62", "Cylinder wall"), ("63", "Piston assy / skirt"), ("64", "Piston rings"),
    ("66", "Connecting rod"), ("67", "Connecting rod main bearing"), ("81", "Main rotor (male)"),
    ("91", "Radial brg, main rotor, dsch"), ("96", "Oil slinger/oil pick-up tube"), ("98", "Slide valve"),
    ("100", "Oil separator"), ("104", "Scroll set"), ("104bb", "Fixed scroll insert plate"),
    ("202", "Clutch"), ("PV01", "Vessel"), ("TR06", "Length of Wire w/ Connector"),
    ("TR09", "Wire to Wire w/out Conn."),
]

ISSUES = [
    ("01", "Incomplete"), ("03", "Missing"), ("04", "Damaged"), ("06", "Open / Not sealing"),
    ("10", "Dirty"), ("12", "Broken"), ("13", "Heavy wear"), ("22", "Discolored"), ("23", "Blocked / Seized"),
    ("32", "Wear"), ("32a", "Polished (Light wear)"), ("34", "Scored / Scratched"), ("36", "-"),
    ("38", "Ground fault"), ("39", "Winding fault"), ("41", "Overheated"), ("46", "Burnt"),
    ("49", "Without any oil"), ("50", "Normal"), ("51", "Too low"), ("54", "Galled"),
]

DEFECT_CODES = [
    ("01", "Cannot be clearly determined"), ("02", "No fault found"), ("04", "Third party intervention"),
    ("06", "Overheating"), ("07", "Motor break down"), ("10", "Lack of lubrication"),
    ("14", "Liquid/Oil slugging"), ("18", "Refrigerant Floodback"), ("24", "Dirt from system"), ("29", "--"),
]

INSPECTION_STATUSES = [
    "Open", "Entered", "En Route", "Received", "Inspected", "Rework in Progress", "Report Complete",
    "Sent to RSM", "Issued to Customer", "Non-Warranty Processed", "Closed",
]

CAUSES: list = []   # Macola has no cause per row; SAP will send a cause catalog

# One report summary (decision 2026-11-17). Macola connector: block "1" <-> oermahdr_extended_ipro.summary_1;
# summary_2/3 are not used by the app (left untouched, still covered by the change check).
SUMMARY_BLOCKS = [
    {"block_id": "1", "label": "Report summary", "max_length": 4000},
]

PRODUCT_TYPES = [
    ("Recip", "recip"), ("Screw", "screw"), ("Scroll", "scroll"), ("Part", "part"),
    ("Transport", "transport"), ("Pressure Vessel", "pressure_vessel"), ("System/Unit", "system_unit"),
    ("", "unknown"),
]
SCROLL_PREFIXES = ("GSD", "GTD", "GUD", "ELH", "ELV", "ELA", "ESH")
SCREW_PREFIXES = ("CHS", "CS", "HS", "OS")

INSPECTORS = [
    {"username": "jdoe", "display_name": "J. Doe", "initials": "JD", "audit_user_id": "JDOE_SQL"},
]

SUMMARY_TEMPLATE = (
    "The primary failure mode is the [ ] of the [ ] likely due to [ ]. Failure progression resulted in [ ]. "
    "Common causes for [ ] include [ ]. No defects in materials or workmanship were found with this compressor."
)

PHOTO_TAGS = ["As received", "Nameplate", "Overview", "Packaging/Freight", "Other"]
NO_LETTER_TEXT_CREDIT = ("FD", "NC", "NR", "RC")


def code(c, group=None):
    """Code object as stored (no description). group is None for Macola."""
    return {"group": group, "code": c}


def row(part, issue, note="", cause=None):
    return {"part_code": code(part), "issue_code": code(issue), "cause_code": cause, "note": note}


def _empty_summary():
    return {"exists": False, "defect_code": None, "blocks": {"1": ""},
            "inspected_by": "", "summary_by": ""}


def _rma(rma, *, model, serial, product_type, status, is_open=True, credit="", failure_code="",
         failure_description="", comments="", parts=None, summary=None, lock=None, lock_check_fails=False,
         return_code="Warranty"):
    return {
        "rma_id": rma,
        "display_number": rma,
        "is_open": is_open,
        "inspectable": True,
        "product_type": product_type,
        "header": {
            "customer_name": "Example Refrigeration Co.",
            "model": model,
            "failed_serial_no": serial,
            "sales_order_no": "SO-000000",
            "return_code": return_code,
            "inspection_status": status,          # status code (Macola: the text); shown as a code object
            "credit_status": credit,
            "failed_date": "2/17/2026",
            "rma_comment": "Example comment",
            "failure_code": failure_code,
            "failure_description": failure_description,
            "comments": comments,
        },
        "parts": parts or [],
        "summary": summary or _empty_summary(),
        "lock": lock or {"locked": False, "held_by": None, "host": None, "possibly_stale": False},
        "lock_check_fails": lock_check_fails,
        "all_pics": [],
        "report_pics": [],
        "report": None,
        "last_act_dt": "2026-11-10",
    }


def initial_rmas() -> dict:
    rmas = [
        _rma("912345", model="4FE-28Y", serial="25X0000001", product_type="Recip", status="Received",
             failure_code="9-Oil Issue", failure_description="Example: low oil pressure trips reported",
             comments="Example: customer reports repeated trips",
             parts=[row("07a", "50"), row("48a", "50", "Within spec, all 3 phases")]),
        _rma("912346", model="HSN7471-75", serial="25X0000002", product_type="Screw", status="Received",
             lock={"locked": True, "held_by": "JDOE2", "host": "EXAMPLE-PC", "possibly_stale": False}),
        _rma("912347", model="4PE-15Y", serial="25X0000003", product_type="Recip", status="Inspected",
             credit="F - Full Warranty Credit", failure_code="2-Motor Grounded",
             parts=[row("44", "46", "Example note"), row("48", "38")],
             summary={"exists": True, "defect_code": code("07"),
                      "blocks": {"1": "Example summary text."},
                      "inspected_by": "JD", "summary_by": "JD"}),
        _rma("912348", model="GSD60235VAB", serial="25X0000004", product_type="Scroll", status="Received"),
        _rma("912349", model="4FE-28Y", serial="25X0000005", product_type="Recip", status="Closed", is_open=False),
        _rma("912350", model="GTD80295", serial="25X0000006", product_type="", status="Received"),
        _rma("912351", model="EXAMPLE-MODEL", serial="25X0000007", product_type="", status="Received"),
        _rma("912352", model="PART-0001", serial="", product_type="Part", status="Received", lock_check_fails=True),
        _rma("912353", model="4NFC-20Y", serial="25X0000008", product_type="Transport", status="Report Complete",
             credit="FD - Freight Damage", return_code="Freight Damage"),
    ]
    return copy.deepcopy({r["rma_id"]: r for r in rmas})
