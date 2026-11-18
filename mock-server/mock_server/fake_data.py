"""FAKE data for the mock server. Nothing here is a real RMA, customer, serial or person.

Code lists are a representative subset of the Macola lookup tables (reference text, not customer data);
the real server serves the full live lists.

Fake RMAs (each exercises a path in docs/api.md). "WL" = on the default work list.
  912345  Recip, Warranty, Received 23 d — happy path (no summary yet, no folder yet)          WL
  912346  Screw, Warranty, Received 41 d, OPEN ELSEWHERE -> 423 RMA_LOCKED                    WL (oldest)
  912347  Recip, Inspection Only, Inspected — parts + summary + report photos                 (not Received)
  912348  Scroll, Warranty, Received 30 d -> read-only (scroll capability off)               WL
  912349  Recip, closed -> 409 RMA_NOT_OPEN
  912350  Blank product type (scroll model), blank return code, Received -> not on the list
  912351  Blank product type, Core Credit, Received -> not on the list
  912352  Part, Repair & Return, Received 19 d (date estimated), lock check fails -> 503       WL
  912353  Transport, Freight Damage, Report Complete, credit status FD (no letter text)
  912354  Recip, Core&Inspection, Received, core-teardown badge -> not on the list (return code)
  912355  Screw, Repair & Return, Received 12 d, core-teardown badge                          WL
  912356  Pressure Vessel, Warranty, Received -> not on the default list (product type)
  912357  Recip, Inspection Only, Received 5 d                                                WL
  912358  Recip, Inspection Only, Report Complete — report already built (resubmit warning)
  912359  Screw, Warranty, Sent to RSM — report already sent (resubmit warning)
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

RETURN_CODES = [
    "Warranty", "Repair & Return", "Core Credit", "New & Unused", "Inspection Only", "Core Buyback",
    "Freight Damage", "Cus Line Reject", "Core&Inspection",
]

# Work-list settings (server config; decision 2026-11-18). Matched trimmed + uppercase.
WORK_LIST_STATUSES = ["Received"]
# "(blank)" = reserved filter token for an empty product type (api §5, issue #36).
BLANK_PRODUCT_TYPE = "(blank)"
WORK_LIST_PRODUCT_TYPES = ["Recip", "Scroll", "Screw", "Part", BLANK_PRODUCT_TYPE]
WORK_LIST_RETURN_CODES = ["Inspection Only", "Repair & Return", "Warranty"]
REPORT_SENT_STATUSES = ["Sent to RSM", "Issued to Customer"]

# Mock sign-in users — FAKE demo credentials for local testing only. Override with the MOCK_USERS
# environment variable (JSON: {"user": {"password": "...", "display_name": "...", "initials": "..."}}).
# The real server keeps its users in a server-side setting, never in the repo.
DEFAULT_USERS = {
    "demo": {"password": "demo", "display_name": "Demo Inspector", "initials": "DI"},
    # A second fake inspector, for tests of held uploads (queue items belong to the inspector who made them).
    "demo2": {"password": "demo2", "display_name": "Second Inspector", "initials": "D2"},
}

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
         return_code="Warranty", status_since="2026-11-10", estimated=False, flags=None):
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
        "last_act_dt": status_since,
        # Date of the last real status change (Old_Status <> New_Status; tiebreak A4glidentity).
        # No audit rows -> rma_dt_entered with estimated = True (Trend Tracker rule).
        "status_since": status_since,
        "status_since_estimated": estimated,
        "flags": flags or [],
    }


CORE_TEARDOWN = {"code": "CORE_TEARDOWN", "label": "In core teardown log"}


def initial_rmas() -> dict:
    rmas = [
        _rma("912345", model="4FE-28Y", serial="25X0000001", product_type="Recip", status="Received",
             failure_code="9-Oil Issue", failure_description="Example: low oil pressure trips reported",
             comments="Example: customer reports repeated trips", status_since="2026-10-26",
             parts=[row("07a", "50"), row("48a", "50", "Within spec, all 3 phases")]),
        _rma("912346", model="HSN7471-75", serial="25X0000002", product_type="Screw", status="Received",
             status_since="2026-10-08",
             lock={"locked": True, "held_by": "JDOE2", "host": "EXAMPLE-PC", "possibly_stale": False}),
        _rma("912347", model="4PE-15Y", serial="25X0000003", product_type="Recip", status="Inspected",
             return_code="Inspection Only", status_since="2026-11-09",
             credit="F - Full Warranty Credit", failure_code="2-Motor Grounded",
             parts=[row("44", "46", "Example note"), row("48", "38")],
             summary={"exists": True, "defect_code": code("07"),
                      "blocks": {"1": "Example summary text."},
                      "inspected_by": "JD", "summary_by": "JD"}),
        _rma("912348", model="GSD60235VAB", serial="25X0000004", product_type="Scroll", status="Received",
             status_since="2026-10-19"),
        _rma("912349", model="4FE-28Y", serial="25X0000005", product_type="Recip", status="Closed", is_open=False),
        _rma("912350", model="GTD80295", serial="25X0000006", product_type="", status="Received", return_code=""),
        _rma("912351", model="EXAMPLE-MODEL", serial="25X0000007", product_type="", status="Received",
             return_code="Core Credit"),
        _rma("912352", model="PART-0001", serial="", product_type="Part", status="Received",
             return_code="Repair & Return", status_since="2026-10-30", estimated=True, lock_check_fails=True),
        _rma("912353", model="4NFC-20Y", serial="25X0000008", product_type="Transport", status="Report Complete",
             credit="FD - Freight Damage", return_code="Freight Damage"),
        _rma("912354", model="4FE-35Y", serial="25X0000009", product_type="Recip", status="Received",
             return_code="Core&Inspection", status_since="2026-10-15", flags=[CORE_TEARDOWN]),
        _rma("912355", model="CSH6553-50Y", serial="25X0000010", product_type="Screw", status="Received",
             return_code="Repair & Return", status_since="2026-11-06", flags=[CORE_TEARDOWN]),
        _rma("912356", model="PV-EXAMPLE", serial="25X0000011", product_type="Pressure Vessel", status="Received",
             status_since="2026-10-01"),
        _rma("912357", model="2KES-05Y", serial="25X0000012", product_type="Recip", status="Received",
             return_code="Inspection Only", status_since="2026-11-13",
             parts=[row("34", "22", "Dark oil")]),
        _rma("912358", model="4JE-22Y", serial="25X0000013", product_type="Recip", status="Report Complete",
             return_code="Inspection Only", status_since="2026-11-03", credit="I/N - Inspection (No CC)",
             parts=[row("63", "34")],
             summary={"exists": True, "defect_code": code("10"), "blocks": {"1": "Example summary text."},
                      "inspected_by": "JD", "summary_by": "JD"}),
        _rma("912359", model="HSK7451-60", serial="25X0000014", product_type="Screw", status="Sent to RSM",
             status_since="2026-10-28", credit="F - Full Warranty Credit",
             parts=[row("81", "34")],
             summary={"exists": True, "defect_code": code("14"), "blocks": {"1": "Example summary text."},
                      "inspected_by": "JD", "summary_by": "JD"}),
        # Received, blank product type, default return code -> on the default work list via "(blank)" (issue #36).
        _rma("912360", model="4CES-9Y", serial="25X0000015", product_type="", status="Received",
             status_since="2026-11-16"),
    ]
    return copy.deepcopy({r["rma_id"]: r for r in rmas})


# ---------------------------------------------------------------- demo dataset (MOCK_DATASET=demo)
# 8 curated RMAs on the default work list + one completed example (not on the list: status Report Complete).
# All FAKE. Dates are relative to "today" so ages stay realistic whenever the demo is run.
#   912361  Recip, Warranty, 24 d, oil trips                      WL
#   912362  Recip, Repair & Return, 11 d, core-teardown badge     WL
#   912363  Recip, Inspection Only, 6 d, misspelled comments      WL
#   912364  Screw, Warranty, 41 d (aged > 30 days)                WL (oldest)
#   912365  Screw, Repair & Return, 9 d                           WL
#   912366  Part, Repair & Return, 15 d                           WL
#   912367  Transport, Warranty, 4 d (Transport added to the demo work-list defaults)   WL
#   912368  Scroll, Warranty, 20 d -> read only                   WL
#   912369  Recip, Inspection Only, Report Complete — finished example (parts, summary, photos, built report)
DEMO_EXTRA_WORK_LIST_TYPES = ["Transport"]

DEMO_CHECKLIST_VERSION = "2026-11-18-demo"


def _step(part, required=True):
    return {"part_code": code(part), "required": required,
            "photos": [{"slot": "overall", "label": "Overall", "required_when": "always"},
                       {"slot": "detail", "label": "Detail", "required_when": "non_normal"}]}


def demo_checklists() -> dict:
    return {
        "recip": {"version": DEMO_CHECKLIST_VERSION,
                  "steps": [_step(p) for p in ("07", "48a", "49", "34", "35", "37", "44", "46", "63")]},
        "screw": {"version": DEMO_CHECKLIST_VERSION,
                  "steps": [_step(p) for p in ("07", "48a", "34", "35")]},
    }


def demo_rmas(today=None) -> dict:
    from datetime import date, timedelta
    today = today or date.today()

    def since(days):
        return (today - timedelta(days=days)).isoformat()

    def mk(rma, *, customer="Example Refrigeration Co.", sales_order, days, **kw):
        r = _rma(rma, status_since=since(days), **kw)
        r["header"]["customer_name"] = customer
        r["header"]["sales_order_no"] = sales_order
        r["header"]["failed_date"] = f"{(today - timedelta(days=days + 60)).month}/{(today - timedelta(days=days + 60)).day}/{(today - timedelta(days=days + 60)).year}"
        return r

    rmas = [
        mk("912361", model="4FE-28Y", serial="26X0100001", product_type="Recip", status="Received", days=24,
           sales_order="SO-100061", failure_code="9-Oil Issue",
           failure_description="Compresser tripped on low oil pressure; scorring suspected on crankshaft journals.",
           comments="Unit ran about 3 months. Bearring noise before failure."),
        mk("912362", model="6GE-34Y", serial="26X0100002", product_type="Recip", status="Received", days=11,
           customer="Sample Cold Storage", sales_order="SO-100062", return_code="Repair & Return",
           failure_code="2-Motor Grounded", failure_description="Motor grounded on start-up after a power outage.",
           comments="Termnal plate shows discoloration.", flags=[CORE_TEARDOWN]),
        mk("912363", model="2KES-05Y", serial="26X0100003", product_type="Recip", status="Received", days=6,
           customer="Sample Cold Storage", sales_order="SO-100063", return_code="Inspection Only",
           failure_description="Customer requests inspection after repeated high-pressure cutouts.",
           comments="Lubercation looked low at the sight glass."),
        mk("912364", model="HSN7471-75", serial="26X0100004", product_type="Screw", status="Received", days=41,
           customer="Demo Food Distribution", sales_order="SO-100064", failure_code="9-Oil Issue",
           failure_description="High oil temperature alarm, then seized rotor."),
        mk("912365", model="CSH6553-50Y", serial="26X0100005", product_type="Screw", status="Received", days=9,
           customer="Demo Food Distribution", sales_order="SO-100065", return_code="Repair & Return",
           failure_description="Excessive noise from the compressor; windng resistance out of range."),
        mk("912366", model="PART-0002", serial="", product_type="Part", status="Received", days=15,
           sales_order="SO-100066", return_code="Repair & Return",
           failure_description="Returned shaft seal kit, leaking at the seal."),
        mk("912367", model="4NFC-20Y", serial="26X0100007", product_type="Transport", status="Received", days=4,
           customer="Sample Cold Storage", sales_order="SO-100067",
           failure_description="Transport compressor arrived with cracked housing cover."),
        mk("912368", model="GSD60235VAB", serial="26X0100008", product_type="Scroll", status="Received", days=20,
           sales_order="SO-100068", failure_description="Scroll set noisy at start-up."),
        mk("912369", model="4PE-15Y", serial="26X0100009", product_type="Recip", status="Report Complete", days=2,
           customer="Example Refrigeration Co.", sales_order="SO-100069", return_code="Inspection Only",
           credit="F - Full Warranty Credit", failure_code="2-Motor Grounded",
           failure_description="Motor grounded; breaker tripped repeatedly.",
           parts=[row("07a", "50"), row("48a", "50", "Within spec on all 3 phases"),
                  row("34", "22", "Dark oil, burnt smell"), row("44", "46", "Stator windings burnt on the motor end"),
                  row("48", "38", "Winding to ground reads 0.2 megohm")],
           summary={"exists": True, "defect_code": code("07"),
                    "blocks": {"1": ("The primary failure mode is the winding fault of the stator likely due to overheating. "
                                     "Failure progression resulted in a ground fault and a tripped breaker. "
                                     "Common causes for motor overheating include loss of cooling, low refrigerant charge, "
                                     "electrical imbalance and liquid floodback. "
                                     "No defects in materials or workmanship were found with this compressor.")},
                    "inspected_by": "DI", "summary_by": "DI"}),
    ]
    return copy.deepcopy({r["rma_id"]: r for r in rmas})


DEMO_COMPLETED_RMA = "912369"
# (filename, label, rgb) - photo names follow docs/spec.md section 5.
DEMO_COMPLETED_PHOTOS = [
    ("00-1 As received.jpg", "As received", (120, 130, 140)),
    ("00-2 Nameplate.jpg", "Nameplate", (200, 200, 190)),
    ("01-1 Terminal box - 50 Normal.jpg", "Terminal box - Normal", (70, 130, 90)),
    ("02-1 Oil Condition - 22 Discolored.jpg", "Oil condition - Discolored", (150, 110, 60)),
    ("03-1 Stator - 46 Burnt.jpg", "Stator - Burnt", (90, 50, 40)),
    ("03-2 Stator - 46 Burnt.jpg", "Stator detail - Burnt", (110, 60, 45)),
    ("04-1 Winding to ground insulation - 38 Ground fault.jpg", "Winding ground fault", (60, 80, 140)),
]
DEMO_COMPLETED_REPORT_PICS = ["00-1 As received.jpg", "03-1 Stator - 46 Burnt.jpg", "03-2 Stator - 46 Burnt.jpg",
                              "04-1 Winding to ground insulation - 38 Ground fault.jpg"]
