package org.colgram.core;

import android.util.Log;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocketFactory;

/**
 * A connection whose socket dials an address resolved by {@link ColgramDohResolver}.
 *
 * The URL keeps its host name throughout, so the SNI, the Host header and certificate
 * verification are all unchanged - only the dialled address differs. That is what makes this a
 * bypass and not a redirect: a resolver that has been cut by SNI cannot be quietly replaced by
 * an impostor, because the name the certificate is checked against never changed.
 *
 * Every failure path returns the original connection, so the ordinary path is untouched whenever
 * the resolver has nothing to add.
 */
final class ColgramPinnedConnection {

    private static final String TAG = "ColgramPinned";
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;

    private ColgramPinnedConnection() {}

    static HttpsURLConnection create(HttpsURLConnection original, URL url, InetAddress address) {
        try {
            HttpsURLConnection copy = (HttpsURLConnection) url.openConnection();
            copy.setSSLSocketFactory(pinning(original.getSSLSocketFactory(), url, address));
            copy.setHostnameVerifier(original.getHostnameVerifier());
            copy.setConnectTimeout(original.getConnectTimeout() > 0
                    ? original.getConnectTimeout() : CONNECT_TIMEOUT_MS);
            copy.setReadTimeout(original.getReadTimeout() > 0
                    ? original.getReadTimeout() : READ_TIMEOUT_MS);
            copy.setInstanceFollowRedirects(original.getInstanceFollowRedirects());
            copy.setUseCaches(original.getUseCaches());
            copy.setDoInput(original.getDoInput());
            copy.setDoOutput(original.getDoOutput());
            copy.setAllowUserInteraction(original.getAllowUserInteraction());
            try {
                copy.setRequestMethod(original.getRequestMethod());
            } catch (java.net.ProtocolException ignored) {
                // A method already committed by the platform default is fine to keep.
            }
            return copy;
        } catch (Throwable t) {
            Log.i(TAG, "pinned connection unavailable, using the platform path: " + t);
            return original;
        }
    }

    /**
     * A socket factory that connects to `address` but speaks TLS for `url`'s host.
     *
     * The overlay matters: the SNI has to carry the name the server expects, and the certificate
     * has to be verified against that same name, or a filtered resolver turns into an impostor.
     */
    private static SSLSocketFactory pinning(final SSLSocketFactory delegate, final URL url,
                                            final InetAddress address) {
        return new SSLSocketFactory() {
            @Override
            public String[] getDefaultCipherSuites() {
                return delegate.getDefaultCipherSuites();
            }

            @Override
            public String[] getSupportedCipherSuites() {
                return delegate.getSupportedCipherSuites();
            }

            @Override
            public Socket createSocket(Socket socket, String host, int port, boolean autoClose)
                    throws IOException {
                // host is the URL's name: the handshake is exactly the one the server expects,
                // and the socket underneath is already pointed at our address.
                return delegate.createSocket(socket, host, port, autoClose);
            }

            @Override
            public Socket createSocket(String host, int port) throws IOException {
                return dial(delegate, url, address, port);
            }

            @Override
            public Socket createSocket(String host, int port, InetAddress local, int localPort)
                    throws IOException {
                return dial(delegate, url, address, port);
            }

            @Override
            public Socket createSocket(InetAddress host, int port) throws IOException {
                return dial(delegate, url, address, port);
            }

            @Override
            public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort)
                    throws IOException {
                return dial(delegate, url, address, port);
            }
        };
    }

    private static Socket dial(SSLSocketFactory delegate, URL url, InetAddress address, int port)
            throws IOException {
        int target = port > 0 ? port : url.getDefaultPort();
        Socket raw = new Socket();
        raw.connect(new InetSocketAddress(address, target), CONNECT_TIMEOUT_MS);
        raw.setSoTimeout(READ_TIMEOUT_MS);
        return delegate.createSocket(raw, url.getHost(), target, true);
    }
}
