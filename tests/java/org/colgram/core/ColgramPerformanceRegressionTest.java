package org.colgram.core;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure-JVM performance and correctness regression tests for:
 * - ColgramConnectFailureBackoff concurrent permit mode (maxConcurrent=4)
 * - ColgramTelegramWsBridge.Obfuscated2FrameSplitter zero-copy fast path & chunked framing parity
 * - WsSession unroll/flush batching, RFC 6455 masking/unmasking parity & throughput
 */
public final class ColgramPerformanceRegressionTest {
    private static int checks;

    private static void check(boolean cond, String msg) {
        checks++;
        if (!cond) {
            throw new AssertionError("ColgramPerformanceRegressionTest failed [" + checks + "]: " + msg);
        }
    }

    public static void main(String[] args) throws Exception {
        testConnectFailureBackoffConcurrentPermits();
        testObfuscated2ZeroCopyAndChunkedParity();
        testWsSessionFlushBatchingAndCiphertextParity();
        testWsRoundTripUnrollWithUnalignedChunkReadsAndThroughput();
        testColgramConfigVolatileCacheCoherence();
        testColgramDatabaseBlockedUsersAndBatchedDeletes();
        System.out.println("ColgramPerformanceRegressionTest: " + checks + " checks PASS");
    }

    private static void testConnectFailureBackoffConcurrentPermits() throws Exception {
        ColgramConnectFailureBackoff backoff = new ColgramConnectFailureBackoff(1200L, 15000L, 4);
        String ep1 = "149.154.167.51:443";
        String ep2 = "149.154.167.91:443";
        long t0 = 100_000L;

        // 4 concurrent permits must be granted for the same endpoint without coalescing.
        ColgramConnectFailureBackoff.Decision d1 = backoff.acquire(ep1, t0);
        ColgramConnectFailureBackoff.Decision d2 = backoff.acquire(ep1, t0);
        ColgramConnectFailureBackoff.Decision d3 = backoff.acquire(ep1, t0);
        ColgramConnectFailureBackoff.Decision d4 = backoff.acquire(ep1, t0);
        check(d1.granted() && !d1.coalesced(), "permit 1 granted");
        check(d2.granted() && !d2.coalesced(), "permit 2 granted");
        check(d3.granted() && !d3.coalesced(), "permit 3 granted");
        check(d4.granted() && !d4.coalesced(), "permit 4 granted");
        check(d1.ticket() == d4.ticket() && d1.ticket() >= 0L, "permits share active generation ticket");

        // 5th simultaneous dial must be refused as coalesced (concurrency), not failure cooldown.
        ColgramConnectFailureBackoff.Decision d5 = backoff.acquire(ep1, t0);
        check(!d5.granted() && d5.coalesced(), "5th concurrent dial is coalesced");
        check(backoff.tryBegin(ep1, t0) == -1L, "tryBegin returns -1 at concurrency cap");
        check(backoff.shouldSkip(ep1, t0), "shouldSkip true at concurrency cap");

        // Independent endpoint keeps its own 4 permits.
        ColgramConnectFailureBackoff.Decision other = backoff.acquire(ep2, t0);
        check(other.granted() && !other.coalesced(), "different endpoint unaffected by ep1 concurrency cap");
        backoff.release(ep2, other.ticket());

        // Release 1 permit without touching network -> 5th slot immediately opens without recording failure.
        backoff.release(ep1, d4.ticket());
        ColgramConnectFailureBackoff.Decision d5Retry = backoff.acquire(ep1, t0);
        check(d5Retry.granted() && !d5Retry.coalesced(), "releasing 1 permit opens slot immediately");

        // One dial fails -> records failure #1 (1200ms cooldown) while remaining 3 stay valid in flight.
        long delay1 = backoff.finish(ep1, d1.ticket(), false, t0 + 10L);
        check(delay1 == 1200L, "first failure uses baseDelayMs=1200, got=" + delay1);
        ColgramConnectFailureBackoff.Decision cooling = backoff.acquire(ep1, t0 + 500L);
        check(!cooling.granted() && !cooling.coalesced(),
                "during cooldown, refusal must have coalesced=false");
        check(backoff.shouldSkip(ep1, t0 + 1209L), "cooldown still active 1ms before expiry");

        // Once cooldown expires, second in-flight failure escalates to 2400ms.
        long delay2 = backoff.finish(ep1, d2.ticket(), false, t0 + 1210L);
        check(delay2 == 2400L, "second failure doubles delay to 2400ms, got=" + delay2);
        check(backoff.shouldSkip(ep1, t0 + 3609L), "2nd cooldown active until t0+3610");

        // Any successful finish clears failure history completely.
        long okDelay = backoff.finish(ep1, d3.ticket(), true, t0 + 1300L);
        check(okDelay == 0L, "success finish returns 0 delay");
        backoff.release(ep1, d5Retry.ticket());
        ColgramConnectFailureBackoff.Decision freshAfterSuccess = backoff.acquire(ep1, t0 + 1300L);
        check(freshAfterSuccess.granted(), "success finish clears failure cooldown immediately");

        // Network transition (clear) invalidates old tickets so late failures never poison new generation.
        long staleTicket = freshAfterSuccess.ticket();
        backoff.clear();
        long staleDelay = backoff.finish(ep1, staleTicket, false, t0 + 2000L);
        check(staleDelay == 0L, "stale-generation finish ignored after clear()");
        check(!backoff.shouldSkip(ep1, t0 + 2000L), "stale failure does not trigger cooldown after clear()");

        // Multi-threaded contention: 8 threads competing on maxConcurrent=4 never exceed 4 active permits.
        final AtomicInteger active = new AtomicInteger(0);
        final AtomicInteger maxSeen = new AtomicInteger(0);
        final AtomicInteger successes = new AtomicInteger(0);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch doneGate = new CountDownLatch(8);
        for (int t = 0; t < 8; t++) {
            final int tid = t;
            Thread th = new Thread(() -> {
                try {
                    startGate.await(5, TimeUnit.SECONDS);
                    for (int iter = 0; iter < 50; iter++) {
                        ColgramConnectFailureBackoff.Decision dec = backoff.acquire(ep1, t0 + 5000L + iter);
                        if (dec.granted()) {
                            int cur = active.incrementAndGet();
                            maxSeen.accumulateAndGet(cur, Math::max);
                            Thread.yield();
                            active.decrementAndGet();
                            backoff.finish(ep1, dec.ticket(), true, t0 + 5000L + iter);
                            successes.incrementAndGet();
                        } else {
                            Thread.yield();
                        }
                    }
                } catch (Throwable ignored) {
                } finally {
                    doneGate.countDown();
                }
            }, "backoff-race-" + tid);
            th.setDaemon(true);
            th.start();
        }
        startGate.countDown();
        check(doneGate.await(10, TimeUnit.SECONDS), "concurrent backoff test threads finished");
        check(maxSeen.get() >= 1 && maxSeen.get() <= 4,
                "concurrent active permits bounded in [1..4], maxSeen=" + maxSeen.get());
        check(successes.get() > 0, "at least one threaded acquire succeeded");
    }

    private static final class Obf2Stream {
        final byte[] initHdr;
        final List<byte[]> cipherPackets;
        final byte[] fullStream;
        final byte[] tailOnly;

        Obf2Stream(byte[] initHdr, List<byte[]> cipherPackets) {
            this.initHdr = initHdr;
            this.cipherPackets = cipherPackets;
            ByteArrayOutputStream tailOut = new ByteArrayOutputStream();
            for (byte[] p : cipherPackets) {
                tailOut.write(p, 0, p.length);
            }
            this.tailOnly = tailOut.toByteArray();
            this.fullStream = new byte[initHdr.length + tailOnly.length];
            System.arraycopy(initHdr, 0, fullStream, 0, initHdr.length);
            System.arraycopy(tailOnly, 0, fullStream, initHdr.length, tailOnly.length);
        }
    }

    private static Obf2Stream buildObfuscated2Stream(long seed, int proto, int dcId, int[] payloadSizes) throws Exception {
        byte[] hdr = new byte[64];
        Random rng = new Random(seed);
        rng.nextBytes(hdr);
        byte[] key = Arrays.copyOfRange(hdr, 8, 40);
        byte[] iv = Arrays.copyOfRange(hdr, 40, 56);
        byte[] plainHdr = hdr.clone();
        plainHdr[56] = (byte) proto;
        plainHdr[57] = (byte) proto;
        plainHdr[58] = (byte) proto;
        plainHdr[59] = (byte) proto;
        plainHdr[60] = (byte) (dcId & 0xff);
        plainHdr[61] = (byte) ((dcId >> 8) & 0xff);

        Cipher enc = Cipher.getInstance("AES/CTR/NoPadding");
        enc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] encHdr = enc.update(plainHdr);
        System.arraycopy(encHdr, 56, hdr, 56, 8);

        List<byte[]> cipherPackets = new ArrayList<>(payloadSizes.length);
        for (int idx = 0; idx < payloadSizes.length; idx++) {
            int bodyBytes = payloadSizes[idx];
            byte[] plain;
            if (proto == 0xef) {
                int words = Math.max(1, (bodyBytes + 3) / 4);
                if (words < 0x7f) {
                    plain = new byte[1 + words * 4];
                    plain[0] = (byte) (((idx & 1) != 0 ? 0x80 : 0) | words);
                    rng.nextBytes(plain);
                    plain[0] = (byte) (((idx & 1) != 0 ? 0x80 : 0) | words);
                } else {
                    plain = new byte[4 + words * 4];
                    rng.nextBytes(plain);
                    plain[0] = (byte) ((idx & 1) != 0 ? 0xff : 0x7f);
                    plain[1] = (byte) (words & 0xff);
                    plain[2] = (byte) ((words >> 8) & 0xff);
                    plain[3] = (byte) ((words >> 16) & 0xff);
                }
            } else {
                // 0xdd PaddedIntermediate: 4-byte LE byte length (with optional bit 31 quickAck)
                int len = Math.max(4, bodyBytes);
                plain = new byte[4 + len];
                rng.nextBytes(plain);
                plain[0] = (byte) (len & 0xff);
                plain[1] = (byte) ((len >> 8) & 0xff);
                plain[2] = (byte) ((len >> 16) & 0xff);
                plain[3] = (byte) (((len >> 24) & 0x7f) | ((idx & 1) != 0 ? 0x80 : 0));
            }
            cipherPackets.add(enc.update(plain));
        }
        return new Obf2Stream(hdr, cipherPackets);
    }

    private static void testObfuscated2ZeroCopyAndChunkedParity() throws Exception {
        for (int proto : new int[]{0xef, 0xdd}) {
            Obf2Stream stream = buildObfuscated2Stream(500L + proto, proto, 2,
                    new int[]{4, 36, 124, 512, 560, 4096});

            // 1. Contiguous feedInitAndTail MUST emit directly from the input buffer (zero-copy identity!)
            ColgramTelegramWsBridge.Obfuscated2FrameSplitter zcSplitter =
                    ColgramTelegramWsBridge.Obfuscated2FrameSplitter.create(
                            stream.fullStream.clone(), stream.fullStream.length, 2);
            check(zcSplitter != null, "create splitter proto=0x" + Integer.toHexString(proto));

            List<byte[]> zcFrames = new ArrayList<>();
            byte[] fullCopy = stream.fullStream.clone();
            zcSplitter.feedInitAndTail(fullCopy, fullCopy.length, (buf, off, len) -> {
                check(buf == fullCopy,
                        "zero-copy fast path must pass input array reference without copy (proto=0x"
                                + Integer.toHexString(proto) + ", frame=" + zcFrames.size() + ")");
                zcFrames.add(Arrays.copyOfRange(buf, off, off + len));
            });
            check(zcFrames.size() == 1 + stream.cipherPackets.size(),
                    "contiguous frame count mismatch, got=" + zcFrames.size());
            check(Arrays.equals(zcFrames.get(0), stream.initHdr), "init frame matches");
            for (int i = 0; i < stream.cipherPackets.size(); i++) {
                check(Arrays.equals(zcFrames.get(1 + i), stream.cipherPackets.get(i)),
                        "zero-copy packet " + i + " matches ciphertext");
            }

            // 2. Non-zero offset feed() on tail buffer also hits zero-copy fast path
            ColgramTelegramWsBridge.Obfuscated2FrameSplitter offSplitter =
                    ColgramTelegramWsBridge.Obfuscated2FrameSplitter.create(stream.initHdr.clone(), 64, 2);
            List<byte[]> offFrames = new ArrayList<>();
            offSplitter.feedInitAndTail(stream.initHdr.clone(), 64,
                    (buf, off, len) -> offFrames.add(Arrays.copyOfRange(buf, off, off + len)));
            int padPrefix = 19;
            byte[] paddedTail = new byte[padPrefix + stream.tailOnly.length + 7];
            System.arraycopy(stream.tailOnly, 0, paddedTail, padPrefix, stream.tailOnly.length);
            offSplitter.feed(paddedTail, padPrefix, stream.tailOnly.length, (buf, off, len) -> {
                check(buf == paddedTail, "offset>0 contiguous feed must stay zero-copy");
                offFrames.add(Arrays.copyOfRange(buf, off, off + len));
            });
            check(offFrames.size() == zcFrames.size(), "offset zero-copy packet count matches");
            for (int i = 0; i < zcFrames.size(); i++) {
                check(Arrays.equals(offFrames.get(i), zcFrames.get(i)),
                        "offset zero-copy packet " + i + " ciphertext parity");
            }

            // 3. Chunked framing across awkward slice sizes must match contiguous ciphertext 100%
            for (int chunk : new int[]{1, 2, 3, 5, 7, 13, 61, 257}) {
                byte[] initChunk = stream.initHdr.clone();
                // Include 3 tail bytes in init buffer when testing >64B init boundary
                int firstTail = Math.min(3, stream.tailOnly.length);
                byte[] initPlus = new byte[64 + firstTail];
                System.arraycopy(initChunk, 0, initPlus, 0, 64);
                System.arraycopy(stream.tailOnly, 0, initPlus, 64, firstTail);

                ColgramTelegramWsBridge.Obfuscated2FrameSplitter chSplitter =
                        ColgramTelegramWsBridge.Obfuscated2FrameSplitter.create(initPlus, initPlus.length, 2);
                List<byte[]> chFrames = new ArrayList<>();
                chSplitter.feedInitAndTail(initPlus, initPlus.length,
                        (buf, off, len) -> chFrames.add(Arrays.copyOfRange(buf, off, off + len)));

                int cursor = firstTail;
                while (cursor < stream.tailOnly.length) {
                    int take = Math.min(chunk, stream.tailOnly.length - cursor);
                    chSplitter.feed(stream.tailOnly, cursor, take,
                            (buf, off, len) -> chFrames.add(Arrays.copyOfRange(buf, off, off + len)));
                    cursor += take;
                }
                check(chFrames.size() == zcFrames.size(),
                        "chunk=" + chunk + " packet count matches (" + chFrames.size() + " vs " + zcFrames.size() + ")");
                for (int i = 0; i < zcFrames.size(); i++) {
                    check(Arrays.equals(chFrames.get(i), zcFrames.get(i)),
                            "chunk=" + chunk + " packet " + i + " byte-for-byte parity");
                }
            }
        }
    }

    private static final class FlushCountingOutputStream extends FilterOutputStream {
        int flushCount;
        int writeCount;

        FlushCountingOutputStream(ByteArrayOutputStream out) {
            super(out);
        }

        @Override
        public void write(int b) throws IOException {
            writeCount++;
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            writeCount++;
            out.write(b, off, len);
        }

        @Override
        public void flush() throws IOException {
            flushCount++;
            out.flush();
        }
    }

    private static List<byte[]> decodeClientMaskedWsBinaryMessages(byte[] wire) throws IOException {
        ByteArrayInputStream in = new ByteArrayInputStream(wire);
        List<byte[]> outMsgs = new ArrayList<>();
        ByteArrayOutputStream fragAcc = null;
        while (true) {
            int b0 = in.read();
            if (b0 < 0) break;
            int b1 = in.read();
            if (b1 < 0) throw new EOFException("truncated frame b1");
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0f;
            boolean masked = (b1 & 0x80) != 0;
            check(masked, "client->server WS frame MUST have MASK bit set");
            long len = b1 & 0x7f;
            if (len == 126) {
                len = ((in.read() & 0xff) << 8) | (in.read() & 0xff);
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) len = (len << 8) | (in.read() & 0xff);
            }
            byte[] mask = new byte[4];
            if (in.read(mask) != 4) throw new EOFException("truncated mask");
            byte[] payload = new byte[(int) len];
            int got = 0;
            while (got < payload.length) {
                int n = in.read(payload, got, payload.length - got);
                if (n < 0) throw new EOFException("truncated payload");
                got += n;
            }
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i & 3];
            }
            if (opcode == 0x2) {
                if (fin) {
                    outMsgs.add(payload);
                } else {
                    fragAcc = new ByteArrayOutputStream();
                    fragAcc.write(payload);
                }
            } else if (opcode == 0x0) {
                check(fragAcc != null, "continuation frame requires active fragmented message");
                fragAcc.write(payload);
                if (fin) {
                    outMsgs.add(fragAcc.toByteArray());
                    fragAcc = null;
                }
            } else {
                throw new AssertionError("unexpected opcode=" + opcode);
            }
        }
        check(fragAcc == null, "unterminated fragmented WS message");
        return outMsgs;
    }

    private static void testWsSessionFlushBatchingAndCiphertextParity() throws Exception {
        Obf2Stream s = buildObfuscated2Stream(909L, 0xef, 4,
                new int[]{4, 16, 120, 128, 256, 512, 70000});

        ByteArrayOutputStream rawWire = new ByteArrayOutputStream();
        FlushCountingOutputStream countingOut = new FlushCountingOutputStream(rawWire);
        ColgramTelegramWsBridge.WsSession session = new ColgramTelegramWsBridge.WsSession(
                4, "149.154.167.220", "vesta.web.telegram.org", null,
                new ByteArrayInputStream(new byte[0]), countingOut);

        // Batch 1: init + first 4 packets -> exactly 1 flush!
        ByteArrayOutputStream firstBatch = new ByteArrayOutputStream();
        firstBatch.write(s.initHdr);
        for (int i = 0; i < 4; i++) firstBatch.write(s.cipherPackets.get(i));
        byte[] firstBytes = firstBatch.toByteArray();

        ColgramTelegramWsBridge.Obfuscated2FrameSplitter splitter =
                ColgramTelegramWsBridge.Obfuscated2FrameSplitter.create(firstBytes.clone(), firstBytes.length, 4);
        splitter.feedInitAndTailToSession(firstBytes, firstBytes.length, session);
        check(countingOut.flushCount == 1,
                "feedInitAndTailToSession must batch flush once for init+4 packets, got=" + countingOut.flushCount);

        // Batch 2: remaining 3 packets (including 70KB multi-fragment > 65500B) -> exactly +1 flush!
        ByteArrayOutputStream secondBatch = new ByteArrayOutputStream();
        for (int i = 4; i < s.cipherPackets.size(); i++) secondBatch.write(s.cipherPackets.get(i));
        byte[] secondBytes = secondBatch.toByteArray();
        splitter.feedToSession(secondBytes, 0, secondBytes.length, session);
        check(countingOut.flushCount == 2,
                "feedToSession must batch flush once for coalesced multi-packet buffer, got=" + countingOut.flushCount);

        List<byte[]> decoded = decodeClientMaskedWsBinaryMessages(rawWire.toByteArray());
        check(decoded.size() == 1 + s.cipherPackets.size(),
                "decoded WS message count mismatch: " + decoded.size());
        check(Arrays.equals(decoded.get(0), s.initHdr), "init header ciphertext parity after WS unmask");
        for (int i = 0; i < s.cipherPackets.size(); i++) {
            check(Arrays.equals(decoded.get(1 + i), s.cipherPackets.get(i)),
                    "packet " + i + " ciphertext parity after WS mask/unmask (len=" + s.cipherPackets.get(i).length + ")");
        }
    }

    private static void testWsRoundTripUnrollWithUnalignedChunkReadsAndThroughput() throws Exception {
        // Test unaligned payload sizes mod 4 (1..3, 125..127, 4093..4099) + 2MB sustained throughput.
        int[] unalignedSizes = {1, 2, 3, 4, 5, 7, 125, 126, 127, 128, 255, 513, 4093, 4094, 4095, 4097};
        ByteArrayOutputStream wireOut = new ByteArrayOutputStream();
        ColgramTelegramWsBridge.WsSession tx = new ColgramTelegramWsBridge.WsSession(
                2, "149.154.167.220", "kws2.web.telegram.org", null,
                new ByteArrayInputStream(new byte[0]), wireOut);

        ByteArrayOutputStream expectedPlain = new ByteArrayOutputStream();
        Random rng = new Random(1337L);
        for (int len : unalignedSizes) {
            byte[] p = new byte[len];
            rng.nextBytes(p);
            expectedPlain.write(p);
            tx.sendBinaryUnflushed(p, 0, p.length);
        }
        // Add 2 MB bulk frames to exercise fast 4-byte XOR unroll in both WsSession and unmask()
        byte[] bulk = new byte[65500];
        rng.nextBytes(bulk);
        for (int i = 0; i < 32; i++) {
            expectedPlain.write(bulk);
            tx.sendBinaryUnflushed(bulk, 0, bulk.length);
        }
        tx.flushOut();

        byte[] wireBytes = wireOut.toByteArray();
        // Feed through an InputStream that returns 13-byte chunks so pumpUpstreamFrames hits unaligned maskOffsets.
        InputStream awkwardChunkedIn = new InputStream() {
            int pos = 0;
            @Override
            public int read() {
                return pos < wireBytes.length ? (wireBytes[pos++] & 0xff) : -1;
            }
            @Override
            public int read(byte[] b, int off, int len) {
                if (pos >= wireBytes.length) return -1;
                int n = Math.min(13, Math.min(len, wireBytes.length - pos));
                System.arraycopy(wireBytes, pos, b, off, n);
                pos += n;
                return n;
            }
        };

        ByteArrayOutputStream pumpedOut = new ByteArrayOutputStream(expectedPlain.size());
        ColgramTelegramWsBridge.WsSession rx = new ColgramTelegramWsBridge.WsSession(
                2, "149.154.167.220", "kws2.web.telegram.org", null,
                awkwardChunkedIn, new ByteArrayOutputStream());

        long tStart = System.nanoTime();
        long total = ColgramTelegramWsBridge.pumpUpstreamFrames(rx, pumpedOut, null);
        long elapsedMs = (System.nanoTime() - tStart) / 1_000_000L;

        byte[] expected = expectedPlain.toByteArray();
        byte[] actual = pumpedOut.toByteArray();
        check(total == expected.length && actual.length == expected.length,
                "round-trip byte count mismatch: got=" + total + " expected=" + expected.length);
        check(Arrays.equals(actual, expected),
                "round-trip WS mask -> pumpUpstreamFrames unmask ciphertext parity failed");
        check(elapsedMs < 5000L, "2MB+ masked WS round-trip throughput too slow: " + elapsedMs + "ms");

        // Also test Ping with non-zero mask on server->client control path + ASCII payload
        byte[] ping = new byte[]{(byte) 0x89, (byte) 0x84, 0x11, 0x22, 0x33, 0x44,
                (byte) ('t' ^ 0x11), (byte) ('e' ^ 0x22), (byte) ('s' ^ 0x33), (byte) ('t' ^ 0x44)};
        ByteArrayOutputStream pongWire = new ByteArrayOutputStream();
        ColgramTelegramWsBridge.WsSession pingSess = new ColgramTelegramWsBridge.WsSession(
                2, "149.154.167.220", "kws2.web.telegram.org", null,
                new ByteArrayInputStream(ping), pongWire);
        ColgramTelegramWsBridge.pumpUpstreamFrames(pingSess, new ByteArrayOutputStream(), null);
        byte[] pw = pongWire.toByteArray();
        check(pw.length == 10 && (pw[0] & 0xff) == 0x8A && (pw[1] & 0xff) == 0x84,
                "masked server ping echoed as masked pong");
        byte[] pongPlain = new byte[4];
        for (int i = 0; i < 4; i++) pongPlain[i] = (byte) (pw[6 + i] ^ pw[2 + (i & 3)]);
        check("test".equals(new String(pongPlain, StandardCharsets.US_ASCII)), "pong payload echoed accurately");
    }

    private static void testColgramConfigVolatileCacheCoherence() throws Exception {
        // 1. Verify hot cache fields are declared volatile so cross-thread reads never see stale values.
        String[] volatileFieldNames = {
                "prefs", "cloakEnabled", "antiDeleteEnabled", "editHistoryEnabled",
                "antiDeleteHighlightEnabled", "antiDeleteWipeEnabled", "chatWallpaperEnabled",
                "preserveMediaEnabled", "ghostReadEnabled", "ghostTypingEnabled",
                "ghostOnlineEnabled", "bypassFlagSecureEnabled", "sandboxStorageEnabled",
                "cyberThemeEnabled", "unlimitedMemoryEnabled", "autoCompressStorageEnabled",
                "dcRemapEnabled", "dcRemapLoaded", "alwaysBackgroundEnabled",
                "quickBarEnabled", "quickBarItems"
        };
        for (String fname : volatileFieldNames) {
            java.lang.reflect.Field f = ColgramConfig.class.getDeclaredField(fname);
            check(java.lang.reflect.Modifier.isVolatile(f.getModifiers()),
                    "ColgramConfig." + fname + " must be volatile");
        }

        // 2. Wire an in-memory Proxy SharedPreferences + Editor + OnSharedPreferenceChangeListener.
        Class<?> spClass = Class.forName("android.content.SharedPreferences");
        Class<?> editorClass = Class.forName("android.content.SharedPreferences$Editor");
        Class<?> listenerClass = Class.forName("android.content.SharedPreferences$OnSharedPreferenceChangeListener");
        java.lang.reflect.Method onChangedMethod = listenerClass.getMethod(
                "onSharedPreferenceChanged", spClass, String.class);

        final java.util.concurrent.ConcurrentHashMap<String, Object> store = new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.List<Object> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicInteger spReadCalls = new AtomicInteger(0);
        final Object[] spRef = new Object[1];

        Object spProxy = java.lang.reflect.Proxy.newProxyInstance(
                ColgramConfig.class.getClassLoader(),
                new Class<?>[]{spClass},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("getBoolean".equals(name)) {
                        spReadCalls.incrementAndGet();
                        Object v = store.get(args[0]);
                        return (v instanceof Boolean) ? v : args[1];
                    } else if ("getString".equals(name)) {
                        spReadCalls.incrementAndGet();
                        Object v = store.get(args[0]);
                        return (v instanceof String) ? v : args[1];
                    } else if ("getInt".equals(name)) {
                        spReadCalls.incrementAndGet();
                        Object v = store.get(args[0]);
                        return (v instanceof Integer) ? v : args[1];
                    } else if ("getLong".equals(name)) {
                        spReadCalls.incrementAndGet();
                        Object v = store.get(args[0]);
                        return (v instanceof Long) ? v : args[1];
                    } else if ("contains".equals(name)) {
                        spReadCalls.incrementAndGet();
                        return store.containsKey(args[0]);
                    } else if ("getAll".equals(name)) {
                        return new java.util.HashMap<>(store);
                    } else if ("registerOnSharedPreferenceChangeListener".equals(name)) {
                        if (args[0] != null && !listeners.contains(args[0])) listeners.add(args[0]);
                        return null;
                    } else if ("unregisterOnSharedPreferenceChangeListener".equals(name)) {
                        listeners.remove(args[0]);
                        return null;
                    } else if ("edit".equals(name)) {
                        final java.util.Map<String, Object> pending = new java.util.LinkedHashMap<>();
                        final java.util.Set<String> removals = new java.util.LinkedHashSet<>();
                        final boolean[] clearAll = {false};
                        return java.lang.reflect.Proxy.newProxyInstance(
                                ColgramConfig.class.getClassLoader(),
                                new Class<?>[]{editorClass},
                                (edProxy, edMethod, edArgs) -> {
                                    String em = edMethod.getName();
                                    if ("putBoolean".equals(em) || "putString".equals(em)
                                            || "putInt".equals(em) || "putLong".equals(em)) {
                                        String k = (String) edArgs[0];
                                        if (edArgs[1] == null) {
                                            pending.remove(k);
                                            removals.add(k);
                                        } else {
                                            removals.remove(k);
                                            pending.put(k, edArgs[1]);
                                        }
                                        return edProxy;
                                    } else if ("remove".equals(em)) {
                                        String k = (String) edArgs[0];
                                        pending.remove(k);
                                        removals.add(k);
                                        return edProxy;
                                    } else if ("clear".equals(em)) {
                                        pending.clear();
                                        removals.clear();
                                        clearAll[0] = true;
                                        return edProxy;
                                    } else if ("apply".equals(em) || "commit".equals(em)) {
                                        java.util.List<String> changed = new java.util.ArrayList<>();
                                        if (clearAll[0]) {
                                            store.clear();
                                        }
                                        for (String rk : removals) {
                                            store.remove(rk);
                                            changed.add(rk);
                                        }
                                        for (java.util.Map.Entry<String, Object> e : pending.entrySet()) {
                                            store.put(e.getKey(), e.getValue());
                                            changed.add(e.getKey());
                                        }
                                        for (Object l : listeners) {
                                            if (clearAll[0]) {
                                                onChangedMethod.invoke(l, spRef[0], null);
                                            }
                                            for (String ck : changed) {
                                                onChangedMethod.invoke(l, spRef[0], ck);
                                            }
                                        }
                                        return "commit".equals(em) ? Boolean.TRUE : null;
                                    }
                                    return null;
                                });
                    }
                    return null;
                });
        spRef[0] = spProxy;

        java.lang.reflect.Field prefsF = ColgramConfig.class.getDeclaredField("prefs");
        prefsF.setAccessible(true);
        java.lang.reflect.Field listenerF = ColgramConfig.class.getDeclaredField("prefChangeListener");
        listenerF.setAccessible(true);
        java.lang.reflect.Field dcLoadedF = ColgramConfig.class.getDeclaredField("dcRemapLoaded");
        dcLoadedF.setAccessible(true);
        java.lang.reflect.Method loadAllM = ColgramConfig.class.getDeclaredMethod("loadAllCachedPrefs", spClass);
        loadAllM.setAccessible(true);
        java.lang.reflect.Method updateKeyM = ColgramConfig.class.getDeclaredMethod("updateCachedKey", spClass, String.class);
        updateKeyM.setAccessible(true);

        Object prevPrefs = prefsF.get(null);
        Object prevListener = listenerF.get(null);
        try {
            // Seed initial preference state before wiring ColgramConfig
            store.put("cyber_theme_enabled", Boolean.TRUE);
            store.put("anti_delete_enabled", Boolean.TRUE);
            store.put("anti_delete_highlight", Boolean.FALSE);
            store.put("unlimited_memory_enabled", Boolean.FALSE);
            store.put("sandbox_storage_enabled", Boolean.FALSE);
            store.put("ghost_read_receipts", Boolean.TRUE);
            store.put("ghost_offline_mode", Boolean.TRUE);
            store.put("dc_remap_enabled", Boolean.TRUE);

            prefsF.set(null, spProxy);
            loadAllM.invoke(null, spProxy);

            Object cfgListener = java.lang.reflect.Proxy.newProxyInstance(
                    ColgramConfig.class.getClassLoader(),
                    new Class<?>[]{listenerClass},
                    (lp, lm, la) -> {
                        if ("onSharedPreferenceChanged".equals(lm.getName())) {
                            if (la[1] == null) {
                                loadAllM.invoke(null, la[0]);
                            } else {
                                updateKeyM.invoke(null, la[0], la[1]);
                            }
                        }
                        return null;
                    });
            listenerF.set(null, cfgListener);
            spClass.getMethod("registerOnSharedPreferenceChangeListener", listenerClass)
                    .invoke(spProxy, cfgListener);

            // Hot reads MUST serve purely from volatile cache with ZERO SharedPreferences reads!
            int readsBeforeHotLoop = spReadCalls.get();
            for (int i = 0; i < 1000; i++) {
                check(ColgramConfig.isCyberThemeEnabled(), "cached cyberThemeEnabled=true");
                check(ColgramConfig.isAntiDeleteEnabled(), "cached antiDeleteEnabled=true");
                check(!ColgramConfig.isAntiDeleteHighlightEnabled(), "cached antiDeleteHighlightEnabled=false");
                check(!ColgramConfig.isUnlimitedMemoryEnabled(), "cached unlimitedMemoryEnabled=false");
                check(!ColgramConfig.isSandboxStorageEnabled(), "cached sandboxStorageEnabled=false");
                check(ColgramConfig.isGhostReadEnabled(), "cached ghostReadEnabled=true");
                check(ColgramConfig.isGhostOnlineEnabled(), "cached ghostOnlineEnabled=true");
                check(ColgramConfig.isDcRemapEnabled(), "cached dcRemapEnabled=true");
            }
            check(spReadCalls.get() == readsBeforeHotLoop,
                    "hot ColgramConfig getters must perform 0 SharedPreferences reads once loaded, delta="
                            + (spReadCalls.get() - readsBeforeHotLoop));

            // Setters update volatile cache AND SharedPreferences synchronously
            ColgramConfig.setAntiDeleteEnabled(false);
            ColgramConfig.setUnlimitedMemoryEnabled(true);
            ColgramConfig.setSandboxStorageEnabled(true);
            ColgramConfig.setQuickGhostMode(false);
            check(!ColgramConfig.isAntiDeleteEnabled() && Boolean.FALSE.equals(store.get("anti_delete_enabled")),
                    "setAntiDeleteEnabled updates volatile cache + prefs");
            check(ColgramConfig.isUnlimitedMemoryEnabled() && Boolean.TRUE.equals(store.get("unlimited_memory_enabled")),
                    "setUnlimitedMemoryEnabled updates volatile cache + prefs");
            check(ColgramConfig.isSandboxStorageEnabled(), "setSandboxStorageEnabled updates volatile cache");
            check(!ColgramConfig.isGhostReadEnabled() && !ColgramConfig.isGhostOnlineEnabled()
                    && !ColgramConfig.isQuickGhostActive(), "setQuickGhostMode(false) updates all ghost volatile fields");

            // External SharedPreferences mutation must propagate via OnSharedPreferenceChangeListener
            Object extEd = spClass.getMethod("edit").invoke(spProxy);
            editorClass.getMethod("putBoolean", String.class, boolean.class)
                    .invoke(extEd, "cyber_theme_enabled", false);
            editorClass.getMethod("putBoolean", String.class, boolean.class)
                    .invoke(extEd, "anti_delete_highlight", true);
            editorClass.getMethod("putBoolean", String.class, boolean.class)
                    .invoke(extEd, "dc_remap_enabled", false);
            editorClass.getMethod("apply").invoke(extEd);
            check(!ColgramConfig.isCyberThemeEnabled(), "listener syncs cyberThemeEnabled=false");
            check(ColgramConfig.isAntiDeleteHighlightEnabled(), "listener syncs antiDeleteHighlightEnabled=true");
            check(!ColgramConfig.isDcRemapEnabled(), "listener syncs dcRemapEnabled=false");

            // Bulk clear() + reload listener sync (key == null)
            store.put("anti_delete_enabled", true);
            onChangedMethod.invoke(cfgListener, spProxy, null);
            check(ColgramConfig.isAntiDeleteEnabled(), "null-key reload syncs antiDeleteEnabled=true");

            // getQuickBarItems() returns a defensive mutable copy without corrupting cache
            ColgramConfig.setQuickBarItems(Arrays.asList("proxy", "koliti"));
            List<String> qb = ColgramConfig.getQuickBarItems();
            check(qb.equals(Arrays.asList("proxy", "koliti")), "quickBarItems matches");
            qb.add("mutated");
            check(ColgramConfig.getQuickBarItems().equals(Arrays.asList("proxy", "koliti")),
                    "mutating returned quickBarItems list must not mutate volatile cache");

            // Cross-thread volatile visibility check
            final CountDownLatch flipped = new CountDownLatch(1);
            final CountDownLatch observed = new CountDownLatch(1);
            Thread reader = new Thread(() -> {
                try {
                    if (flipped.await(5, TimeUnit.SECONDS)) {
                        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                        while (System.nanoTime() < deadline) {
                            if (ColgramConfig.isCyberThemeEnabled()) {
                                observed.countDown();
                                return;
                            }
                        }
                    }
                } catch (InterruptedException ignored) {}
            }, "colgram-config-reader");
            reader.setDaemon(true);
            reader.start();
            ColgramConfig.setCyberThemeEnabled(true);
            flipped.countDown();
            check(observed.await(5, TimeUnit.SECONDS),
                    "reader thread observes volatile cyberThemeEnabled flip immediately");
        } finally {
            prefsF.set(null, null);
            listenerF.set(null, prevListener);
            dcLoadedF.setBoolean(null, false);
            // Restore default static values so downstream tests see clean uninitialized defaults
            Object defaultSp = java.lang.reflect.Proxy.newProxyInstance(
                    ColgramConfig.class.getClassLoader(),
                    new Class<?>[]{spClass},
                    (p, m, a) -> {
                        String n = m.getName();
                        if ("getBoolean".equals(n)) {
                            if ("ghost_read_receipts".equals(a[0]) || "ghost_typing_indicator".equals(a[0])) {
                                return Boolean.FALSE;
                            }
                            return a[1];
                        }
                        if ("getString".equals(n) || "getInt".equals(n) || "getLong".equals(n)) return a[1];
                        if ("contains".equals(n)) return Boolean.FALSE;
                        return null;
                    });
            loadAllM.invoke(null, defaultSp);
            dcLoadedF.setBoolean(null, false);
            prefsF.set(null, prevPrefs);
        }
    }

    private static void testColgramDatabaseBlockedUsersAndBatchedDeletes() throws Exception {
        // Allocate ColgramDatabase without invoking Android SDK SQLiteOpenHelper Stub! constructor.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        java.lang.reflect.Field theUnsafeF = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafeF.setAccessible(true);
        Object unsafe = theUnsafeF.get(null);
        java.lang.reflect.Method alloc = unsafeClass.getMethod("allocateInstance", Class.class);
        ColgramDatabase db = (ColgramDatabase) alloc.invoke(unsafe, ColgramDatabase.class);

        setPrivateField(db, "deletedMessagesByDialog", new java.util.concurrent.ConcurrentHashMap<>());
        setPrivateField(db, "deletedDialogsCache", java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>()));
        setPrivateField(db, "blockedUsersCache", java.util.concurrent.ConcurrentHashMap.newKeySet());
        setPrivateField(db, "pendingPurgesDuringLoad", java.util.concurrent.ConcurrentHashMap.newKeySet());
        setPrivateField(db, "cacheLoadLock", new Object());
        setPrivateField(db, "blockedCacheLock", new Object());
        setPrivateField(db, "deletedCachesLoaded", Boolean.TRUE);
        setPrivateField(db, "deletedCacheLoadStarted", Boolean.TRUE);
        setPrivateField(db, "blockedUsersLoaded", Boolean.TRUE);

        // Verify schema bumped to DATABASE_VERSION == 6
        java.lang.reflect.Field dbVerF = ColgramDatabase.class.getDeclaredField("DATABASE_VERSION");
        dbVerF.setAccessible(true);
        check(dbVerF.getInt(null) == 6, "ColgramDatabase.DATABASE_VERSION must be 6");

        // 1. O(1) blocked-users cache semantics (0 SQLite calls when loaded; ignores uid=0)
        check(!db.isNotificationsBlockedFrom(0L), "uid=0 never blocked");
        check(!db.isNotificationsBlockedFrom(9001L), "unblocked user initially false");
        db.blockNotificationsFrom(0L);
        check(!db.isNotificationsBlockedFrom(0L), "blockNotificationsFrom(0) ignored");
        db.blockNotificationsFrom(9001L);
        db.blockNotificationsFrom(9002L);
        check(db.isNotificationsBlockedFrom(9001L) && db.isNotificationsBlockedFrom(9002L),
                "blocked users immediately visible via O(1) blockedUsersCache");
        db.unblockNotificationsFrom(9001L);
        check(!db.isNotificationsBlockedFrom(9001L) && db.isNotificationsBlockedFrom(9002L),
                "unblockNotificationsFrom removes only target user from cache");

        // 2. Batched markMessagesDeleted populates per-dialog cache, filters invalid IDs, preserves isolation
        long dialogA = -10011223344L;
        long dialogB = 555666777L;
        db.markMessagesDeleted(0L, Arrays.asList(1, 2, 3));
        check(!db.isMessageDeleted(0L, 1), "dialogId=0 batch ignored");
        db.markMessagesDeleted(dialogA, Arrays.asList(10, 20, 0, null, 30, 20));
        check(db.isMessageDeleted(dialogA, 10)
                        && db.isMessageDeleted(dialogA, 20)
                        && db.isMessageDeleted(dialogA, 30),
                "markMessagesDeleted populates all valid IDs in dialogA cache");
        check(!db.isMessageDeleted(dialogA, 0), "messageId=0 ignored in batch");
        check(!db.isMessageDeleted(dialogB, 10), "dialogA deletions never leak to dialogB");

        List<Integer> idsA = db.getDeletedMessageIdsForDialog(dialogA);
        check(idsA.size() == 3 && idsA.containsAll(Arrays.asList(10, 20, 30)),
                "getDeletedMessageIdsForDialog returns cached IDs O(1), got=" + idsA);
        check(db.getDeletedMessageIdsForDialog(dialogB).isEmpty(), "dialogB deleted IDs empty");

        db.removeMessageDeleted(dialogA, 20);
        check(!db.isMessageDeleted(dialogA, 20) && db.isMessageDeleted(dialogA, 10),
                "removeMessageDeleted removes single ID from dialog cache");

        // 3. pendingPurgesDuringLoad tracks purges made before async preload finishes
        setPrivateField(db, "deletedCachesLoaded", Boolean.FALSE);
        db.removeMessageDeleted(dialogA, 10);
        @SuppressWarnings("unchecked")
        java.util.Set<String> pendingPurges = (java.util.Set<String>) getPrivateField(db, "pendingPurgesDuringLoad");
        check(pendingPurges.contains(dialogA + ":10"),
                "purge during preload recorded in pendingPurgesDuringLoad");
        check(!db.isMessageDeleted(dialogA, 10), "purged ID stays absent after preload catches Stub");
    }

    private static void setPrivateField(Object target, String name, Object value) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object getPrivateField(Object target, String name) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }
}
