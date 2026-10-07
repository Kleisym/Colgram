package io.nekohasekai.libbox;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public interface CommandClientHandler {
    void clearLogs();

    void connected();

    void disconnected(String str);

    void initializeClashMode(StringIterator stringIterator, String str);

    void setDefaultLogLevel(int i);

    void updateClashMode(String str);

    void writeConnectionEvents(ConnectionEvents connectionEvents);

    void writeGroups(OutboundGroupIterator outboundGroupIterator);

    void writeLogs(LogIterator logIterator);

    void writeOutbounds(OutboundGroupItemIterator outboundGroupItemIterator);

    void writeStatus(StatusMessage statusMessage);
}
