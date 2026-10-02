// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.linuxapps;\n\nimport static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small persistent cache of applications reported by droidbridge-agent.
 *
 * <p>The guest is always the source of truth. This cache exists so launcher shortcuts and the
 * Android app catalog remain useful while the VM is stopped.</p>
 */
public final class LinuxAppRegistry {
    private static final String TAG = "LinuxAppRegistry";
    private static final String FILE_NAME = "linux_apps.json";
    private static final int MAX_APPS = 4096;

    private final File file;

    public LinuxAppRegistry(@NonNull Context context) {
        file = new File(context.getFilesDir(), FILE_NAME);
    }

    @NonNull
    public synchronized List<LinuxAppDescriptor> listAll() {
        return new ArrayList<>(read().values());
    }

    @NonNull
    public synchronized List<LinuxAppDescriptor> listForVm(@NonNull String vmId) {
        var out = new ArrayList<LinuxAppDescriptor>();
        for (var app : read().values())
            if (vmId.equals(app.vmId)) out.add(app);
        return out;
    }

    @Nullable
    public synchronized LinuxAppDescriptor find(@NonNull String vmId, @NonNull String appId) {
        return read().get(key(vmId, appId));
    }

    /**
     * Replaces exactly one VM's catalog after a successful apps.list response.
     */
    public synchronized void replaceForVm(
        @NonNull String vmId,
        @NonNull List<LinuxAppDescriptor> apps
    ) {
        // Validate the VM id even when apps is empty.
        java.util.UUID.fromString(vmId);
        if (apps.size() > MAX_APPS)
            throw new IllegalArgumentException("Guest app catalog exceeds limit");

        var all = read();
        all.entrySet().removeIf(e -> vmId.equals(e.getValue().vmId));
        for (var app : apps) {
            if (!vmId.equals(app.vmId))
                throw new IllegalArgumentException("Catalog contains an app for another VM");
            all.put(key(app.vmId, app.appId), app);
        }
        write(all);
    }

    public synchronized void removeVm(@NonNull String vmId) {
        var all = read();
        if (all.entrySet().removeIf(e -> vmId.equals(e.getValue().vmId)))
            write(all);
    }

    @NonNull
    private Map<String, LinuxAppDescriptor> read() {
        var out = new LinkedHashMap<String, LinuxAppDescriptor>();
        if (!file.isFile()) return out;
        try {
            var root = new JSONObject(Files.readString(file.toPath(), StandardCharsets.UTF_8));
            var arr = root.optJSONArray("apps");
            if (arr == null) return out;
            int n = Math.min(arr.length(), MAX_APPS);
            for (int i = 0; i < n; i++) {
                var obj = arr.optJSONObject(i);
                if (obj == null) continue;
                try {
                    var app = new LinuxAppDescriptor(obj);
                    out.put(key(app.vmId, app.appId), app);
                } catch (Exception e) {
                    Log.w(TAG, "Skipping malformed cached Linux app", e);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to read Linux app cache", e);
        }
        return out;
    }

    private void write(@NonNull Map<String, LinuxAppDescriptor> apps) {
        try {
            var arr = new JSONArray();
            for (var app : apps.values()) arr.put(app.toJson());
            var root = new JSONObject();
            root.put("version", 1);
            root.put("apps", arr);

            var tmp = new File(file.getParentFile(), fmt("%s.tmp", file.getName()));
            try (var os = new FileOutputStream(tmp, false)) {
                os.write(root.toString().getBytes(StandardCharsets.UTF_8));
                os.flush();
                os.getFD().sync();
            }

            if (!tmp.renameTo(file)) {
                try (var os = new FileOutputStream(file, false)) {
                    os.write(root.toString().getBytes(StandardCharsets.UTF_8));
                    os.flush();
                    os.getFD().sync();
                }
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to persist Linux app registry", e);
        }
    }

    @NonNull
    private static String key(@NonNull String vmId, @NonNull String appId) {
        return vmId + "\0" + appId;
    }
}
