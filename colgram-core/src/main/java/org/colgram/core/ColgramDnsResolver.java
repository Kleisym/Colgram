package org.colgram.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * ColgramDnsResolver — DNS-over-HTTPS (DoH) resolver.
 * Circumvents ISP-level DNS poisoning and censorship by querying
 * encrypted DoH endpoints (Cloudflare 1.1.1.1 / Google DNS).
 */
public class ColgramDnsResolver {

    private static final String CLOUDFLARE_DOH = "https://1.1.1.1/dns-query?name=";
    private static final String GOOGLE_DOH = "https://dns.google/resolve?name=";

    /**
     * Resolves a hostname to a list of IP addresses via SNI-pinned DoH ({@link ColgramDohResolver}),
     * preventing plaintext DNS or unpinned system fallback leaks.
     */
    public static List<InetAddress> resolve(String hostname) {
        List<InetAddress> addresses = new ArrayList<>();
        if (!ColgramConfig.isDohEnabled() || hostname == null) {
            return addresses;
        }
        InetAddress[] resolved = ColgramDohResolver.resolve(hostname);
        if (resolved != null) {
            for (InetAddress addr : resolved) {
                if (addr != null && addr.getAddress() != null && addr.getAddress().length == 4) {
                    addresses.add(addr);
                }
            }
            if (addresses.isEmpty()) {
                for (InetAddress addr : resolved) {
                    if (addr != null) addresses.add(addr);
                }
            }
        }
        if (addresses.isEmpty() && !ColgramConfig.isStrictProxySecurityEnabled()
                && !ColgramConfig.isMaxAnonymityEnabled()) {
            addresses = queryDoh(CLOUDFLARE_DOH + hostname + "&type=A");
            if (addresses.isEmpty()) {
                addresses = queryDoh(GOOGLE_DOH + hostname + "&type=A");
            }
        }
        return addresses;
    }

    private static List<InetAddress> queryDoh(String requestUrl) {
        List<InetAddress> list = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(requestUrl);
            java.net.Proxy proxy = ColgramHttp.pickRelayProxy();
            if (proxy == null && ColgramDpiBypass.isBound()) {
                proxy = new java.net.Proxy(java.net.Proxy.Type.SOCKS,
                        new java.net.InetSocketAddress("127.0.0.1", ColgramDpiBypass.activePort()));
            }
            if (proxy != null) {
                conn = (HttpURLConnection) url.openConnection(proxy);
            } else {
                if (ColgramConfig.isStrictProxySecurityEnabled() || ColgramConfig.isMaxAnonymityEnabled()) {
                    return list;
                }
                conn = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
            }
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", ColgramPolymorphicStealth.pickBrowserUserAgent());
            conn.setRequestProperty("Accept", "application/dns-json");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);

            if (conn.getResponseCode() == 200) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                StringBuilder response = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
                reader.close();

                JSONObject json = new JSONObject(response.toString());
                if (json.has("Answer")) {
                    JSONArray answers = json.getJSONArray("Answer");
                    for (int i = 0; i < answers.length(); i++) {
                        JSONObject ans = answers.getJSONObject(i);
                        // Type 1 = A (IPv4)
                        if (ans.optInt("type") == 1 && ans.has("data")) {
                            String ip = ans.getString("data");
                            list.add(InetAddress.getByName(ip));
                        }
                    }
                }
            }
        } catch (Exception e) {
            // Silently fallback to system resolver
        } finally {
            if (conn != null) {
                // Always disconnect, even for non-200 responses: returning the socket to
                // the keep-alive pool is the only way the next DoH query reuses it. This
                // runs on the name-resolution path, so leaked connections surface as slow
                // connects rather than as an obvious error.
                conn.disconnect();
            }
        }
        return list;
    }
}
