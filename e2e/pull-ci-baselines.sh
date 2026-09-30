#!/usr/bin/env bash
# Забирает linux-эталоны из упавшего прогона CI (.github/workflows/ui-screenshots.yml):
# при расхождении CI перерисовывает все кадры и выкладывает их артефактом ui-screenshots-linux.
#   ./pull-ci-baselines.sh            — последний прогон текущей ветки
#   ./pull-ci-baselines.sh <run-id>
# После — просмотреть PNG в git diff и закоммитить.
set -euo pipefail
cd "$(dirname "$0")"
run="${1:-$(gh run list --workflow ui-screenshots.yml --branch "$(git branch --show-current)" \
  --limit 1 --json databaseId --jq '.[0].databaseId')}"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT
gh run download "$run" --name ui-screenshots-linux --dir "$tmp"
mkdir -p __screenshots__/linux
cp "$tmp"/*.png __screenshots__/linux/
git status --short __screenshots__/linux
