package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class LogEntry implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public LogEntry() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof LogEntry)) {
            return false;
        }
        LogEntry logEntry = (LogEntry) obj;
        if (getLevel() != logEntry.getLevel()) {
            return false;
        }
        String message = getMessage();
        String message2 = logEntry.getMessage();
        if (message == null) {
            return message2 == null;
        }
        return message.equals(message2);
    }

    public final native int getLevel();

    public final native String getMessage();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Integer.valueOf(getLevel()), getMessage()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setLevel(int i);

    public final native void setMessage(String str);

    public String toString() {
        return "LogEntry{Level:" + getLevel() + ",Message:" + getMessage() + ",}";
    }

    public LogEntry(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
