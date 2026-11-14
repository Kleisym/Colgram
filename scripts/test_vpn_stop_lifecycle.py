from pathlib import Path
import unittest
ROOT=Path(__file__).resolve().parents[1]
class VpnStopLifecycleTest(unittest.TestCase):
 def test_stop_releases_the_tun_before_waiting_for_android_unbind(self):
  s=(ROOT/'vendor/colgram-singbox/src/main/java/org/colgram/singbox/ColgramVpnService.java').read_text(encoding='utf-8')
  self.assertTrue('stopOwnedGeneration' in s, 'stopService alone leaves the system VPN binding and TUN alive')
  body=s.split('private void stopOwnedGeneration(',1)[1].split('private static final class AppProfile',1)[0]
  self.assertLess(body.index('releaseResources()'), body.index('stopSelf()'))
  self.assertTrue('requested != controlGeneration' in body)
if __name__=='__main__': unittest.main()
