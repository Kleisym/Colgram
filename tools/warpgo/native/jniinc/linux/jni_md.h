/*
 * jni_md.h for a non-Windows target.
 *
 * The JDK ships include/win32/jni_md.h, where JNICALL is __stdcall. The NDK targets do not have
 * that calling convention, so including the win32 header produced, for every bridge function:
 *
 *     warning: '__stdcall' calling convention is not supported for this target
 *
 * and left the ABI mismatched. The Android layout of this header is fixed and tiny, so it is written
 * out here rather than pulled from a JDK that only ships the Windows variant. jniinc/linux goes first
 * on the include path so this one is the file that gets found.
 */
#include <stdint.h>

#ifndef COLGRAM_JNI_MD_H
#define COLGRAM_JNI_MD_H

#define JNIEXPORT __attribute__((visibility("default")))
#define JNIIMPORT
#define JNICALL
#define JNIEXPORT_OPT

typedef long jint;
typedef int64_t jlong;
typedef signed char jbyte;

#endif /* COLGRAM_JNI_MD_H */