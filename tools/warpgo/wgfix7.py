import io
p = r'C:\Colgram\tools\warpgo\wgprobe\main.go'
lines = open(p, encoding='utf-8').read().split(chr(10))
start = next(i for i, ln in enumerate(lines) if 'SetReadDeadline' in ln)
end = start
while 'return nil' not in lines[end]:
    end += 1
NL = chr(92) + 'n'
tail = [
    chr(9) + 'conn.SetReadDeadline(time.Now().Add(8 * time.Second))',
    chr(9) + 'buf := make([]byte, 256)',
    chr(9) + 'n, rerr := conn.Read(buf)',
    chr(9) + 'if rerr != nil {',
    chr(9)*2 + 'return fmt.Errorf("read: %w", rerr)',
    chr(9) + '}',
    chr(9) + 'msgType := binary.LittleEndian.Uint32(buf[0:4])',
    chr(9) + 'fmt.Printf("  ANSWERED: %d bytes, message type %d' + NL + '", n, msgType)',
    chr(9) + 'return nil',
]
lines[start:end + 1] = tail
open(p, 'w', encoding='utf-8').write(chr(10).join(lines))
print('ok')