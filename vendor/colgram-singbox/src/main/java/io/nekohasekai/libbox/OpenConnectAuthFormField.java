package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class OpenConnectAuthFormField implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public OpenConnectAuthFormField() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof OpenConnectAuthFormField)) {
            return false;
        }
        OpenConnectAuthFormField openConnectAuthFormField = (OpenConnectAuthFormField) obj;
        String submissionKey = getSubmissionKey();
        String submissionKey2 = openConnectAuthFormField.getSubmissionKey();
        if (submissionKey == null) {
            if (submissionKey2 != null) {
                return false;
            }
        } else if (!submissionKey.equals(submissionKey2)) {
            return false;
        }
        String name = getName();
        String name2 = openConnectAuthFormField.getName();
        if (name == null) {
            if (name2 != null) {
                return false;
            }
        } else if (!name.equals(name2)) {
            return false;
        }
        String label = getLabel();
        String label2 = openConnectAuthFormField.getLabel();
        if (label == null) {
            if (label2 != null) {
                return false;
            }
        } else if (!label.equals(label2)) {
            return false;
        }
        String kind = getKind();
        String kind2 = openConnectAuthFormField.getKind();
        if (kind == null) {
            if (kind2 != null) {
                return false;
            }
        } else if (!kind.equals(kind2)) {
            return false;
        }
        String value = getValue();
        String value2 = openConnectAuthFormField.getValue();
        if (value == null) {
            return value2 == null;
        }
        return value.equals(value2);
    }

    public final native String getKind();

    public final native String getLabel();

    public final native String getName();

    public final native String getSubmissionKey();

    public final native String getValue();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getSubmissionKey(), getName(), getLabel(), getKind(), getValue()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public native OpenConnectAuthFormChoiceIterator options();

    public final native void setKind(String str);

    public final native void setLabel(String str);

    public final native void setName(String str);

    public final native void setSubmissionKey(String str);

    public final native void setValue(String str);

    public String toString() {
        return "OpenConnectAuthFormField{SubmissionKey:" + getSubmissionKey() + ",Name:" + getName() + ",Label:" + getLabel() + ",Kind:" + getKind() + ",Value:" + getValue() + ",}";
    }

    public OpenConnectAuthFormField(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
