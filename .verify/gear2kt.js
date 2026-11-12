const D = "M19.43 12.98c.04-.32.07-.64.07-.98s-.03-.66-.07-.98l2.11-1.65c.19-.15.24-.42.12-.64"
  + "l-2-3.46c-.12-.22-.39-.3-.61-.22l-2.49 1c-.52-.4-1.08-.73-1.69-.98l-.38-2.65"
  + "C14.46 2.18 14.25 2 14 2h-4c-.25 0-.46.18-.49.42l-.38 2.65c-.61.25-1.17.59-1.69.98"
  + "l-2.49-1c-.23-.09-.49 0-.61.22l-2 3.46c-.13.22-.07.49.12.64l2.11 1.65"
  + "c-.04.32-.07.65-.07.98s.03.66.07.98l-2.11 1.65c-.19.15-.24.42-.12.64l2 3.46"
  + "c.12.22.39.3.61.22l2.49-1c.52.4 1.08.73 1.69.98l.38 2.65c.03.24.24.42.49.42h4"
  + "c.25 0 .46-.18.49-.42l.38-2.65c.61-.25 1.17-.59 1.69-.98l2.49 1c.23.09.49 0 .61-.22"
  + "l2-3.46c.12-.22.07-.49-.12-.64l-2.11-1.65z";
const C = "M12 15.5c-1.93 0-3.5-1.57-3.5-3.5s1.57-3.5 3.5-3.5 3.5 1.57 3.5 3.5-1.57 3.5-3.5 3.5z";

function fmt(x) {
  let s = x.toFixed(3).replace(/0+$/, "").replace(/\.$/, "");
  if (s === "" || s === "-0") s = "0";
  return s;
}

function convert(d) {
  const toks = d.match(/[MmLlHhVvCcSsZz]|-?\d*\.?\d+/g);
  let i = 0, cmd = null, cur = [0, 0], start = [0, 0], prev = null;
  const out = [];
  const take = (n) => { const v = toks.slice(i, i + n).map(Number); i += n; return v; };
  while (i < toks.length) {
    const t = toks[i];
    if (/[A-Za-z]/.test(t)) {
      cmd = t; i++;
      if (cmd === "Z" || cmd === "z") { out.push("close()"); cur = [...start]; prev = null; continue; }
    }
    const rel = cmd === cmd.toLowerCase();
    const c = cmd.toUpperCase();
    if (c === "M") {
      let [x, y] = take(2);
      if (rel) { x += cur[0]; y += cur[1]; }
      cur = [x, y]; start = [...cur];
      out.push(`moveTo(${fmt(x)}f, ${fmt(y)}f)`);
      cmd = rel ? "l" : "L"; prev = null;
    } else if (c === "L") {
      let [x, y] = take(2);
      if (rel) { x += cur[0]; y += cur[1]; }
      cur = [x, y];
      out.push(`lineTo(${fmt(x)}f, ${fmt(y)}f)`);
      prev = null;
    } else if (c === "H") {
      let [x] = take(1);
      if (rel) x += cur[0];
      cur = [x, cur[1]];
      out.push(`lineTo(${fmt(cur[0])}f, ${fmt(cur[1])}f)`);
      prev = null;
    } else if (c === "V") {
      let [y] = take(1);
      if (rel) y += cur[1];
      cur = [cur[0], y];
      out.push(`lineTo(${fmt(cur[0])}f, ${fmt(cur[1])}f)`);
      prev = null;
    } else if (c === "C") {
      let [x1, y1, x2, y2, x, y] = take(6);
      if (rel) { x1 += cur[0]; y1 += cur[1]; x2 += cur[0]; y2 += cur[1]; x += cur[0]; y += cur[1]; }
      out.push(`cubicTo(${fmt(x1)}f, ${fmt(y1)}f, ${fmt(x2)}f, ${fmt(y2)}f, ${fmt(x)}f, ${fmt(y)}f)`);
      prev = [x2, y2]; cur = [x, y];
    } else if (c === "S") {
      let [x2, y2, x, y] = take(4);
      if (rel) { x2 += cur[0]; y2 += cur[1]; x += cur[0]; y += cur[1]; }
      const [x1, y1] = prev ? [2 * cur[0] - prev[0], 2 * cur[1] - prev[1]] : [cur[0], cur[1]];
      out.push(`cubicTo(${fmt(x1)}f, ${fmt(y1)}f, ${fmt(x2)}f, ${fmt(y2)}f, ${fmt(x)}f, ${fmt(y)}f)`);
      prev = [x2, y2]; cur = [x, y];
    } else {
      throw new Error("unsupported cmd: " + c);
    }
  }
  return out;
}

const lines = [...convert(D), ...convert(C)];
console.log(lines.map((l) => "            " + l).join("\n"));
