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
     * Resolves a hostname to a list of IP addresses via DoH.
     */
    public static List<InetAddress> resolve(String hostname) {
        List<InetAddress> addresses = new ArrayList<>();
        if (!ColgramConfig.isDohEnabled() || hostname == null) {
            return addresses;
        }

        // Try Cloudflare DoH first
        addresses = queryDoh(CLOUDFLARE_DOH + hostname + "&type=A");
        if (addresses.isEmpty()) {
            // Fallback to Google DoH
            addresses = queryDoh(GOOGLE_DOH + hostname + "&type=A");
        }

        return addresses;
    }

    private static List<InetAddress> queryDoh(String requestUrl) {
        List<InetAddress> list = new ArrayList<>();
        HttpURLConnection conn = null;
        try {
            URL url = new URL(requestUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
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
                conn.disconnect();
            }
        }
        return list;
    }
}
