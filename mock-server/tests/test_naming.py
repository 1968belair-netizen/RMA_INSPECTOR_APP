import json

from mock_server import naming
from conftest import REPO

CASES = json.loads((REPO / "testdata" / "photo_naming_cases.json").read_text(encoding="utf-8"))


def test_part_photo_names():
    for c in CASES["part_photos"]:
        assert naming.part_photo(c["seq"], c["n"], c["part"], c["issue_cd"], c["issue"]) == c["expect"]


def test_rma_photo_names():
    for c in CASES["rma_photos"]:
        assert naming.rma_photo(c["n"], c["tag"], c["tag_text"]) == c["expect"]


def test_natural_sort():
    for key in ("natural_sort", "natural_sort_filenames"):
        assert naming.natural_sorted(CASES[key]["input"]) == CASES[key]["expect"]


def test_next_photo_number_never_overwrites():
    names = ["03-1 A.jpg", "03-2 A.jpg", "04-1 B.jpg", "00-1 As received.jpg"]
    assert naming.next_photo_number(names, 3) == 3
    assert naming.next_photo_number(names, 5) == 1
    assert naming.next_photo_number(names, 0) == 2
