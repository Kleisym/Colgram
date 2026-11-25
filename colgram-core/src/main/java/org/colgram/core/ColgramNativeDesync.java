package org.colgram.core;

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/**
 * ColgramNativeDesync — Unrooted ByeDPI-class Linux kernel socket desync engine (`libcolgramdesync.so`).
 *
 * All native socket descriptors are created, protected via VpnService.protect(int fd), manipulated
 * via setsockopt(IP_TTL / IPV6_UNICAST_HOPS / TCP_MAXSEG / TCP_WINDOW_CLAMP), getsockopt(TCP_INFO),
 * send(MSG_OOB), and LTE-safe sendfile()+mmap(), and closed exclusively inside C with
 * generation-tagged reference-counted handles — zero ParcelFileDescriptor.fromSocket() and zero
 * Android bionic fdsan risk.
 */
public final class ColgramNativeDesync {

    private static final String TAG = "ColgramNativeDesync";

    /* Kept strictly synchronized with colgram_desync.c */
    public static final int MODE_PLAIN              = 0;
    public static final int MODE_DISORDER           = 1;
    public static final int MODE_DISORDER_BYTE1     = 2;
    public static final int MODE_OOB_BYTE1_DISORDER = 3;
    public static final int MODE_OOB_DISORDER       = 4;
    public static final int MODE_FAKE_TTL           = 5;
    public static final int MODE_MSS_SPLIT          = 6;
    public static final int MODE_MSS_OOB_BYTE1      = 7;
    public static final int MODE_FAKE_TTL_DISORDER  = 8;
    public static final int MODE_OOB_BYTE1_ONLY     = 9;
    public static final int MODE_SPLIT_BYTE1        = 10;
    public static final int MODE_MULTI_SPLIT_SNI    = 11;
    public static final int MAX_SAFE_FAKE_TTL       = 12;

    private static volatile SSLSocketFactory cachedEngineSslFactory;

    /** Callback invoked synchronously by nativeConnect()/nativeSendUdpFakeBurst() before connect/send. */
    public interface FdProtector {
        boolean protectFd(int fd);
    }

    private static final boolean LOADED;
    private static volatile String tempDirPath;
    private static volatile int selfTestFlags = -1;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("colgramdesync");
            ok = true;
        } catch (Throwable ignored) {
            // Host JVM tests or stripped builds gracefully fall back to Java desync strategies.
        }
        LOADED = ok;
    }

    private ColgramNativeDesync() {}

    public static boolean isAvailable() {
        return LOADED;
    }

    public static void setTempDir(String path) {
        if (path != null && !path.isEmpty()) {
            tempDirPath = path;
        }
    }

    public static int runSelfTest() {
        if (!LOADED) return 0;
        int cached = selfTestFlags;
        if (cached >= 0) return cached;
        try {
            int flags = nativeSelfTest(tempDirPath);
            selfTestFlags = flags;
            Log.i(TAG, "libcolgramdesync ready: flags=0x" + Integer.toHexString(flags));
            return flags;
        } catch (Throwable t) {
            Log.w(TAG, "nativeSelfTest failed: " + t);
            return 0;
        }
    }

    /**
     * Opens a native non-blocking TCP socket to {@code ipLiteral:port} (IPv4 or IPv6), protecting
     * its raw fd before connect() when {@code protector != null} and optionally clamping TCP MSS.
     */
    public static NativeSocket connect(String ipLiteral, int port, int timeoutMs,
                                       int mssClamp, FdProtector protector) throws IOException {
        return connect(ipLiteral, port, timeoutMs, mssClamp, 0, protector);
    }

    /**
     * Opens a native non-blocking TCP socket to {@code ipLiteral:port} (IPv4 or IPv6), with
     * optional {@code TCP_MAXSEG} ({@code mssClamp}) and {@code TCP_WINDOW_CLAMP}
     * ({@code windowClamp}) before connect(). Call {@link NativeSocket#unclampWindow()} after
     * ServerHello arrives to restore full 4G/Wi-Fi throughput.
     */
    /**
     * Returns an {@link SSLSocketFactory} with Android Conscrypt engine-socket mode explicitly
     * enabled ({@code setUseEngineSocket(true)}). On Android 7–10, OEM Conscrypt builds, and
     * GMS ProviderInstaller environments where {@code useEngineSocket} defaults to {@code false},
     * Conscrypt otherwise creates a {@code ConscryptFileDescriptorSocket} that inspects the
     * underlying {@code PlainSocketImpl} FD and crashes with {@code SocketException: Socket is closed}
     * on custom {@link Socket} wrappers ({@link NativeSocket}, {@link FirstTlsNativeSocket},
     * {@code FragmentedHelloSocket}, {@code SniFragmentingSocket}).
     */
    public static SSLSocketFactory createEngineSslSocketFactory() {
        SSLSocketFactory existing = cachedEngineSslFactory;
        if (existing != null) return existing;
        synchronized (ColgramNativeDesync.class) {
            if (cachedEngineSslFactory != null) return cachedEngineSslFactory;
            SSLSocketFactory factory;
            try {
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, null, null);
                factory = ctx.getSocketFactory();
            } catch (Throwable t) {
                factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            }
            configureConscryptEngineSocket(factory);
            cachedEngineSslFactory = factory;
            return factory;
        }
    }

    private static void configureConscryptEngineSocket(SSLSocketFactory factory) {
        if (factory == null) return;
        // 1. Direct instance method on OpenSSLSocketFactoryImpl (AOSP / GMS / bundled Conscrypt)
        try {
            Method m = findDeclaredMethod(factory.getClass(), "setUseEngineSocket", boolean.class);
            if (m != null) {
                m.setAccessible(true);
                m.invoke(factory, Boolean.TRUE);
                return;
            }
        } catch (Throwable ignored) {
        }
        // 2. Static Conscrypt.setUseEngineSocket(SSLSocketFactory, boolean)
        String[] conscryptClasses = {
                "com.android.org.conscrypt.Conscrypt",
                "org.conscrypt.Conscrypt",
                "com.google.android.gms.org.conscrypt.Conscrypt"
        };
        for (String clsName : conscryptClasses) {
            try {
                Class<?> c = Class.forName(clsName);
                Method m = c.getMethod("setUseEngineSocket", SSLSocketFactory.class, boolean.class);
                m.invoke(null, factory, Boolean.TRUE);
                return;
            } catch (Throwable ignored) {
            }
        }
        // 3. Direct boolean field fallback on OpenSSLSocketFactoryImpl
        try {
            Field f = findDeclaredField(factory.getClass(), "useEngineSocket");
            if (f != null && (f.getType() == boolean.class || f.getType() == Boolean.class)) {
                f.setAccessible(true);
                f.setBoolean(factory, true);
            }
        } catch (Throwable ignored) {
        }
    }

    private static Method findDeclaredMethod(Class<?> cls, String name, Class<?>... params) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredMethod(name, params);
            } catch (NoSuchMethodException ignored) {
            }
        }
        return null;
    }

    private static Field findDeclaredField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    public static NativeSocket connect(String ipLiteral, int port, int timeoutMs,
                                       int mssClamp, int windowClamp,
                                       FdProtector protector) throws IOException {
        if (!LOADED) {
            throw new IOException("libcolgramdesync not loaded");
        }
        if (ipLiteral == null || ipLiteral.isEmpty() || port <= 0 || port > 65535) {
            throw new IOException("Invalid native target " + ipLiteral + ":" + port);
        }
        int safeMss = (mssClamp > 0 && mssClamp < 88) ? 88 : mssClamp;
        long handle = nativeConnect(ipLiteral, port, timeoutMs > 0 ? timeoutMs : 5000,
                safeMss, windowClamp, protector);
        if (handle <= 0L) {
            throw new IOException("Native connect failed to " + ipLiteral + ":" + port);
        }
        InetAddress addr;
        try {
            addr = InetAddress.getByName(ipLiteral);
        } catch (Throwable ignored) {
            addr = null;
        }
        return new NativeSocket(handle, ipLiteral, addr, port);
    }

    /**
     * Sends {@code count} tiny low-TTL UDP decoy datagrams ({@code -Ku -aN}, IPv4 or IPv6) to
     * {@code ipLiteral:port} to confuse mobile UDP/STUN/call DPI before the real stream begins.
     */
    public static int sendUdpFakeBurst(String ipLiteral, int port, int ttl, int count,
                                       FdProtector protector) {
        if (!LOADED || ipLiteral == null || ipLiteral.isEmpty() || port <= 0 || port > 65535 || count <= 0) {
            return 0;
        }
        try {
            int sent = nativeSendUdpFakeBurst(ipLiteral, port, ttl, count, protector);
            return Math.max(0, sent);
        } catch (Throwable ignored) {
            return 0;
        }
    }

    /**
     * Wraps a connected {@link NativeSocket} so the very first TLS ClientHello written by Java's
     * {@code SSLSocket} (e.g. Telegram WSS bridge or DoH resolver) is transmitted through native
     * ByeDPI desync (disorder / fake TTL / byte-1 OOB / TLS record split / MSS clamp).
     */
    public static Socket wrapForFirstTlsHello(final NativeSocket base, final int nativeMode,
                                              final int fakeTtl, final boolean tlsRecordSplit,
                                              final int decoyVariant) {
        return wrapForFirstTlsHello(base, nativeMode, fakeTtl, tlsRecordSplit, false,
                ColgramDpiBypass.isCellularNetwork(), decoyVariant);
    }

    public static Socket wrapForFirstTlsHello(final NativeSocket base, final int nativeMode,
                                              final int fakeTtl, final boolean tlsRecordSplit,
                                              final boolean sniPlusOne, final boolean cellular,
                                              final int decoyVariant) {
        return new FirstTlsNativeSocket(base, nativeMode, fakeTtl, tlsRecordSplit, sniPlusOne, cellular, decoyVariant);
    }

    /**
     * Builds the byte payload (optionally split into 2 RFC-valid TLS records across SNI midpoint
     * or SNI+1) and computes the exact split offset for native kernel desync.
     */
    static PreparedDesync prepareTlsOrRawPayload(byte[] data, int off, int len,
                                                 boolean tlsRecordSplit, boolean needFake,
                                                 int decoyVariant) {
        return prepareTlsOrRawPayload(data, off, len, tlsRecordSplit, false, needFake, decoyVariant);
    }

    static PreparedDesync prepareTlsOrRawPayload(byte[] data, int off, int len,
                                                 boolean tlsRecordSplit, boolean sniPlusOne,
                                                 boolean needFake, int decoyVariant) {
        byte[] work;
        if (off == 0 && len == data.length) {
            work = data;
        } else {
            work = new byte[len];
            System.arraycopy(data, off, work, 0, len);
        }
        int split1 = Math.max(1, Math.min(work.length - 1, work.length / 2));
        boolean looksTls = ColgramTlsMimic.looksLikeClientHello(work);

        if (looksTls) {
            if (tlsRecordSplit) {
                byte[][] recs = sniPlusOne
                        ? ColgramTlsMimic.splitTlsRecordAtSniAndSld(work, 1)
                        : ColgramTlsMimic.splitTlsRecordAtSni(work, 2);
                if (recs != null && recs.length >= 2) {
                    int totalLen = 0;
                    for (byte[] rec : recs) totalLen += rec.length;
                    byte[] combined = new byte[totalLen];
                    int pos = 0;
                    for (byte[] rec : recs) {
                        System.arraycopy(rec, 0, combined, pos, rec.length);
                        pos += rec.length;
                    }
                    work = combined;
                    split1 = recs[0].length;
                } else {
                    int[] sni = ColgramTlsMimic.findTlsSniSpan(work, work.length);
                    if (sni != null) {
                        split1 = sniPlusOne
                                ? Math.min(work.length - 1, sni[0] + 1)
                                : ColgramTlsMimic.findSldMidpoint(work, sni[0], sni[1]);
                    }
                }
            } else {
                int[] sni = ColgramTlsMimic.findTlsSniSpan(work, work.length);
                if (sni != null) {
                    split1 = sniPlusOne
                            ? Math.min(work.length - 1, sni[0] + 1)
                            : ColgramTlsMimic.findSldMidpoint(work, sni[0], sni[1]);
                }
            }
        } else {
            int httpCut = findHttpHostOffset(work);
            if (httpCut > 1 && httpCut < work.length) {
                work = ColgramTlsMimic.mangleHttpHostHeader(work, work.length);
                split1 = httpCut;
            } else if (work.length >= 64) {
                // MTProto obfuscated2 or binary handshake: cut early so first 64B header is split/disordered
                split1 = 16;
            }
        }

        if (split1 <= 0 || split1 >= work.length) {
            split1 = Math.max(1, work.length / 2);
        }

        byte[] fakePayload = null;
        if (needFake && split1 >= 16) {
            fakePayload = ColgramTlsMimic.buildFakeWhitelistedClientHello(split1, decoyVariant);
            if (fakePayload.length > split1) {
                // Always trim decoy to split1 so we never shift split1 off the TLS record / SNI boundary.
                byte[] trimmed = new byte[split1];
                System.arraycopy(fakePayload, 0, trimmed, 0, split1);
                fakePayload = trimmed;
            }
        }
        return new PreparedDesync(work, split1, fakePayload);
    }

    private static int findHttpHostOffset(byte[] buf) {
        if (buf == null || buf.length < 16) return -1;
        byte b0 = buf[0];
        if (b0 != 'G' && b0 != 'P' && b0 != 'H' && b0 != 'C' && b0 != 'O' && b0 != 'D') return -1;
        for (int i = 0; i < buf.length - 8; i++) {
            if (buf[i] == '\n'
                    && (buf[i + 1] == 'H' || buf[i + 1] == 'h')
                    && (buf[i + 2] == 'o' || buf[i + 2] == 'O')
                    && (buf[i + 3] == 's' || buf[i + 3] == 'S')
                    && (buf[i + 4] == 't' || buf[i + 4] == 'T')
                    && buf[i + 5] == ':') {
                return Math.min(buf.length - 1, i + 8);
            }
        }
        return -1;
    }

    static final class PreparedDesync {
        final byte[] payload;
        final int split1;
        final byte[] fakePayload;

        PreparedDesync(byte[] payload, int split1, byte[] fakePayload) {
            this.payload = payload;
            this.split1 = split1;
            this.fakePayload = fakePayload;
        }
    }

    /**
     * Java {@link Socket} facade over a C-owned native TCP handle. Never allocates or wraps a
     * Java {@code FileDescriptor}, eliminating any Android bionic fdsan ownership collision.
     */
    public static final class NativeSocket extends Socket {
        private final long handle;
        private final String remoteIp;
        private final InetAddress remoteAddr;
        private final int remotePort;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile boolean inputShutdown = false;
        private volatile boolean outputShutdown = false;
        private volatile int soTimeoutMs = 0;
        private volatile int soLinger = -1;
        private volatile boolean reuseAddress = false;
        private volatile boolean oobInline = false;
        private final InputStream in;
        private final OutputStream out;

        NativeSocket(long handle, String remoteIp, InetAddress remoteAddr, int remotePort) {
            this.handle = handle;
            this.remoteIp = remoteIp;
            this.remoteAddr = remoteAddr;
            this.remotePort = remotePort;
            this.in = new NativeInputStream();
            this.out = new NativeOutputStream();
        }

        public String getRemoteIp() {
            return remoteIp;
        }

        /** Restores full TCP receive window after handshake when TCP_WINDOW_CLAMP was enabled. */
        public void unclampWindow() {
            if (!closed.get()) {
                nativeUnclampWindow(handle);
            }
        }

        /** Sends the initial packet using native kernel desync (disorder / fake TTL / OOB / MSS). */
        public void sendDesynced(byte[] payload, int off, int len,
                                 int nativeMode, int fakeTtl, boolean tlsRecordSplit,
                                 int decoyVariant) throws IOException {
            sendDesynced(payload, off, len, nativeMode, fakeTtl, tlsRecordSplit, false,
                    ColgramDpiBypass.isCellularNetwork(), decoyVariant);
        }

        public void sendDesynced(byte[] payload, int off, int len,
                                 int nativeMode, int fakeTtl, boolean tlsRecordSplit,
                                 boolean sniPlusOne, boolean cellular,
                                 int decoyVariant) throws IOException {
            if (closed.get()) throw new IOException("NativeSocket closed");
            boolean needFake = (nativeMode == MODE_FAKE_TTL || nativeMode == MODE_FAKE_TTL_DISORDER);
            int safeTtl = needFake ? Math.max(1, Math.min(MAX_SAFE_FAKE_TTL, fakeTtl > 0 ? fakeTtl : 3)) : fakeTtl;
            PreparedDesync prep = prepareTlsOrRawPayload(
                    payload, off, len, tlsRecordSplit, sniPlusOne, needFake, decoyVariant);
            int rc = nativeSendDesync(handle, prep.payload, 0, prep.payload.length,
                    prep.split1, nativeMode, safeTtl, cellular, prep.fakePayload, tempDirPath);
            if (rc != 0) {
                throw new IOException("nativeSendDesync failed rc=" + rc + " mode=" + nativeMode);
            }
        }

        /** Reads first response bytes with an explicit timeout without mutating long-lived soTimeout. */
        public int readWithTimeout(byte[] buf, int off, int len, int timeoutMs) throws IOException {
            if (closed.get()) throw new IOException("NativeSocket closed");
            int rc = nativeRead(handle, buf, off, len, timeoutMs);
            if (rc > 0) return rc;
            if (rc == 0) return -1;
            if (rc == -1) throw new SocketTimeoutException("Native read timed out (" + timeoutMs + "ms)");
            throw new IOException("Native socket reset/closed (rc=" + rc + ")");
        }

        @Override public InputStream getInputStream() throws IOException {
            if (closed.get()) throw new SocketException("Socket closed");
            return in;
        }

        @Override public OutputStream getOutputStream() throws IOException {
            if (closed.get()) throw new SocketException("Socket closed");
            return out;
        }

        @Override public void setSoTimeout(int timeout) { this.soTimeoutMs = Math.max(0, timeout); }
        @Override public int getSoTimeout() { return this.soTimeoutMs; }
        @Override public void setTcpNoDelay(boolean on) { /* Always enabled in nativeConnect */ }
        @Override public boolean getTcpNoDelay() { return true; }
        @Override public void setSendBufferSize(int size) { /* Managed by native kernel socket */ }
        @Override public int getSendBufferSize() { return 65536; }
        @Override public void setReceiveBufferSize(int size) { /* Managed by native kernel socket */ }
        @Override public int getReceiveBufferSize() { return 65536; }
        @Override public void setKeepAlive(boolean on) { /* Always enabled in nativeConnect */ }
        @Override public boolean getKeepAlive() { return true; }
        @Override public void setOOBInline(boolean on) { this.oobInline = on; }
        @Override public boolean getOOBInline() { return this.oobInline; }
        @Override public void setSoLinger(boolean on, int linger) { this.soLinger = on ? Math.max(0, linger) : -1; }
        @Override public int getSoLinger() { return this.soLinger; }
        @Override public void setReuseAddress(boolean on) { this.reuseAddress = on; }
        @Override public boolean getReuseAddress() { return this.reuseAddress; }
        @Override public void setTrafficClass(int tc) { /* Managed by native kernel socket */ }
        @Override public int getTrafficClass() { return 0; }
        @Override public boolean isConnected() { return !closed.get(); }
        @Override public boolean isBound() { return !closed.get(); }
        @Override public boolean isClosed() { return closed.get(); }
        @Override public boolean isInputShutdown() { return closed.get() || inputShutdown; }
        @Override public boolean isOutputShutdown() { return closed.get() || outputShutdown; }
        @Override public InetAddress getInetAddress() { return remoteAddr; }
        @Override public int getPort() { return remotePort; }
        @Override public SocketAddress getRemoteSocketAddress() {
            return remoteAddr != null ? new InetSocketAddress(remoteAddr, remotePort)
                    : InetSocketAddress.createUnresolved(remoteIp, remotePort);
        }
        @Override public InetAddress getLocalAddress() { return InetAddress.getLoopbackAddress(); }
        @Override public int getLocalPort() { return 0; }
        @Override public SocketAddress getLocalSocketAddress() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);
        }

        @Override
        public void shutdownInput() {
            inputShutdown = true;
        }

        @Override
        public void shutdownOutput() {
            if (!closed.get() && !outputShutdown) {
                outputShutdown = true;
                nativeShutdownOutput(handle);
            }
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                nativeClose(handle);
            }
        }

        private final class NativeInputStream extends InputStream {
            private final byte[] single = new byte[1];

            @Override
            public int read() throws IOException {
                int n = read(single, 0, 1);
                return n < 0 ? -1 : (single[0] & 0xFF);
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (len == 0) return 0;
                if (closed.get()) return -1;
                int rc = nativeRead(handle, b, off, len, soTimeoutMs);
                if (rc > 0) return rc;
                if (rc == 0) return -1;
                if (rc == -1) throw new SocketTimeoutException("Native read timed out");
                throw new IOException("Native read error rc=" + rc);
            }

            @Override
            public void close() {
                NativeSocket.this.close();
            }
        }

        private final class NativeOutputStream extends OutputStream {
            private final byte[] single = new byte[1];

            @Override
            public void write(int b) throws IOException {
                single[0] = (byte) b;
                write(single, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (len == 0) return;
                if (closed.get()) throw new IOException("NativeSocket closed");
                int rc = nativeWrite(handle, b, off, len);
                if (rc != 0) throw new IOException("Native write error rc=" + rc);
            }

            @Override
            public void close() {
                NativeSocket.this.close();
            }
        }
    }

    /** Transparent wrapper for Java SSLSocket that desyncs only the initial TLS ClientHello natively. */
    private static final class FirstTlsNativeSocket extends Socket {
        private final NativeSocket base;
        private final int nativeMode;
        private final int fakeTtl;
        private final boolean tlsRecordSplit;
        private final boolean sniPlusOne;
        private final boolean cellular;
        private final int decoyVariant;
        private volatile boolean handshakeDone;
        private volatile OutputStream wrappedOut;

        FirstTlsNativeSocket(NativeSocket base, int nativeMode, int fakeTtl,
                             boolean tlsRecordSplit, boolean sniPlusOne,
                             boolean cellular, int decoyVariant) {
            this.base = base;
            this.nativeMode = nativeMode;
            this.fakeTtl = fakeTtl;
            this.tlsRecordSplit = tlsRecordSplit;
            this.sniPlusOne = sniPlusOne;
            this.cellular = cellular;
            this.decoyVariant = decoyVariant;
        }

        @Override public InputStream getInputStream() throws IOException { return base.getInputStream(); }
        @Override public void setSoTimeout(int timeout) { base.setSoTimeout(timeout); }
        @Override public int getSoTimeout() { return base.getSoTimeout(); }
        @Override public void setTcpNoDelay(boolean on) { base.setTcpNoDelay(on); }
        @Override public boolean getTcpNoDelay() { return base.getTcpNoDelay(); }
        @Override public void setSendBufferSize(int size) { base.setSendBufferSize(size); }
        @Override public int getSendBufferSize() { return base.getSendBufferSize(); }
        @Override public void setReceiveBufferSize(int size) { base.setReceiveBufferSize(size); }
        @Override public int getReceiveBufferSize() { return base.getReceiveBufferSize(); }
        @Override public void setKeepAlive(boolean on) { base.setKeepAlive(on); }
        @Override public boolean getKeepAlive() { return base.getKeepAlive(); }
        @Override public void setOOBInline(boolean on) { base.setOOBInline(on); }
        @Override public boolean getOOBInline() { return base.getOOBInline(); }
        @Override public void setSoLinger(boolean on, int linger) { base.setSoLinger(on, linger); }
        @Override public int getSoLinger() { return base.getSoLinger(); }
        @Override public void setReuseAddress(boolean on) { base.setReuseAddress(on); }
        @Override public boolean getReuseAddress() { return base.getReuseAddress(); }
        @Override public void setTrafficClass(int tc) { base.setTrafficClass(tc); }
        @Override public int getTrafficClass() { return base.getTrafficClass(); }
        @Override public boolean isConnected() { return base.isConnected(); }
        @Override public boolean isBound() { return base.isBound(); }
        @Override public boolean isClosed() { return base.isClosed(); }
        @Override public boolean isInputShutdown() { return base.isInputShutdown(); }
        @Override public boolean isOutputShutdown() { return base.isOutputShutdown(); }
        @Override public void shutdownInput() { base.shutdownInput(); }
        @Override public void shutdownOutput() { base.shutdownOutput(); }
        @Override public InetAddress getInetAddress() { return base.getInetAddress(); }
        @Override public int getPort() { return base.getPort(); }
        @Override public SocketAddress getRemoteSocketAddress() { return base.getRemoteSocketAddress(); }
        @Override public InetAddress getLocalAddress() { return base.getLocalAddress(); }
        @Override public int getLocalPort() { return base.getLocalPort(); }
        @Override public SocketAddress getLocalSocketAddress() { return base.getLocalSocketAddress(); }
        @Override public void close() { base.close(); }

        @Override
        public OutputStream getOutputStream() throws IOException {
            if (handshakeDone) return base.getOutputStream();
            OutputStream existing = wrappedOut;
            if (existing != null) return existing;
            synchronized (this) {
                if (handshakeDone) return base.getOutputStream();
                if (wrappedOut != null) return wrappedOut;
                final OutputStream raw = base.getOutputStream();
                OutputStream fresh = new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        if (handshakeDone) {
                            raw.write(b);
                            return;
                        }
                        writeSingleLocked(b);
                    }

                    @Override
                    public void write(byte[] b, int off, int len) throws IOException {
                        if (handshakeDone) {
                            raw.write(b, off, len);
                            return;
                        }
                        writeFirstLocked(b, off, len);
                    }

                    private synchronized void writeSingleLocked(int b) throws IOException {
                        if (handshakeDone) {
                            raw.write(b);
                            return;
                        }
                        try {
                            raw.write(b);
                        } finally {
                            handshakeDone = true;
                        }
                    }

                    private synchronized void writeFirstLocked(byte[] b, int off, int len) throws IOException {
                        if (handshakeDone) {
                            raw.write(b, off, len);
                            return;
                        }
                        try {
                            if (len >= 11 && (b[off] & 0xFF) == 0x16 && b[off + 1] == 0x03
                                    && (b[off + 5] & 0xFF) == 0x01) {
                                base.sendDesynced(b, off, len, nativeMode, fakeTtl, tlsRecordSplit,
                                        sniPlusOne, cellular, decoyVariant);
                                return;
                            }
                            raw.write(b, off, len);
                        } finally {
                            handshakeDone = true;
                        }
                    }

                    @Override public void flush() throws IOException { raw.flush(); }
                    @Override public void close() throws IOException { base.close(); }
                };
                wrappedOut = fresh;
                return fresh;
            }
        }
    }

    private static native int nativeSelfTest(String tmpDir);
    private static native long nativeConnect(String ipLiteral, int port, int timeoutMs,
                                             int mssClamp, int windowClamp, FdProtector protector);
    private static native void nativeUnclampWindow(long handle);
    private static native int nativeSendDesync(long handle, byte[] payload, int off, int len,
                                               int split1, int mode, int fakeTtl, boolean cellular,
                                               byte[] fakePayload, String tmpDir);
    private static native int nativeSendUdpFakeBurst(String ipLiteral, int port, int ttl,
                                                     int count, FdProtector protector);
    private static native int nativeRead(long handle, byte[] buf, int off, int len, int timeoutMs);
    private static native int nativeWrite(long handle, byte[] buf, int off, int len);
    private static native void nativeShutdownOutput(long handle);
    private static native void nativeClose(long handle);
}
