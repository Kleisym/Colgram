package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class CommandServer implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public CommandServer(CommandServerHandler commandServerHandler, PlatformInterface platformInterface) {
        int i__NewCommandServer = __NewCommandServer(commandServerHandler, platformInterface);
        this.refnum = i__NewCommandServer;
        Seq.trackGoRef(i__NewCommandServer, this);
    }

    private static native int __NewCommandServer(CommandServerHandler commandServerHandler, PlatformInterface platformInterface);

    public native void cancelNotification(String str, int i);

    public native void close();

    public native void closeService();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof CommandServer)) {
            return false;
        }
        return true;
    }

    public int hashCode() {
        return Arrays.hashCode(new Object[0]);
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public native boolean needFindProcess();

    public native boolean needWIFIState();

    public native void pause();

    public native void resetNetwork();

    public native void setError(String str);

    public native void start();

    public native void startOrReloadService(String str, OverrideOptions overrideOptions);

    public String toString() {
        return "CommandServer{}";
    }

    public native void updateWIFIState();

    public native void wake();

    public native void writeMessage(int i, String str);

    public CommandServer(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
