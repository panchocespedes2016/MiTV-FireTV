#!/bin/bash
# Repeat: build -> extract missing modules from the log -> mirror them ->
# rebuild, until the build succeeds or we run out of rounds.
cd "$(dirname "$0")/.."
for i in $(seq 1 12); do
  LOG="tools/iter-build-$i.log"
  echo "=== round $i: building -> $LOG"
  tools/build.sh assembleRelease > "$LOG" 2>&1
  if grep -q "BUILD SUCCESSFUL" "$LOG"; then
    echo "=== BUILD SUCCESSFUL in round $i"
    exit 0
  fi
  MISSING=$(grep -oE "Could not find [a-zA-Z0-9_.-]+:[a-zA-Z0-9_.-]+:[a-zA-Z0-9_.+-]+" "$LOG" \
    | sed -E 's/Could not find //; s/\.$//' | sort -u | paste -sd';')
  if [ -z "$MISSING" ]; then
    echo "=== round $i failed but no missing modules found; stopping"
    grep -B1 -A 12 "What went wrong" "$LOG" | head -30
    exit 1
  fi
  echo "=== round $i missing: $MISSING"
  EXTRA_SEEDS="$MISSING" python3 tools/mvnwalk.py > "tools/iter-walk-$i.log" 2>&1
  tail -1 "tools/iter-walk-$i.log"
done
echo "=== out of rounds"
exit 1
