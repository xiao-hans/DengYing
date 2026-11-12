import re

D = ("M19.43 12.98c.04-.32.07-.64.07-.98s-.03-.66-.07-.98l2.11-1.65c.19-.15.24-.42.12-.64"
     "l-2-3.46c-.12-.22-.39-.3-.61-.22l-2.49 1c-.52-.4-1.08-.73-1.69-.98l-.38-2.65"
     "C14.46 2.18 14.25 2 14 2h-4c-.25 0-.46.18-.49.42l-.38 2.65c-.61.25-1.17.59-1.69.98"
     "l-2.49-1c-.23-.09-.49 0-.61.22l-2 3.46c-.13.22-.07.49.12.64l2.11 1.65"
     "c-.04.32-.07.65-.07.98s.03.66.07.98l-2.11 1.65c-.19.15-.24.42-.12.64l2 3.46"
     "c.12.22.39.3.61.22l2.49-1c.52.4 1.08.73 1.69.98l.38 2.65c.03.24.24.42.49.42h4"
     "c.25 0 .46-.18.49-.42l.38-2.65c.61-.25 1.17-.59 1.69-.98l2.49 1c.23.09.49 0 .61-.22"
     "l2-3.46c.12-.22.07-.49-.12-.64l-2.11-1.65z")

C = "M12 15.5c-1.93 0-3.5-1.57-3.5-3.5s1.57-3.5 3.5-3.5 3.5 1.57 3.5 3.5-1.57 3.5-3.5 3.5z"

TOK = re.compile(r"[MmLlHhVvCcSsZz]|-?\d*\.?\d+")


def fmt(x):
    s = f"{x:.3f}".rstrip("0").rstrip(".")
    return "0" if s in ("", "-0") else s


def convert(d):
    toks = TOK.findall(d)
    i = 0
    cur = [0.0, 0.0]
    start = [0.0, 0.0]
    prev = None
    cmd = None
    out = []

    def take(n):
        nonlocal i
        v = [float(toks[i + k]) for k in range(n)]
        i += n
        return v

    while i < len(toks):
        t = toks[i]
        if re.match(r"[A-Za-z]", t):
            cmd = t
            i += 1
            if cmd in "Zz":
                out.append("close()")
                cur = list(start)
                prev = None
                continue
        rel = cmd.islower()
        c = cmd.upper()
        if c == "M":
            x, y = take(2)
            if rel:
                x += cur[0]
                y += cur[1]
            cur = [x, y]
            start = list(cur)
            out.append(f"moveTo({fmt(x)}f, {fmt(y)}f)")
            cmd = "l" if rel else "L"
            prev = None
        elif c == "L":
            x, y = take(2)
            if rel:
                x += cur[0]
                y += cur[1]
            cur = [x, y]
            out.append(f"lineTo({fmt(x)}f, {fmt(y)}f)")
            prev = None
        elif c == "H":
            (x,) = take(1)
            if rel:
                x += cur[0]
            cur = [x, cur[1]]
            out.append(f"lineTo({fmt(cur[0])}f, {fmt(cur[1])}f)")
            prev = None
        elif c == "V":
            (y,) = take(1)
            if rel:
                y += cur[1]
            cur = [cur[0], y]
            out.append(f"lineTo({fmt(cur[0])}f, {fmt(cur[1])}f)")
            prev = None
        elif c == "C":
            x1, y1, x2, y2, x, y = take(6)
            if rel:
                x1 += cur[0]
                y1 += cur[1]
                x2 += cur[0]
                y2 += cur[1]
                x += cur[0]
                y += cur[1]
            out.append(
                f"cubicTo({fmt(x1)}f, {fmt(y1)}f, {fmt(x2)}f, {fmt(y2)}f, {fmt(x)}f, {fmt(y)}f)"
            )
            prev = [x2, y2]
            cur = [x, y]
        elif c == "S":
            x2, y2, x, y = take(4)
            if rel:
                x2 += cur[0]
                y2 += cur[1]
                x += cur[0]
                y += cur[1]
            if prev:
                x1 = 2 * cur[0] - prev[0]
                y1 = 2 * cur[1] - prev[1]
            else:
                x1, y1 = cur[0], cur[1]
            out.append(
                f"cubicTo({fmt(x1)}f, {fmt(y1)}f, {fmt(x2)}f, {fmt(y2)}f, {fmt(x)}f, {fmt(y)}f)"
            )
            prev = [x2, y2]
            cur = [x, y]
        else:
            raise SystemExit("unsupported cmd: " + c)
    return out


lines = convert(D) + convert(C)
print("        val p = Path().apply {")
for ln in lines:
    print("            " + ln)
print("            fillType = PathFillType.EvenOdd")
print("        }")
print("        p0.fill(p)")
