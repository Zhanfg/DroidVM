// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.linuxapps;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.daemon.DaemonConnection;

/**
 * Unprivileged Android-side client for the authenticated daemon DroidBridge proxy.
 */
public final class DroidBridgeClient {
    public interface AppsCallback {
        void onApps(@NonNull List<LinuxAppDescriptor> apps);
    }

    public interface LaunchCallback {
        void onLaunch(@NonNull String launchId, int pid);
    }

    public interface ErrorCallback {
        void onError(@NonNull String message);
    }

    private DroidBridgeClient() {
    }

    public static void syncApps(
        @NonNull Context context,
        @NonNull String vmId,
        @NonNull AppsCallback success,
        @NonNull ErrorCallback failure
    ) {
        var payload = new JSONObject();
        try {
            payload.put("op", "apps.list");
        } catch (Exception e) {
            failure.onError("Unable to build DroidBridge request");
            return;
        }

        request(vmId, payload, response -> {
            try {
                var arr = response.optJSONArray("apps");
                if (arr == null) arr = new JSONArray();
                var apps = new ArrayList<LinuxAppDescriptor>();
                for (int i = 0; i < arr.length(); i++) {
                    var src = arr.optJSONObject(i);
                    if (src == null) continue;
                    try {
                        var copy = new JSONObject(src.toString());
                        copy.put("vm_id", vmId);
                        apps.add(new LinuxAppDescriptor(copy));
                    } catch (Exception ignored) {
                        // One malformed desktop entry must not hide the rest of the catalog.
                    }
                }
                var registry = new LinuxAppRegistry(context);
                registry.replaceForVm(vmId, apps);
                LinuxShortcutPublisher.publishDynamic(context, apps);
                success.onApps(apps);
            } catch (Exception e) {
                failure.onError("Unable to store Linux application catalog");
            }
        }, failure);
    }

    public static void launchApp(
        @NonNull LinuxAppDescriptor app,
        @NonNull LaunchCallback success,
        @NonNull ErrorCallback failure
    ) {
        var payload = new JSONObject();
        try {
            payload.put("op", "apps.launch");
            payload.put("app_id", app.appId);
            payload.put("files", new JSONArray());
            payload.put("uris", new JSONArray());
            payload.put("display_session", "auto");
        } catch (Exception e) {
            failure.onError("Unable to build Linux application launch request");
            return;
        }

        request(app.vmId, payload, response -> {
            var launchId = response.optString("launch_id", "");
            int pid = response.optInt("pid", -1);
            if (launchId.isEmpty()) {
                failure.onError("DroidBridge did not return a launch id");
                return;
            }
            success.onLaunch(launchId, pid);
        }, failure);
    }

    private interface ResponseCallback {
        void onResponse(@NonNull JSONObject response);
    }

    private static void request(
        @NonNull String vmId,
        @NonNull JSONObject payload,
        @NonNull ResponseCallback success,
        @NonNull ErrorCallback failure
    ) {
        DaemonConnection.getInstance().buildRequest("droidbridge_request")
            .put("vm_id", vmId)
            .put("request", payload)
            .onResponse(resp -> {
                var bridge = resp.optJSONObject("bridge_response");
                if (bridge == null) {
                    failure.onError("DroidBridge response is missing");
                    return;
                }
                if (!bridge.optBoolean("ok", false)) {
                    failure.onError(fmt(
                        "%s: %s",
                        bridge.optString("error", "DROIDBRIDGE_ERROR"),
                        bridge.optString("message", "request failed")
                    ));
                    return;
                }
                success.onResponse(bridge);
            })
            .onUnsuccessful(resp -> failure.onError(
                resp.optString("message", "DroidBridge request was rejected")))
            .onError(error -> failure.onError(
                error.getMessage() == null ? "DroidBridge request failed" : error.getMessage()))
            .invoke();
    }
}
