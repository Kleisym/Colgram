p = r'C:\Colgram\tools\warpgo\h3probe\main.go'
s = open(p, encoding='utf-8').read()
anchor = '\tfmt.Printf("  QUIC handshake OK sni=%s in %dms' + chr(92) + 'n", sni, time.Since(sw).Milliseconds())'
if anchor not in s:
    raise SystemExit('anchor missing')
add = anchor + chr(10) + chr(9) + '''// The edge either supports extended CONNECT or it does not, and nothing else in the handshake says
	// which. quic-go exposes it only through the HTTP/3 layer, so a ClientConn is opened here and its
	// settings read: an edge that answers datagrams=false and extendedConnect=false cannot carry MASQUE
	// no matter how many times the dial is retried.
	tr := &http3.Transport{DisableCompression: true}
	defer tr.Close()
	hc := tr.NewClientConn(conn)
	sctx, scancel := context.WithTimeout(context.Background(), 8*time.Second)
	defer scancel()
	select {
	case <-hc.ReceivedSettings():
		set := hc.Settings()
		fmt.Printf("  SETTINGS extendedConnect=%v datagrams=%v" + chr(92) + "n", set.EnableExtendedConnect, set.EnableDatagrams)
	case <-sctx.Done():
		fmt.Println("  SETTINGS: none within 8s")
	}'''
s = s.replace(anchor, add, 1)
open(p, 'w', encoding='utf-8').write(s)
print('ok')