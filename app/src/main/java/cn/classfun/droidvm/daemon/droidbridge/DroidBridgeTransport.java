// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.daemon.droidbridge;

import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.io.IOException;
import java.util.Arrays;

import cn.classfun.droidvm.lib.natives.UnixHelper;

/**
 * One-shot framed JSON transport to the guest DroidBridge agent.
 *
 * <p>Connections are intentionally short-lived for the app catalog and launch control plane:
 * when no Linux app is being opened, there is no idle poller waking the phone. Window events will
 * use a separate long-lived channel only while a seamless GUI session exists.</p>
 */
public final class DroidBridgeTransport {
    public static final int MAX_FRAME_BYTES = 1024 * 1024;
    private static final int CONNECT_TIMEOUT_MS = 1500;
    private static final int RESPONSE_TIMEOUT_MS = 3000;

    private DroidBridgeTransport() {
    }

    @NonNull
    public static JSONObject request(int cid, int port, @NonNull JSONObject request)
        throws IOException {
        byte[] payload = request.toString().getBytes(UTF_8);
        if (payload.length == 0 || payload.length > MAX_FRAME_BYTES)
            throw new IOException("DroidBridge request frame is out of range");

        int fd = UnixHelper.nativeVsockConnect(cid, port, CONNECT_TIMEOUT_MS);
        if (fd < 0)
            throw new IOException(fmt("DroidBridge vsock connect failed: errno=%d", -fd));

        try {
            writeFrame(fd, payload);
            var response = readFrame(fd);
            try {
                return new JSONObject(new String(response, UTF_8));
            } catch (Exception e) {
                throw new IOException("DroidBridge returned invalid JSON", e);
            }
        } finally {
            UnixHelper.nativeCloseFd(fd);
        }
    }

    private static void writeFrame(int fd, @NonNull byte[] payload) throws IOException {
        var header = new byte[]{
            (byte) (payload.length >>> 24),
            (byte) (payload.length >>> 16),
            (byte) (payload.length >>> 8),
            (byte) payload.length
        };
        writeAll(fd, header);
        writeAll(fd, payload);
    }

    private static void writeAll(int fd, @NonNull byte[] data) throws IOException {
        int offset = 0;
        while (offset < data.length) {
            byte[] chunk = offset == 0 ? data : Arrays.copyOfRange(data, offset, data.length);
            int n = UnixHelper.nativeWrite(fd, chunk, chunk.length);
            if (n <= 0) throw new IOException("DroidBridge vsock write failed");
            offset += n;
        }
    }

    @NonNull
    private static byte[] readFrame(int fd) throws IOException {
        byte[] header = readExact(fd, 4);
        int size = ((header[0] & 0xff) << 24)
            | ((header[1] & 0xff) << 16)
            | ((header[2] & 0xff) << 8)
            | (header[3] & 0xff);
        if (size <= 0 || size > MAX_FRAME_BYTES)
            throw new IOException("DroidBridge response frame is out of range");
        return readExact(fd, size);
    }

    @NonNull
    private static byte[] readExact(int fd, int size) throws IOException {
        var out = new byte[size];
        var chunk = new byte[Math.min(16 * 1024, Math.max(size, 1))];
        int offset = 0;
        long deadline = android.os.SystemClock.elapsedRealtime() + RESPONSE_TIMEOUT_MS;

        while (offset < size) {
            long remain = deadline - android.os.SystemClock.elapsedRealtime();
            if (remain <= 0) throw new IOException("DroidBridge response timed out");
            int poll = UnixHelper.nativePollIn(fd, (int) Math.min(remain, Integer.MAX_VALUE));
            if (poll == 0) throw new IOException("DroidBridge response timed out");
            if (poll < 0) throw new IOException("DroidBridge vsock closed before response");

            int want = Math.min(chunk.length, size - offset);
            int n = UnixHelper.nativeRead(fd, chunk, want);
            if (n <= 0) throw new IOException("DroidBridge vsock read failed");
            System.arraycopy(chunk, 0, out, offset, n);
            offset += n;
        }
        return out;
    }
}
