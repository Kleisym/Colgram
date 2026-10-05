p = r'C:\Colgram\tools\warpgo\wgprobe\main.go'
s = open(p, encoding='utf-8').read()
s = s.replace('import (', 'import (\n\t"encoding/base64"')
s += '''
func base64Decode(s string) ([]byte, error) { return base64.StdEncoding.DecodeString(s) }
'''
open(p, 'w', encoding='utf-8').write(s)