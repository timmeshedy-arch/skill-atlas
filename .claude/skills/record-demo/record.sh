#!/usr/bin/env bash
# Прогоняет сценарий демо в браузере, пишет видео и конвертирует его в mp4 для PR.
# usage: record.sh <scenario.mjs> <url> <out.mp4>   → печатает VIDEO=… FRAMES=…
set -euo pipefail

dir=$(cd "$(dirname "$0")" && pwd)
scenario=$(realpath "${1:?usage: record.sh <scenario.mjs> <url> <out.mp4>}")
url=${2:?url}
out=${3:?out.mp4}

[[ -d $dir/node_modules/playwright ]] || npm --prefix "$dir" install --silent >&2
(cd "$dir" && npx playwright install ffmpeg >/dev/null)

work=$(mktemp -d)
webm=$(node "$dir/record.mjs" "$scenario" "$url" "$work")

mkdir -p "$(dirname "$out")"
ffmpeg -loglevel error -y -i "$webm" -c:v libx264 -pix_fmt yuv420p -crf 23 -movflags +faststart "$out"

# Лист из 8 кадров по всей длине — чтобы проверить запись, не просматривая видео.
frames="${out%.*}-frames.png"
duration=$(ffprobe -v error -show_entries format=duration -of csv=p=0 "$out")
rate=$(awk -v d="$duration" 'BEGIN { print 8 / d }')
ffmpeg -loglevel error -y -i "$out" -vf "fps=$rate,scale=640:-1,tile=2x4" -frames:v 1 "$frames"

echo "VIDEO=$out"
echo "DURATION=${duration%.*}s"
echo "FRAMES=$frames"
