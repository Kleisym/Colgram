import base64
p = (b'{"v":"2","ps":"Moscow","add":"ru.example.org","port":"443",'
     b'"id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","aid":"0","scy":"auto",'
     b'"net":"ws","type":"none","host":"cdn.example.org","path":"/ray",'
     b'"tls":"tls"}')
b = base64.b64encode(p).decode()
print('full uri len:', len('vmess://' + b))
print('b64 has padding:', b.endswith('='))
# The payload contains + and / which are valid base64 chars, but the encoded text itself may
# contain characters that Base64.decode in android handles differently from python's.
print('chars used:', sorted(set(ch for ch in b if not ch.isalnum())))
