// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.daemon.droidbridge;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import cn.classfun.droidvm.daemon.vm.VMInstance;
import cn.classfun.droidvm.lib.store.vm.DroidBridgeConfig;

/**
 * Fallback DroidBridge transport over the VM's private fourth 16550 serial port.
 *
 * <p>The protocol is newline-delimited JSON only on this transport. The guest agent puts the tty
 * into raw mode, so crosvm's pipe carries exactly one JSON object per line. Every response must
 * echo the request id and contain an explicit {@code ok} field; this prevents a tty echo from being
 * mistaken for the reply.</p>
 */
public final class DroidBridgeSerialTransport {
    private static final long TIMEOUT_MS = 5000;
    private static final int MAX_RESPONSE_CHARS = 1 << 20;

    private DroidBridgeSerialTransport() {
    }

    @NonNull
    public static JSONObject request(
        @NonNull VMInstance vm,
        @NonNull JSONObject payload
    ) throws Exception {
        if (!DroidBridgeConfig.isSerialFallbackActive(vm.item))
            throw new IllegalStateException("DroidBridge serial fallback is not active");

        var stream = vm.getStream(DroidBridgeConfig.SERIAL_STREAM);
        if (stream == null || !stream.isReadable() || !stream.isWritable())
            throw new IllegalStateException("DroidBridge serial stream is not ready");

        var expectedId = payload.optString("id", "");
        if (expectedId.isEmpty())
            throw new IllegalArgumentException("DroidBridge request id is missing");

        synchronized (stream) {
            long mark = stream.mark();
            if (!stream.write(fmt("%s\n", payload.toString())))
                throw new IllegalStateException("Failed to write DroidBridge serial request");

            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                var chunk = stream.since(mark);
                if (chunk.length() > MAX_RESPONSE_CHARS)
                    throw new IllegalStateException("DroidBridge serial response exceeded limit");

                for (var line : chunk.split("\\r?\\n")) {
                    var trimmed = line.trim();
                    if (!trimmed.startsWith("{")) continue;
                    try {
                        var response = new JSONObject(trimmed);
                        if (!response.has("ok")) continue;
                        if (expectedId.equals(response.optString("id", "")))
                            return response;
                    } catch (Exception ignored) {
                    }
                }
                Thread.sleep(20);
            }
        }
        throw new IllegalStateException("DroidBridge serial request timed out");
    }
}
