#!/usr/bin/env bash
set -euo pipefail

SNAPSHOTS_DIR="app/src/test/snapshots/images"
OUT_DIR="screenshots"

mkdir -p "$OUT_DIR"

echo "=== Recording Paparazzi snapshots (1080p + 4K) ==="
./gradlew :app:recordPaparazziDebug

echo ""
echo "=== Collecting screenshots ==="

for f in "$SNAPSHOTS_DIR"/*.png; do
    base="$(basename "$f")"
    # Extract snapshot name: everything from first "activity_" or "item_" to end
    name="$(echo "$base" | sed -E 's/.*_(activity_|item_)/\1/' | sed 's/\.png$//')"
    cp "$f" "$OUT_DIR/${name}.png"
    echo "  $name.png"
done

echo ""
echo "=== Upscaling 4K to 8K via ffmpeg (Lanczos) ==="
for f in "$OUT_DIR"/*_4k.png; do
    [ -f "$f" ] || continue
    name_8k="${f/_4k.png/_8k.png}"
    echo "  $(basename "$f") -> $(basename "$name_8k")"
    ffmpeg -y -loglevel warning -i "$f" -vf scale=4320:7680:flags=lanczos "$name_8k"
done

echo ""
echo "=== Done ==="
ls -lh "$OUT_DIR/"*.png
