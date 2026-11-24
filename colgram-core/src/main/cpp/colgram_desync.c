#define _GNU_SOURCE

#include <jni.h>
#include <android/log.h>
#include <arpa/inet.h>
#include <errno.h>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <pthread.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/eventfd.h>
#include <sys/mman.h>
#include <sys/sendfile.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <sys/types.h>
#include <unistd.h>

#define LOG_TAG "ColgramNativeDesync"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define MAX_CONNS 2048
#define DEFAULT_TTL_FALLBACK 64
#define MIN_LINUX_TCP_MSS 88
#define MAX_SAFE_FAKE_TTL 5

/* Native desync modes — kept strictly synchronized with ColgramNativeDesync.java */
#define MODE_PLAIN               0
#define MODE_DISORDER            1  /* IP_TTL=1 on [0..split1), wait TX, restore TTL, send [split1..len) */
#define MODE_DISORDER_BYTE1      2  /* Exact ByeDPI -d1: IP_TTL=1 on byte 0 (0x16), restore TTL, send [1..split1), [split1..len) */
#define MODE_OOB_BYTE1_DISORDER  3  /* Exact mobile ByeDPI -o1 -d1 -r1+s: IP_TTL=1 + MSG_OOB at byte 1, restore TTL, send rest */
#define MODE_OOB_DISORDER        4  /* IP_TTL=1 + MSG_OOB at split1, restore TTL, send rest */
#define MODE_FAKE_TTL            5  /* LTE-safe sendfile()+mmap() full whitelisted RU SNI decoy at fakeTtl, wait TX, overwrite, send rest */
#define MODE_MSS_SPLIT           6  /* TCP_MAXSEG clamped before connect(); split across split1 */
#define MODE_MSS_OOB_BYTE1       7  /* TCP_MAXSEG>=88 + MSG_OOB at byte 1 + split across split1 */
#define MODE_FAKE_TTL_DISORDER   8  /* Double whammy: low-TTL fake decoy + IP_TTL=1 disorder on middle chunk */
#define MODE_OOB_BYTE1_ONLY      9  /* Exact mobile ByeDPI -o1 -r1+s: byte-1 MSG_OOB + 2-rec SNI+1 split, NO TTL=1, normal SYN */
#define MODE_SPLIT_BYTE1        10  /* Exact ByeDPI -s1 -r1+s: 1B kernel TCP split + 2-rec SNI+1 split, NO TTL=1, NO OOB */
#define MODE_MULTI_SPLIT_SNI    11  /* Carrier-safe multi-split across SNI (-s1 -s3+s -s6+s + -r1+s): NO OOB, NO TTL=1, NO tiny MSS */

typedef struct {
    atomic_int refcount;
    atomic_int closed;
    int fd;
    int wake_fd;
    int family;
    int default_ttl;
    int window_clamped;
} DesyncConn;

typedef struct {
    DesyncConn *conn;
    uint32_t gen;
} ConnSlot;

static pthread_mutex_t g_table_lock = PTHREAD_MUTEX_INITIALIZER;
static ConnSlot g_slots[MAX_CONNS];
static atomic_uint g_tmp_seq = 1;

static jlong make_handle(uint32_t slot, uint32_t gen) {
    return (jlong)((((uint64_t)gen) << 16) | ((uint64_t)(slot & 0xFFFFu)));
}

static DesyncConn *conn_acquire(jlong handle) {
    if (handle <= 0) return NULL;
    uint32_t slot = (uint32_t)((uint64_t)handle & 0xFFFFu);
    uint32_t gen = (uint32_t)(((uint64_t)handle) >> 16);
    if (slot >= MAX_CONNS || gen == 0) return NULL;
    DesyncConn *conn = NULL;
    pthread_mutex_lock(&g_table_lock);
    if (g_slots[slot].gen == gen && g_slots[slot].conn != NULL) {
        conn = g_slots[slot].conn;
        atomic_fetch_add_explicit(&conn->refcount, 1, memory_order_relaxed);
    }
    pthread_mutex_unlock(&g_table_lock);
    return conn;
}

static void conn_release(DesyncConn *conn) {
    if (conn == NULL) return;
    if (atomic_fetch_sub_explicit(&conn->refcount, 1, memory_order_acq_rel) == 1) {
        if (conn->fd >= 0) {
            close(conn->fd);
            conn->fd = -1;
        }
        if (conn->wake_fd >= 0) {
            close(conn->wake_fd);
            conn->wake_fd = -1;
        }
        free(conn);
    }
}

static jlong conn_register(DesyncConn *conn) {
    pthread_mutex_lock(&g_table_lock);
    for (uint32_t i = 0; i < MAX_CONNS; i++) {
        if (g_slots[i].conn == NULL) {
            g_slots[i].gen++;
            if (g_slots[i].gen == 0) g_slots[i].gen = 1;
            g_slots[i].conn = conn;
            jlong handle = make_handle(i, g_slots[i].gen);
            pthread_mutex_unlock(&g_table_lock);
            return handle;
        }
    }
    pthread_mutex_unlock(&g_table_lock);
    return 0;
}

static int set_ttl(int fd, int family, int ttl) {
    if (ttl < 1) ttl = 1;
    if (ttl > 255) ttl = 255;
    if (family == AF_INET6) {
        return setsockopt(fd, IPPROTO_IPV6, IPV6_UNICAST_HOPS, &ttl, sizeof(ttl));
    }
    return setsockopt(fd, IPPROTO_IP, IP_TTL, &ttl, sizeof(ttl));
}

static int get_default_ttl(int fd, int family) {
    int ttl = DEFAULT_TTL_FALLBACK;
    socklen_t len = sizeof(ttl);
    int rc;
    if (family == AF_INET6) {
        rc = getsockopt(fd, IPPROTO_IPV6, IPV6_UNICAST_HOPS, &ttl, &len);
    } else {
        rc = getsockopt(fd, IPPROTO_IP, IP_TTL, &ttl, &len);
    }
    if (rc != 0 || ttl <= 0 || ttl > 255) {
        return DEFAULT_TTL_FALLBACK;
    }
    return ttl;
}

static int wait_fd_event(DesyncConn *conn, short events, int timeout_ms) {
    struct pollfd pfds[2];
    pfds[0].fd = conn->fd;
    pfds[0].events = events;
    pfds[0].revents = 0;
    pfds[1].fd = conn->wake_fd;
    pfds[1].events = POLLIN;
    pfds[1].revents = 0;

    while (1) {
        if (atomic_load_explicit(&conn->closed, memory_order_acquire)) {
            return -2;
        }
        int rc = poll(pfds, 2, timeout_ms);
        if (rc < 0) {
            if (errno == EINTR) continue;
            return -2;
        }
        if (rc == 0) {
            return -1;
        }
        if (pfds[1].revents & POLLIN) {
            return -2;
        }
        if (pfds[0].revents & (POLLERR | POLLNVAL)) {
            return -2;
        }
        return 1;
    }
}

/*
 * Wait until the Linux TCP stack has actually pushed at least one unacked segment onto the
 * NIC / LTE baseband (tcpi_unacked >= min_unacked), then allow a short DMA/radio flush window
 * (cellular LTE needs ~15-20ms for uplink grant; Wi-Fi needs ~3ms) while staying far below
 * the 200ms Linux minimum TCP RTO.
 */
static void wait_tx_pushed(DesyncConn *conn, uint32_t min_unacked, int cellular) {
    int max_poll_ms = cellular ? 35 : 10;
    for (int i = 0; i < max_poll_ms; i++) {
        if (atomic_load_explicit(&conn->closed, memory_order_acquire)) return;
        struct tcp_info ti;
        socklen_t len = sizeof(ti);
        memset(&ti, 0, sizeof(ti));
        if (getsockopt(conn->fd, IPPROTO_TCP, TCP_INFO, &ti, &len) == 0) {
            if (ti.tcpi_unacked >= min_unacked) break;
        } else {
            break;
        }
        usleep(1000);
    }
    usleep(cellular ? 18000 : 4000);
}

static int send_all(DesyncConn *conn, const uint8_t *buf, size_t len, int flags) {
    size_t sent = 0;
    while (sent < len) {
        if (atomic_load_explicit(&conn->closed, memory_order_acquire)) {
            return -2;
        }
        ssize_t n = send(conn->fd, buf + sent, len - sent, flags | MSG_NOSIGNAL);
        if (n > 0) {
            sent += (size_t)n;
            continue;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            int w = wait_fd_event(conn, POLLOUT, 5000);
            if (w <= 0) return -2;
            continue;
        }
        return -2;
    }
    return 0;
}

static int send_tail_split(DesyncConn *conn, const uint8_t *buf, size_t start,
                           size_t cut2, size_t total, int delay_us) {
    if (cut2 > start && cut2 < total) {
        int rc = send_all(conn, buf + start, cut2 - start, 0);
        if (rc == 0) {
            usleep((useconds_t)delay_us);
            rc = send_all(conn, buf + cut2, total - cut2, 0);
        }
        return rc;
    }
    return send_all(conn, buf + start, total - start, 0);
}

static int send_oob_chunk(DesyncConn *conn, const uint8_t *buf, size_t len, uint8_t oob_byte) {
    if (len == 0) return 0;
    /* Send any multi-byte prefix via normal send_all() so MSG_OOB is applied ONLY once, on the
     * final 2-byte [last_real_byte, oob_byte] chunk — never looped across partial writes. */
    if (len > 1) {
        int pre_rc = send_all(conn, buf, len - 1, 0);
        if (pre_rc != 0) return pre_rc;
    }

    uint8_t tail[2];
    tail[0] = buf[len - 1];
    tail[1] = oob_byte;

    while (1) {
        if (atomic_load_explicit(&conn->closed, memory_order_acquire)) {
            return -2;
        }
        ssize_t n = send(conn->fd, tail, sizeof(tail), MSG_OOB | MSG_NOSIGNAL);
        if (n == (ssize_t)sizeof(tail)) {
            return 0;
        }
        if (n == 1) {
            /* Kernel accepted only tail[0] with MSG_OOB; do not write oob_byte inline. */
            LOGW("send_oob_chunk partial 1/2 write; aborting OOB segment");
            return -2;
        }
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            int w = wait_fd_event(conn, POLLOUT, 5000);
            if (w <= 0) return -2;
            continue;
        }
        if (errno == EOPNOTSUPP || errno == EINVAL) {
            return send_all(conn, &buf[len - 1], 1, 0);
        }
        LOGW("send_oob_chunk failed: errno=%d (%s)", errno, strerror(errno));
        return -2;
    }
}

static int open_fake_backing_fd(const char *tmp_dir) {
    int ffd = -1;
#ifdef __NR_memfd_create
    ffd = (int)syscall(__NR_memfd_create, "cg_desync", 0u);
    if (ffd >= 0) {
        fcntl(ffd, F_SETFD, FD_CLOEXEC);
        return ffd;
    }
#endif
    if (tmp_dir != NULL && tmp_dir[0] != '\0') {
        char path[512];
        unsigned int seq = atomic_fetch_add_explicit(&g_tmp_seq, 1, memory_order_relaxed);
        int written = snprintf(path, sizeof(path), "%s/cg_fake_%d_%u.tmp",
                               tmp_dir, (int)getpid(), seq);
        if (written > 0 && (size_t)written < sizeof(path)) {
            ffd = open(path, O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
            if (ffd >= 0) {
                unlink(path);
                return ffd;
            }
        }
    }
    return -1;
}

/*
 * LTE-safe unrooted ByeDPI fake-packet injection via Linux sendfile() + mmap(MAP_SHARED):
 *   1. Write complete whitelisted RU TLS 1.3 ClientHello (max.ru/vk.com/yandex.ru/gosuslugi.ru)
 *      into page-backed memfd/unlinked tmpfile.
 *   2. Lower IP_TTL to fake_ttl (reaches carrier TSPU, expires before real destination server).
 *   3. sendfile() queues zero-copy page references and emits the whitelisted decoy packet.
 *   4. wait_tx_pushed() confirms kernel/LTE baseband actually transmitted the decoy before
 *      restoring IP_TTL and overwriting the exact same RAM page with real ClientHello bytes.
 *   5. Optionally applies IP_TTL=1 disorder to the middle slice (when disorder_tail != 0),
 *      then transmits the remaining real bytes at normal TTL to trigger fast SACK/retransmit.
 */
static int send_fake_with_ttl(DesyncConn *conn,
                              const uint8_t *real_buf, size_t real_len,
                              size_t fake_len, int fake_ttl,
                              const uint8_t *fake_buf, size_t fake_buf_len,
                              const char *tmp_dir, int cellular, int disorder_tail) {
    if (fake_buf == NULL || fake_buf_len < 16 || fake_len < 16 || fake_len >= real_len) {
        return -10;
    }
    if (fake_len > fake_buf_len) {
        fake_len = fake_buf_len;
    }
    int ffd = open_fake_backing_fd(tmp_dir);
    if (ffd < 0) return -10;

    if (ftruncate(ffd, (off_t)fake_len) != 0) {
        close(ffd);
        return -10;
    }
    void *map = mmap(NULL, fake_len, PROT_READ | PROT_WRITE, MAP_SHARED, ffd, 0);
    if (map == MAP_FAILED) {
        close(ffd);
        return -10;
    }

    memcpy(map, fake_buf, fake_len);

    if (set_ttl(conn->fd, conn->family, fake_ttl) != 0) {
        munmap(map, fake_len);
        close(ffd);
        return -10;
    }

    off_t off = 0;
    int sf_err = 0;
    while ((size_t)off < fake_len) {
        if (atomic_load_explicit(&conn->closed, memory_order_acquire)) {
            sf_err = -2;
            break;
        }
        ssize_t n = sendfile(conn->fd, ffd, &off, fake_len - (size_t)off);
        if (n > 0) continue;
        if (n < 0 && errno == EINTR) continue;
        if (n < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
            if (wait_fd_event(conn, POLLOUT, 3000) <= 0) {
                sf_err = -2;
                break;
            }
            continue;
        }
        sf_err = (off == 0) ? -10 : -2;
        break;
    }

    if (sf_err == 0) {
        /* Guarantee the low-TTL decoy actually left the kernel/LTE modem before overwriting page */
        wait_tx_pushed(conn, 1, cellular);
        set_ttl(conn->fd, conn->family, conn->default_ttl);
        memcpy(map, real_buf, fake_len);
    } else {
        set_ttl(conn->fd, conn->family, conn->default_ttl);
    }

    munmap(map, fake_len);
    close(ffd);

    if (sf_err != 0) return sf_err;

    size_t tail_len = real_len - fake_len;
    if (disorder_tail && tail_len > 24 && set_ttl(conn->fd, conn->family, 1) == 0) {
        size_t mid = tail_len / 2;
        int rc = send_all(conn, real_buf + fake_len, mid, 0);
        if (rc == 0) wait_tx_pushed(conn, 2, cellular);
        set_ttl(conn->fd, conn->family, conn->default_ttl);
        if (rc == 0) rc = send_all(conn, real_buf + fake_len + mid, tail_len - mid, 0);
        return rc;
    }
    return send_all(conn, real_buf + fake_len, tail_len, 0);
}

static int parse_ip_sockaddr(const char *ip_str, int port,
                             struct sockaddr_storage *out, socklen_t *out_len, int *out_family) {
    if (ip_str == NULL || port <= 0 || port > 65535) return -1;
    char clean[128];
    size_t slen = strlen(ip_str);
    if (slen == 0 || slen >= sizeof(clean)) return -1;

    const char *start = ip_str;
    if (start[0] == '[' && slen > 2 && start[slen - 1] == ']') {
        start++;
        slen -= 2;
    }
    memcpy(clean, start, slen);
    clean[slen] = '\0';
    char *pct = strchr(clean, '%');
    if (pct != NULL) *pct = '\0';

    memset(out, 0, sizeof(*out));
    if (strchr(clean, ':') != NULL) {
        struct sockaddr_in6 *sa6 = (struct sockaddr_in6 *)out;
        sa6->sin6_family = AF_INET6;
        sa6->sin6_port = htons((uint16_t)port);
        if (inet_pton(AF_INET6, clean, &sa6->sin6_addr) != 1) return -1;
        *out_len = sizeof(struct sockaddr_in6);
        *out_family = AF_INET6;
        return 0;
    } else {
        struct sockaddr_in *sa4 = (struct sockaddr_in *)out;
        sa4->sin_family = AF_INET;
        sa4->sin_port = htons((uint16_t)port);
        if (inet_pton(AF_INET, clean, &sa4->sin_addr) != 1) return -1;
        *out_len = sizeof(struct sockaddr_in);
        *out_family = AF_INET;
        return 0;
    }
}

static int invoke_fd_protector(JNIEnv *env, jobject protector, int fd) {
    if (protector == NULL) return 1;
    jclass pcls = (*env)->GetObjectClass(env, protector);
    if (pcls == NULL) return 0;
    jmethodID mid = (*env)->GetMethodID(env, pcls, "protectFd", "(I)Z");
    (*env)->DeleteLocalRef(env, pcls);
    if (mid == NULL) return 0;
    jboolean ok = (*env)->CallBooleanMethod(env, protector, mid, (jint)fd);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return 0;
    }
    return ok ? 1 : 0;
}

JNIEXPORT jint JNICALL
Java_org_colgram_core_ColgramNativeDesync_nativeSelfTest(JNIEnv *env, jclass clazz, jstring jTmpDir) {
    (void)clazz;
    jint flags = 0;

    int s = socket(AF_INET, SOCK_STREAM | SOCK_CLOEXEC, 0);
    if (s >= 0) {
        flags |= 1;
        int ttl = 1;
        if (setsockopt(s, IPPROTO_IP, IP_TTL, &ttl, sizeof(ttl)) == 0) flags |= 2;
        int mss = 88;
        if (setsockopt(s, IPPROTO_TCP, TCP_MAXSEG, &mss, sizeof(mss)) == 0) flags |= 16;
        int wclamp = 2048;
        if (setsockopt(s, IPPROTO_TCP, TCP_WINDOW_CLAMP, &wclamp, sizeof(wclamp)) == 0) flags |= 32;
        struct tcp_info ti;
        socklen_t tilen = sizeof(ti);
        if (getsockopt(s, IPPROTO_TCP, TCP_INFO, &ti, &tilen) == 0) flags |= 64;
        close(s);
    }

#ifdef __NR_memfd_create
    int mfd = (int)syscall(__NR_memfd_create, "cg_test", 0u);
    if (mfd >= 0) {
        if (ftruncate(mfd, 64) == 0) {
            void *m = mmap(NULL, 64, PROT_READ | PROT_WRITE, MAP_SHARED, mfd, 0);
            if (m != MAP_FAILED) {
                flags |= 4;
                munmap(m, 64);
            }
        }
        close(mfd);
    }
#endif

    if (jTmpDir != NULL) {
        const char *tmp_dir = (*env)->GetStringUTFChars(env, jTmpDir, NULL);
        if (tmp_dir != NULL) {
            char path[512];
            int w = snprintf(path, sizeof(path), "%s/cg_selftest_%d.tmp", tmp_dir, (int)getpid());
            if (w > 0 && (size_t)w < sizeof(path)) {
                int tfd = open(path, O_RDWR | O_CREAT | O_EXCL | O_CLOEXEC, 0600);
                if (tfd >= 0) {
                    unlink(path);
                    if (ftruncate(tfd, 64) == 0) {
                        void *m = mmap(NULL, 64, PROT_READ | PROT_WRITE, MAP_SHARED, tfd, 0);
                        if (m != MAP_FAILED) {
                            flags |= 8;
                            munmap(m, 64);
                        }
                    }
                    close(tfd);
                }
            }
            (*env)->ReleaseStringUTFChars(env, jTmpDir, tmp_dir);
        }
    }

    LOGI("nativeSelfTest flags=0x%x (sock=%d ttl=%d memfd=%d tmp=%d mss=%d wclamp=%d tcpinfo=%d)",
         (unsigned int)flags, (flags & 1) != 0, (flags & 2) != 0,
         (flags & 4) != 0, (flags & 8) != 0, (flags & 16) != 0,
         (flags & 32) != 0, (flags & 64) != 0);
    return flags;
}

JNIEXPORT jlong JNICALL
Java_org_colgram_core_ColgramNativeDesync_nativeConnect(JNIEnv *env, jclass clazz,
                                                        jstring jIp, jint port,
                                                        jint timeoutMs, jint mssClamp,
                                                        jint windowClamp, jobject protector) {
    (void)clazz;
    if (jIp == NULL || port <= 0 || port > 65535) return 0;

    const char *ip_str = (*env)->GetStringUTFChars(env, jIp, NULL);
    if (ip_str == NULL) return 0;

    struct sockaddr_storage sa;
    socklen_t sa_len = 0;
    int family = AF_INET;
    char ip_log[64];
    snprintf(ip_log, sizeof(ip_log), "%s", ip_str);
    int parse_rc = parse_ip_sockaddr(ip_str, (int)port, &sa, &sa_len, &family);
    (*env)->ReleaseStringUTFChars(env, jIp, ip_str);
    if (parse_rc != 0) {
        LOGW("nativeConnect invalid ip=%s port=%d", ip_log, (int)port);
        return 0;
    }

    int fd = socket(family, SOCK_STREAM | SOCK_NONBLOCK | SOCK_CLOEXEC, 0);
    if (fd < 0) {
        LOGW("nativeConnect socket(family=%d) failed: errno=%d (%s)", family, errno, strerror(errno));
        return 0;
    }

    int one = 1;
    if (setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one)) != 0) {
        LOGD("setsockopt(TCP_NODELAY) failed: errno=%d", errno);
    }
    if (setsockopt(fd, SOL_SOCKET, SO_KEEPALIVE, &one, sizeof(one)) != 0) {
        LOGD("setsockopt(SO_KEEPALIVE) failed: errno=%d", errno);
    }
#ifdef TCP_KEEPIDLE
    {
        int keepidle = 25;
        setsockopt(fd, IPPROTO_TCP, TCP_KEEPIDLE, &keepidle, sizeof(keepidle));
    }
#endif
#ifdef TCP_KEEPINTVL
    {
        int keepintvl = 8;
        setsockopt(fd, IPPROTO_TCP, TCP_KEEPINTVL, &keepintvl, sizeof(keepintvl));
    }
#endif
#ifdef TCP_KEEPCNT
    {
        int keepcnt = 3;
        setsockopt(fd, IPPROTO_TCP, TCP_KEEPCNT, &keepcnt, sizeof(keepcnt));
    }
#endif
#ifdef TCP_USER_TIMEOUT
    {
        int user_timeout = 25000;
        setsockopt(fd, IPPROTO_TCP, TCP_USER_TIMEOUT, &user_timeout, sizeof(user_timeout));
    }
#endif

    if (mssClamp > 0) {
        int mss = (int)mssClamp;
        if (mss < MIN_LINUX_TCP_MSS) mss = MIN_LINUX_TCP_MSS;
        if (mss > 1200) mss = 1200;
        if (setsockopt(fd, IPPROTO_TCP, TCP_MAXSEG, &mss, sizeof(mss)) != 0) {
            LOGW("setsockopt(TCP_MAXSEG=%d) failed: errno=%d (%s)", mss, errno, strerror(errno));
        }
    }
    int clamped = 0;
    if (windowClamp >= 512 && windowClamp <= 65535) {
        int wc = (int)windowClamp;
        if (setsockopt(fd, IPPROTO_TCP, TCP_WINDOW_CLAMP, &wc, sizeof(wc)) == 0) {
            clamped = 1;
        } else {
            LOGW("setsockopt(TCP_WINDOW_CLAMP=%d) failed: errno=%d (%s)", wc, errno, strerror(errno));
        }
    } else if (windowClamp == 0) {
        int buf_sz = 262144;
        setsockopt(fd, SOL_SOCKET, SO_RCVBUF, &buf_sz, sizeof(buf_sz));
        setsockopt(fd, SOL_SOCKET, SO_SNDBUF, &buf_sz, sizeof(buf_sz));
    }

    if (!invoke_fd_protector(env, protector, fd)) {
        LOGW("nativeConnect fd protector rejected fd=%d for %s:%d", fd, ip_log, (int)port);
        close(fd);
        return 0;
    }

    int rc = connect(fd, (const struct sockaddr *)&sa, sa_len);
    if (rc != 0) {
        int conn_err = errno;
        if (conn_err != EINPROGRESS) {
            LOGW("nativeConnect %s:%d immediate error errno=%d (%s)", ip_log, (int)port, conn_err, strerror(conn_err));
            close(fd);
            return 0;
        }
        struct pollfd pfd;
        pfd.fd = fd;
        pfd.events = POLLOUT;
        pfd.revents = 0;
        int wait_ms = timeoutMs > 0 ? (int)timeoutMs : 5000;
        int prc;
        do {
            prc = poll(&pfd, 1, wait_ms);
        } while (prc < 0 && errno == EINTR);
        if (prc <= 0) {
            if (prc < 0) {
                LOGW("nativeConnect %s:%d poll error errno=%d (%s)", ip_log, (int)port, errno, strerror(errno));
            } else {
                LOGD("nativeConnect %s:%d poll timeout (%dms)", ip_log, (int)port, wait_ms);
            }
            close(fd);
            return 0;
        }
        int so_err = 0;
        socklen_t err_len = sizeof(so_err);
        if (getsockopt(fd, SOL_SOCKET, SO_ERROR, &so_err, &err_len) != 0 || so_err != 0) {
            int gs_err = (so_err != 0) ? so_err : errno;
            LOGW("nativeConnect %s:%d SO_ERROR=%d (%s)", ip_log, (int)port, gs_err, strerror(gs_err));
            close(fd);
            return 0;
        }
    }

    int wake_fd = eventfd(0, EFD_NONBLOCK | EFD_CLOEXEC);
    if (wake_fd < 0) {
        close(fd);
        return 0;
    }

    DesyncConn *conn = (DesyncConn *)calloc(1, sizeof(DesyncConn));
    if (conn == NULL) {
        close(wake_fd);
        close(fd);
        return 0;
    }
    atomic_init(&conn->refcount, 1);
    atomic_init(&conn->closed, 0);
    conn->fd = fd;
    conn->wake_fd = wake_fd;
    conn->family = family;
    conn->default_ttl = get_default_ttl(fd, family);
    conn->window_clamped = clamped;

    jlong handle = conn_register(conn);
    if (handle == 0) {
        conn_release(conn);
        return 0;
    }
    return handle;
}

JNIEXPORT void JNICALL
Java_org_colgram_core_ColgramNativeDesync_nativeUnclampWindow(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    DesyncConn *conn = conn_acquire(handle);
    if (conn == NULL) return;
    if (!atomic_load_explicit(&conn->closed, memory_order_acquire) && conn->window_clamped && conn->fd >= 0) {
        int max_win = 1048576;
        setsockopt(conn->fd, IPPROTO_TCP, TCP_WINDOW_CLAMP, &max_win, sizeof(max_win));
        conn->window_clamped = 0;
    }
    conn_release(conn);
}

JNIEXPORT jint JNICALL
Java_org_colgram_core_ColgramNativeDesync_nativeSendDesync(JNIEnv *env, jclass clazz,
                                                           jlong handle,
                                                           jbyteArray jPayload, jint off, jint len,
                                                           jint split1, jint mode, jint fakeTtl,
                                                           jboolean cellular,
                                                           jbyteArray jFakePayload, jstring jTmpDir) {
    (void)clazz;
    if (jPayload == NULL || off < 0 || len <= 0) return -1;
    jsize arr_len = (*env)->GetArrayLength(env, jPayload);
    if (off + len > arr_len) return -1;

    DesyncConn *conn = conn_acquire(handle);
    if (conn == NULL) return -2;

    uint8_t *buf = (uint8_t *)malloc((size_t)len);
    if (buf == NULL) {
        conn_release(conn);
        return -3;
    }
    (*env)->GetByteArrayRegion(env, jPayload, off, len, (jbyte *)buf);

    uint8_t *fake_buf = NULL;
    size_t fake_buf_len = 0;
    if (jFakePayload != NULL) {
        jsize flen = (*env)->GetArrayLength(env, jFakePayload);
        if (flen > 0) {
            fake_buf = (uint8_t *)malloc((size_t)flen);
            if (fake_buf != NULL) {
                (*env)->GetByteArrayRegion(env, jFakePayload, 0, flen, (jbyte *)fake_buf);
                fake_buf_len = (size_t)flen;
            }
        }
    }

    const char *tmp_dir = NULL;
    if (jTmpDir != NULL) {
        tmp_dir = (*env)->GetStringUTFChars(env, jTmpDir, NULL);
    }

    size_t total = (size_t)len;
    size_t cut = (split1 > 0 && (size_t)split1 < total) ? (size_t)split1 : (total > 2 ? total / 2 : 1);
    size_t cut2 = 0;
    if (total - cut > 5 && buf[cut] == 0x16 && buf[cut + 1] == 0x03) {
        size_t rec2_len = (((size_t)(buf[cut + 3] & 0xFF)) << 8) | (size_t)(buf[cut + 4] & 0xFF);
        if (rec2_len > 0 && rec2_len <= total - cut - 5) {
            size_t candidate_cut2 = cut + 5 + rec2_len;
            if (candidate_cut2 > cut && candidate_cut2 + 5 < total
                    && buf[candidate_cut2] == 0x16 && buf[candidate_cut2 + 1] == 0x03) {
                cut2 = candidate_cut2;
            }
        }
    }
    int is_cell = cellular ? 1 : 0;
    int delay_us = is_cell ? 6000 : 2000;
    int rc = 0;

    if (total <= 2 || mode == MODE_PLAIN) {
        rc = send_all(conn, buf, total, 0);
    } else if (mode == MODE_FAKE_TTL || mode == MODE_FAKE_TTL_DISORDER) {
        int ttl = (fakeTtl >= 1 && fakeTtl <= MAX_SAFE_FAKE_TTL)
                ? (int)fakeTtl
                : (fakeTtl > MAX_SAFE_FAKE_TTL ? MAX_SAFE_FAKE_TTL : 3);
        rc = send_fake_with_ttl(conn, buf, total, cut, ttl, fake_buf, fake_buf_len, tmp_dir,
                                is_cell, mode == MODE_FAKE_TTL_DISORDER);
        if (rc == -10) {
            mode = MODE_MULTI_SPLIT_SNI;
            rc = 0;
        }
    }

    if (rc == 0 && mode != MODE_PLAIN && mode != MODE_FAKE_TTL && mode != MODE_FAKE_TTL_DISORDER) {
        if (mode == MODE_DISORDER) {
            if (set_ttl(conn->fd, conn->family, 1) == 0) {
                rc = send_all(conn, buf, cut, 0);
                if (rc == 0) wait_tx_pushed(conn, 1, is_cell);
                set_ttl(conn->fd, conn->family, conn->default_ttl);
                if (rc == 0) rc = send_tail_split(conn, buf, cut, cut2, total, delay_us);
            } else {
                rc = send_all(conn, buf, cut, 0);
                if (rc == 0) {
                    usleep(is_cell ? 12000 : 3000);
                    rc = send_tail_split(conn, buf, cut, cut2, total, delay_us);
                }
            }
        } else if (mode == MODE_DISORDER_BYTE1) {
            /* Exact ByeDPI -d1: drop ONLY byte 0 (0x16) at hop 1 so TSPU sees 0x03 0x01 first! */
            if (set_ttl(conn->fd, conn->family, 1) == 0) {
                rc = send_all(conn, buf, 1, 0);
                if (rc == 0) wait_tx_pushed(conn, 1, is_cell);
                set_ttl(conn->fd, conn->family, conn->default_ttl);
                if (rc == 0 && cut > 1) {
                    rc = send_all(conn, buf + 1, cut - 1, 0);
                    if (rc == 0) usleep(is_cell ? 8000 : 2500);
                }
                if (rc == 0) rc = send_tail_split(conn, buf, cut, cut2, total, delay_us);
            } else {
                rc = send_all(conn, buf, 1, 0);
                if (rc == 0 && cut > 1) {
                    usleep(is_cell ? 10000 : 2500);
                    rc = send_all(conn, buf + 1, cut - 1, 0);
                }
                if (rc == 0) {
                    usleep(is_cell ? 10000 : 2500);
                    rc = send_tail_split(conn, buf, cut, cut2, total, delay_us);
                }
            }
        } else if (mode == MODE_OOB_BYTE1_DISORDER) {
            /* Exact #1 Russian mobile ByeDPI: -o1 -d1 -r1+s (OOB + IP_TTL=1 at byte 1, then Rec1 & Rec2/Rec3) */
            if (set_ttl(conn->fd, conn->family, 1) == 0) {
                rc = send_oob_chunk(conn, buf, 1, (uint8_t)'a');
                if (rc == 0) wait_tx_pushed(conn, 1, is_cell);
                set_ttl(conn->fd, conn->family, conn->default_ttl);
                if (rc == 0 && cut > 1) {
                    rc = send_all(conn, buf + 1, cut - 1, 0);
                    if (rc == 0) usleep(is_cell ? 8000 : 2500);
                }
                if (rc == 0) rc = send_tail_split(conn, buf, cut, cut2, total, delay_us);
            } else {
                rc = send_oob_chunk(conn, buf, 1, (uint8_t)'a');
                if (rc == 0 && cut > 1) {
                    usleep(is_cell ? 10000 : 2500);
                    rc = send_all(conn, buf + 1, cut - 1, 0);
                }
                if (rc == 0) {
                    usleep(is_cell ? 10000 : 2500);
                    rc = send_tail_split(conn, buf, cut, cut2, total, delay_us);
                }
            }
        } else if (mode == MODE_OOB_DISORDER) {
            if (set_ttl(conn->fd, conn->family, 1) == 0) {
                rc = send_oob_chunk(conn, buf, cut, (uint8_t)'a');
                if (rc == 0) wait_tx_pushed(conn, 1, is_cell);
                set_ttl(conn->fd, conn->family, conn->default_ttl);
                if (rc == 0) rc = send_tail_split(conn, buf, cut, cut2, total, delay_us);
            } else {
                rc = send_oob_chunk(conn, buf, cut, (uint8_t)'a');
                if (rc == 0) {
                    usleep(is_cell ? 12000 : 3000);
                    rc = send_tail_split(conn, buf, cut, cut2, total, delay_us);
                }
            }
        } else if (mode == MODE_MSS_OOB_BYTE1) {
            rc = send_oob_chunk(conn, buf, 1, (uint8_t)'a');
            if (rc == 0 && cut > 1) {
                usleep((useconds_t)delay_us);
                rc = send_all(conn, buf + 1, cut - 1, 0);
            }
            if (rc == 0) {
                usleep((useconds_t)delay_us);
                rc = send_tail_split(conn, buf, cut, cut2, total, delay_us);
            }
        } else if (mode == MODE_MSS_SPLIT) {
            rc = send_all(conn, buf, cut, 0);
            if (rc == 0) {
                usleep(is_cell ? 6000 : 2500);
                rc = send_tail_split(conn, buf, cut, cut2, total, delay_us);
            }
        } else if (mode == MODE_OOB_BYTE1_ONLY) {
            /* Exact mobile ByeDPI -o1 -r1+s without IP_TTL=1: 0ms RTO, LTE-modem safe, normal SYN */
            rc = send_oob_chunk(conn, buf, 1, (uint8_t)'a');
            if (rc == 0 && cut > 1) {
                usleep((useconds_t)delay_us);
                rc = send_all(conn, buf + 1, cut - 1, 0);
            }
            if (rc == 0) {
                usleep((useconds_t)delay_us);
                rc = send_tail_split(conn, buf, cut, cut2, total, delay_us);
            }
        } else if (mode == MODE_SPLIT_BYTE1) {
            /* Exact ByeDPI -s1 -r1+s without IP_TTL=1 or MSG_OOB: pure kernel TCP 1B + SNI+1/SLD split */
            rc = send_all(conn, buf, 1, 0);
            if (rc == 0 && cut > 1) {
                usleep((useconds_t)delay_us);
                rc = send_all(conn, buf + 1, cut - 1, 0);
            }
            if (rc == 0) {
                usleep((useconds_t)delay_us);
                rc = send_tail_split(conn, buf, cut, cut2, total, delay_us);
            }
        } else if (mode == MODE_MULTI_SPLIT_SNI) {
            /* Carrier-safe multi-split across SNI (-s1 -s3+s -s6+s + -r1+s) + SLD midpoint:
             * ZERO MSG_OOB, ZERO IP_TTL=1, ZERO tiny MSS — safe across LTE CGNAT/PGWs. */
            rc = send_all(conn, buf, 1, 0);
            if (rc == 0 && cut > 1) {
                usleep((useconds_t)delay_us);
                rc = send_all(conn, buf + 1, cut - 1, 0);
            }
            if (rc == 0) {
                size_t pos = cut;
                size_t rec2_end = (cut2 > cut) ? cut2 : total;
                int has_rec2 = (pos + 6 < total && buf[pos] == 0x16 && buf[pos + 1] == 0x03);
                size_t hdr_skip = has_rec2 ? 5 : 0;
                size_t step1 = pos + hdr_skip + 2; /* ~ -s3+s */
                size_t step2 = pos + hdr_skip + 5; /* ~ -s6+s */
                if (step1 > pos && step1 < rec2_end) {
                    usleep((useconds_t)delay_us);
                    rc = send_all(conn, buf + pos, step1 - pos, 0);
                    pos = step1;
                }
                if (rc == 0 && step2 > pos && step2 < rec2_end) {
                    usleep((useconds_t)delay_us);
                    rc = send_all(conn, buf + pos, step2 - pos, 0);
                    pos = step2;
                }
                if (rc == 0 && pos < rec2_end) {
                    usleep((useconds_t)delay_us);
                    rc = send_all(conn, buf + pos, rec2_end - pos, 0);
                    pos = rec2_end;
                }
                if (rc == 0 && pos < total) {
                    usleep((useconds_t)delay_us);
                    rc = send_all(conn, buf + pos, total - pos, 0);
                }
            }
        } else {
            rc = send_all(conn, buf, total, 0);
        }
    }

    if (tmp_dir != NULL) (*env)->ReleaseStringUTFChars(env, jTmpDir, tmp_dir);
    free(fake_buf);
    free(buf);
    conn_release(conn);
    return (jint)rc;
}

/* Sends N tiny low-TTL UDP decoy datagrams (-Ku -a3) to confuse mobile UDP/STUN/voice DPI */
JNIEXPORT jint JNICALL
Java_org_colgram_core_ColgramNativeDesync_nativeSendUdpFakeBurst(JNIEnv *env, jclass clazz,
                                                                 jstring jIp, jint port,
                                                                 jint ttl, jint count,
                                                                 jobject protector) {
    (void)clazz;
    if (jIp == NULL || port <= 0 || port > 65535 || count <= 0) return -1;
    if (count > 6) count = 6;
    if (ttl < 1 || ttl > 12) ttl = 3;

    const char *ip_str = (*env)->GetStringUTFChars(env, jIp, NULL);
    if (ip_str == NULL) return -1;
    struct sockaddr_storage sa;
    socklen_t sa_len = 0;
    int family = AF_INET;
    int parse_rc = parse_ip_sockaddr(ip_str, (int)port, &sa, &sa_len, &family);
    (*env)->ReleaseStringUTFChars(env, jIp, ip_str);
    if (parse_rc != 0) return -1;

    int fd = socket(family, SOCK_DGRAM | SOCK_CLOEXEC, 0);
    if (fd < 0) return -1;
    if (!invoke_fd_protector(env, protector, fd)) {
        close(fd);
        return -1;
    }
    set_ttl(fd, family, (int)ttl);
    uint8_t decoy[40];
    for (int i = 0; i < (int)sizeof(decoy); i++) decoy[i] = (uint8_t)(0xA5 ^ (i * 17));
    int sent_ok = 0;
    for (int i = 0; i < (int)count; i++) {
        decoy[0] = (uint8_t)(0x40 + i);
        if (sendto(fd, decoy, sizeof(decoy), MSG_NOSIGNAL, (const struct sockaddr *)&sa, sa_len) > 0) {
            sent_ok++;
        }
        if (i + 1 < (int)count) usleep(1500);
    }
    close(fd);
    return sent_ok;
}

JNIEXPORT jint JNICALL
Java_org_colgram_core_ColgramNativeDesync_nativeRead(JNIEnv *env, jclass clazz,
                                                     jlong handle, jbyteArray jBuf,
                                                     jint off, jint len, jint timeoutMs) {
    (void)clazz;
    if (jBuf == NULL || off < 0 || len <= 0) return -3;
    jsize arr_len = (*env)->GetArrayLength(env, jBuf);
    if (off + len > arr_len) return -3;

    DesyncConn *conn = conn_acquire(handle);
    if (conn == NULL) return -2;

    size_t want = (size_t)len;
    if (want > 65536) want = 65536;
    uint8_t *tmp = (uint8_t *)malloc(want);
    if (tmp == NULL) {
        conn_release(conn);
        return -3;
    }

    int result = 0;
    while (1) {
        if (atomic_load_explicit(&conn->closed, memory_order_acquire)) {
            result = -2;
            break;
        }
        ssize_t n = recv(conn->fd, tmp, want, 0);
        if (n > 0) {
            (*env)->SetByteArrayRegion(env, jBuf, off, (jsize)n, (const jbyte *)tmp);
            result = (int)n;
            break;
        }
        if (n == 0) {
            result = 0;
            break;
        }
        if (errno == EINTR) continue;
        if (errno == EAGAIN || errno == EWOULDBLOCK) {
            int w = wait_fd_event(conn, POLLIN, timeoutMs > 0 ? (int)timeoutMs : -1);
            if (w == -1) {
                result = -1;
                break;
            }
            if (w <= 0) {
                result = -2;
                break;
            }
            continue;
        }
        result = -2;
        break;
    }

    free(tmp);
    conn_release(conn);
    return (jint)result;
}

JNIEXPORT jint JNICALL
Java_org_colgram_core_ColgramNativeDesync_nativeWrite(JNIEnv *env, jclass clazz,
                                                      jlong handle, jbyteArray jBuf,
                                                      jint off, jint len) {
    (void)clazz;
    if (jBuf == NULL || off < 0 || len < 0) return -3;
    if (len == 0) return 0;
    jsize arr_len = (*env)->GetArrayLength(env, jBuf);
    if (off + len > arr_len) return -3;

    DesyncConn *conn = conn_acquire(handle);
    if (conn == NULL) return -2;

    uint8_t *tmp = (uint8_t *)malloc((size_t)len);
    if (tmp == NULL) {
        conn_release(conn);
        return -3;
    }
    (*env)->GetByteArrayRegion(env, jBuf, off, len, (jbyte *)tmp);
    int rc = send_all(conn, tmp, (size_t)len, 0);
    free(tmp);
    conn_release(conn);
    return (jint)rc;
}

JNIEXPORT void JNICALL
Java_org_colgram_core_ColgramNativeDesync_nativeShutdownOutput(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    DesyncConn *conn = conn_acquire(handle);
    if (conn == NULL) return;
    if (!atomic_load_explicit(&conn->closed, memory_order_acquire) && conn->fd >= 0) {
        shutdown(conn->fd, SHUT_WR);
    }
    conn_release(conn);
}

JNIEXPORT void JNICALL
Java_org_colgram_core_ColgramNativeDesync_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    (void)env;
    (void)clazz;
    if (handle <= 0) return;
    uint32_t slot = (uint32_t)((uint64_t)handle & 0xFFFFu);
    uint32_t gen = (uint32_t)(((uint64_t)handle) >> 16);
    if (slot >= MAX_CONNS || gen == 0) return;

    DesyncConn *conn = NULL;
    pthread_mutex_lock(&g_table_lock);
    if (g_slots[slot].gen == gen && g_slots[slot].conn != NULL) {
        conn = g_slots[slot].conn;
        g_slots[slot].conn = NULL;
        g_slots[slot].gen++;
        if (g_slots[slot].gen == 0) g_slots[slot].gen = 1;
    }
    pthread_mutex_unlock(&g_table_lock);

    if (conn != NULL) {
        atomic_store_explicit(&conn->closed, 1, memory_order_release);
        if (conn->fd >= 0) {
            shutdown(conn->fd, SHUT_RDWR);
        }
        if (conn->wake_fd >= 0) {
            uint64_t one = 1;
            ssize_t ignored = write(conn->wake_fd, &one, sizeof(one));
            (void)ignored;
        }
        conn_release(conn);
    }
}
