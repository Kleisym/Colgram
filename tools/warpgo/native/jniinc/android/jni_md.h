#ifndef COLGRAM_ANDROID_JNI_MD_H
#define COLGRAM_ANDROID_JNI_MD_H

/* Android JNI ABI types for building with a host JDK's architecture-independent jni.h. */
#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT
#define JNICALL
typedef int jint;
typedef long long jlong;
typedef signed char jbyte;

#endif
