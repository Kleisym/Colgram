import os
p = r'C:\Colgram\tools\warpgo\wgprobe\main.go'
s = open(p, encoding='utf-8').read()
old = 'conn, err := net.Dial("udp", net.JoinHostPort(t.ip, strconv.Itoa(t.port)))'
if old not in s:
    raise SystemExit('anchor missing')
new = '''// BIND picks the local address. With several tunnels holding a default route, the source address the
	// kernel picks decides which one carries the datagram, and the address the tunnel owns is not the one
	// with a working path to the edge.
	laddr := &net.UDPAddr{}
	if b := os.Getenv("BIND"); b != "" {
		laddr.IP = net.ParseIP(b).To4()
	}
	conn, err := net.DialUDP("udp", laddr, &net.UDPAddr{IP: net.ParseIP(t.ip), Port: t.port})'''
s = s.replace(old, new, 1)
open(p, 'w', encoding='utf-8').write(s)
print('ok')