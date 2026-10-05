p1 = r'C:\Colgram\Telegram-Src\colgram-core\src\main\java\org\colgram\core\SocksUdpRelay.java'
s = open(p1, encoding='utf-8').read()
s = s.replace(
  '            ctrl.connect(new InetSocketAddress(\n                    ColgramDohResolver.resolveOrSystem(host), port), GREETING_TIMEOUT_MS);',
  '            // resolveOrSystem answers an array because that is what InetAddress takes; a proxy name\n'
  '            // that the system resolver cannot answer is the case the DoH resolver exists for.\n'
  '            java.net.InetAddress[] addrs = ColgramDohResolver.resolveOrSystem(host);\n'
  '            java.net.InetAddress target1 = addrs != null && addrs.length > 0 ? addrs[0]\n'
  '                    : java.net.InetAddress.getByAddress(new byte[]{0, 0, 0, 0});\n'
  '            ctrl.connect(new InetSocketAddress(target1, port), GREETING_TIMEOUT_MS);')
s = s.replace('            DatagramSocket dgram = new DatagramSocket(new InetSocketAddress(0));',
              '            DatagramSocket dgram = new DatagramSocket();')
open(p1, 'w', encoding='utf-8').write(s)

p2 = r'C:\Colgram\Telegram-Src\colgram-core\src\main\java\org\colgram\core\ColgramUdpTunnel.java'
t = open(p2, encoding='utf-8').read()
t = t.replace('SocksUdpRelay.open(udpRelaySetting())', 'SocksUdpRelay.open(SocksUdpRelay.udpRelaySetting())')
t = t.replace('Log.i(TAG, "udp leaving through SOCKS relay " + udpRelaySetting()',
              'Log.i(TAG, "udp leaving through SOCKS relay " + SocksUdpRelay.udpRelaySetting()')
open(p2, 'w', encoding='utf-8').write(t)
print('fixed')