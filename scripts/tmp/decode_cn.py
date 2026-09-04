import io, re, sys
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8", errors="replace")
pat = re.compile(chr(92) + chr(92) + "u([0-9a-fA-F]{4})")
for p in sys.argv[1:]:
    txt = open(p, encoding="utf-8").read()
    n = len(pat.findall(txt))
    if n:
        txt = pat.sub(lambda m: chr(int(m.group(1), 16)), txt)
        open(p, "w", encoding="utf-8", newline="\n").write(txt)
    print(p.split("/")[-1], "| decoded:", n)
