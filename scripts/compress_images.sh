#!/usr/bin/env bash
set -e

echo "Compressing images with FFmpeg..."
find app/src/main/res -name "*.png" | while read -r img; do
  echo "Compressing $img..."
  ffmpeg -nostdin -y -v error -i "$img" -pred mixed -compression_level 9 "${img}.tmp.png" && mv "${img}.tmp.png" "$img"
done
echo "Done! Image sizes:"
ls -lh app/src/main/res/drawable-*/ic_launcher.png
