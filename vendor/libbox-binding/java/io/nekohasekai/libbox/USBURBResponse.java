package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class USBURBResponse implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public USBURBResponse(String str, long j) {
        int i__NewUSBURBResponse = __NewUSBURBResponse(str, j);
        this.refnum = i__NewUSBURBResponse;
        Seq.trackGoRef(i__NewUSBURBResponse, this);
    }

    private static native int __NewUSBURBResponse(String str, long j);

    public native void addIsoPacket(int i, int i2, int i3, int i4);

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof USBURBResponse)) {
            return false;
        }
        USBURBResponse uSBURBResponse = (USBURBResponse) obj;
        String deviceID = getDeviceID();
        String deviceID2 = uSBURBResponse.getDeviceID();
        if (deviceID == null) {
            if (deviceID2 != null) {
                return false;
            }
        } else if (!deviceID.equals(deviceID2)) {
            return false;
        }
        if (getSeq() != uSBURBResponse.getSeq() || getStatus() != uSBURBResponse.getStatus() || getActualLength() != uSBURBResponse.getActualLength()) {
            return false;
        }
        byte[] inData = getInData();
        byte[] inData2 = uSBURBResponse.getInData();
        if (inData == null) {
            return inData2 == null;
        }
        return inData.equals(inData2);
    }

    public final native int getActualLength();

    public final native String getDeviceID();

    public final native byte[] getInData();

    public final native long getSeq();

    public final native int getStatus();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getDeviceID(), Long.valueOf(getSeq()), Integer.valueOf(getStatus()), Integer.valueOf(getActualLength()), getInData()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setActualLength(int i);

    public final native void setDeviceID(String str);

    public final native void setInData(byte[] bArr);

    public final native void setSeq(long j);

    public final native void setStatus(int i);

    public String toString() {
        return "USBURBResponse{DeviceID:" + getDeviceID() + ",Seq:" + getSeq() + ",Status:" + getStatus() + ",ActualLength:" + getActualLength() + ",InData:" + getInData() + ",}";
    }

    public USBURBResponse(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
