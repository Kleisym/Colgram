package org.colgram.singbox;

import android.os.ParcelFileDescriptor;

import io.nekohasekai.libbox.RoutePrefix;
import io.nekohasekai.libbox.RoutePrefixIterator;
import io.nekohasekai.libbox.StringIterator;
import io.nekohasekai.libbox.TunOptions;

/**
 * Builds the VpnService tunnel the sing-box engine reads its options from.
 *
 * The engine describes the tunnel in TunOptions - addresses, routes, DNS, MTU - and this turns
 * that into the Builder calls Android actually understands. Keeping it behind an interface means
 * PlatformInterface has no Android-specific code in it, so the engine contract stays testable
 * without a device.
 */
public interface ColgramTunConfigurator {

    /** Open the tunnel, or return null when the platform refuses. */
    ParcelFileDescriptor open(TunOptions options);

    /** True only when the app holds consent to route other apps' traffic as well as its own. */
    boolean hasWholeDeviceConsent();
}
