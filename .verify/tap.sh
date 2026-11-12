#!/usr/bin/env bash
# 按文本/语义描述点控件：uiautomator dump → 解析 bounds → 点中心（同一条命令内完成，避免布局漂移）。
# 用法: tap.sh text <正则>   |   tap.sh desc <描述>
set -uo pipefail
SERIAL="adb-10AF4X1BCN0061Z-VjLOHa._adb-tls-connect._tcp"
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -W)"
OUT="$DIR/ui_dump.xml"
export MSYS_NO_PATHCONV=1

mode="${1:?usage: tap.sh text|desc <pattern>}"
pat="${2:?}"

adb -s "$SERIAL" shell uiautomator dump /sdcard/ui_dump.xml >/dev/null 2>&1
adb -s "$SERIAL" exec-out cat /sdcard/ui_dump.xml > "$OUT" 2>/dev/null

XY=$(PAT="$pat" MODE="$mode" OUT="$OUT" node -e '
const fs=require("fs");
const x=fs.readFileSync(process.env.OUT,"utf8");
const fold=s=>s.replace(/[·⋯\s]/g,"").toLowerCase();
const want=fold(process.env.PAT);
const re=/<node[^>]*>/g;let m;let hits=[];
while((m=re.exec(x))){
  const n=m[0];
  const t=(n.match(/text="([^"]*)"/)||[])[1]||"";
  const d=(n.match(/content-desc="([^"]*)"/)||[])[1]||"";
  const b=(n.match(/bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"/));
  if(!b) continue;
  const target=process.env.MODE==="desc"?d:t;
  if(target && fold(target).includes(want)){
    hits.push({x1:+b[1],y1:+b[2],x2:+b[3],y2:+b[4]});
  }
}
// 同一文本出现多处时取最靠下的一处（详情页标题栏与剧集格同名，格子在下方）
const hit=hits.length?hits.sort((a,b)=>b.y1-a.y1)[0]:null;
if(!hit){console.log("");process.exit(0)}
console.log(Math.round((hit.x1+hit.x2)/2)+" "+Math.round((hit.y1+hit.y2)/2));
')

if [ -z "$XY" ]; then
  echo "[tap] NOT FOUND: $mode=$pat"
  exit 1
fi
echo "[tap] $mode=$pat -> $XY"
adb -s "$SERIAL" shell input tap $XY
