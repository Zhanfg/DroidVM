// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.linuxapps;

import android.content.Context;

import androidx.annotation.NonNull;

import org.json.JSONArray;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.lib.daemon.DaemonConnection;

/** Android-side facade for the privileged daemon's DroidBridge operations. */
public final class DroidBridgeDaemonClient {
    private DroidBridgeDaemonClient() {
    }

    public interface AppsCallback {
        void onSuccess(@NonNull List<LinuxAppDescriptor> apps);
        void onError(@NonNull String message);
    }

    public interface LaunchCallback {
        void onSuccess(@NonNull String launchId, long pid);
        void onError(@NonNull String message);
    }

    public static void syncApps(
        @NonNull Context context,
        @NonNull String vmId,
        @NonNull AppsCallback callback
    ) {
        DaemonConnection.getInstance().buildRequest("linux_apps_list")
            .put("vm_id", vmId)
            .onResponse(response -> {
                try {
                    var apps = parseApps(vmId, response.optJSONArray("apps"));
                    new LinuxAppRegistry(context).replaceForVm(vmId, apps);
                    LinuxShortcutPublisher.publishDynamic(context, apps);
                    callback.onSuccess(apps);
                } catch (Exception e) {
                    callback.onError(messageOf(e));
                }
            })
            .onUnsuccessful(response ->
                callback.onError(response.optString("message", "Unable to list Linux apps")))
            .onError(error -> callback.onError(messageOf(error)))
            .invoke();
    }

    public static void launch(
        @NonNull String vmId,
        @NonNull String appId,
        @NonNull JSONArray files,
        @NonNull JSONArray uris,
        @NonNull LaunchCallback callback
    ) {
        DaemonConnection.getInstance().buildRequest("linux_app_launch")
            .put("vm_id", vmId)
            .put("app_id", appId)
            .put("files", files)
            .put("uris", uris)
            .onResponse(response -> callback.onSuccess(
                response.optString("launch_id", ""), response.optLong("pid", -1)))
            .onUnsuccessful(response ->
                callback.onError(response.optString("message", "Unable to launch Linux app")))
            .onError(error -> callback.onError(messageOf(error)))
            .invoke();
    }

    @NonNull
    private static List<LinuxAppDescriptor> parseApps(
        @NonNull String vmId,
        JSONArray array
    ) {
        var out = new ArrayList<LinuxAppDescriptor>();
        if (array == null) return out;
        for (int i = 0; i < array.length(); i++) {
            var obj = array.optJSONObject(i);
            if (obj == null) continue;
            var appId = obj.optString("app_id", "");
            if (!LinuxAppDescriptor.isValidAppId(appId)) continue;
            out.add(new LinuxAppDescriptor(
                vmId,
                appId,
                obj.optString("name", appId),
                obj.optString("generic_name", ""),
                obj.optString("icon_key", ""),
                new LinuxAppDescriptor.Capabilities(
                    obj.optBoolean("terminal", false),
                    obj.optBoolean("supports_files", false),
                    obj.optBoolean("supports_uris", false)
                )
            ));
        }
        return out;
    }

    @NonNull
    private static String messageOf(@NonNull Throwable error) {
        var message = error.getMessage();
        return message == null || message.isEmpty() ? error.getClass().getSimpleName() : message;
    }
}
