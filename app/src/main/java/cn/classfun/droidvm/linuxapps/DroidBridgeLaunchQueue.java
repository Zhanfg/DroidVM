// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.linuxapps;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/**
 * Crash-safe handoff between Android task launch and the future DroidBridge transport.
 *
 * <p>The queue is deliberately narrow: app ids only, no shell command strings.</p>
 */
public final class DroidBridgeLaunchQueue {
    private static final String FILE_NAME = "droidbridge_launch_queue.json";
    private static final int MAX_PENDING = 64;

    private DroidBridgeLaunchQueue() {
    }

    @NonNull
    public static synchronized String enqueue(
        @NonNull Context context,
        @NonNull LinuxAppDescriptor app
    ) {
        try {
            var file = new File(context.getFilesDir(), FILE_NAME);
            var root = file.isFile()
                ? new JSONObject(Files.readString(file.toPath(), StandardCharsets.UTF_8))
                : new JSONObject();
            var arr = root.optJSONArray("pending");
            if (arr == null) arr = new JSONArray();

            // Keep the newest bounded set.
            var bounded = new JSONArray();
            int start = Math.max(0, arr.length() - (MAX_PENDING - 1));
            for (int i = start; i < arr.length(); i++) bounded.put(arr.get(i));

            var id = UUID.randomUUID().toString();
            var req = new JSONObject();
            req.put("launch_id", id);
            req.put("created_at", System.currentTimeMillis());
            req.put("vm_id", app.vmId);
            req.put("app_id", app.appId);
            req.put("files", new JSONArray());
            req.put("uris", new JSONArray());
            bounded.put(req);

            root.put("version", 1);
            root.put("pending", bounded);
            try (var os = new FileOutputStream(file, false)) {
                os.write(root.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.getFD().sync();
            }
            return id;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to queue Linux app launch", e);
        }
    }
}
