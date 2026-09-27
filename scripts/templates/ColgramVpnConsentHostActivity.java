package org.colgram.core;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import android.view.View;

/** Debug-only foreground host used by the account-free WARP device integration test. */
public final class ColgramVpnConsentHostActivity extends Activity {
    private static final int REQUEST_VPN_CONSENT = 1494;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(new View(this));
    }

    public void requestVpnConsent() {
        Intent consent = VpnService.prepare(this);
        if (consent == null) {
            setResult(RESULT_OK);
            finish();
            return;
        }
        startActivityForResult(consent, REQUEST_VPN_CONSENT);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_VPN_CONSENT) {
            setResult(resultCode);
            finish();
        }
    }
}
