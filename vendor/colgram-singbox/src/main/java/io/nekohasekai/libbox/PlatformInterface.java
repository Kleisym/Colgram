package io.nekohasekai.libbox;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public interface PlatformInterface {
    void autoDetectInterfaceControl(int i);

    void cancelNotification(String str, int i);

    void checkPlatformShell();

    void clearDNSCache();

    void closeDefaultInterfaceMonitor(InterfaceUpdateListener interfaceUpdateListener);

    void closeNeighborMonitor(NeighborUpdateListener neighborUpdateListener);

    BridgeSession createBridge(BridgeOptions bridgeOptions);

    ConnectionOwner findConnectionOwner(int i, String str, int i2, String str2, int i3);

    NetworkInterfaceIterator getInterfaces();

    boolean includeAllNetworks();

    LocalDNSTransport localDNSTransport();

    String lookupSFTPServer();

    PlatformUser lookupUser(String str);

    ShellSession openShellSession(PlatformUser platformUser, String str, StringIterator stringIterator, String str2, int i, int i2);

    int openTun(TunOptions tunOptions);

    String readSystemSSHHostKey();

    WIFIState readWIFIState();

    void registerMyInterface(String str);

    void sendNotification(Notification notification);

    void startDefaultInterfaceMonitor(InterfaceUpdateListener interfaceUpdateListener);

    void startNeighborMonitor(NeighborUpdateListener neighborUpdateListener);

    String tailscaleHostname();

    boolean underNetworkExtension();

    boolean usePlatformAutoDetectInterfaceControl();

    boolean usePlatformBridge();

    boolean usePlatformShell();

    boolean useProcFS();
}
