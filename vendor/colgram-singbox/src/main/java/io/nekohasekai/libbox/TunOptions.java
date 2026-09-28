package io.nekohasekai.libbox;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public interface TunOptions {
    boolean getAutoRoute();

    StringBox getDNSMode();

    StringIterator getDNSServerAddress();

    StringIterator getExcludePackage();

    StringIterator getHTTPProxyBypassDomain();

    StringIterator getHTTPProxyMatchDomain();

    String getHTTPProxyServer();

    int getHTTPProxyServerPort();

    StringIterator getIncludePackage();

    RoutePrefixIterator getInet4Address();

    RoutePrefixIterator getInet4RouteAddress();

    RoutePrefixIterator getInet4RouteExcludeAddress();

    RoutePrefixIterator getInet4RouteRange();

    RoutePrefixIterator getInet6Address();

    RoutePrefixIterator getInet6RouteAddress();

    RoutePrefixIterator getInet6RouteExcludeAddress();

    RoutePrefixIterator getInet6RouteRange();

    int getMTU();

    boolean getStrictRoute();

    boolean isHTTPProxyEnabled();
}
