package org.colgram.core;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class ColgramSubscriptionSecurityDeviceTest {
    private static final String NODE = "vless://11111111-1111-4111-8111-111111111111@fixture.invalid:443";

    private static JSONObject outbound(String uri) throws Exception {
        return new JSONObject(ColgramProfileBuilder.forSelectedNode(ColgramSubscription.parse(uri).get(0)))
                .getJSONArray("outbounds").getJSONObject(0);
    }

    private static void check(boolean result, String label) {
        if (!result) throw new AssertionError(label);
    }

    @Test public void vlessProfileHonorsSecurityAndTransport() throws Exception {
        check(!outbound(NODE + "?security=none&type=tcp").has("tls"), "explicit plain VLESS must not start TLS");
        JSONObject tls = outbound(NODE + "?security=tls&sni=fixture.invalid&type=ws&path=%2Ffixture");
        check(tls.getJSONObject("tls").getBoolean("enabled"), "TLS requested by the link");
        check(!tls.getJSONObject("tls").getBoolean("insecure"), "certificate is verified");
        check("fixture.invalid".equals(tls.getJSONObject("tls").getString("server_name")), "TLS server name");
        check("/fixture".equals(tls.getJSONObject("transport").getString("path")), "WebSocket path");
        try {
            outbound(NODE + "?security=reality&type=tcp");
            throw new AssertionError("REALITY without its public key must fail import");
        } catch (IllegalArgumentException expected) { }
        try {
            outbound(NODE + "?security=tls&type=xhttp");
            throw new AssertionError("Unsupported VLESS transport must not silently become TCP");
        } catch (IllegalArgumentException expected) { }
    }
}
