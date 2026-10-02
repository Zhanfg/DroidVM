// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.daemon.linuxapps;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicLong;

import cn.classfun.droidvm.daemon.console.ConsoleStream;
import cn.classfun.droidvm.daemon.vm.VMInstance;
import cn.classfun.droidvm.lib.store.vm.VMState;

/**
 * Synchronous request/response transport over the private DroidBridge serial stream.
 *
 * <p>The stream is exclusive to this protocol. Requests are serialized per stream and responses
 * are newline-delimited JSON carrying the same numeric id. The daemon's normal stream reader keeps
 * collecting bytes into the in-memory ring buffer; this class waits only for bytes appended after
 * its own mark.</p>
 */
public final class DroidBridgeSerialClient {
    private static final AtomicLong NEXT_ID = new AtomicLong(1);
    private static final long DEFAULT_TIMEOUT_MS = 5000;
    private static final int MAX_RESPONSE_CHARS = 1 << 20;

    private DroidBridgeSerialClient() {
    }

    @NonNull
    public static JSONObject request(
        @NonNull VMInstance vm,
        @NonNull String streamName,
        @NonNull JSONObject payload
    ) throws Exception {
        if (vm.getState() != VMState.RUNNING)
            throw new IllegalStateException("Linux environment is not running");

        var stream = vm.getStream(streamName);
        if (stream == null || !stream.isReadable() || !stream.isWritable())
            throw new IllegalStateException(fmt(
                "DroidBridge stream %s is not ready", streamName));

        synchronized (stream) {
            long id = NEXT_ID.getAndIncrement();
            payload.put("id", id);
            long mark = stream.mark();
            if (!stream.write(fmt("%s\n", payload)))
                throw new IllegalStateException("Failed to write DroidBridge request");

            long deadline = System.currentTimeMillis() + DEFAULT_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                var chunk = stream.since(mark);
                if (chunk.length() > MAX_RESPONSE_CHARS)
                    throw new IllegalStateException("DroidBridge response exceeded limit");

                var lines = chunk.split("\\r?\\n");
                for (var line : lines) {
                    var trimmed = line.trim();
                    if (!trimmed.startsWith("{")) continue;
                    try {
                        var response = new JSONObject(trimmed);
                        if (response.optLong("id", -1) == id)
                            return response;
                    } catch (Exception ignored) {
                    }
                }
                Thread.sleep(20);
            }
            throw new IllegalStateException("DroidBridge request timed out");
        }
    }
}
