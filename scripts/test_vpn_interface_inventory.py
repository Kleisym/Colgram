from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1]


class VpnInterfaceInventoryTest(unittest.TestCase):
    def test_libbox_receives_prefixes_not_scoped_bare_addresses(self):
        source = (ROOT / 'vendor/colgram-singbox/src/main/java/org/colgram/singbox/ColgramPlatformInterface.java').read_text(encoding='utf-8')
        addresses = source.split('private static StringIterator addresses(', 1)[1].split('@Override\n    public synchronized void startDefault', 1)[0]
        self.assertIn('getNetworkPrefixLength()', addresses)
        self.assertIn('getInterfaceAddresses()', addresses)
        self.assertIn('indexOf(\'%\')', addresses)
        self.assertIn(' + "/" + ', addresses)
        self.assertIn('wrapper.setFlags(', source)


if __name__ == '__main__': unittest.main()
