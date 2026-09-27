#!/usr/bin/env python3
"""Final touch-ups: fix the three empty-string wrapper lines and the duplicated javadoc opener."""
import io

P = r"C:\Colgram\scripts\apply-patches.py"
lines = io.open(P, encoding="utf-8").read().split("\n")
BS = chr(92)

fixed_empty = 0
for i, line in enumerate(lines):
    if line == '        "':
        lines[i] = '        "' + BS + 'n"'
        fixed_empty += 1

# The guard javadoc opener got duplicated when region A was rebuilt.
for i in range(len(lines) - 1):
    if lines[i] == '        "    /**' + BS + 'n"' and lines[i + 1] == '        "    /**' + BS + 'n"':
        del lines[i]
        break

io.open(P, "w", encoding="utf-8").write("\n".join(lines))
print("empty lines fixed:", fixed_empty)
