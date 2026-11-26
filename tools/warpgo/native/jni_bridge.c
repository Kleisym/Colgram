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
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>

static pthread_mutex_t g_exchange_lock = PTHREAD_MUTEX_INITIALIZER;

static jstring new_jstring_from_utf8_safe(JNIEnv *env, const char *utf8) {
    if (!env) return NULL;
    if (!utf8) return NULL;
    size_t len = strlen(utf8);
    if (len == 0) {
        const jchar empty = 0;
        return (*env)->NewString(env, &empty, 0);
    }
    jchar *utf16 = (jchar *)malloc((len * 2 + 1) * sizeof(jchar));
    if (!utf16) {
        return NULL;
    }
    const unsigned char *p = (const unsigned char *)utf8;
    size_t i = 0;
    jsize out = 0;
    while (i < len) {
        unsigned char c = p[i];
        uint32_t cp = 0xFFFDu;
        if (c < 0x80u) {
            cp = c;
            i += 1;
        } else if ((c & 0xE0u) == 0xC0u) {
            if (i + 1 < len && (p[i + 1] & 0xC0u) == 0x80u) {
                uint32_t v = ((uint32_t)(c & 0x1Fu) << 6) | (uint32_t)(p[i + 1] & 0x3Fu);
                if (v >= 0x80u) {
                    cp = v;
                    i += 2;
                } else {
                    i += 1;
                }
            } else {
                i += 1;
            }
        } else if ((c & 0xF0u) == 0xE0u) {
            if (i + 2 < len && (p[i + 1] & 0xC0u) == 0x80u && (p[i + 2] & 0xC0u) == 0x80u) {
                uint32_t v = ((uint32_t)(c & 0x0Fu) << 12)
                           | ((uint32_t)(p[i + 1] & 0x3Fu) << 6)
                           | (uint32_t)(p[i + 2] & 0x3Fu);
                if (v >= 0x800u && !(v >= 0xD800u && v <= 0xDFFFu)) {
                    cp = v;
                    i += 3;
                } else {
                    i += 1;
                }
            } else {
                i += 1;
            }
        } else if ((c & 0xF8u) == 0xF0u) {
            if (i + 3 < len && (p[i + 1] & 0xC0u) == 0x80u && (p[i + 2] & 0xC0u) == 0x80u && (p[i + 3] & 0xC0u) == 0x80u) {
                uint32_t v = ((uint32_t)(c & 0x07u) << 18)
                           | ((uint32_t)(p[i + 1] & 0x3Fu) << 12)
                           | ((uint32_t)(p[i + 2] & 0x3Fu) << 6)
                           | (uint32_t)(p[i + 3] & 0x3Fu);
                if (v >= 0x10000u && v <= 0x10FFFFu) {
                    cp = v;
                    i += 4;
                } else {
                    i += 1;
                }
            } else {
                i += 1;
            }
        } else {
            i += 1;
        }
        if (cp <= 0xFFFFu) {
            utf16[out++] = (jchar)cp;
        } else {
            cp -= 0x10000u;
            utf16[out++] = (jchar)(0xD800u + (cp >> 10));
            utf16[out++] = (jchar)(0xDC00u + (cp & 0x3FFu));
        }
    }
    jstring result = (*env)->NewString(env, utf16, out);
    free(utf16);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }
    return result;
}

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
#ifdef __ANDROID__
#include <android/log.h>
#else
#include <stdio.h>
#endif

void colgram_masque_emit(const char *line) {
    if (line != NULL) {
#ifdef __ANDROID__
        __android_log_print(ANDROID_LOG_INFO, "ColgramMasque", "%s", line);
#else
        fprintf(stderr, "ColgramMasque: %s\n", line);
#endif
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
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }
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
    jstring out = new_jstring_from_utf8_safe(env, s);
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
    if ((*env)->ExceptionCheck(env) || len < 0) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return NULL;
    }
    jbyte *bytes = (*env)->GetByteArrayElements(env, packet, NULL);
    if ((*env)->ExceptionCheck(env) || bytes == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        return NULL;
    }
    char *cb = jni_copy(env, bind);
    char *ce = jni_copy(env, edge);
    /* Serialize exchange + out-of-band reply length read so concurrent callers never cross-read lengths. */
    pthread_mutex_lock(&g_exchange_lock);
    char *reply = colgram_masque_exchange_slice(bytes, (long)len, cb, ce);
    char *length_text = reply != NULL ? colgram_masque_last_reply_len() : NULL;
    pthread_mutex_unlock(&g_exchange_lock);
    (*env)->ReleaseByteArrayElements(env, packet, bytes, JNI_ABORT);
    free(cb);
    free(ce);
    if (reply == NULL) {
        free(length_text);
        return NULL;
    }
    long reply_len = length_text == NULL ? 0 : strtol(length_text, NULL, 10);
    free(length_text);
    if (reply_len <= 0) {
        free(reply);
        return NULL;
    }
    jbyteArray out = (*env)->NewByteArray(env, (jsize)reply_len);
    if ((*env)->ExceptionCheck(env) || out == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        free(reply);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)reply_len, (const jbyte *)reply);
    free(reply);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }
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

extern void *colgram_masque_receive_slice(void);
extern char *colgram_masque_tunnel_address(void);

JNIEXPORT jbyteArray JNICALL
Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1receive(JNIEnv *env, jclass cls) {
    (void)cls;
    unsigned char *framed = (unsigned char *)colgram_masque_receive_slice();
    if (framed == NULL) return NULL;
    uint32_t length = (uint32_t)framed[0] | ((uint32_t)framed[1] << 8)
        | ((uint32_t)framed[2] << 16) | ((uint32_t)framed[3] << 24);
    if (length < 20 || length > 65535) { free(framed); return NULL; }
    jbyteArray out = (*env)->NewByteArray(env, (jsize)length);
    if ((*env)->ExceptionCheck(env) || out == NULL) {
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        free(framed);
        return NULL;
    }
    (*env)->SetByteArrayRegion(env, out, 0, (jsize)length, (const jbyte *)(framed + 4));
    free(framed);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return NULL;
    }
    return out;
}

JNIEXPORT jstring JNICALL
Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1tunnel_1address(JNIEnv *env, jclass cls) {
    (void)cls;
    return jni_take(env, colgram_masque_tunnel_address());
}

static JavaVM *vpn_vm;
static jobject vpn_service;
static jmethodID vpn_protect;
static pthread_mutex_t vpn_lock = PTHREAD_MUTEX_INITIALIZER;

JNIEXPORT void JNICALL
Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1set_1vpn(JNIEnv *env, jclass cls, jobject service) {
    (void)cls;
    pthread_mutex_lock(&vpn_lock);
    if (vpn_service != NULL) (*env)->DeleteGlobalRef(env, vpn_service);
    vpn_service = service == NULL ? NULL : (*env)->NewGlobalRef(env, service);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        vpn_service = NULL;
    }
    (*env)->GetJavaVM(env, &vpn_vm);
    vpn_protect = NULL;
    if (service != NULL) {
        jclass type = (*env)->GetObjectClass(env, service);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            type = NULL;
        }
        if (type != NULL) {
            vpn_protect = (*env)->GetMethodID(env, type, "protect", "(I)Z");
            if ((*env)->ExceptionCheck(env)) {
                (*env)->ExceptionClear(env);
                vpn_protect = NULL;
            }
            (*env)->DeleteLocalRef(env, type);
        }
    }
    pthread_mutex_unlock(&vpn_lock);
}

int colgram_masque_protect_fd(int fd) {
    pthread_mutex_lock(&vpn_lock);
    JavaVM *vm = vpn_vm;
    pthread_mutex_unlock(&vpn_lock);
    if (vm == NULL) return 1; /* Standalone diagnostic, no installed VPN. */
    JNIEnv *env = NULL;
    int attached = 0;
    jint state = (*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6);
    if (state == JNI_EDETACHED) {
        if ((*vm)->AttachCurrentThread(vm, &env, NULL) != JNI_OK) return 0;
        attached = 1;
    } else if (state != JNI_OK) return 0;
    pthread_mutex_lock(&vpn_lock);
    jobject service = vpn_service == NULL ? NULL : (*env)->NewLocalRef(env, vpn_service);
    jmethodID method = vpn_protect;
    pthread_mutex_unlock(&vpn_lock);
    int result = 0;
    if (service != NULL && method != NULL) {
        result = (*env)->CallBooleanMethod(env, service, method, (jint)fd) == JNI_TRUE;
        if ((*env)->ExceptionCheck(env)) { (*env)->ExceptionClear(env); result = 0; }
        (*env)->DeleteLocalRef(env, service);
    }
    if (attached) (*vm)->DetachCurrentThread(vm);
    return result;
}

extern void colgram_masque_open_owned(char *bind, char *edge, long long owner);
extern void colgram_masque_close_owned(long long owner);

JNIEXPORT void JNICALL
Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1open_1owned(JNIEnv *env, jclass cls, jstring bind, jstring edge, jlong owner) {
    (void)cls;
    char *cb = jni_copy(env, bind);
    char *ce = jni_copy(env, edge);
    colgram_masque_open_owned(cb, ce, (long long)owner);
    free(cb); free(ce);
}

JNIEXPORT void JNICALL
Java_org_colgram_core_ColgramMasqueNative_colgram_1masque_1close_1owned(JNIEnv *env, jclass cls, jlong owner) {
    (void)env; (void)cls;
    colgram_masque_close_owned((long long)owner);
}
