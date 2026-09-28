package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class CommandClient implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public CommandClient(CommandClientHandler commandClientHandler, CommandClientOptions commandClientOptions) {
        int i__NewCommandClient = __NewCommandClient(commandClientHandler, commandClientOptions);
        this.refnum = i__NewCommandClient;
        Seq.trackGoRef(i__NewCommandClient, this);
    }

    private static native int __NewCommandClient(CommandClientHandler commandClientHandler, CommandClientOptions commandClientOptions);

    public native void cancelOpenConnectAuthChallenge(String str, String str2);

    public native void cancelOpenVPNChallenge(String str, String str2);

    public native void cancelTaildropReceiving(String str, String str2, String str3);

    public native void clearLogs();

    public native void closeConnection(String str);

    public native void closeConnections();

    public native void connect();

    public native void connectWithFD(int i);

    public native void deleteTaildropFile(String str, String str2);

    public native void disconnect();

    public native TaildropDownloadSession downloadTaildropFile(String str, String str2, String str3, TaildropDownloadHandler taildropDownloadHandler);

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof CommandClient)) {
            return false;
        }
        return true;
    }

    public native int getAPIVersion();

    public native DeprecatedNoteIterator getDeprecatedNotes();

    public native long getStartedAt();

    public native SystemProxyStatus getSystemProxyStatus();

    public int hashCode() {
        return Arrays.hashCode(new Object[0]);
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public native void markTaildropInboxRead(String str);

    public native USBLocalProviderManager newUSBLocalProvider(USBLocalProviderHandler uSBLocalProviderHandler);

    public native USBProviderSession provideUSBDevices(USBProviderHandler uSBProviderHandler);

    public native void selectOutbound(String str, String str2);

    public native TaildropSendSession sendTaildropFiles(TaildropSendOptions taildropSendOptions, TaildropSendHandler taildropSendHandler);

    public native void serviceClose();

    public native void serviceReload();

    public native void setClashMode(String str);

    public native void setGroupExpand(String str, boolean z);

    public native void setSystemProxyEnabled(boolean z);

    public native void setTailscaleExitNode(String str, String str2);

    public native NetworkQualityTestSession startNetworkQualityTest(String str, String str2, boolean z, int i, boolean z2, NetworkQualityTestHandler networkQualityTestHandler);

    public native STUNTestSession startSTUNTest(String str, String str2, STUNTestHandler sTUNTestHandler);

    public native TailscalePingSession startTailscalePing(String str, String str2, TailscalePingHandler tailscalePingHandler);

    public native TailscaleSSHSession startTailscaleSSHSession(TailscaleSSHOptions tailscaleSSHOptions, TailscaleSSHHandler tailscaleSSHHandler);

    public native void submitOpenConnectAuthResponse(String str, String str2, OpenConnectAuthResponse openConnectAuthResponse);

    public native void submitOpenVPNChallengeResponse(String str, String str2, OpenVPNChallengeResponse openVPNChallengeResponse);

    public native OpenConnectStatusSubscription subscribeOpenConnectStatus(OpenConnectStatusHandler openConnectStatusHandler);

    public native OpenVPNStatusSubscription subscribeOpenVPNStatus(OpenVPNStatusHandler openVPNStatusHandler);

    public native TaildropInboxSubscription subscribeTaildropInbox(String str, TaildropInboxHandler taildropInboxHandler);

    public native TailscaleStatusSubscription subscribeTailscaleStatus(TailscaleStatusHandler tailscaleStatusHandler);

    public native USBIPServerStatusSubscription subscribeUSBIPServerStatus(USBIPServerStatusHandler uSBIPServerStatusHandler);

    public native void tailscaleLogout(String str);

    public String toString() {
        return "CommandClient{}";
    }

    public native void triggerGoCrash();

    public native void triggerNativeCrash();

    public native void triggerOOMReport();

    public native void urlTest(String str);

    public CommandClient(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
