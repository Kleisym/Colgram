package org.colgram.core;

import android.net.VpnService;
import org.json.JSONArray;
import org.json.JSONObject;

/** A protected local underlay for TCP VPN protocols. Never sends a VPN connection into its TUN. */
public final class ColgramLocalBaseUplink implements AutoCloseable {
    private final ColgramDpiBypass.CallListener listener;

    public ColgramLocalBaseUplink(VpnService owner) throws Exception {
        listener = new ColgramDpiBypass.CallListener();
        listener.attach(owner);
    }

    public String route(String config) throws Exception {
        JSONObject root = new JSONObject(config);
        JSONArray outbounds = root.getJSONArray("outbounds");
        JSONObject local = new JSONObject(listener.config()).getJSONArray("outbounds").getJSONObject(0);
        local.put("tag", "colgram-local-base");
        boolean changed = false;
        for (int i = 0; i < outbounds.length(); i++) {
            JSONObject out = outbounds.getJSONObject(i);
            String type = out.optString("type");
            // QUIC transports keep their protected UDP socket; never redirect UDP into a TCP front.
            if ((type.equals("vless") || type.equals("vmess") || type.equals("trojan")
                    || type.equals("shadowsocks") || type.equals("socks") || type.equals("http"))
                    && !out.has("detour")) {
                out.put("detour", "colgram-local-base");
                changed = true;
            }
        }
        if (changed) outbounds.put(local);
        return root.toString();
    }

    @Override public void close() { listener.close(); }
}
