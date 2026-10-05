p = r'C:\Colgram\Telegram-Src\colgram-core\src\main\java\org\colgram\core\ColgramWarpMasqueTunnel.java'
s = open(p, encoding='utf-8').read()
# setSocksFront must not also set the relay: they are different capabilities and conflating them would
# send datagrams through a node picked for a CONNECT.
old = '''        ColgramUdpTunnel.setUdpRelay(udpRelay);
'''
# only remove the occurrence inside setSocksFront (the first one)
i = s.find(old)
if i >= 0:
    s = s[:i] + s[i + len(old):]
open(p, 'w', encoding='utf-8').write(s)
print('ok')