p = r'C:\Colgram\Telegram-Src\colgram-core\src\main\java\org\colgram\core\ColgramUdpTunnel.java'
s = open(p, encoding='utf-8').read()
# The relay is named by the caller rather than read from a preference, because this class has no
# Context and the app sets the field through setSocksFront-like plumbing on the Java side.
s = s.replace('SocksUdpRelay.open(SocksUdpRelay.udpRelaySetting())', 'SocksUdpRelay.open(udpRelayName)')
s = s.replace('Log.i(TAG, "udp leaving through SOCKS relay " + SocksUdpRelay.udpRelaySetting()',
              'Log.i(TAG, "udp leaving through SOCKS relay " + udpRelayName')
open(p, 'w', encoding='utf-8').write(s)
print('ok')