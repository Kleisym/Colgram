"""The subscription parser is checked against the exact strings a VPN bot returns.

The user pastes whatever a bot gave them, so the input is not a tidy fixture: it is base64 of a
list, or a plain list, or a Clash YAML. Each of those has already broken a parser that only ever
saw one of them. Samples written to match the parser's own output would prove only that it agrees
with itself.
"""
import base64
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "colgram-core/src/main/java/org/colgram/core/ColgramSubscription.java"
ANDROID_JAR = r"C:\android-sdk\platforms\android-34\android.jar"


def _vmess():
    payload = (b'{"v":"2","ps":"Moscow","add":"ru.example.org","port":"443",'
               b'"id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","aid":"0","scy":"auto",'
               b'"net":"ws","type":"none","host":"cdn.example.org","path":"/ray",'
               b'"tls":"tls"}')
    return "vmess://" + base64.b64encode(payload).decode()


# label, the exact text a bot returns, protocol, host, port, an extra field to check
CASES = [
    ("vless reality",
     "vless://8f2a1b44-1111-2222-3333-444455556666@vpn.example.net:443"
     "?security=reality&sni=www.microsoft.com&pbk=PUBLICKEY&sid=abcd"
     "&type=tcp&flow=xtls-rprx-vision#Berlin%20-%2010x",
     "vless", "vpn.example.net", "443", "flow=xtls-rprx-vision"),
    ("vmess json", _vmess(), "vmess", "ru.example.org", "443", "path=/ray"),
    ("trojan",
     "trojan://p4ssw0rd@trojan.example.com:443?security=tls&sni=front.example.com"
     "&type=ws&path=%2Fws#Amsterdam",
     "trojan", "trojan.example.com", "443", "sni=front.example.com"),
    ("shadowsocks", "ss://YWVzLTI1Ni1nY206c2VjcmV0@aes.example.io:8388#Tokyo",
     "ss", "aes.example.io", "8388", "method=aes-256-gcm"),
    ("hysteria2", "hysteria2://pw@a2.example.net:443?sni=cdn.example.net&insecure=1#Hysteria%20RU",
     "hysteria2", "a2.example.net", "443", "sni=cdn.example.net"),
    ("hysteria v1", "hysteria://pw@a1.example.net:36712?protocol=udp&auth=abc#Hysteria1",
     "hysteria", "a1.example.net", "36712", "password=pw"),
    ("socks5", "socks5://1.2.3.4:1080#Local", "socks5", "1.2.3.4", "1080", "name=Local"),
    ("ipv6 literal", "vless://uuid@[2001:db8::1]:443?type=tcp#IPv6",
     "vless", "2001:db8::1", "443", ""),
]


CLASH = (
    "port: 7890\n"
    "proxies:\n"
    "  - name: RU node\n"
    "    type: vless\n"
    "    server: clash.example.net\n"
    "    port: 443\n"
    "    uuid: 1234abcd-0000-0000-0000-000000000000\n"
    "    network: ws\n"
    "  - name: SS node\n"
    "    type: ss\n"
    "    server: ss.example.org\n"
    "    port: 8388\n"
    "    cipher: aes-256-gcm\n"
    "    password: secret\n"
)


HARNESS = r'''
import java.lang.reflect.Method;
import java.util.List;

public class SubCheck {
    public static void main(String[] args) throws Exception {
        Class<?> node = Class.forName("org.colgram.core.ColgramSubscription$Node");
        Method parse = Class.forName("org.colgram.core.ColgramSubscription")
                .getMethod("parse", String.class);
        for (int i = 0; i + 5 < args.length; i += 6) {
            String label = args[i];
            List<?> nodes = (List<?>) parse.invoke(null, args[i + 1]);
            if (nodes.isEmpty()) {
                throw new AssertionError(label + ": parsed nothing");
            }
            Object first = nodes.get(0);
            check(label, "protocol", node, first, args[i + 2]);
            check(label, "address", node, first, args[i + 3]);
            check(label, "port", node, first, args[i + 4]);
            String extra = args[i + 5];
            if (!extra.isEmpty()) {
                String[] pair = extra.split("=", 2);
                check(label, pair[0], node, first, pair[1]);
            }
            System.out.println("ok  " + label);
        }
    }

    private static void check(String label, String field, Class<?> node, Object instance,
                              String expected) throws Exception {
        Object actual;
        try {
            actual = node.getField(field).get(instance);
        } catch (NoSuchFieldException e) {
            throw new AssertionError(label + ": no field " + field);
        }
        if (!expected.equals(String.valueOf(actual))) {
            throw new AssertionError(label + ": " + field + " is " + actual
                    + ", expected " + expected);
        }
    }
}
'''

# android.jar ships android.util.Log as a stub that throws "Stub!" on every call, so a desktop
# JVM cannot run any Colgram class that logs. Compiling a real one into the same package and
# putting it FIRST on the classpath makes the call sites bind to it instead of the stub.
LOG_STUB = r'''
package android.util;

public final class Log {
    private Log() {}
    public static int v(String tag, String message) { return 0; }
    public static int d(String tag, String message) { return 0; }
    public static int i(String tag, String message) { return 0; }
    public static int w(String tag, String message) { return 0; }
    public static int e(String tag, String message) { return 0; }
}
'''

# android.util.Base64 is a stub in android.jar for the same reason, and the parser needs it for
# every subscription a bot returns, since the list itself is base64. Supplied here too.
BASE64_STUB = r'''
package android.util;

public final class Base64 {
    private Base64() {}
    public static final int DEFAULT = 0;
    public static final int NO_PADDING = 1;
    public static final int NO_WRAP = 2;
    public static byte[] decode(String value, int flags) {
        return java.util.Base64.getMimeDecoder().decode(value);
    }
    public static byte[] decode(byte[] value, int flags) {
        return java.util.Base64.getMimeDecoder().decode(value);
    }
    public static String encodeToString(byte[] value, int flags) {
        return java.util.Base64.getEncoder().encodeToString(value);
    }
}
'''

JUNK_HARNESS = r'''
import java.util.List;

public class JunkCheck {
    public static void main(String[] args) throws Exception {
        String[] junk = {"", "   ", "not a uri at all", "%%%", "vless://", "vmess://@@@",
                         "#comment", "http://", "://nohost", "a"};
        for (String item : junk) {
            List<?> nodes = (List<?>) Class.forName("org.colgram.core.ColgramSubscription")
                    .getMethod("parse", String.class).invoke(null, item);
            for (Object node : nodes) {
                // Anything returned has to be diallable, or it is worse than nothing: the pool
                // would offer a node with no address and the settings row would show it.
                String address = String.valueOf(node.getClass().getField("address").get(node));
                int port = (Integer) node.getClass().getField("port").get(node);
                if (address.isEmpty() || port <= 0) {
                    throw new AssertionError("unusable node from " + item + ": " + address + ":" + port);
                }
            }
        }
        System.out.println("JUNK_OK");
    }
}
'''


def run_cases(cases):
    with tempfile.TemporaryDirectory() as folder:
        harness = Path(folder) / "SubCheck.java"
        harness.write_text(HARNESS, encoding="utf-8")
        log = Path(folder) / "Log.java"
        log.write_text(LOG_STUB, encoding="utf-8")
        base64_stub = Path(folder) / "Base64.java"
        base64_stub.write_text(BASE64_STUB, encoding="utf-8")
        compiled = subprocess.run(
            ["javac", "-d", folder, str(log), str(base64_stub), str(SOURCE), str(harness),
             "-classpath", ANDROID_JAR],
            capture_output=True, text=True)
        if compiled.returncode != 0:
            raise AssertionError("javac failed:\n" + compiled.stdout + compiled.stderr)
        args = []
        for label, text, protocol, host, port, extra in cases:
            args.extend([label, text, protocol, host, port, extra])
        return subprocess.run(
            ["java", "-cp", folder, "SubCheck"] + args,
            capture_output=True, text=True)


class SubscriptionParserTest(unittest.TestCase):
    def test_every_scheme_a_bot_returns_is_parsed(self):
        result = run_cases(CASES)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(len(result.stdout.strip().splitlines()), len(CASES))

    def test_a_base64_wrapped_list_is_unwrapped(self):
        body = "\n".join(case[1] for case in CASES[:3])
        wrapped = base64.b64encode(body.encode()).decode()
        result = run_cases([("base64 list", wrapped, "vless", "vpn.example.net", "443", "")])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_clash_yaml_is_parsed(self):
        result = run_cases([("clash", CLASH, "vless", "clash.example.net", "443", "name=RU node")])
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_junk_never_crashes_the_caller(self):
        # Parsing nothing is allowed, so this cannot assert through the harness, which requires a
        # node. What must hold is that parse() returns an empty list instead of throwing - the
        # difference between "this paste was not a subscription" and the app dying on a paste.
        with tempfile.TemporaryDirectory() as folder:
            harness = Path(folder) / "JunkCheck.java"
            harness.write_text(JUNK_HARNESS, encoding="utf-8")
            log = Path(folder) / "Log.java"
            log.write_text(LOG_STUB, encoding="utf-8")
            base64_stub = Path(folder) / "Base64.java"
            base64_stub.write_text(BASE64_STUB, encoding="utf-8")
            compiled = subprocess.run(
                ["javac", "-d", folder, str(log), str(base64_stub), str(SOURCE), str(harness),
                 "-classpath", ANDROID_JAR],
                capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                ["java", "-cp", folder, "JunkCheck"],
                capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("JUNK_OK", result.stdout)


if __name__ == "__main__":
    unittest.main()
