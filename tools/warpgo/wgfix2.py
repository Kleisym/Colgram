p = r'C:\Colgram\tools\warpgo\wgprobe\main.go'
s = open(p, encoding='utf-8').read()
# PowerShell wrote literal newlines into the string literals. Rebuild them from the intent.
s = s.replace('fmt.Printf("peer public key derived, %d bytes' + chr(10) + '", len(peerPub))',
              'fmt.Printf("peer public key derived, %d bytes' + chr(92) + 'n", len(peerPub))')
s = s.replace('fmt.Println("  sent 148-byte handshake initiation' + chr(10) + '")',
              'fmt.Println("  sent 148-byte handshake initiation")')
s = s.replace('fmt.Printf("  ANSWERED: %d bytes, message type %d' + chr(10) + '", n, t)',
              'fmt.Printf("  ANSWERED: %d bytes, message type %d' + chr(92) + 'n", n, t)')
open(p, 'w', encoding='utf-8').write(s)
print('ok')