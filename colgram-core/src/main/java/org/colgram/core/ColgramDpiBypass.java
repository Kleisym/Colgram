package org.colgram.core;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ColgramDpiBypass — Embedded Local SOCKS5 Proxy with Multi-Stage TCP Desync.
 * 
 * Evades TSPU / RKN DPI by:
 * 1. 1-Byte TCP Head Splitting with microsecond timing desync.
 * 2. Multi-port DC fallback: connects to 443, 80, 5222, 8443 if primary port is throttled.
 * 3. TLS ClientHello SNI Fragmentation to prevent deep packet inspection of domain signatures.
 * 4. Completely local (127.0.0.1:9876): zero third-party servers, maximum speed, complete privacy.
 */
public class ColgramDpiBypass {

    private static final String TAG = "ColgramDpiBypass";
    public static final int LOCAL_PORT = 9876;
    private static ServerSocket serverSocket;
    private static volatile boolean isRunning = false;
    private static volatile boolean startScheduled = false;
    private static Thread deferredStart;
    /** Keep the proxy listener out of the fragile application-startup window. */
    private static final long START_DELAY_MS = 8000L;
    private static final ExecutorService workerPool = Executors.newCachedThreadPool();

    public static synchronized void start() {
        if (isRunning || startScheduled) return;
        startScheduled = true;

        // Defer binding the local proxy socket out of the application-startup
        // window. Telegram's native MTProto stack (libtmessages.so ->
        // tgnet::ConnectionSocket) is still opening and closing its own sockets
        // during the first seconds of the process; adding a ServerSocket plus a
        // cached pool of worker sockets in that same window makes the two stacks
        // race over the process-wide descriptor table. bionic's fdsan then kills
        // the process with SIGABRT ("attempted to close file descriptor N ...
        // owned by SocketImpl") inside libtmessages.49.so.
        deferredStart = new Thread(() -> {
            try {
                Thread.sleep(START_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return;
            }
            startScheduled = false;
            startNow();
        }, "colgram-dpi-deferred");
        deferredStart.setDaemon(true);
        deferredStart.start();
    }

    /** Binds the proxy listener immediately. Prefer start(), which defers safely. */
    private static synchronized void startNow() {
        if (isRunning) return;
        isRunning = true;

        workerPool.execute(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress("127.0.0.1", LOCAL_PORT));
                Log.d(TAG, "ColgramDpiBypass started on 127.0.0.1:" + LOCAL_PORT);

                while (isRunning) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        workerPool.execute(() -> handleClient(clientSocket));
                    } catch (Exception e) {
                        if (!isRunning) break;
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "ColgramDpiBypass server error", e);
            }
        });
    }

    public static synchronized void stop() {
        isRunning = false;
        startScheduled = false;
        if (deferredStart != null) {
            deferredStart.interrupt();
            deferredStart = null;
        }
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
            serverSocket = null;
        }
        Log.d(TAG, "ColgramDpiBypass stopped");
    }

    public static boolean isRunning() {
        return isRunning;
    }

    private static void handleClient(Socket client) {
        Socket targetSocket = null;
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(15000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // 1. SOCKS5 greeting
            int ver = in.read();
            if (ver != 5) {
                closeQuietly(client);
                return;
            }
            int nmethods = in.read();
            if (nmethods <= 0) {
                closeQuietly(client);
                return;
            }
            byte[] methods = new byte[nmethods];
            int read = in.read(methods);
            if (read <= 0) {
                closeQuietly(client);
                return;
            }

            // Reply: NO AUTH REQUIRED
            out.write(new byte[]{0x05, 0x00});
            out.flush();

            // 2. SOCKS5 request
            int reqVer = in.read();
            int cmd = in.read();
            int rsv = in.read();
            int atyp = in.read();

            if (reqVer != 5 || cmd != 1) { // CONNECT only
                out.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
                closeQuietly(client);
                return;
            }

            String destHost;
            if (atyp == 1) { // IPv4
                byte[] ip = new byte[4];
                in.read(ip);
                destHost = (ip[0] & 0xFF) + "." + (ip[1] & 0xFF) + "." + (ip[2] & 0xFF) + "." + (ip[3] & 0xFF);
            } else if (atyp == 3) { // Domain
                int len = in.read();
                byte[] domainBytes = new byte[len];
                in.read(domainBytes);
                destHost = new String(domainBytes, StandardCharsets.UTF_8);
            } else if (atyp == 4) { // IPv6
                byte[] ip6 = new byte[16];
                in.read(ip6);
                destHost = InetAddress.getByAddress(ip6).getHostAddress();
            } else {
                closeQuietly(client);
                return;
            }

            int destPort = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);

            // 3. Connect to destination with multi-port fallback & TCP desync
            targetSocket = establishConnection(destHost, destPort);
            if (targetSocket == null) {
                Log.w(TAG, "Failed to connect to target: " + destHost + ":" + destPort);
                out.write(new byte[]{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
                closeQuietly(client);
                return;
            }

            // SOCKS5 success reply
            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            out.flush();

            client.setSoTimeout(0);
            targetSocket.setSoTimeout(0);

            // 4. Pipe traffic with TCP Desync (1-byte head split)
            pipeWithAdvancedDesync(client, targetSocket);

        } catch (Exception e) {
            closeQuietly(client);
            closeQuietly(targetSocket);
        }
    }

    /**
     * Connects to target host with multi-port fallback:
     * If port 443 is blocked/throttled by TSPU, automatically attempts 80, 5222, 8443.
     */
    private static Socket establishConnection(String host, int port) {
        int[] candidatePorts;
        if (port == 443) {
            candidatePorts = new int[]{443, 80, 5222, 8443};
        } else {
            candidatePorts = new int[]{port};
        }

        for (int p : candidatePorts) {
            try {
                Socket directSocket = new Socket();
                directSocket.setTcpNoDelay(true);
                directSocket.connect(new InetSocketAddress(host, p), 3000);
                return directSocket;
            } catch (Exception ignored) {}
        }
        return null;
    }

    /**
     * Advanced TCP Desync:
     * 1. 1-byte head split: sends the first byte in a separate TCP packet.
     * 2. 3ms delay to desynchronize TSPU DPI state machines.
     * 3. Sends the remainder of the handshake.
     */
    private static void pipeWithAdvancedDesync(final Socket client, final Socket dest) {
        // Client -> Target (Desynced)
        workerPool.execute(() -> {
            try {
                InputStream cin = client.getInputStream();
                OutputStream dout = dest.getOutputStream();
                byte[] buffer = new byte[16384];
                boolean firstPacket = true;
                int len;

                while ((len = cin.read(buffer)) != -1) {
                    if (firstPacket) {
                        firstPacket = false;
                        if (len > 5 && buffer[0] == 0x16 && buffer[1] == 0x03) {
                            // TLS ClientHello: Split across record header (5 bytes)
                            dout.write(buffer, 0, 5);
                            dout.flush();
                            try { Thread.sleep(3); } catch (Exception ignored) {}
                            int mid = 5 + Math.min(20, len - 5);
                            dout.write(buffer, 5, mid - 5);
                            dout.flush();
                            try { Thread.sleep(3); } catch (Exception ignored) {}
                            dout.write(buffer, mid, len - mid);
                            dout.flush();
                        } else if (len > 1) {
                            // MTProto handshake: 1-byte split
                            dout.write(buffer, 0, 1);
                            dout.flush();
                            try { Thread.sleep(3); } catch (Exception ignored) {}
                            dout.write(buffer, 1, len - 1);
                            dout.flush();
                        } else {
                            dout.write(buffer, 0, len);
                            dout.flush();
                        }
                    } else {
                        dout.write(buffer, 0, len);
                        dout.flush();
                    }
                }
            } catch (Exception ignored) {
            } finally {
                closeQuietly(client);
                closeQuietly(dest);
            }
        });

        // Target -> Client (Direct)
        workerPool.execute(() -> {
            try {
                InputStream din = dest.getInputStream();
                OutputStream cout = client.getOutputStream();
                byte[] buffer = new byte[16384];
                int len;

                while ((len = din.read(buffer)) != -1) {
                    cout.write(buffer, 0, len);
                    cout.flush();
                }
            } catch (Exception ignored) {
            } finally {
                closeQuietly(client);
                closeQuietly(dest);
            }
        });
    }

    private static void closeQuietly(Socket s) {
        if (s != null) {
            try { s.close(); } catch (Exception ignored) {}
        }
    }
}
