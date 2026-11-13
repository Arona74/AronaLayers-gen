#!/bin/bash
# usage: gen_seq.sh <gradle module, e.g. v1_21_1:fabric> <world name> <log file> <cx0,cz0,nx,nz>...
# Creates a fresh world with seed 7474 and generates the given chunk rectangles ONE CHUNK AT A TIME,
# in a fixed order, each waited for. Worldgen results depend on the order chunks are generated in
# (features and this mod read neighbouring chunks), so a plain "forceload a big area" gives a
# different world on every run. Run with JAVA_TOOL_OPTIONS=-Dmax.bg.threads=1 and JAVA_HOME = the
# JDK the module's server needs. Run from the repo root. Never creates eula.txt.
mod=$1; world=$2; log=$3; shift 3
run="${mod//://}/run"
sed -i -e "s/^level-name=.*/level-name=$world/" -e "s/^level-seed=.*/level-seed=7474/" "$run/server.properties"
: > "$log"
{
  until grep -q 'Done (' "$log" 2>/dev/null; do
    grep -q 'BUILD FAILED\|Exception in thread "main"' "$log" 2>/dev/null && exit 0
    sleep 1
  done
  sleep 2
  echo "gamerule randomTickSpeed 0"
  i=0
  for rect in "$@"; do
    IFS=, read -r cx0 cz0 nx nz <<< "$rect"
    for ((cx=cx0; cx<cx0+nx; cx++)); do for ((cz=cz0; cz<cz0+nz; cz++)); do
      i=$((i+1)); bx=$((cx*16)); bz=$((cz*16))
      echo "forceload add $bx $bz"
      t=0
      until grep -q "SEQ${i}_" "$log" 2>/dev/null; do
        sleep 0.25; t=$((t+1)); [ $t -gt 240 ] && break
        echo "execute if loaded $bx 0 $bz run say SEQ${i}_"
        sleep 0.15
      done
    done; done
  done
  sleep 5
  echo "save-all flush"
  until grep -q 'Saved the game' "$log" 2>/dev/null; do sleep 1; done
  sleep 2
  echo "stop"
} | ./gradlew.bat ":$mod:runServer" --console=plain >> "$log" 2>&1
echo "EXIT=$?" >> "$log"
