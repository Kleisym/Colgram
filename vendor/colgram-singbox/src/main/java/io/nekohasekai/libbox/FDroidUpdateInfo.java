package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class FDroidUpdateInfo implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public FDroidUpdateInfo() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof FDroidUpdateInfo)) {
            return false;
        }
        FDroidUpdateInfo fDroidUpdateInfo = (FDroidUpdateInfo) obj;
        if (getVersionCode() != fDroidUpdateInfo.getVersionCode()) {
            return false;
        }
        String versionName = getVersionName();
        String versionName2 = fDroidUpdateInfo.getVersionName();
        if (versionName == null) {
            if (versionName2 != null) {
                return false;
            }
        } else if (!versionName.equals(versionName2)) {
            return false;
        }
        String downloadURL = getDownloadURL();
        String downloadURL2 = fDroidUpdateInfo.getDownloadURL();
        if (downloadURL == null) {
            if (downloadURL2 != null) {
                return false;
            }
        } else if (!downloadURL.equals(downloadURL2)) {
            return false;
        }
        if (getFileSize() != fDroidUpdateInfo.getFileSize()) {
            return false;
        }
        String fileSHA256 = getFileSHA256();
        String fileSHA257 = fDroidUpdateInfo.getFileSHA256();
        if (fileSHA256 == null) {
            return fileSHA257 == null;
        }
        return fileSHA256.equals(fileSHA257);
    }

    public final native String getDownloadURL();

    public final native String getFileSHA256();

    public final native long getFileSize();

    public final native int getVersionCode();

    public final native String getVersionName();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{Integer.valueOf(getVersionCode()), getVersionName(), getDownloadURL(), Long.valueOf(getFileSize()), getFileSHA256()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setDownloadURL(String str);

    public final native void setFileSHA256(String str);

    public final native void setFileSize(long j);

    public final native void setVersionCode(int i);

    public final native void setVersionName(String str);

    public String toString() {
        return "FDroidUpdateInfo{VersionCode:" + getVersionCode() + ",VersionName:" + getVersionName() + ",DownloadURL:" + getDownloadURL() + ",FileSize:" + getFileSize() + ",FileSHA256:" + getFileSHA256() + ",}";
    }

    public FDroidUpdateInfo(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
