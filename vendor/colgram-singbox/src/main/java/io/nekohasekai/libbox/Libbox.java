package io.nekohasekai.libbox;

import go.Seq;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public abstract class Libbox {
    public static final int CommandClashMode = 3;
    public static final int CommandConnections = 4;
    public static final int CommandGroup = 2;
    public static final int CommandLog = 0;
    public static final int CommandOutbounds = 5;
    public static final int CommandStatus = 1;
    public static final long ConnectionEventClosed = 2;
    public static final long ConnectionEventNew = 0;
    public static final long ConnectionEventUpdate = 1;
    public static final long ConnectionStateActive = 1;
    public static final long ConnectionStateAll = 0;
    public static final long ConnectionStateClosed = 2;
    public static final String DNSModeDisabled = "disabled";
    public static final String DNSModeHijack = "hijack";
    public static final String DNSModeNative = "native";
    public static final int InterfaceTypeCellular = 1;
    public static final int InterfaceTypeEthernet = 2;
    public static final int InterfaceTypeOther = 3;
    public static final int InterfaceTypeWIFI = 0;
    public static final long MessageTypeError = 0;
    public static final long MessageTypeProfileContent = 3;
    public static final long MessageTypeProfileContentRequest = 2;
    public static final long MessageTypeProfileList = 1;
    public static final int NATFilteringAddressAndPortDependent = 3;
    public static final int NATFilteringAddressDependent = 2;
    public static final int NATFilteringEndpointIndependent = 1;
    public static final int NATMappingAddressAndPortDependent = 4;
    public static final int NATMappingAddressDependent = 3;
    public static final int NATMappingEndpointIndependent = 2;
    public static final int NetworkQualityAccuracyHigh = 2;
    public static final int NetworkQualityAccuracyLow = 0;
    public static final int NetworkQualityAccuracyMedium = 1;
    public static final String NetworkQualityDefaultConfigURL = "https://mensura.cdn-apple.com/api/v1/gm/config";
    public static final int NetworkQualityDefaultMaxRuntimeSeconds = 20;
    public static final int NetworkQualityPhaseDone = 3;
    public static final int NetworkQualityPhaseDownload = 1;
    public static final int NetworkQualityPhaseIdle = 0;
    public static final int NetworkQualityPhaseUpload = 2;
    public static final int ProfileTypeLocal = 0;
    public static final int ProfileTypeRemote = 2;
    public static final int ProfileTypeiCloud = 1;
    public static final String STUNDefaultServer = "stun.voipgate.com:3478";
    public static final int STUNPhaseBinding = 0;
    public static final int STUNPhaseDone = 3;
    public static final int STUNPhaseNATFiltering = 2;
    public static final int STUNPhaseNATMapping = 1;
    public static final long TaildropChunkSize = 16320;
    public static final int USBBackendDarwinIOKit = 3;
    public static final int USBBackendDynamic = 2;
    public static final int USBBackendLinuxSysfs = 1;
    public static final int USBBackendUnspecified = 0;
    public static final int USBBackendWindowsVBoxUSB = 4;
    public static final int USBDeviceStateAttached = 1;
    public static final int USBDeviceStateIdle = 0;
    public static final int USBDeviceStateUnavailable = 2;

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyBridgeSession implements Seq.Proxy, BridgeSession {
        public final int refnum;

        public proxyBridgeSession(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.BridgeSession
        public native void close();

        @Override // io.nekohasekai.libbox.BridgeSession
        public native int fileDescriptor();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.BridgeSession
        public native boolean inet6Active();

        @Override // io.nekohasekai.libbox.BridgeSession
        public native String name();

        @Override // io.nekohasekai.libbox.BridgeSession
        public native void setEgress(String str);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyCommandClientHandler implements Seq.Proxy, CommandClientHandler {
        public final int refnum;

        public proxyCommandClientHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.CommandClientHandler
        public native void clearLogs();

        @Override // io.nekohasekai.libbox.CommandClientHandler
        public native void connected();

        @Override // io.nekohasekai.libbox.CommandClientHandler
        public native void disconnected(String str);

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.CommandClientHandler
        public native void initializeClashMode(StringIterator stringIterator, String str);

        @Override // io.nekohasekai.libbox.CommandClientHandler
        public native void setDefaultLogLevel(int i);

        @Override // io.nekohasekai.libbox.CommandClientHandler
        public native void updateClashMode(String str);

        @Override // io.nekohasekai.libbox.CommandClientHandler
        public native void writeConnectionEvents(ConnectionEvents connectionEvents);

        @Override // io.nekohasekai.libbox.CommandClientHandler
        public native void writeGroups(OutboundGroupIterator outboundGroupIterator);

        @Override // io.nekohasekai.libbox.CommandClientHandler
        public native void writeLogs(LogIterator logIterator);

        @Override // io.nekohasekai.libbox.CommandClientHandler
        public native void writeOutbounds(OutboundGroupItemIterator outboundGroupItemIterator);

        @Override // io.nekohasekai.libbox.CommandClientHandler
        public native void writeStatus(StatusMessage statusMessage);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyCommandServerHandler implements Seq.Proxy, CommandServerHandler {
        public final int refnum;

        public proxyCommandServerHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.CommandServerHandler
        public native int connectSSHAgent();

        @Override // io.nekohasekai.libbox.CommandServerHandler
        public native SystemProxyStatus getSystemProxyStatus();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.CommandServerHandler
        public native void serviceReload();

        @Override // io.nekohasekai.libbox.CommandServerHandler
        public native void serviceStop();

        @Override // io.nekohasekai.libbox.CommandServerHandler
        public native void setSystemProxyEnabled(boolean z);

        @Override // io.nekohasekai.libbox.CommandServerHandler
        public native void triggerNativeCrash();

        @Override // io.nekohasekai.libbox.CommandServerHandler
        public native void writeDebugMessage(String str);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyConnectionEventIterator implements Seq.Proxy, ConnectionEventIterator {
        public final int refnum;

        public proxyConnectionEventIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.ConnectionEventIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.ConnectionEventIterator
        public native ConnectionEvent next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyConnectionIterator implements Seq.Proxy, ConnectionIterator {
        public final int refnum;

        public proxyConnectionIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.ConnectionIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.ConnectionIterator
        public native Connection next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyDeprecatedNoteIterator implements Seq.Proxy, DeprecatedNoteIterator {
        public final int refnum;

        public proxyDeprecatedNoteIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.DeprecatedNoteIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.DeprecatedNoteIterator
        public native DeprecatedNote next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyFDroidMirrorIterator implements Seq.Proxy, FDroidMirrorIterator {
        public final int refnum;

        public proxyFDroidMirrorIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.FDroidMirrorIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.FDroidMirrorIterator
        public native int len();

        @Override // io.nekohasekai.libbox.FDroidMirrorIterator
        public native FDroidMirror next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyFDroidPingResultIterator implements Seq.Proxy, FDroidPingResultIterator {
        public final int refnum;

        public proxyFDroidPingResultIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.FDroidPingResultIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.FDroidPingResultIterator
        public native int len();

        @Override // io.nekohasekai.libbox.FDroidPingResultIterator
        public native FDroidPingResult next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyFunc implements Seq.Proxy, Func {
        public final int refnum;

        public proxyFunc(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.Func
        public native void invoke();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyHTTPClient implements Seq.Proxy, HTTPClient {
        public final int refnum;

        public proxyHTTPClient(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.HTTPClient
        public native void close();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.HTTPClient
        public native void keepAlive();

        @Override // io.nekohasekai.libbox.HTTPClient
        public native void modernTLS();

        @Override // io.nekohasekai.libbox.HTTPClient
        public native HTTPRequest newRequest();

        @Override // io.nekohasekai.libbox.HTTPClient
        public native void pinnedSHA256(String str);

        @Override // io.nekohasekai.libbox.HTTPClient
        public native void pinnedTLS12();

        @Override // io.nekohasekai.libbox.HTTPClient
        public native void restrictedTLS();

        @Override // io.nekohasekai.libbox.HTTPClient
        public native void trySocks5(int i);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyHTTPRequest implements Seq.Proxy, HTTPRequest {
        public final int refnum;

        public proxyHTTPRequest(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.HTTPRequest
        public native HTTPResponse execute();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.HTTPRequest
        public native void randomUserAgent();

        @Override // io.nekohasekai.libbox.HTTPRequest
        public native void setContent(byte[] bArr);

        @Override // io.nekohasekai.libbox.HTTPRequest
        public native void setContentString(String str);

        @Override // io.nekohasekai.libbox.HTTPRequest
        public native void setHeader(String str, String str2);

        @Override // io.nekohasekai.libbox.HTTPRequest
        public native void setMethod(String str);

        @Override // io.nekohasekai.libbox.HTTPRequest
        public native void setURL(String str);

        @Override // io.nekohasekai.libbox.HTTPRequest
        public native void setUserAgent(String str);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyHTTPResponse implements Seq.Proxy, HTTPResponse {
        public final int refnum;

        public proxyHTTPResponse(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.HTTPResponse
        public native StringBox getContent();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.HTTPResponse
        public native void writeTo(String str);

        @Override // io.nekohasekai.libbox.HTTPResponse
        public native void writeToWithProgress(String str, HTTPResponseWriteToProgressHandler hTTPResponseWriteToProgressHandler);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyHTTPResponseWriteToProgressHandler implements Seq.Proxy, HTTPResponseWriteToProgressHandler {
        public final int refnum;

        public proxyHTTPResponseWriteToProgressHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.HTTPResponseWriteToProgressHandler
        public native void update(long j, long j2);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyInt32Iterator implements Seq.Proxy, Int32Iterator {
        public final int refnum;

        public proxyInt32Iterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.Int32Iterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.Int32Iterator
        public native int len();

        @Override // io.nekohasekai.libbox.Int32Iterator
        public native int next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyInterfaceUpdateListener implements Seq.Proxy, InterfaceUpdateListener {
        public final int refnum;

        public proxyInterfaceUpdateListener(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.InterfaceUpdateListener
        public native void updateDefaultInterface(String str, int i, boolean z, boolean z2);

        @Override // io.nekohasekai.libbox.InterfaceUpdateListener
        public native void updateNetworkPath(String str);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyLocalDNSTransport implements Seq.Proxy, LocalDNSTransport {
        public final int refnum;

        public proxyLocalDNSTransport(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.LocalDNSTransport
        public native void exchange(ExchangeContext exchangeContext, byte[] bArr);

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.LocalDNSTransport
        public native void lookup(ExchangeContext exchangeContext, String str, String str2);

        @Override // io.nekohasekai.libbox.LocalDNSTransport
        public native boolean raw();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyLogIterator implements Seq.Proxy, LogIterator {
        public final int refnum;

        public proxyLogIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.LogIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.LogIterator
        public native int len();

        @Override // io.nekohasekai.libbox.LogIterator
        public native LogEntry next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyNeighborEntryIterator implements Seq.Proxy, NeighborEntryIterator {
        public final int refnum;

        public proxyNeighborEntryIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.NeighborEntryIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.NeighborEntryIterator
        public native NeighborEntry next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyNeighborUpdateListener implements Seq.Proxy, NeighborUpdateListener {
        public final int refnum;

        public proxyNeighborUpdateListener(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.NeighborUpdateListener
        public native void updateNeighborTable(NeighborEntryIterator neighborEntryIterator);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyNetworkInterfaceIterator implements Seq.Proxy, NetworkInterfaceIterator {
        public final int refnum;

        public proxyNetworkInterfaceIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.NetworkInterfaceIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.NetworkInterfaceIterator
        public native NetworkInterface next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyNetworkQualityTestHandler implements Seq.Proxy, NetworkQualityTestHandler {
        public final int refnum;

        public proxyNetworkQualityTestHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.NetworkQualityTestHandler
        public native void onError(String str);

        @Override // io.nekohasekai.libbox.NetworkQualityTestHandler
        public native void onProgress(NetworkQualityProgress networkQualityProgress);

        @Override // io.nekohasekai.libbox.NetworkQualityTestHandler
        public native void onResult(NetworkQualityResult networkQualityResult);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyOnDemandRule implements Seq.Proxy, OnDemandRule {
        public final int refnum;

        public proxyOnDemandRule(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.OnDemandRule
        public native StringIterator dnsSearchDomainMatch();

        @Override // io.nekohasekai.libbox.OnDemandRule
        public native StringIterator dnsServerAddressMatch();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.OnDemandRule
        public native int interfaceTypeMatch();

        @Override // io.nekohasekai.libbox.OnDemandRule
        public native String probeURL();

        @Override // io.nekohasekai.libbox.OnDemandRule
        public native StringIterator ssidMatch();

        @Override // io.nekohasekai.libbox.OnDemandRule
        public native int target();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyOnDemandRuleIterator implements Seq.Proxy, OnDemandRuleIterator {
        public final int refnum;

        public proxyOnDemandRuleIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.OnDemandRuleIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.OnDemandRuleIterator
        public native OnDemandRule next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyOpenConnectAuthFormChoiceIterator implements Seq.Proxy, OpenConnectAuthFormChoiceIterator {
        public final int refnum;

        public proxyOpenConnectAuthFormChoiceIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.OpenConnectAuthFormChoiceIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.OpenConnectAuthFormChoiceIterator
        public native OpenConnectAuthFormChoice next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyOpenConnectAuthFormFieldIterator implements Seq.Proxy, OpenConnectAuthFormFieldIterator {
        public final int refnum;

        public proxyOpenConnectAuthFormFieldIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.OpenConnectAuthFormFieldIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.OpenConnectAuthFormFieldIterator
        public native OpenConnectAuthFormField next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyOpenConnectEndpointStatusIterator implements Seq.Proxy, OpenConnectEndpointStatusIterator {
        public final int refnum;

        public proxyOpenConnectEndpointStatusIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.OpenConnectEndpointStatusIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.OpenConnectEndpointStatusIterator
        public native OpenConnectEndpointStatus next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyOpenConnectStatusHandler implements Seq.Proxy, OpenConnectStatusHandler {
        public final int refnum;

        public proxyOpenConnectStatusHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.OpenConnectStatusHandler
        public native void onError(String str);

        @Override // io.nekohasekai.libbox.OpenConnectStatusHandler
        public native void onStatusUpdate(OpenConnectStatusUpdate openConnectStatusUpdate);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyOpenVPNEndpointStatusIterator implements Seq.Proxy, OpenVPNEndpointStatusIterator {
        public final int refnum;

        public proxyOpenVPNEndpointStatusIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.OpenVPNEndpointStatusIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.OpenVPNEndpointStatusIterator
        public native OpenVPNEndpointStatus next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyOpenVPNStatusHandler implements Seq.Proxy, OpenVPNStatusHandler {
        public final int refnum;

        public proxyOpenVPNStatusHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.OpenVPNStatusHandler
        public native void onError(String str);

        @Override // io.nekohasekai.libbox.OpenVPNStatusHandler
        public native void onStatusUpdate(OpenVPNStatusUpdate openVPNStatusUpdate);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyOutboundGroupItemIterator implements Seq.Proxy, OutboundGroupItemIterator {
        public final int refnum;

        public proxyOutboundGroupItemIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.OutboundGroupItemIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.OutboundGroupItemIterator
        public native OutboundGroupItem next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyOutboundGroupIterator implements Seq.Proxy, OutboundGroupIterator {
        public final int refnum;

        public proxyOutboundGroupIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.OutboundGroupIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.OutboundGroupIterator
        public native OutboundGroup next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyPlatformInterface implements Seq.Proxy, PlatformInterface {
        public final int refnum;

        public proxyPlatformInterface(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native void autoDetectInterfaceControl(int i);

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native void cancelNotification(String str, int i);

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native void checkPlatformShell();

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native void clearDNSCache();

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native void closeDefaultInterfaceMonitor(InterfaceUpdateListener interfaceUpdateListener);

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native void closeNeighborMonitor(NeighborUpdateListener neighborUpdateListener);

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native BridgeSession createBridge(BridgeOptions bridgeOptions);

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native ConnectionOwner findConnectionOwner(int i, String str, int i2, String str2, int i3);

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native NetworkInterfaceIterator getInterfaces();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native boolean includeAllNetworks();

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native LocalDNSTransport localDNSTransport();

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native String lookupSFTPServer();

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native PlatformUser lookupUser(String str);

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native ShellSession openShellSession(PlatformUser platformUser, String str, StringIterator stringIterator, String str2, int i, int i2);

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native int openTun(TunOptions tunOptions);

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native String readSystemSSHHostKey();

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native WIFIState readWIFIState();

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native void registerMyInterface(String str);

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native void sendNotification(Notification notification);

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native void startDefaultInterfaceMonitor(InterfaceUpdateListener interfaceUpdateListener);

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native void startNeighborMonitor(NeighborUpdateListener neighborUpdateListener);

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native String tailscaleHostname();

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native boolean underNetworkExtension();

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native boolean usePlatformAutoDetectInterfaceControl();

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native boolean usePlatformBridge();

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native boolean usePlatformShell();

        @Override // io.nekohasekai.libbox.PlatformInterface
        public native boolean useProcFS();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyProfilePreviewIterator implements Seq.Proxy, ProfilePreviewIterator {
        public final int refnum;

        public proxyProfilePreviewIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.ProfilePreviewIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.ProfilePreviewIterator
        public native ProfilePreview next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyRoutePrefixIterator implements Seq.Proxy, RoutePrefixIterator {
        public final int refnum;

        public proxyRoutePrefixIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.RoutePrefixIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.RoutePrefixIterator
        public native RoutePrefix next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxySTUNTestHandler implements Seq.Proxy, STUNTestHandler {
        public final int refnum;

        public proxySTUNTestHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.STUNTestHandler
        public native void onError(String str);

        @Override // io.nekohasekai.libbox.STUNTestHandler
        public native void onProgress(STUNTestProgress sTUNTestProgress);

        @Override // io.nekohasekai.libbox.STUNTestHandler
        public native void onResult(STUNTestResult sTUNTestResult);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyShellSession implements Seq.Proxy, ShellSession {
        public final int refnum;

        public proxyShellSession(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.ShellSession
        public native void close();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.ShellSession
        public native int masterFD();

        @Override // io.nekohasekai.libbox.ShellSession
        public native void resize(int i, int i2);

        @Override // io.nekohasekai.libbox.ShellSession
        public native void signal(int i);

        @Override // io.nekohasekai.libbox.ShellSession
        public native int waitExit();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyStringIterator implements Seq.Proxy, StringIterator {
        public final int refnum;

        public proxyStringIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.StringIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.StringIterator
        public native int len();

        @Override // io.nekohasekai.libbox.StringIterator
        public native String next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyTaildropDownloadHandler implements Seq.Proxy, TaildropDownloadHandler {
        public final int refnum;

        public proxyTaildropDownloadHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.TaildropDownloadHandler
        public native void onFinish(String str);

        @Override // io.nekohasekai.libbox.TaildropDownloadHandler
        public native void onProgress(long j, long j2);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyTaildropFileIterator implements Seq.Proxy, TaildropFileIterator {
        public final int refnum;

        public proxyTaildropFileIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.TaildropFileIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.TaildropFileIterator
        public native TaildropFile next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyTaildropInboxHandler implements Seq.Proxy, TaildropInboxHandler {
        public final int refnum;

        public proxyTaildropInboxHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.TaildropInboxHandler
        public native void onError(String str);

        @Override // io.nekohasekai.libbox.TaildropInboxHandler
        public native void onInboxUpdate(TaildropInbox taildropInbox);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyTaildropReceivingFileIterator implements Seq.Proxy, TaildropReceivingFileIterator {
        public final int refnum;

        public proxyTaildropReceivingFileIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.TaildropReceivingFileIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.TaildropReceivingFileIterator
        public native TaildropReceivingFile next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyTaildropSendHandler implements Seq.Proxy, TaildropSendHandler {
        public final int refnum;

        public proxyTaildropSendHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.TaildropSendHandler
        public native void onFileCompleted(int i, long j);

        @Override // io.nekohasekai.libbox.TaildropSendHandler
        public native void onFinish(String str);

        @Override // io.nekohasekai.libbox.TaildropSendHandler
        public native void onProgress(int i, long j);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyTailscaleEndpointStatusIterator implements Seq.Proxy, TailscaleEndpointStatusIterator {
        public final int refnum;

        public proxyTailscaleEndpointStatusIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.TailscaleEndpointStatusIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.TailscaleEndpointStatusIterator
        public native TailscaleEndpointStatus next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyTailscalePeerIterator implements Seq.Proxy, TailscalePeerIterator {
        public final int refnum;

        public proxyTailscalePeerIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.TailscalePeerIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.TailscalePeerIterator
        public native TailscalePeer next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyTailscalePingHandler implements Seq.Proxy, TailscalePingHandler {
        public final int refnum;

        public proxyTailscalePingHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.TailscalePingHandler
        public native void onError(String str);

        @Override // io.nekohasekai.libbox.TailscalePingHandler
        public native void onPingResult(TailscalePingResult tailscalePingResult);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyTailscaleSSHHandler implements Seq.Proxy, TailscaleSSHHandler {
        public final int refnum;

        public proxyTailscaleSSHHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.TailscaleSSHHandler
        public native void onAuthBanner(String str);

        @Override // io.nekohasekai.libbox.TailscaleSSHHandler
        public native void onError(String str);

        @Override // io.nekohasekai.libbox.TailscaleSSHHandler
        public native void onExit(int i, String str, String str2);

        @Override // io.nekohasekai.libbox.TailscaleSSHHandler
        public native void onOutput(byte[] bArr);

        @Override // io.nekohasekai.libbox.TailscaleSSHHandler
        public native void onReady();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyTailscaleStatusHandler implements Seq.Proxy, TailscaleStatusHandler {
        public final int refnum;

        public proxyTailscaleStatusHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.TailscaleStatusHandler
        public native void onError(String str);

        @Override // io.nekohasekai.libbox.TailscaleStatusHandler
        public native void onStatusUpdate(TailscaleStatusUpdate tailscaleStatusUpdate);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyTailscaleUserGroupIterator implements Seq.Proxy, TailscaleUserGroupIterator {
        public final int refnum;

        public proxyTailscaleUserGroupIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.TailscaleUserGroupIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.TailscaleUserGroupIterator
        public native TailscaleUserGroup next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyTunOptions implements Seq.Proxy, TunOptions {
        public final int refnum;

        public proxyTunOptions(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.TunOptions
        public native boolean getAutoRoute();

        @Override // io.nekohasekai.libbox.TunOptions
        public native StringBox getDNSMode();

        @Override // io.nekohasekai.libbox.TunOptions
        public native StringIterator getDNSServerAddress();

        @Override // io.nekohasekai.libbox.TunOptions
        public native StringIterator getExcludePackage();

        @Override // io.nekohasekai.libbox.TunOptions
        public native StringIterator getHTTPProxyBypassDomain();

        @Override // io.nekohasekai.libbox.TunOptions
        public native StringIterator getHTTPProxyMatchDomain();

        @Override // io.nekohasekai.libbox.TunOptions
        public native String getHTTPProxyServer();

        @Override // io.nekohasekai.libbox.TunOptions
        public native int getHTTPProxyServerPort();

        @Override // io.nekohasekai.libbox.TunOptions
        public native StringIterator getIncludePackage();

        @Override // io.nekohasekai.libbox.TunOptions
        public native RoutePrefixIterator getInet4Address();

        @Override // io.nekohasekai.libbox.TunOptions
        public native RoutePrefixIterator getInet4RouteAddress();

        @Override // io.nekohasekai.libbox.TunOptions
        public native RoutePrefixIterator getInet4RouteExcludeAddress();

        @Override // io.nekohasekai.libbox.TunOptions
        public native RoutePrefixIterator getInet4RouteRange();

        @Override // io.nekohasekai.libbox.TunOptions
        public native RoutePrefixIterator getInet6Address();

        @Override // io.nekohasekai.libbox.TunOptions
        public native RoutePrefixIterator getInet6RouteAddress();

        @Override // io.nekohasekai.libbox.TunOptions
        public native RoutePrefixIterator getInet6RouteExcludeAddress();

        @Override // io.nekohasekai.libbox.TunOptions
        public native RoutePrefixIterator getInet6RouteRange();

        @Override // io.nekohasekai.libbox.TunOptions
        public native int getMTU();

        @Override // io.nekohasekai.libbox.TunOptions
        public native boolean getStrictRoute();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.TunOptions
        public native boolean isHTTPProxyEnabled();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyUSBIPServerStatusHandler implements Seq.Proxy, USBIPServerStatusHandler {
        public final int refnum;

        public proxyUSBIPServerStatusHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.USBIPServerStatusHandler
        public native void onError(String str);

        @Override // io.nekohasekai.libbox.USBIPServerStatusHandler
        public native void onStatusUpdate(USBIPServerStatusUpdate uSBIPServerStatusUpdate);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyUSBIPServerStatusIterator implements Seq.Proxy, USBIPServerStatusIterator {
        public final int refnum;

        public proxyUSBIPServerStatusIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.USBIPServerStatusIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.USBIPServerStatusIterator
        public native USBIPServerStatus next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyUSBLocalDeviceInfoIterator implements Seq.Proxy, USBLocalDeviceInfoIterator {
        public final int refnum;

        public proxyUSBLocalDeviceInfoIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.USBLocalDeviceInfoIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.USBLocalDeviceInfoIterator
        public native USBLocalDeviceInfo next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyUSBLocalProviderHandler implements Seq.Proxy, USBLocalProviderHandler {
        public final int refnum;

        public proxyUSBLocalProviderHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.USBLocalProviderHandler
        public native void onDeviceError(String str, String str2, String str3);

        @Override // io.nekohasekai.libbox.USBLocalProviderHandler
        public native void onLocalDevicesChanged();

        @Override // io.nekohasekai.libbox.USBLocalProviderHandler
        public native void onSessionError(String str, String str2);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyUSBProviderHandler implements Seq.Proxy, USBProviderHandler {
        public final int refnum;

        public proxyUSBProviderHandler(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.USBProviderHandler
        public native void onAbort(String str, int i);

        @Override // io.nekohasekai.libbox.USBProviderHandler
        public native void onError(String str, String str2);

        @Override // io.nekohasekai.libbox.USBProviderHandler
        public native void onReady(String str, String str2);

        @Override // io.nekohasekai.libbox.USBProviderHandler
        public native void onURBRequest(USBURBRequest uSBURBRequest);
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyUSBSharedDeviceInterfaceIterator implements Seq.Proxy, USBSharedDeviceInterfaceIterator {
        public final int refnum;

        public proxyUSBSharedDeviceInterfaceIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.USBSharedDeviceInterfaceIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.USBSharedDeviceInterfaceIterator
        public native USBSharedDeviceInterface next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyUSBSharedDeviceIterator implements Seq.Proxy, USBSharedDeviceIterator {
        public final int refnum;

        public proxyUSBSharedDeviceIterator(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.USBSharedDeviceIterator
        public native boolean hasNext();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }

        @Override // io.nekohasekai.libbox.USBSharedDeviceIterator
        public native USBSharedDevice next();
    }

    /* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
    public static final class proxyXPCDialer implements Seq.Proxy, XPCDialer {
        public final int refnum;

        public proxyXPCDialer(int i) {
            this.refnum = i;
            Seq.trackGoRef(i, this);
        }

        @Override // io.nekohasekai.libbox.XPCDialer
        public native int dialXPC();

        @Override // go.Seq.GoObject
        public final int incRefnum() {
            Seq.incGoRef(this.refnum, this);
            return this.refnum;
        }
    }

    static {
        Seq.touch();
        _init();
    }

    private Libbox() {
    }

    private static native void _init();

    public static native int availablePort(int i);

    public static native void checkConfig(String str);

    public static native FDroidUpdateInfo checkFDroidUpdate(String str, String str2, int i, String str3);

    public static native boolean compareSemver(String str, String str2);

    public static native void createZipArchive(String str, String str2, boolean z);

    public static native ErrorMessage decodeErrorMessage(byte[] bArr);

    public static native int decodeLengthChunk(byte[] bArr);

    public static native ProfileContent decodeProfileContent(byte[] bArr);

    public static native ProfileContentRequest decodeProfileContentRequest(byte[] bArr);

    public static native byte[] encodeChunkedMessage(byte[] bArr);

    public static native String formatBitrate(long j);

    public static native String formatBytes(long j);

    public static native StringBox formatConfig(String str);

    public static native String formatDuration(long j);

    public static native String formatFQDN(String str);

    public static native String formatMemoryBytes(long j);

    public static native String formatNATFiltering(int i);

    public static native String formatNATMapping(int i);

    public static native StringBox generateConfigSchema();

    public static native String generateRemoteProfileImportLink(String str, String str2);

    public static native FDroidMirrorIterator getFDroidMirrors();

    public static native String goVersion();

    public static native boolean hasTunInbound(String str);

    public static native BridgeSession newBridgeService(BridgeOptions bridgeOptions);

    public static native CommandClient newCommandClient(CommandClientHandler commandClientHandler, CommandClientOptions commandClientOptions);

    public static native CommandServer newCommandServer(CommandServerHandler commandServerHandler, PlatformInterface platformInterface);

    public static native Connections newConnections();

    public static native HTTPClient newHTTPClient();

    public static native NetworkQualityTest newNetworkQualityTest();

    public static native OpenConnectAuthResponse newOpenConnectAuthFormResponse(OpenConnectFormValues openConnectFormValues);

    public static native OpenConnectAuthResponse newOpenConnectBrowserAuthResponse(OpenConnectBrowserResult openConnectBrowserResult);

    public static native OpenConnectBrowserResult newOpenConnectBrowserResult(String str);

    public static native OpenConnectFormValues newOpenConnectFormValues();

    public static native PProfServer newPProfServer(long j);

    public static native CommandClient newRemoteCommandClient(CommandClientHandler commandClientHandler, CommandClientOptions commandClientOptions, RemoteConnectionOptions remoteConnectionOptions);

    public static native STUNTest newSTUNTest();

    public static native CommandClient newStandaloneCommandClient();

    public static native CommandClient newStandaloneRemoteCommandClient(RemoteConnectionOptions remoteConnectionOptions);

    public static native TaildropSendOptions newTaildropSendOptions();

    public static native USBDeviceDescriptor newUSBDeviceDescriptor(String str, String str2);

    public static native USBURBResponse newUSBURBResponse(String str, long j);

    public static native WIFIState newWIFIState(String str, String str2);

    public static native ShellSession openNativePipeSession(String str, String str2, StringIterator stringIterator, StringIterator stringIterator2, int i, int i2, Int32Iterator int32Iterator);

    public static native ShellSession openNativeShellSession(String str, String str2, StringIterator stringIterator, StringIterator stringIterator2, String str3, int i, int i2, int i3, int i4, Int32Iterator int32Iterator);

    public static native ImportRemoteProfile parseRemoteProfileImportLink(String str);

    public static native FDroidPingResult pingFDroidMirror(String str);

    public static native FDroidPingResultIterator pingFDroidMirrors(String str);

    public static native void prepareCrashSignalHandlers();

    public static native void promoteOOMDraft();

    public static native void promoteOOMDraftAt(String str);

    public static native void promotePowerReportDraft();

    public static native String proxyDisplayType(String str);

    public static native StringBox randomHex(int i);

    public static native AndroidVPNType readAndroidVPNType(StringIterator stringIterator);

    public static native void reinstallCrashSignalHandlers();

    public static native void reloadSetupOptions(SetupOptions setupOptions);

    public static native void setLocale(String str);

    public static native void setXPCDialer(XPCDialer xPCDialer);

    public static native void setup(SetupOptions setupOptions);

    public static native NeighborSubscription subscribeNeighborTable(NeighborUpdateListener neighborUpdateListener);

    public static native void triggerGoPanic();

    public static native String version();

    public static void touch() {
    }
}
