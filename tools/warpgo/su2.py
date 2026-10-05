import os
p = r'C:\Colgram\tools\warpgo\socksudp\main.go'
s = open(p, encoding='utf-8').read()
# resolve the proxy name over DoH: the system resolver on this device answers nothing, which is the
# condition the app's own ColgramDohResolver exists for.
old = '\tctrl, err := net.DialTimeout("tcp", proxy, 8*time.Second)'
new = '\tip := resolveProxy(proxy)\n\tif ip == "" {\n\t\treturn fmt.Errorf("no A record over DoH")\n\t}\n\tctrl, err := net.DialTimeout("tcp", net.JoinHostPort(ip, proxyPort(proxy)), 8*time.Second)'
if old not in s:
    raise SystemExit('anchor missing')
s = s.replace(old, new, 1)
s += '''
// proxyPort is the port in a host:port pair.
func proxyPort(p string) string {
	_, port, _ := net.SplitHostPort(p)
	return port
}

// resolveProxy asks DoH for an A record. The system resolver on this device answers nothing at all -
// measured: "lookup X on [::1]:53: read udp [::1]:37268->[::1]:53: read: connection refused" - and a
// Go binary that cannot resolve its own proxy cannot test it. This is the same masking the app does.
func resolveProxy(name string) string {
	host, _, _ := net.SplitHostPort(name)
	for _, r := range []string{"https://1.1.1.1/dns-query", "https://8.8.4.4/dns-query"} {
		req, err := http.NewRequest("GET", r+"?name="+host+"&type=A", nil)
		if err != nil {
			continue
		}
		req.Header.Set("Accept", "application/dns-json")
		cl := &http.Client{Timeout: 10 * time.Second}
		resp, err := cl.Do(req)
		if err != nil {
			continue
		}
		raw, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		var out struct {
			Answer []struct {
				Type int    `json:"type"`
				Data string `json:"data"`
			} `json:"Answer"`
		}
		json.Unmarshal(raw, &out)
		for _, a := range out.Answer {
			if a.Type == 1 {
				return a.Data
			}
		}
	}
	return ""
}
'''
s = s.replace('\t"strconv"', '\t"strconv"' + chr(10) + '\t"encoding/json"' + chr(10) + '\t"net/http"')
open(p, 'w', encoding='utf-8').write(s)
print('patched')