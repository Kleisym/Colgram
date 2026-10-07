package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class NetworkQualityProgress implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public NetworkQualityProgress() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof NetworkQualityProgress)) {
            return false;
        }
        NetworkQualityProgress networkQualityProgress = (NetworkQualityProgress) obj;
        return getPhase() == networkQualityProgress.getPhase() && getDownloadCapacity() == networkQualityProgress.getDownloadCapacity() && getUploadCapacity() == networkQualityProgress.getUploadCapacity() && getDownloadRPM() == networkQualityProgress.getDownloadRPM() && getUploadRPM() == networkQualityProgress.getUploadRPM() && getIdleLatencyMs() == networkQualityProgress.getIdleLatencyMs() && getElapsedMs() == networkQualityProgress.getElapsedMs() && getDownloadCapacityAccuracy() == networkQualityProgress.getDownloadCapacityAccuracy() && getUploadCapacityAccuracy() == networkQualityProgress.getUploadCapacityAccuracy() && getDownloadRPMAccuracy() == networkQualityProgress.getDownloadRPMAccuracy() && getUploadRPMAccuracy() == networkQualityProgress.getUploadRPMAccuracy();
    }

    public final native long getDownloadCapacity();

    public final native int getDownloadCapacityAccuracy();

    public final native int getDownloadRPM();

    public final native int getDownloadRPMAccuracy();

    public final native long getElapsedMs();

    public final native int getIdleLatencyMs();

    public final native int getPhase();

    public final native long getUploadCapacity();

    public final native int getUploadCapacityAccuracy();

    public final native int getUploadRPM();

    public final native int getUploadRPMAccuracy();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Integer.valueOf(getPhase()), Long.valueOf(getDownloadCapacity()), Long.valueOf(getUploadCapacity()), Integer.valueOf(getDownloadRPM()), Integer.valueOf(getUploadRPM()), Integer.valueOf(getIdleLatencyMs()), Long.valueOf(getElapsedMs()), Integer.valueOf(getDownloadCapacityAccuracy()), Integer.valueOf(getUploadCapacityAccuracy()), Integer.valueOf(getDownloadRPMAccuracy()), Integer.valueOf(getUploadRPMAccuracy())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setDownloadCapacity(long j);

    public final native void setDownloadCapacityAccuracy(int i);

    public final native void setDownloadRPM(int i);

    public final native void setDownloadRPMAccuracy(int i);

    public final native void setElapsedMs(long j);

    public final native void setIdleLatencyMs(int i);

    public final native void setPhase(int i);

    public final native void setUploadCapacity(long j);

    public final native void setUploadCapacityAccuracy(int i);

    public final native void setUploadRPM(int i);

    public final native void setUploadRPMAccuracy(int i);

    public String toString() {
        return "NetworkQualityProgress{Phase:" + getPhase() + ",DownloadCapacity:" + getDownloadCapacity() + ",UploadCapacity:" + getUploadCapacity() + ",DownloadRPM:" + getDownloadRPM() + ",UploadRPM:" + getUploadRPM() + ",IdleLatencyMs:" + getIdleLatencyMs() + ",ElapsedMs:" + getElapsedMs() + ",DownloadCapacityAccuracy:" + getDownloadCapacityAccuracy() + ",UploadCapacityAccuracy:" + getUploadCapacityAccuracy() + ",DownloadRPMAccuracy:" + getDownloadRPMAccuracy() + ",UploadRPMAccuracy:" + getUploadRPMAccuracy() + ",}";
    }

    public NetworkQualityProgress(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
