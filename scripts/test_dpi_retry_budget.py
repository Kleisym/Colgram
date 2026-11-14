from pathlib import Path
import unittest
CORE=Path(__file__).resolve().parents[1]/'colgram-core/src/main/java/org/colgram/core'
class DpiRetryBudgetTest(unittest.TestCase):
 def test_failed_local_connects_cannot_create_an_unbounded_native_retry_loop(self):
  s=(CORE/'ColgramDpiBypass.java').read_text(encoding='utf-8')
  self.assertTrue('HANDSHAKE_SLOTS' in s)
  accept=s.split('private static void acceptClients(',1)[1].split('public static synchronized void stop()',1)[0]
  self.assertLess(accept.index('HANDSHAKE_SLOTS.tryAcquire'), accept.index('listener.accept()'))
  self.assertTrue('HANDSHAKE_SLOTS.release()' in accept)
  failed=s.split('if (targetSocket == null) {',1)[1].split('// SOCKS5 success reply',1)[0]
  self.assertTrue('REJECT_RETRY_DELAY_MS' in failed)
if __name__=='__main__': unittest.main()
