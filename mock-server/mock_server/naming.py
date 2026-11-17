"""Photo naming rules (docs/spec.md §5). Mirror of app/.../core/PhotoNaming.kt and NaturalSort.kt.

Both are tested against testdata/photo_naming_cases.json. The real server (Phase 4) should reuse this module.
"""
from __future__ import annotations

import re

PART_MAX = 40
ISSUE_MAX = 30
TAG_MAX = 40
NAME_MAX = 120
EXT = ".jpg"

_ILLEGAL = re.compile(r'[\\:*?"<>|\x00-\x1f\x7f]')
_WITH_OUT = re.compile(r"\bw/(?=[A-Za-z])", re.IGNORECASE)
_WITH = re.compile(r"\bw/", re.IGNORECASE)
_COMMA_SPACES = re.compile(r"\s*,[\s,]*")
_SPACES = re.compile(r"\s+")
_CHUNK = re.compile(r"\d+|\D+")


def _trim_ends(s: str) -> str:
    return s.strip().lstrip(", ").rstrip(", .")


def sanitize(text: str, max_len: int | None = None) -> str:
    s = _WITH_OUT.sub("with", text)
    s = _WITH.sub("with", s)
    s = s.replace("/", ", ")
    s = _ILLEGAL.sub("", s)
    s = _COMMA_SPACES.sub(", ", s)
    s = _SPACES.sub(" ", s)
    s = _trim_ends(s)
    if max_len is not None and len(s) > max_len:
        s = _trim_ends(s[:max_len])
    return s


def _fit(stem: str) -> str:
    limit = NAME_MAX - len(EXT)
    if len(stem) > limit:
        stem = _trim_ends(stem[:limit])
    return stem + EXT


def seq_label(seq: int) -> str:
    return str(seq).zfill(2)


def part_photo(seq: int, n: int, part_description: str, issue_code: str, issue_description: str) -> str:
    part = sanitize(part_description, PART_MAX)
    code = sanitize(issue_code)
    issue = sanitize(issue_description, ISSUE_MAX)
    stem = f"{seq_label(seq)}-{n} {part} - {code}" + (f" {issue}" if issue else "")
    return _fit(stem)


def rma_photo(n: int, tag: str, tag_text: str | None = None) -> str:
    label = tag_text if tag.lower() == "other" and tag_text and tag_text.strip() else tag
    return _fit(f"00-{n} {sanitize(label, TAG_MAX)}")


def caption_to_filename(caption: str) -> str:
    """Report-photo caption -> filename (sanitised, ≤ 120 incl. extension)."""
    return _fit(sanitize(caption))


def natural_key(s: str):
    """Sort key: digits by value (ties: shorter first), text case-insensitive; digits before letters."""
    key = []
    for part in _CHUNK.findall(s):
        if part[0].isdigit():
            t = part.lstrip("0") or "0"
            key.append((0, len(t), t, len(part), ""))
        else:
            key.append((1, 0, "", 0, part.lower() + "\x00" + part))
    return key


def natural_sorted(items):
    return sorted(items, key=natural_key)


def next_photo_number(existing_names, seq: int) -> int:
    """Next free n for an SS prefix, given filenames already in the folder (never overwrite)."""
    prefix = re.compile(rf"^{re.escape(seq_label(seq))}-(\d+)\b")
    used = [int(m.group(1)) for name in existing_names if (m := prefix.match(name))]
    return (max(used) + 1) if used else 1
