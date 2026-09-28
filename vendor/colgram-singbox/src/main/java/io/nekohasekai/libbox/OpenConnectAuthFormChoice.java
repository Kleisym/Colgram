package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class OpenConnectAuthFormChoice implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public OpenConnectAuthFormChoice() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof OpenConnectAuthFormChoice)) {
            return false;
        }
        OpenConnectAuthFormChoice openConnectAuthFormChoice = (OpenConnectAuthFormChoice) obj;
        String value = getValue();
        String value2 = openConnectAuthFormChoice.getValue();
        if (value == null) {
            if (value2 != null) {
                return false;
            }
        } else if (!value.equals(value2)) {
            return false;
        }
        String label = getLabel();
        String label2 = openConnectAuthFormChoice.getLabel();
        if (label == null) {
            return label2 == null;
        }
        return label.equals(label2);
    }

    public final native String getLabel();

    public final native String getValue();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getValue(), getLabel()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setLabel(String str);

    public final native void setValue(String str);

    public String toString() {
        return "OpenConnectAuthFormChoice{Value:" + getValue() + ",Label:" + getLabel() + ",}";
    }

    public OpenConnectAuthFormChoice(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
