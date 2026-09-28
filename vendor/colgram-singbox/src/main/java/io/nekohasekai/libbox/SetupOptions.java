package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class SetupOptions implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public SetupOptions() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof SetupOptions)) {
            return false;
        }
        SetupOptions setupOptions = (SetupOptions) obj;
        String basePath = getBasePath();
        String basePath2 = setupOptions.getBasePath();
        if (basePath == null) {
            if (basePath2 != null) {
                return false;
            }
        } else if (!basePath.equals(basePath2)) {
            return false;
        }
        String workingPath = getWorkingPath();
        String workingPath2 = setupOptions.getWorkingPath();
        if (workingPath == null) {
            if (workingPath2 != null) {
                return false;
            }
        } else if (!workingPath.equals(workingPath2)) {
            return false;
        }
        String tempPath = getTempPath();
        String tempPath2 = setupOptions.getTempPath();
        if (tempPath == null) {
            if (tempPath2 != null) {
                return false;
            }
        } else if (!tempPath.equals(tempPath2)) {
            return false;
        }
        if (getFixAndroidStack() != setupOptions.getFixAndroidStack() || getCommandServerListenPort() != setupOptions.getCommandServerListenPort()) {
            return false;
        }
        String commandServerSecret = getCommandServerSecret();
        String commandServerSecret2 = setupOptions.getCommandServerSecret();
        if (commandServerSecret == null) {
            if (commandServerSecret2 != null) {
                return false;
            }
        } else if (!commandServerSecret.equals(commandServerSecret2)) {
            return false;
        }
        if (getLogMaxLines() != setupOptions.getLogMaxLines() || getDebug() != setupOptions.getDebug()) {
            return false;
        }
        String crashReportSource = getCrashReportSource();
        String crashReportSource2 = setupOptions.getCrashReportSource();
        if (crashReportSource == null) {
            if (crashReportSource2 != null) {
                return false;
            }
        } else if (!crashReportSource.equals(crashReportSource2)) {
            return false;
        }
        String appVersion = getAppVersion();
        String appVersion2 = setupOptions.getAppVersion();
        if (appVersion == null) {
            if (appVersion2 != null) {
                return false;
            }
        } else if (!appVersion.equals(appVersion2)) {
            return false;
        }
        String appMarketingVersion = getAppMarketingVersion();
        String appMarketingVersion2 = setupOptions.getAppMarketingVersion();
        if (appMarketingVersion == null) {
            if (appMarketingVersion2 != null) {
                return false;
            }
        } else if (!appMarketingVersion.equals(appMarketingVersion2)) {
            return false;
        }
        return getOomKillerEnabled() == setupOptions.getOomKillerEnabled() && getOomKillerDisabled() == setupOptions.getOomKillerDisabled() && getOomMemoryLimit() == setupOptions.getOomMemoryLimit() && getPowerReportEnabled() == setupOptions.getPowerReportEnabled();
    }

    public final native String getAppMarketingVersion();

    public final native String getAppVersion();

    public final native String getBasePath();

    public final native int getCommandServerListenPort();

    public final native String getCommandServerSecret();

    public final native String getCrashReportSource();

    public final native boolean getDebug();

    public final native boolean getFixAndroidStack();

    public final native long getLogMaxLines();

    public final native boolean getOomKillerDisabled();

    public final native boolean getOomKillerEnabled();

    public final native long getOomMemoryLimit();

    public final native boolean getPowerReportEnabled();

    public final native String getTempPath();

    public final native String getWorkingPath();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getBasePath(), getWorkingPath(), getTempPath(), Boolean.valueOf(getFixAndroidStack()), Integer.valueOf(getCommandServerListenPort()), getCommandServerSecret(), Long.valueOf(getLogMaxLines()), Boolean.valueOf(getDebug()), getCrashReportSource(), getAppVersion(), getAppMarketingVersion(), Boolean.valueOf(getOomKillerEnabled()), Boolean.valueOf(getOomKillerDisabled()), Long.valueOf(getOomMemoryLimit()), Boolean.valueOf(getPowerReportEnabled())});
    }

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public final native void setAppMarketingVersion(String str);

    public final native void setAppVersion(String str);

    public final native void setBasePath(String str);

    public final native void setCommandServerListenPort(int i);

    public final native void setCommandServerSecret(String str);

    public final native void setCrashReportSource(String str);

    public final native void setDebug(boolean z);

    public final native void setFixAndroidStack(boolean z);

    public final native void setLogMaxLines(long j);

    public final native void setOomKillerDisabled(boolean z);

    public final native void setOomKillerEnabled(boolean z);

    public final native void setOomMemoryLimit(long j);

    public final native void setPowerReportEnabled(boolean z);

    public final native void setTempPath(String str);

    public final native void setWorkingPath(String str);

    public String toString() {
        return "SetupOptions{BasePath:" + getBasePath() + ",WorkingPath:" + getWorkingPath() + ",TempPath:" + getTempPath() + ",FixAndroidStack:" + getFixAndroidStack() + ",CommandServerListenPort:" + getCommandServerListenPort() + ",CommandServerSecret:" + getCommandServerSecret() + ",LogMaxLines:" + getLogMaxLines() + ",Debug:" + getDebug() + ",CrashReportSource:" + getCrashReportSource() + ",AppVersion:" + getAppVersion() + ",AppMarketingVersion:" + getAppMarketingVersion() + ",OomKillerEnabled:" + getOomKillerEnabled() + ",OomKillerDisabled:" + getOomKillerDisabled() + ",OomMemoryLimit:" + getOomMemoryLimit() + ",PowerReportEnabled:" + getPowerReportEnabled() + ",}";
    }

    public SetupOptions(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
