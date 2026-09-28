package io.nekohasekai.libbox;

import go.Seq;
import java.util.Arrays;

/* JADX INFO: compiled from: r8-map-id-6e4d8520bdb2bc9b727214c3f64a1aff1c7d3eb19d3c0ee047c19d270927f5b2 */
/* JADX INFO: loaded from: classes.dex */
public final class DeprecatedNote implements Seq.Proxy {
    public final int refnum;

    static {
        Libbox.touch();
    }

    public DeprecatedNote() {
        int i__New = __New();
        this.refnum = i__New;
        Seq.trackGoRef(i__New, this);
    }

    private static native int __New();

    public boolean equals(Object obj) {
        if (obj == null || !(obj instanceof DeprecatedNote)) {
            return false;
        }
        DeprecatedNote deprecatedNote = (DeprecatedNote) obj;
        String name = getName();
        String name2 = deprecatedNote.getName();
        if (name == null) {
            if (name2 != null) {
                return false;
            }
        } else if (!name.equals(name2)) {
            return false;
        }
        String description = getDescription();
        String description2 = deprecatedNote.getDescription();
        if (description == null) {
            if (description2 != null) {
                return false;
            }
        } else if (!description.equals(description2)) {
            return false;
        }
        String deprecatedVersion = getDeprecatedVersion();
        String deprecatedVersion2 = deprecatedNote.getDeprecatedVersion();
        if (deprecatedVersion == null) {
            if (deprecatedVersion2 != null) {
                return false;
            }
        } else if (!deprecatedVersion.equals(deprecatedVersion2)) {
            return false;
        }
        String scheduledVersion = getScheduledVersion();
        String scheduledVersion2 = deprecatedNote.getScheduledVersion();
        if (scheduledVersion == null) {
            if (scheduledVersion2 != null) {
                return false;
            }
        } else if (!scheduledVersion.equals(scheduledVersion2)) {
            return false;
        }
        String envName = getEnvName();
        String envName2 = deprecatedNote.getEnvName();
        if (envName == null) {
            if (envName2 != null) {
                return false;
            }
        } else if (!envName.equals(envName2)) {
            return false;
        }
        String migrationLink = getMigrationLink();
        String migrationLink2 = deprecatedNote.getMigrationLink();
        if (migrationLink == null) {
            return migrationLink2 == null;
        }
        return migrationLink.equals(migrationLink2);
    }

    public final native String getDeprecatedVersion();

    public final native String getDescription();

    public final native String getEnvName();

    public final native String getMigrationLink();

    public final native String getName();

    public final native String getScheduledVersion();

    public int hashCode() {
        return Arrays.hashCode(new Object[]{getName(), getDescription(), getDeprecatedVersion(), getScheduledVersion(), getEnvName(), getMigrationLink()});
    }

    public native boolean impending();

    @Override // go.Seq.GoObject
    public final int incRefnum() {
        Seq.incGoRef(this.refnum, this);
        return this.refnum;
    }

    public native String message();

    public native String messageWithLink();

    public final native void setDeprecatedVersion(String str);

    public final native void setDescription(String str);

    public final native void setEnvName(String str);

    public final native void setMigrationLink(String str);

    public final native void setName(String str);

    public final native void setScheduledVersion(String str);

    public String toString() {
        return "DeprecatedNote{Name:" + getName() + ",Description:" + getDescription() + ",DeprecatedVersion:" + getDeprecatedVersion() + ",ScheduledVersion:" + getScheduledVersion() + ",EnvName:" + getEnvName() + ",MigrationLink:" + getMigrationLink() + ",}";
    }

    public DeprecatedNote(int i) {
        this.refnum = i;
        Seq.trackGoRef(i, this);
    }
}
