package org.colgram.core;

import android.content.SharedPreferences;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy.Type;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Isolated JVM tests for Track 1 privacy defaults, Contract A spam/mention clear gate,
 * cloaking suffix stripping, DoH Wave-1/max-anonymity stripping, translator fail-closed
 * security guards, and strict encrypted DNS enforcement.
 */
public final class ColgramPrivacyCloakTest {
    private static int checks;

    private static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            throw new AssertionError("ColgramPrivacyCloakTest failed [" + checks + "]: " + msg);
        }
    }

    private static SharedPreferences createSimplePrefs(final Map<String, Object> backing) {
        final Class<?> spClass = SharedPreferences.class;
        final Class<?> edClass = SharedPreferences.Editor.class;
        return (SharedPreferences) Proxy.newProxyInstance(
                spClass.getClassLoader(),
                new Class<?>[]{spClass},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getBoolean".equals(name)) {
                        Object v = backing.get(args[0]);
                        return (v instanceof Boolean) ? v : args[1];
                    } else if ("getString".equals(name)) {
                        Object v = backing.get(args[0]);
                        return (v instanceof String) ? v : args[1];
                    } else if ("getInt".equals(name)) {
                        Object v = backing.get(args[0]);
                        return (v instanceof Integer) ? v : args[1];
                    } else if ("getLong".equals(name)) {
                        Object v = backing.get(args[0]);
                        return (v instanceof Long) ? v : args[1];
                    } else if ("contains".equals(name)) {
                        return backing.containsKey(args[0]);
                    } else if ("getAll".equals(name)) {
                        return new HashMap<>(backing);
                    } else if ("edit".equals(name)) {
                        final Map<String, Object> pending = new HashMap<>();
                        return Proxy.newProxyInstance(
                                edClass.getClassLoader(),
                                new Class<?>[]{edClass},
                                (edProxy, edMethod, edArgs) -> {
                                    String em = edMethod.getName();
                                    if ("putBoolean".equals(em) || "putString".equals(em)
                                            || "putInt".equals(em) || "putLong".equals(em)) {
                                        if (edArgs[1] == null) {
                                            pending.remove(edArgs[0]);
                                        } else {
                                            pending.put((String) edArgs[0], edArgs[1]);
                                        }
                                        return edProxy;
                                    } else if ("apply".equals(em) || "commit".equals(em)) {
                                        backing.putAll(pending);
                                        return "commit".equals(em) ? Boolean.TRUE : null;
                                    }
                                    return null;
                                });
                    }
                    return null;
                });
    }

    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        // 1. Safe defaults in ColgramConfig static initializers
        check(!ColgramConfig.isGhostReadEnabled(), "ghostReadEnabled default must be false");
        check(!ColgramConfig.isGhostTypingEnabled(), "ghostTypingEnabled default must be false");
        check(ColgramConfig.isAutoUpdateEnabled(), "autoUpdateEnabled default must be true");
        check(!ColgramConfig.isUnlimitedMemoryEnabled(), "unlimitedMemoryEnabled default must be false");
        check(ColgramConfig.isStrictProxySecurityEnabled(), "strictProxySecurityEnabled default must be true");

        // 2. Contract A: ColgramGhostMode spam/mention/read-all clear ThreadLocal gate
        check(!ColgramGhostMode.isAllowingSpamMentionClear(),
                "isAllowingSpamMentionClear must default to false");
        check(!ColgramGhostMode.shouldPreventReadReceipt(123456L),
                "shouldPreventReadReceipt must honor ghostReadEnabled=false by default");

        ColgramGhostMode.runAllowingSpamMentionClear(() -> {
            check(ColgramGhostMode.isAllowingSpamMentionClear(),
                    "isAllowingSpamMentionClear must be true inside runAllowingSpamMentionClear");
            check(!ColgramGhostMode.shouldPreventReadReceipt(123456L),
                    "shouldPreventReadReceipt must allow read receipts inside runAllowingSpamMentionClear");
            ColgramGhostMode.runAllowingSpamMentionClear(() ->
                    check(ColgramGhostMode.isAllowingSpamMentionClear(),
                            "nested runAllowingSpamMentionClear must preserve depth > 0"));
            check(ColgramGhostMode.isAllowingSpamMentionClear(),
                    "outer runAllowingSpamMentionClear must stay active after inner exits");
        });
        check(!ColgramGhostMode.isAllowingSpamMentionClear(),
                "isAllowingSpamMentionClear must reset to false after outer exits");

        boolean caught = false;
        try {
            ColgramGhostMode.runAllowingSpamMentionClear(() -> {
                throw new IllegalStateException("boom");
            });
        } catch (IllegalStateException expected) {
            caught = true;
        }
        check(caught && !ColgramGhostMode.isAllowingSpamMentionClear(),
                "runAllowingSpamMentionClear must clean up ThreadLocal on exception");

        // 3. ColgramCloak: strip -colgram suffix cleanly from initConnection app_version
        check("12.10.6".equals(ColgramCloak.stripColgramSuffix("12.10.6-colgram.ci42")),
                "stripColgramSuffix must drop -colgram.* suffix");
        check("12.10.6".equals(ColgramCloak.stripColgramSuffix("12.10.6")),
                "stripColgramSuffix must preserve vanilla version");
        Map<String, String> cloaked = ColgramCloak.getCloakedConnectionParams(
                "RealPixel", "Android 14", "12.10.6-colgram.20261114", "ru");
        check("12.10.6".equals(cloaked.get("app_version")),
                "getCloakedConnectionParams must mask -colgram build tag");

        // 4. ColgramDohResolver: strict encrypted DNS disables plaintext domestic UDP/53 & Wave-1 layout
        byte[] dummyQuery = new byte[16];
        check(ColgramDohResolver.queryFastDomesticUdp(dummyQuery, null) == null,
                "queryFastDomesticUdp must return null when strictProxySecurityEnabled is true");
        ColgramDohResolver.Endpoint[] eps = ColgramDohResolver.configuredEndpoints();
        check(eps != null && eps.length == 16, "configuredEndpoints must keep all 16 entries");
        String firstGroup = eps[0].providerGroup;
        check(!"comss".equals(firstGroup) && !"yandex".equals(firstGroup) && !"alidns".equals(firstGroup),
                "first configured DoH endpoint must prioritize non-RU resolver, got=" + firstGroup);
        check("1.1.1.1".equals(eps[0].ip) && "cloudflare".equals(eps[0].providerGroup),
                "Wave 1 slot [0] must be Cloudflare 1.1.1.1");
        check("94.140.14.14".equals(eps[1].ip) && "adguard".equals(eps[1].providerGroup),
                "Wave 1 slot [1] must be AdGuard 94.140.14.14");
        check("141.133.184.24".equals(eps[2].ip) && "comss".equals(eps[2].providerGroup),
                "Wave 1 slot [2] must be RU-reachable Comss 141.133.184.24");
        check("76.76.2.0".equals(eps[3].ip) && "controld".equals(eps[3].providerGroup),
                "Wave 2 slot [3] must be ControlD 76.76.2.0");

        // 5 & 6. Max-anonymity RU DoH strip + ColgramLocalTranslator fail-closed guards (R12)
        Method activeEpsMethod = ColgramDohResolver.class.getDeclaredMethod("activeEndpoints", boolean.class);
        activeEpsMethod.setAccessible(true);
        try {
            Map<String, Object> backing = new HashMap<>();
            SharedPreferences sp = createSimplePrefs(backing);
            ColgramConfig.migrateAndLoadForTest(sp);

            // Default: strictProxySecurity=true, maxAnonymity=false
            List<ColgramDohResolver.Endpoint> defaultActive =
                    (List<ColgramDohResolver.Endpoint>) activeEpsMethod.invoke(null, false);
            check("141.133.184.24".equals(defaultActive.get(2).ip),
                    "default activeEndpoints Wave 1 slot [2] must keep Comss when maxAnonymity is off");
            check(!ColgramLocalTranslator.shouldAllowNoProxyFallback(),
                    "strictProxySecurityEnabled=true must block Proxy.NO_PROXY fallback");
            check(ColgramLocalTranslator.shouldFailClosedWhenUnpinned(null, java.net.Proxy.NO_PROXY),
                    "strictProxySecurityEnabled=true must fail closed when via == null");

            java.net.Proxy socks = new java.net.Proxy(Type.SOCKS, new InetSocketAddress("127.0.0.1", 1080));
            check(!ColgramLocalTranslator.shouldFailClosedWhenUnpinned(null, socks),
                    "SOCKS proxy with via == null remains remote-pinned and allowed");
            check(!ColgramLocalTranslator.shouldFailClosedWhenUnpinned(InetAddress.getByName("127.0.0.1"), java.net.Proxy.NO_PROXY),
                    "resolved non-null DoH address stays pinned and allowed");

            // Relax strictProxySecurity while maxAnonymity=false -> direct fallback allowed
            ColgramConfig.setStrictProxySecurityEnabled(false);
            check(ColgramLocalTranslator.shouldAllowNoProxyFallback(),
                    "relaxed strict+non-maxAnonymity allows Proxy.NO_PROXY fallback");
            check(!ColgramLocalTranslator.shouldFailClosedWhenUnpinned(null, java.net.Proxy.NO_PROXY),
                    "relaxed mode allows direct connection when via == null");

            // Force maxAnonymity=true while strictProxySecurity=false -> RU endpoints stripped & translator fails closed
            backing.put("max_anonymity_enabled", Boolean.TRUE);
            backing.put("strict_proxy_security_enabled", Boolean.FALSE);
            ColgramConfig.migrateAndLoadForTest(sp);
            List<ColgramDohResolver.Endpoint> maxAnonActive =
                    (List<ColgramDohResolver.Endpoint>) activeEpsMethod.invoke(null, false);
            for (ColgramDohResolver.Endpoint ep : maxAnonActive) {
                check(!"comss".equals(ep.providerGroup) && !"yandex".equals(ep.providerGroup)
                                && !"alidns".equals(ep.providerGroup),
                        "maxAnonymityEnabled must strip RU/CN endpoint: " + ep.ip);
            }
            check("76.76.2.0".equals(maxAnonActive.get(2).ip),
                    "ControlD promotes into Wave 1 slot [2] when maxAnonymityEnabled strips Comss");
            check(!ColgramLocalTranslator.shouldAllowNoProxyFallback(),
                    "maxAnonymityEnabled must block Proxy.NO_PROXY fallback even when strictProxySecurity=false");
            check(ColgramLocalTranslator.shouldFailClosedWhenUnpinned(null, java.net.Proxy.NO_PROXY),
                    "maxAnonymityEnabled must fail closed when via == null");
        } finally {
            ColgramConfig.resetForTest();
        }

        System.out.println("ColgramPrivacyCloakTest: " + checks + " checks PASS");
    }
}
