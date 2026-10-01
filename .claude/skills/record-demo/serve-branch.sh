#!/usr/bin/env bash
# Собирает skill-atlas из ветки и поднимает `serve` на свободном порту — для записи демо.
# usage: serve-branch.sh [branch]   → печатает URL=… PID=… LOG=… WORKTREE=…
set -euo pipefail

root=$(git rev-parse --show-toplevel)
branch=${1:-$(git -C "$root" branch --show-current)}
slug=${branch//\//-}

# Worktree, в котором уже checkout'нута ветка, — иначе новый рядом с репо.
wt=$(git -C "$root" worktree list --porcelain \
  | awk -v b="branch refs/heads/$branch" '/^worktree /{p=substr($0, 10)} $0 == b {print p}')
if [[ -z $wt ]]; then
  wt="$(dirname "$root")/demo-$slug"
  git -C "$root" worktree add -q "$wt" "$branch"
  echo "created worktree $wt" >&2
fi

# gradle.properties локальный (org.gradle.java.home) и в git не лежит.
if [[ -f $root/gradle.properties && ! -f $wt/gradle.properties ]]; then
  cp "$root/gradle.properties" "$wt/"
fi

(cd "$wt" && ./gradlew shadowJar -q) >&2

port=$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
token=${GITHUB_TOKEN:-$(gh auth token ${DEMO_GH_USER:+-u "$DEMO_GH_USER"} 2>/dev/null || true)}
[[ -n $token ]] || echo "warning: no GitHub token — scans will hit the anonymous rate limit" >&2
log="${TMPDIR:-/tmp}/skill-atlas-demo-$slug.log"

GITHUB_TOKEN=$token nohup java -jar "$wt/build/libs/skill-atlas.jar" serve --port "$port" >"$log" 2>&1 &
pid=$!

for _ in $(seq 1 60); do
  curl -fs -o /dev/null "http://127.0.0.1:$port/" && break
  kill -0 "$pid" 2>/dev/null || { cat "$log" >&2; exit 1; }
  sleep 0.5
done
curl -fs -o /dev/null "http://127.0.0.1:$port/" || { echo "serve did not start, see $log" >&2; kill "$pid"; exit 1; }

echo "URL=http://127.0.0.1:$port/"
echo "PID=$pid"
echo "LOG=$log"
echo "WORKTREE=$wt"
