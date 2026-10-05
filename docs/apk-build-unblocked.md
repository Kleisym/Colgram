# Three build blockers removed, none of them from the feature work

The APK could not be built at all when this step started. Three separate failures stood between the
source tree and an installable app, and none of them was caused by the search or WARP changes:

## 1. Google Services had no client for the web package

    Execution failed for task ':TMessagesProj_AppStandalone:processAfatStandaloneGoogleServices'
    > No matching client found for package name 'org.colgram.messenger.web'

The file listed org.telegram.messenger.web but the module builds org.colgram.messenger.web. The web
variant had been renamed and the Firebase client list had not followed. Added the missing client,
mirroring the existing org.colgram.messenger entry.

## 2. The application label was declared twice

    Attribute application@label value=(@string/AppName) from AndroidManifest.xml
    is also present at AndroidManifest.xml value=(Colgram).

Two sources, two different values, so the merger refused. The main manifest already says Colgram,
which is the intended name - so the overlay's duplicate android:label was removed rather than the
main manifest's value being changed.

    TMessagesProj/src/main/AndroidManifest.xml:150              android:label="Colgram"
    TMessagesProj/config/release/AndroidManifest_standalone.xml  (removed)

## 3. A duplicate tools:replace while fixing the second one

The element already ended with tools:replace="android:supportsRtl". Adding a second
tools:replace attribute made the XML unparseable ("'tools:replace' is a duplicate attribute name"),
and listing android:label inside the existing value made the merger complain about
"Multiple entries with same key: tools:label=REPLACE and android:label=REPLACE". The correct fix was
neither: removing the duplicate declaration was enough, and that is what is in the tree now.

## What this means for verification

Source-level compilation was already green:

    :TMessagesProj:compileStandaloneJavaWithJavac     BUILD SUCCESSFUL

The full APK build is what proves the changes actually ship, so it is the gate that matters for the
search-history and subscriber-count work. It is past R8 now, which is the slow stage.
