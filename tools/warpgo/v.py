import io
p = r'C:\Colgram\Telegram-Src\colgram-core\src\main\java\org\colgram\core\ColgramUdpTunnel.java'
s = open(p, encoding='utf-8').read()
old = '    private static InetAddress localIPv4() {'
if old not in s:
    raise SystemExit('anchor missing')
s = s.replace(old, '    static InetAddress localIPv4() {', 1)
open(p, 'w', encoding='utf-8').write(s)
print('widened')