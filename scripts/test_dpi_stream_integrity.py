"""A local TCP bridge may split TLS bytes; it must not rewrite the TLS transcript."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class DpiStreamIntegrityTest(unittest.TestCase):
    def test_generic_tcp_bridge_preserves_tls_handshake_bytes(self):
        source = (ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramDpiBypass.java").read_text(encoding="utf-8")
        pipe = source.split("private static void pipeWithAdvancedDesync(", 1)[1]
        self.assertNotIn("ColgramTlsMimic.rewrite(firstPayload)", pipe,
                         "Changing a ClientHello invalidates both TLS transcript and FakeTLS HMAC")
        self.assertIn("ColgramTlsMimic.fragment(firstPayload", pipe)

    def test_local_dial_uses_a_single_deadline_and_preserves_the_destination_port(self):
        source = (ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramDpiBypass.java").read_text(encoding="utf-8")
        method = source.split("private static Socket establishConnection(", 1)[1].split("private static boolean isMtProtoHost", 1)[0]
        self.assertIn("ColgramTelegramDcAddresses.candidatesFor(host)", method)
        self.assertNotIn("8443", method)
        self.assertNotIn("5222", method)


if __name__ == "__main__":
    unittest.main()
