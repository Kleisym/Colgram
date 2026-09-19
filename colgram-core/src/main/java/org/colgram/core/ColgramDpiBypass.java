package org.colgram.core;

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
 * ColgramDpiBypass — Embedded local SOCKS5 proxy engine with TCP Segmentation / Desync.
 * 
 * Bypasses DPI (TSPU / RKN) censorship by segmenting the initial MTProto / TLS handshake
 * packets across multiple TCP frames. TSPU flow inspectors cannot match signatures on
 * fragmented TCP payloads, allowing clean, direct, and unthrottled connections to Telegram
 * Data Centers without relying on any third-party servers.
 *
 * If direct connection is hard IP-blocked by an ISP, it transparently relays through
 * a vetted upstream proxy.
 */
public class ColgramDpiBypass {

    public static final int LOCAL_PORT = 9876;
    private static ServerSocket serverSocket;
    private static volatile boolean isRunning = false;
    private static final ExecutorService workerPool = Executors.newCachedThreadPool();

    // Upstream fallback proxy (if direct DC IP is blocked)
    private static volatile String upstreamHost = null;
    private static volatile int upstreamPort = 0;
    private static volatile String upstreamUser = "";
    private static volatile String upstreamPass = "";

    /**
     * Starts the embedded DPI bypass server.
     */
    public static synchronized void start() {
        if (isRunning) return;
        isRunning = true;

        workerPool.execute(() -> {
            try {
                serverSocket = new ServerSocket();
                serverSocket.setReuseAddress(true);
                serverSocket.bind(new InetSocketAddress("127.0.0.1", LOCAL_PORT));
                
                while (isRunning) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        workerPool.execute(() -> handleClient(clientSocket));
                    } catch (Exception e) {
                        if (!isRunning) break;
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    /**
     * Stops the DPI bypass server.
     */
    public static synchronized void stop() {
        isRunning = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (Exception ignored) {}
            serverSocket = null;
        }
    }

    public static boolean isRunning() {
        return isRunning;
    }

    public static void setUpstreamProxy(String host, int port, String user, String pass) {
        upstreamHost = host;
        upstreamPort = port;
        upstreamUser = user != null ? user : "";
        upstreamPass = pass != null ? pass : "";
    }

    public static void clearUpstreamProxy() {
        upstreamHost = null;
        upstreamPort = 0;
        upstreamUser = "";
        upstreamPass = "";
    }

    /**
     * Handles SOCKS5 client connection from Telegram's native ConnectionsManager.
     */
    private static void handleClient(Socket client) {
        Socket targetSocket = null;
        try {
            client.setTcpNoDelay(true);
            client.setSoTimeout(15000);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // 1. SOCKS5 Method Negotiation
            int ver = in.read();
            if (ver != 5) {
                client.close();
                return;
            }
            int nmethods = in.read();
            if (nmethods <= 0) {
                client.close();
                return;
            }
            byte[] methods = new byte[nmethods];
            int readMethods = in.read(methods);
            if (readMethods <= 0) {
                client.close();
                return;
            }

            // Accept NO_AUTH (0x00)
            out.write(new byte[]{0x05, 0x00});
            out.flush();

            // 2. SOCKS5 Request
            int reqVer = in.read();
            int cmd = in.read();
            int rsv = in.read();
            int atyp = in.read();

            if (reqVer != 5 || cmd != 1) { // Only CONNECT is supported
                out.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
                out.flush();
                client.close();
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
                client.close();
                return;
            }

            int destPort = ((in.read() & 0xFF) << 8) | (in.read() & 0xFF);

            // 3. Connect to destination (Direct with Desync OR via Upstream)
            targetSocket = establishConnection(destHost, destPort);
            if (targetSocket == null) {
                out.write(new byte[]{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); // Host unreachable
                out.flush();
                client.close();
                return;
            }

            // 4. SOCKS5 Success Response
            out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            out.flush();

            // Reset timeout for long-lived MTProto connection
            client.setSoTimeout(0);
            targetSocket.setSoTimeout(0);

            // 5. Bidirectional Relay with TCP Desync / Segmentation
            pipeWithDpiBypass(client, targetSocket);

        } catch (Exception e) {
            try { client.close(); } catch (Exception ignored) {}
            if (targetSocket != null) {
                try { targetSocket.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * Connects to target host: attempts direct connection first;
     * if blocked, falls back to upstream proxy.
     */
    private static Socket establishConnection(String host, int port) {
        // If upstream is explicitly configured, use it
        if (upstreamHost != null && upstreamPort > 0) {
            try {
                Socket s = new Socket();
                s.setTcpNoDelay(true);
                s.connect(new InetSocketAddress(upstreamHost, upstreamPort), 4000);
                return s;
            } catch (Exception ignored) {}
        }

        // Try direct connection to Telegram DC
        try {
            Socket directSocket = new Socket();
            directSocket.setTcpNoDelay(true);
            directSocket.connect(new InetSocketAddress(host, port), 4000);
            return directSocket;
        } catch (Exception e) {
            // Direct failed, try upstream if available
            if (upstreamHost != null && upstreamPort > 0) {
                try {
                    Socket s = new Socket();
                    s.setTcpNoDelay(true);
                    s.connect(new InetSocketAddress(upstreamHost, upstreamPort), 5000);
                    return s;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /**
     * Relays traffic between client and destination with TCP Segmentation / Desync
     * on the first outgoing packet (the MTProto / TLS handshake).
     */
    private static void pipeWithDpiBypass(final Socket client, final Socket dest) {
        // Client -> Destination (with DPI Desync)
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
                        // DPI Desync: Split the handshake packet into 2 segments:
                        // 1. Send the first byte
                        // 2. Flush immediately (forces TCP segment emission)
                        // 3. 2ms pause so DPI state machine desynchronizes
                        // 4. Send the rest of the handshake
                        if (len > 1) {
                            dout.write(buffer, 0, 1);
                            dout.flush();
                            try { Thread.sleep(2); } catch (Exception ignored) {}
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
            } catch (Exception ignored) {}
            finally {
                closeQuietly(client);
                closeQuietly(dest);
            }
        });

        // Destination -> Client (standard relay)
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
            } catch (Exception ignored) {}
            finally {
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
