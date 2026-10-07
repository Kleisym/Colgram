package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class USBURBRequest implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public USBURBRequest() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof USBURBRequest)) {
            return false;
        }
        USBURBRequest uSBURBRequest = (USBURBRequest) obj;
        String deviceID = getDeviceID();
        String deviceID2 = uSBURBRequest.getDeviceID();
        if (deviceID == null) {
            if (deviceID2 != null) {
                return false;
            }
        } else if (!deviceID.equals(deviceID2)) {
            return false;
        }
        if (getSeq() != uSBURBRequest.getSeq() || getEndpoint() != uSBURBRequest.getEndpoint() || getDirectionIn() != uSBURBRequest.getDirectionIn() || getTransferFlags() != uSBURBRequest.getTransferFlags()) {
            return false;
        }
        byte[] setup = getSetup();
        byte[] setup2 = uSBURBRequest.getSetup();
        if (setup == null) {
            if (setup2 != null) {
                return false;
            }
        } else if (!setup.equals(setup2)) {
            return false;
        }
        if (getTransferBufferLength() != uSBURBRequest.getTransferBufferLength()) {
            return false;
        }
        byte[] outData = getOutData();
        byte[] outData2 = uSBURBRequest.getOutData();
        if (outData == null) {
            if (outData2 != null) {
                return false;
            }
        } else if (!outData.equals(outData2)) {
            return false;
        }
        return getNumberOfPackets() == uSBURBRequest.getNumberOfPackets() && getStartFrame() == uSBURBRequest.getStartFrame() && getInterval() == uSBURBRequest.getInterval();
    }

    public final native String getDeviceID();

    public final native boolean getDirectionIn();

    public final native int getEndpoint();

    public final native int getInterval();

    public native USBIsoPacket getIsoPacket(int i);

    public final native int getNumberOfPackets();

    public final native byte[] getOutData();

    public final native long getSeq();

    public final native byte[] getSetup();

    public final native int getStartFrame();

    public final native int getTransferBufferLength();

    public final native int getTransferFlags();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getDeviceID(), Long.valueOf(getSeq()), Integer.valueOf(getEndpoint()), Boolean.valueOf(getDirectionIn()), Integer.valueOf(getTransferFlags()), getSetup(), Integer.valueOf(getTransferBufferLength()), getOutData(), Integer.valueOf(getNumberOfPackets()), Integer.valueOf(getStartFrame()), Integer.valueOf(getInterval())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public native int isoPacketCount();

    public final native void setDeviceID(String str);

    public final native void setDirectionIn(boolean z);

    public final native void setEndpoint(int i);

    public final native void setInterval(int i);

    public final native void setNumberOfPackets(int i);

    public final native void setOutData(byte[] bArr);

    public final native void setSeq(long j);

    public final native void setSetup(byte[] bArr);

    public final native void setStartFrame(int i);

    public final native void setTransferBufferLength(int i);

    public final native void setTransferFlags(int i);

    public String toString() {
        return "USBURBRequest{DeviceID:" + getDeviceID() + ",Seq:" + getSeq() + ",Endpoint:" + getEndpoint() + ",DirectionIn:" + getDirectionIn() + ",TransferFlags:" + getTransferFlags() + ",Setup:" + getSetup() + ",TransferBufferLength:" + getTransferBufferLength() + ",OutData:" + getOutData() + ",NumberOfPackets:" + getNumberOfPackets() + ",StartFrame:" + getStartFrame() + ",Interval:" + getInterval() + ",}";
    }

    public USBURBRequest(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
