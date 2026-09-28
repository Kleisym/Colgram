package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class OpenConnectAuthChallenge implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public OpenConnectAuthChallenge() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof OpenConnectAuthChallenge)) {
            return false;
        }
        OpenConnectAuthChallenge openConnectAuthChallenge = (OpenConnectAuthChallenge) obj;
        String id = getID();
        String id2 = openConnectAuthChallenge.getID();
        if (id == null) {
            if (id2 != null) {
                return false;
            }
        } else if (!id.equals(id2)) {
            return false;
        }
        String banner = getBanner();
        String banner2 = openConnectAuthChallenge.getBanner();
        if (banner == null) {
            if (banner2 != null) {
                return false;
            }
        } else if (!banner.equals(banner2)) {
            return false;
        }
        String message = getMessage();
        String message2 = openConnectAuthChallenge.getMessage();
        if (message == null) {
            if (message2 != null) {
                return false;
            }
        } else if (!message.equals(message2)) {
            return false;
        }
        String error = getError();
        String error2 = openConnectAuthChallenge.getError();
        if (error == null) {
            if (error2 != null) {
                return false;
            }
        } else if (!error.equals(error2)) {
            return false;
        }
        OpenConnectAuthForm form = getForm();
        OpenConnectAuthForm form2 = openConnectAuthChallenge.getForm();
        if (form == null) {
            if (form2 != null) {
                return false;
            }
        } else if (!form.equals(form2)) {
            return false;
        }
        OpenConnectBrowserRequest browser = getBrowser();
        OpenConnectBrowserRequest browser2 = openConnectAuthChallenge.getBrowser();
        if (browser == null) {
            return browser2 == null;
        }
        return browser.equals(browser2);
    }

    public final native String getBanner();

    public final native OpenConnectBrowserRequest getBrowser();

    public final native String getError();

    public final native OpenConnectAuthForm getForm();

    public final native String getID();

    public final native String getMessage();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getID(), getBanner(), getMessage(), getError(), getForm(), getBrowser()});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setBanner(String str);

    public final native void setBrowser(OpenConnectBrowserRequest openConnectBrowserRequest);

    public final native void setError(String str);

    public final native void setForm(OpenConnectAuthForm openConnectAuthForm);

    public final native void setID(String str);

    public final native void setMessage(String str);

    public String toString() {
        return "OpenConnectAuthChallenge{ID:" + getID() + ",Banner:" + getBanner() + ",Message:" + getMessage() + ",Error:" + getError() + ",Form:" + getForm() + ",Browser:" + getBrowser() + ",}";
    }

    public OpenConnectAuthChallenge(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
