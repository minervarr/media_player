#!/usr/bin/env python3
"""
Scan AutoEq ParametricEQ.txt files and emit a compressed JSON index.

Usage:
    python3 tools/build_eq_index.py

Reads from:  ignoreGIT/LIBS/AutoEq-master/results/
Writes to:   app/src/main/assets/eq_profiles.json.gz
"""

import json
import gzip
import re
import sys
from pathlib import Path

RESULTS_DIR = Path(__file__).resolve().parent.parent / "ignoreGIT" / "LIBS" / "AutoEq-master" / "results"
OUTPUT_FILE = Path(__file__).resolve().parent.parent / "app" / "src" / "main" / "assets" / "eq_profiles.bin"

# Map directory names to form factors
FORM_MAP = {
    "over-ear": "over-ear",
    "in-ear": "in-ear",
    "earbud": "earbud",
}

FILTER_RE = re.compile(
    r"Filter\s+\d+:\s+ON\s+(PK|LSC|HSC)\s+Fc\s+([\d.]+)\s+Hz\s+Gain\s+([-\d.]+)\s+dB\s+Q\s+([\d.]+)"
)
PREAMP_RE = re.compile(r"Preamp:\s+([-\d.]+)\s+dB")


def parse_profile(filepath):
    """Parse a ParametricEQ.txt file into a profile dict."""
    with open(filepath, "r", encoding="utf-8") as f:
        text = f.read()

    preamp_match = PREAMP_RE.search(text)
    if not preamp_match:
        return None
    preamp = float(preamp_match.group(1))

    filters = []
    for m in FILTER_RE.finditer(text):
        filters.append({
            "type": m.group(1),
            "fc": round(float(m.group(2)), 1),
            "gain": round(float(m.group(3)), 1),
            "q": round(float(m.group(4)), 2),
        })

    if not filters:
        return None

    return {"preamp": round(preamp, 1), "filters": filters}


def detect_form(path_parts):
    """Detect form factor from directory path."""
    for part in path_parts:
        lower = part.lower()
        if lower in FORM_MAP:
            return FORM_MAP[lower]
    return ""


def main():
    if not RESULTS_DIR.exists():
        print(f"Error: results directory not found: {RESULTS_DIR}", file=sys.stderr)
        sys.exit(1)

    profiles = []
    seen = set()

    for eq_file in sorted(RESULTS_DIR.rglob("*ParametricEQ.txt")):
        rel = eq_file.relative_to(RESULTS_DIR)
        parts = rel.parts

        if len(parts) < 3:
            continue

        source = parts[0]
        form = detect_form(parts)
        # Name is the parent directory of the EQ file
        name = eq_file.parent.name

        # Deduplicate by name+source+form
        key = (name, source, form)
        if key in seen:
            continue

        parsed = parse_profile(eq_file)
        if parsed is None:
            continue

        seen.add(key)
        profiles.append({
            "name": name,
            "source": source,
            "form": form,
            **parsed,
        })

    profiles.sort(key=lambda p: p["name"].lower())

    # Write compressed JSON
    OUTPUT_FILE.parent.mkdir(parents=True, exist_ok=True)
    json_bytes = json.dumps(profiles, separators=(",", ":")).encode("utf-8")
    with gzip.open(OUTPUT_FILE, "wb", compresslevel=9) as f:
        f.write(json_bytes)

    raw_size = len(json_bytes)
    gz_size = OUTPUT_FILE.stat().st_size
    print(f"Wrote {len(profiles)} profiles to {OUTPUT_FILE}")
    print(f"  JSON: {raw_size:,} bytes -> gzip: {gz_size:,} bytes ({gz_size*100//raw_size}%)")


if __name__ == "__main__":
    main()
