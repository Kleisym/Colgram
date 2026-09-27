"""The rotation comparator decides which proxy gets applied, so it is checked by running it.

The reported bug was a proxy whose state was never known being chosen and then never replaced.
One of the three causes is ordering: an entry that was never probed carries ping = -1, which
sorts ABOVE every real latency under a plain ascending compare. A source-level assertion can
only confirm the text changed; this extracts the comparator from the real file and executes it,
so a future edit that reintroduces the plain compare fails here.
"""
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ROTATION = (ROOT / "Telegram-Src/TMessagesProj/src/main/java/org/telegram/messenger"
            / "ProxyRotationController.java")

TEMPLATE = r"""
import java.util.ArrayList;
import java.util.List;

public class RotationSort {
    static class P {
        final int ping;
        P(int ping) { this.ping = ping; }
    }

    static int compare(P o1, P o2) {
BODY_PLACEHOLDER
    }

    public static void main(String[] args) {
        P unprobed = new P(-1);
        P fast = new P(120);
        P slow = new P(800);
        if (compare(unprobed, fast) <= 0) {
            throw new AssertionError("unprobed must sort AFTER a measured 120ms proxy");
        }
        if (compare(unprobed, slow) <= 0) {
            throw new AssertionError("unprobed must sort AFTER a measured 800ms proxy");
        }
        if (compare(fast, slow) >= 0) {
            throw new AssertionError("120ms must still beat 800ms");
        }
        List<P> list = new ArrayList<>(List.of(unprobed, slow, fast));
        list.sort(RotationSort::compare);
        if (list.get(0) != fast) {
            throw new AssertionError("the fastest measured proxy must win, got " + list.get(0).ping);
        }
        if (list.get(2) != unprobed) {
            throw new AssertionError("the unprobed proxy must end up last, got " + list.get(2).ping);
        }
        System.out.println("ROTATION_SORT_OK");
    }
}
"""


def comparator_from_source():
    source = ROTATION.read_text(encoding="utf-8")
    match = re.search(r"sortedList\.sort\(\(o1, o2\) -> \{(.*?)\n        \}\);", source, re.S)
    if not match:
        raise AssertionError("the rotation comparator was not found in ProxyRotationController")
    return match.group(1)


class RotationSortTest(unittest.TestCase):
    def test_unprobed_sorts_last_and_measured_still_wins_by_latency(self):
        java = TEMPLATE.replace("BODY_PLACEHOLDER", comparator_from_source())
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "RotationSort.java"
            path.write_text(java, encoding="utf-8")
            compiled = subprocess.run(["javac", "-d", folder, str(path)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(["java", "-cp", folder, "RotationSort"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("ROTATION_SORT_OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
