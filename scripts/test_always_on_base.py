from pathlib import Path
import unittest
import re
ROOT=Path(__file__).resolve().parents[1]
CORE=ROOT/'colgram-core/src/main/java/org/colgram/core'
class AlwaysOnBaseTest(unittest.TestCase):
 def test_base_is_not_an_optional_preference(self):
  s=(CORE/'ColgramConfig.java').read_text(encoding='utf-8')
  body=s.split('public static boolean isDpiBypassEnabled()',1)[1].split('}',1)[0]
  self.assertIn('return true;',body)
 def test_route_changes_never_stop_the_base_listener(self):
  for path in [CORE/'ColgramProxyManager.java',ROOT/'scripts/templates/ProxyListWarpControls.java.inc',ROOT/'scripts/templates/ColgramSubscriptionControls.java']:
   self.assertTrue('ColgramDpiBypass.stop()' not in path.read_text(encoding='utf-8'),str(path))
 def test_disabling_a_remote_proxy_returns_to_base(self):
  s=(CORE/'ColgramProxyManager.java').read_text(encoding='utf-8')
  body=s.split('public static void disableProxy(Context context)',1)[1].split('private static void publishPoolToStock()',1)[0]
  self.assertIn('ensureLocalBaseRoute(ctx)',body)
  self.assertTrue('putBoolean("proxy_enabled", false)' in body)
 def test_every_active_feed_is_requested_and_reports_a_parser_result(self):
  s=(CORE/'ColgramProxyManager.java').read_text(encoding='utf-8')
  f=s.split('private static void fetchSourceFeedsConcurrently()',1)[1].split('private static void runSourceFetchTasks(',1)[0]
  self.assertNotIn('MAX_STARTUP_',f)
  self.assertIn('batch.parsed',s)
  self.assertIn('getSourceDiagnostics()',s)
  self.assertIn('selectFeedWindow(',s)
 def test_protocol_probes_include_the_same_byte_preserving_base(self):
  s=(CORE/'ColgramProxyManager.java').read_text(encoding='utf-8')
  self.assertTrue('ColgramGuestProxyProbe.checkThroughBase(' in s)
 def test_masque_enrolment_requests_the_actual_masque_transport(self):
  s=(ROOT/'tools/warpgo/native/main.go').read_text(encoding='utf-8')
  self.assertTrue('"key_type": "secp256r1", "tunnel_type": "masque"' in s)
  self.assertTrue('"tun_type": "masque"' not in s)
 def test_positive_nodes_do_not_expire_before_the_scheduled_refresh(self):
  s=(CORE/'ColgramProxyManager.java').read_text(encoding='utf-8')
  def value(name):return int(re.search(name+r' = ([0-9_]+)L',s)[1].replace('_',''))
  ttl=value('REMEMBERED_VALID_MS')
  self.assertGreaterEqual(ttl,value('BACKGROUND_PROXY_CHECK_WINDOW_MS')+value('BACKGROUND_PROXY_READY_MS')+value('BACKGROUND_PROXY_POSITIVE_RECHECK_MS')+10000)
  due=s.split('private static void sortCatalogProbeOrder(',1)[1].split('private static void backgroundProxyTick',1)[0]
  self.assertTrue('candidate.nativeVerified && candidate.lastCheckAt > 0' in due)
 def test_fixture_flushes_catalog_restore_before_instrumentation_exits(self):
  s=(ROOT/'scripts/templates/ColgramControlledProxyDeviceTest.java').read_text(encoding='utf-8')
  f=s.split('finally {',1)[1]
  self.assertIn('.commit()',f)
if __name__=='__main__': unittest.main()
