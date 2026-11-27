package org.colgram.core;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.function.Consumer;

/** A temporary app-scoped TUN for private calls. No subscription state is written. */
public final class ColgramCallSocksTunnel {
    private static final String SERVICE = "org.colgram.singbox.ColgramVpnService";
    private static final String SOCKS = "colgram-call-socks";
    private static final String DPI = "colgram-call-dpi";
    private static final String SYSTEM_DPI = "colgram-system-dpi";
    private static final int SOCKET_TIMEOUT_MS = 2500;

    private ColgramCallSocksTunnel() { }

    /**
     * Resolve and check the exact chosen SOCKS5 endpoint before capturing traffic.
     * Call off main, after obtaining Android's VPN consent. A greeting verifies
     * protocol/authentication, not that media reaches the other caller.
     */
    public static long start(Context context, String host, int port, String user, String password)
            throws IOException {
        Context app = requireAvailable(context);
        long route = ColgramRouteEpoch.snapshot();
        ColgramProfileBuilder.forCallSocks(host, port, user, password);
        ConnectivityManager manager = app.getSystemService(ConnectivityManager.class);
        Network network = manager == null ? null : manager.getActiveNetwork();
        String address = resolveAndProbe(network, host, port, user, password);
        return launchIfCurrent(route, app, SOCKS,
                ColgramProfileBuilder.forCallSocks(address, port, user, password), null, null);
    }

    /** A separate authenticated loopback listener owned by this exact call tunnel. */
    public static long startLocalDpi(Context context) throws IOException {
        Context app = requireAvailable(context);
        long route = ColgramRouteEpoch.snapshot();
        ColgramDpiBypass.CallListener local = new ColgramDpiBypass.CallListener(false);
        try {
            return launchIfCurrent(route, app, DPI, local.config(), local::attach, local::close);
        } catch (IOException | RuntimeException failure) {
            local.close();
            throw failure;
        }
    }

    /**
     * Whole-device Local DPI Bypass TUN: routes all phone apps (YouTube, Discord, browsers,
     * Telegram, calls) through local TLS SNI-splitting + DoH + QUIC fallback with zero external server.
     */
    public static long startSystemDpi(Context context) throws IOException {
        Context app = requireAvailable(context);
        long route = ColgramRouteEpoch.snapshot();
        ColgramDpiBypass.CallListener local = new ColgramDpiBypass.CallListener(true);
        try {
            return launchIfCurrent(route, app, SYSTEM_DPI, local.config(), local::attach, local::close);
        } catch (IOException | RuntimeException failure) {
            local.close();
            throw failure;
        }
    }

    public static String systemDpiStatus(Context context) {
        if (context == null) return "unavailable";
        try {
            return (String) service(context).getMethod("status", String.class).invoke(null, SYSTEM_DPI);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return "unavailable";
        }
    }

    public static boolean isSystemDpiRunning(Context context) {
        return "running".equals(systemDpiStatus(context));
    }

    public static void stopSystemDpi(Context context) {
        if (context == null) return;
        try {
            service(context).getMethod("stopProfile", Context.class, String.class)
                    .invoke(null, context.getApplicationContext(), SYSTEM_DPI);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            // Backend unavailable
        }
    }

    public static String systemDpiLastError(Context context) {
        if (context == null) return null;
        try {
            Object err = service(context).getMethod("lastError").invoke(null);
            return err == null ? null : String.valueOf(err);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return null;
        }
    }

    public static boolean awaitStopped(Context context, long timeoutMs) {
        if (context == null) return true;
        Context app = context.getApplicationContext();
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (true) {
            boolean serviceBusy = false;
            try {
                Object stObj = service(app).getMethod("activeState").invoke(null);
                String st = stObj == null ? "stopped" : String.valueOf(stObj);
                serviceBusy = "starting".equals(st) || "running".equals(st) || "stopping".equals(st);
            } catch (ReflectiveOperationException | LinkageError ignored) {}
            boolean warpBusy = ColgramWarpTunnel.isUp();
            boolean vpnTransportUp = hasActiveVpnTransport(app);
            if (!serviceBusy && !warpBusy && !vpnTransportUp) {
                return true;
            }
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(75L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    private static boolean hasActiveVpnTransport(Context app) {
        try {
            ConnectivityManager manager = app.getSystemService(ConnectivityManager.class);
            if (manager == null) return false;
            Network active = manager.getActiveNetwork();
            if (active != null) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(active);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
            Network[] all = manager.getAllNetworks();
            if (all != null) {
                for (Network net : all) {
                    if (net == null) continue;
                    NetworkCapabilities caps = manager.getNetworkCapabilities(net);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static String status(Context context, boolean localDpi) {
        String st = status(context, localDpi, -1L);
        if (localDpi && !"running".equals(st) && "running".equals(systemDpiStatus(context))) {
            return "running";
        }
        return st;
    }

    public static String status(Context context, boolean localDpi, long generation) {
        if (context == null) return "unavailable";
        try {
            Class<?> service = service(context);
            String id = localDpi ? DPI : SOCKS;
            return generation < 0 ? (String) service.getMethod("status", String.class).invoke(null, id)
                    : (String) service.getMethod("status", String.class, long.class).invoke(null, id, generation);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            return "unavailable";
        }
    }

    public static void stop(Context context) {
        stop(context, false, -1L);
        stop(context, true, -1L);
    }

    public static void stop(Context context, boolean localDpi) {
        stop(context, localDpi, -1L);
    }

    public static void stop(Context context, boolean localDpi, long generation) {
        if (context == null) return;
        try {
            Class<?> service = service(context);
            String id = localDpi ? DPI : SOCKS;
            if (generation < 0) service.getMethod("stopProfile", Context.class, String.class)
                    .invoke(null, context.getApplicationContext(), id);
            else service.getMethod("stopProfile", Context.class, String.class, long.class)
                    .invoke(null, context.getApplicationContext(), id, generation);
        } catch (ReflectiveOperationException | LinkageError unavailable) {
            // A build without the backend cannot own this tunnel.
        }
    }

    private static Context requireAvailable(Context context) throws IOException {
        if (context == null) throw new IOException("Нет контекста приложения");
        Context app = context.getApplicationContext();
        if (VpnService.prepare(app) != null) throw new IOException("Нужно разрешение Android на VPN");
        ConnectivityManager manager = app.getSystemService(ConnectivityManager.class);
        Network network = manager == null ? null : manager.getActiveNetwork();
        NetworkCapabilities caps = network == null ? null : manager.getNetworkCapabilities(network);
        if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            throw new IOException("Уже работает другой Android VPN");
        }
        return app;
    }

    private static Class<?> service(Context context) throws ClassNotFoundException {
        return Class.forName(SERVICE, true, context.getClassLoader());
    }

    private static long launchIfCurrent(long route, Context app, String id, String json,
            Consumer<VpnService> attach, Runnable close) throws IOException {
        final long[] result = {-1L};
        final IOException[] failure = {null};
        if (!ColgramRouteEpoch.runIfCurrent(route, () -> {
            try {
                requireAvailable(app);
                result[0] = ((Number) service(app).getMethod("startAppProfile", Context.class,
                        String.class, String.class, Consumer.class, Runnable.class)
                        .invoke(null, app, id, json, attach, close)).longValue();
            } catch (InvocationTargetException busy) {
                String code = systemDpiLastError(app);
                if ((code == null || code.isEmpty()) && busy.getCause() != null) {
                    code = busy.getCause().getClass().getSimpleName();
                }
                failure[0] = new IOException(code != null && !code.isEmpty()
                        ? ("VPN для звонка не запустился (" + code + ")")
                        : "VPN для звонка не запустился");
            } catch (ReflectiveOperationException | LinkageError unavailable) {
                failure[0] = new IOException("VPN-бэкенд недоступен");
            } catch (IOException noConsent) {
                failure[0] = noConsent;
            }
        })) throw new IOException("Выбран другой маршрут");
        if (failure[0] != null) throw failure[0];
        return result[0];
    }

    private static String resolveAndProbe(Network network, String host, int port, String user,
            String password) throws IOException {
        String name = host.trim();
        if (name.startsWith("[") && name.endsWith("]")) name = name.substring(1, name.length() - 1);
        InetAddress[] addresses = network == null ? InetAddress.getAllByName(name) : network.getAllByName(name);
        for (int i = 0; i < Math.min(2, addresses.length); i++) {
            try (Socket socket = network == null ? new Socket() : network.getSocketFactory().createSocket()) {
                socket.connect(new InetSocketAddress(addresses[i], port), SOCKET_TIMEOUT_MS);
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                probeAuthentication(socket, user, password);
                return addresses[i].getHostAddress();
            } catch (IOException ignored) {
                // The next address still belongs to the exact selected host, not another proxy.
            }
        }
        throw new IOException("Выбранный SOCKS5 не прошёл проверку подключения");
    }

    static void probeAuthentication(Socket socket, String user, String password) throws IOException {
        boolean auth = user != null && !user.isEmpty();
        InputStream input = socket.getInputStream();
        OutputStream output = socket.getOutputStream();
        int expected = auth ? 2 : 0;
        output.write(new byte[]{5, 1, (byte) expected});
        output.flush();
        if (ColgramSocks5Codec.readByte(input) != 5 || ColgramSocks5Codec.readByte(input) != expected) {
            throw new IOException("SOCKS5 rejected the selected authentication method");
        }
        if (!auth) return;
        byte[] name = user.getBytes(StandardCharsets.UTF_8);
        byte[] secret = password.getBytes(StandardCharsets.UTF_8);
        try {
            output.write(new byte[]{1, (byte) name.length});
            output.write(name);
            output.write(secret.length);
            output.write(secret);
            output.flush();
            if (ColgramSocks5Codec.readByte(input) != 1 || ColgramSocks5Codec.readByte(input) != 0) {
                throw new IOException("SOCKS5 authentication failed");
            }
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }
}
