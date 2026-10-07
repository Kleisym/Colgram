package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class NetworkQualityResult implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public NetworkQualityResult() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof NetworkQualityResult)) {
            return false;
        }
        NetworkQualityResult networkQualityResult = (NetworkQualityResult) obj;
        return getDownloadCapacity() == networkQualityResult.getDownloadCapacity() && getUploadCapacity() == networkQualityResult.getUploadCapacity() && getDownloadRPM() == networkQualityResult.getDownloadRPM() && getUploadRPM() == networkQualityResult.getUploadRPM() && getIdleLatencyMs() == networkQualityResult.getIdleLatencyMs() && getDownloadCapacityAccuracy() == networkQualityResult.getDownloadCapacityAccuracy() && getUploadCapacityAccuracy() == networkQualityResult.getUploadCapacityAccuracy() && getDownloadRPMAccuracy() == networkQualityResult.getDownloadRPMAccuracy() && getUploadRPMAccuracy() == networkQualityResult.getUploadRPMAccuracy();
    }

    public final native long getDownloadCapacity();

    public final native int getDownloadCapacityAccuracy();

    public final native int getDownloadRPM();

    public final native int getDownloadRPMAccuracy();

    public final native int getIdleLatencyMs();

    public final native long getUploadCapacity();

    public final native int getUploadCapacityAccuracy();

    public final native int getUploadRPM();

    public final native int getUploadRPMAccuracy();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Long.valueOf(getDownloadCapacity()), Long.valueOf(getUploadCapacity()), Integer.valueOf(getDownloadRPM()), Integer.valueOf(getUploadRPM()), Integer.valueOf(getIdleLatencyMs()), Integer.valueOf(getDownloadCapacityAccuracy()), Integer.valueOf(getUploadCapacityAccuracy()), Integer.valueOf(getDownloadRPMAccuracy()), Integer.valueOf(getUploadRPMAccuracy())});
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

    public final native void setIdleLatencyMs(int i);

    public final native void setUploadCapacity(long j);

    public final native void setUploadCapacityAccuracy(int i);

    public final native void setUploadRPM(int i);

    public final native void setUploadRPMAccuracy(int i);

    public String toString() {
        return "NetworkQualityResult{DownloadCapacity:" + getDownloadCapacity() + ",UploadCapacity:" + getUploadCapacity() + ",DownloadRPM:" + getDownloadRPM() + ",UploadRPM:" + getUploadRPM() + ",IdleLatencyMs:" + getIdleLatencyMs() + ",DownloadCapacityAccuracy:" + getDownloadCapacityAccuracy() + ",UploadCapacityAccuracy:" + getUploadCapacityAccuracy() + ",DownloadRPMAccuracy:" + getDownloadRPMAccuracy() + ",UploadRPMAccuracy:" + getUploadRPMAccuracy() + ",}";
    }

    public NetworkQualityResult(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
