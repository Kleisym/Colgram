p = r'C:\Colgram\tools\warpgo\wgprobe\main.go'
lines = open(p, encoding='utf-8').read().split(chr(10))
# Replace the tail of probe() from the deadline line to the closing brace with a clean version.
for i, ln in enumerate(lines):
    if 'SetReadDeadline' in ln:
        start = i
        break
else:
    raise SystemExit('anchor not found')
end = start
while 'return nil' not in lines[end]:
    end += 1
tail = [
    '\tconn.SetReadDeadline(time.Now().Add(8 * time.Second))',
    '\tbuf := make([]byte, 256)',
    '\tn, rerr := conn.Read(buf)',
    '\tif rerr != nil {',
    '\t\treturn fmt.Errorf("read: %w", rerr)',
    '\t}',
    '\tmsgType := binary.LittleEndian.Uint32(buf[0:4])',
    '\tfmt.Printf("  ANSWERED: %d bytes, message type %d" + chr(92) + 'n", n, msgType)',
    '\treturn nil',
]
lines[start:end + 1] = tail
open(p, 'w', encoding='utf-8').write(chr(10).join(lines))
print('rewrote probe tail')