// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.linuxapps;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import cn.classfun.droidvm.lib.store.vm.DroidBridgeConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.utils.ThreadUtils;

/**
 * Event-driven Linux app catalog refresh.
 *
 * <p>No polling service is kept alive. A VM entering RUNNING gets a short bounded retry window
 * while its guest agent starts, then the coordinator goes idle again.</p>
 */
public final class LinuxAppSyncCoordinator {
    private static final String TAG = "LinuxAppSync";
    private static final int MAX_ATTEMPTS = 12;
    private static final long RETRY_MS = 750;
    private static final Set<UUID> IN_FLIGHT = ConcurrentHashMap.newKeySet();

    private LinuxAppSyncCoordinator() {
    }

    public static void onVmRunning(@NonNull Context context, @NonNull UUID vmId) {
        var appContext = context.getApplicationContext();
        ThreadUtils.runOnPool(() -> {
            var store = new VMStore();
            if (!store.load(appContext)) return;
            var vm = store.findById(vmId);
            if (vm == null || !DroidBridgeConfig.isEnabled(vm.item)) return;
            if (!IN_FLIGHT.add(vmId)) return;
            new Handler(Looper.getMainLooper()).postDelayed(
                () -> attempt(appContext, vmId, 0), RETRY_MS);
        });
    }

    private static void attempt(@NonNull Context context, @NonNull UUID vmId, int attempt) {
        DroidBridgeClient.syncApps(
            context,
            vmId.toString(),
            apps -> {
                IN_FLIGHT.remove(vmId);
                Log.i(TAG, fmt("Synced %d Linux apps for VM %s", apps.size(), vmId));
            },
            message -> {
                if (attempt >= MAX_ATTEMPTS) {
                    IN_FLIGHT.remove(vmId);
                    Log.w(TAG, fmt("Linux app sync gave up for VM %s: %s", vmId, message));
                    return;
                }
                new Handler(Looper.getMainLooper()).postDelayed(
                    () -> attempt(context, vmId, attempt + 1), RETRY_MS);
            }
        );
    }
}
