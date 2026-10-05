/*
 * JNI bridge for the Colgram MASQUE client.
 *
 * Why this file exists at all.
 *
 * The Go side exports its entry points with //export, which produces plain C symbols:
 *
 *     colgram_masque_exchange                    (the Go function)
 *     _cgoexp_9add0986..._colgram_masque_exchange (cgo's expansion thunk)
 *
 * Neither is a JNI symbol. The VM resolves a native method to the mangled name
 *
 *     Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1exchange
 *
 * where every underscore in the Java identifier becomes _1. Since none of those exist, every call
 * from Java failed at runtime with
 *
 *     java.lang.UnsatisfiedLinkError: No implementation found for byte[]
 *     org.colgram.core.ColgramMasqueNative.colgram_masque_exchange(byte[], java.lang.String,
 *     java.lang.String) ... is the library loaded, e.g. System.loadLibrary?
 *
 * The message blames a missing loadLibrary() call, and that is exactly what sent this the wrong way
 * for a long time: the library really was loaded. Verified on device -- the .so was mapped into the
 * process, and the C symbols above were present in .dynsym -- while the Java side still could not
 * find its entry point. The visible symptom was a tunnel that accepted every packet and returned
 * none: tx climbed, rx stayed at zero.
 *
 * These wrappers are the real JNI entry points. They marshal, call the Go export, marshal back, and
 * do nothing else; all protocol work stays in Go.
 *
 * It is a separate .c file rather than part of main.go's cgo preamble on purpose. Code in the
 * preamble is compiled into cgo's generated _cgo_export.c, and declaring these functions there made
 * the linker report every one of them twice (once from the preamble, once from the export file).
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>

/*
 * The Go client writes every transport decision to stderr, which nothing on Android reads. The
 * device therefore showed one line - "no edge route answered" - for a run that had in fact tried a
 * TCP carrier and reported why, and the reason was unreachable from the log. This forwards those
 * lines to logcat so the run can be read on the phone.
 *
 * The JVM and the VM are one process, so the pointers below are set once by colgram_masque_measure
 * and read afterwards. They are cached in local statics rather than looked up per line: this is
 * called from the dial path, and FindClass there can allocate.
 */
#include <android/log.h>

void colgram_masque_emit(const char *line) {
    if (line != NULL) {
        __android_log_print(ANDROID_LOG_INFO, "ColgramMasque", "%s", line);
    }
}

/* The Go exports this bridge forwards to. */
extern char *colgram_masque_version(void);
extern char *colgram_masque_last_error(void);
extern char *colgram_masque_measure(char *bind, char *edge, char *port, char *relayBind);
extern void colgram_masque_set_socks(char *addr);
extern void colgram_masque_open_session(char *bind, char *edge);
extern void colgram_masque_close_session(void);
extern char *colgram_masque_exchange_slice(void *data, long len, char *bind, char *edge);
extern char *colgram_masque_last_reply_len(void);
extern char *colgram_masque_session_progress(void);
extern char *colgram_masque_capsule_stats(void);
extern char *colgram_masque_trace(void);

/*
 * Copies a Java string into a malloc'd C string.
 *
 * GetStringUTFChars hands back a view owned by the VM and must be released, so it cannot simply be
 * cast to char* and handed to Go: the VM is free to move or collect it at any point. Hence the copy.
 * Returns NULL for a null Java string, which Go already treats as not configured.
 */
static char *jni_copy(JNIEnv *env, jstring s) {
    if (s == NULL) {
        return NULL;
    }
    const char *chars = (*env)->GetStringUTFChars(env, s, NULL);
    if (chars == NULL) {
        return NULL;
    }
    char *copy = (char *)malloc(strlen(chars) + 1);
    if (copy != NULL) {
        strcpy(copy, chars);
    }
    (*env)->ReleaseStringUTFChars(env, s, chars);
    return copy;
}

/* Wraps a malloc'd C string as a Java String and takes ownership, so the Go exports can just
   return C.CString and not worry about who frees it. */
static jstring jni_take(JNIEnv *env, char *s) {
    if (s == NULL) {
        return NULL;
    }
    jstring out = (*env)->NewStringUTF(env, s);
    free(s);
    return out;
}

JNIEXPORT jstring JNICALL
Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1version(JNIEnv *env, jclass cls) {
    (void)cls;
    return jni_take(env, colgram_masque_version());
}

JNIEXPORT jstring JNICALL
Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1last_1error(JNIEnv *env, jclass cls) {
    (void)cls;
    return jni_take(env, colgram_masque_last_error());
}

JNIEXPORT jstring JNICALL
Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1session_1progress(JNIEnv *env, jclass cls) {
    (void)cls;
    return jni_take(env, colgram_masque_session_progress());
}

JNIEXPORT jstring JNICALL
Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1capsule_1stats(JNIEnv *env, jclass cls) {
    (void)cls;
    return jni_take(env, colgram_masque_capsule_stats());
}

JNIEXPORT jstring JNICALL
Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1trace(JNIEnv *env, jclass cls) {
    (void)cls;
    return jni_take(env, colgram_masque_trace());
}

JNIEXPORT void JNICALL
Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1set_1socks(JNIEnv *env, jclass cls, jstring addr) {
    (void)cls;
    char *c = jni_copy(env, addr);
    colgram_masque_set_socks(c);
    free(c);
}

JNIEXPORT void JNICALL
Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1open_1session(JNIEnv *env, jclass cls, jstring bind, jstring edge) {
    (void)cls;
    char *cb = jni_copy(env, bind);
    char *ce = jni_copy(env, edge);
    colgram_masque_open_session(cb, ce);
    free(cb);
    free(ce);
}

JNIEXPORT void JNICALL
Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1close_1session(JNIEnv *env, jclass cls) {
    (void)cls;
    colgram_masque_close_session();
}

JNIEXPORT jbyteArray JNICALL
Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1exchange(JNIEnv *env, jclass cls, jbyteArray packet, jstring bind, jstring edge) {
    (void)cls;
    if (packet == NULL) {
        return NULL;
    }
    jsize len = (*env)->GetArrayLength(env, packet);
    jbyte *bytes = (*env)->GetByteArrayElements(env, packet, NULL);
    if (bytes == NULL) {
        return NULL;
    }
    char *cb = jni_copy(env, bind);
    char *ce = jni_copy(env, edge);
    /* C.CBytes on the Go side, so this buffer is freed here with free(), not on the Go heap. */
    char *reply = colgram_masque_exchange_slice(bytes, (long)len, cb, ce);
    (*env)->ReleaseByteArrayElements(env, packet, bytes, 0);
    free(cb);
    free(ce);
    if (reply == NULL) {
        return NULL;
    }
    /* The length comes back out of band: the buffer is owned by C, so C is what has to know how
       much to release. A parsed integer rather than a second return value because the Go export
       can only return one pointer through the cgo //export path. */
    long reply_len = strtol(colgram_masque_last_reply_len(), NULL, 10);
    if (reply_len <= 0) {
        free(reply);
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize)reply_len);
    if (out == NULL) {
        free(reply);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)reply_len, (const jbyte *)reply);
    free(reply);
    return out;
}

JNIEXPORT jstring JNICALL
Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1measure(JNIEnv *env, jclass cls, jstring bind, jstring edge, jstring port, jstring relay) {
    (void)cls;
    char *cb = jni_copy(env, bind);
    char *ce = jni_copy(env, edge);
    char *cp = jni_copy(env, port);
    char *cr = jni_copy(env, relay);
    char *out = colgram_masque_measure(cb, ce, cp, cr);
    free(cb);
    free(ce);
    free(cp);
    free(cr);
    return jni_take(env, out);
}
