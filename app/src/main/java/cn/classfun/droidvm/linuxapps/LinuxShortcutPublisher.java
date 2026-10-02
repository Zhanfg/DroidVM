// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.linuxapps;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import cn.classfun.droidvm.R;

/**
 * Publishes Linux desktop entries into Android's launcher-facing shortcut API.
 */
public final class LinuxShortcutPublisher {
    private LinuxShortcutPublisher() {
    }

    /**
     * Updates the dynamic catalog for launcher long-press/search surfaces.
     *
     * <p>Launchers cap this list per Activity, so the stable catalog stays in
     * {@link LinuxAppRegistry}; this publishes only what the current launcher accepts.</p>
     */
    public static void publishDynamic(
        @NonNull Context context,
        @NonNull List<LinuxAppDescriptor> apps
    ) {
        var manager = context.getSystemService(ShortcutManager.class);
        if (manager == null) return;
        int max = Math.max(0, manager.getMaxShortcutCountPerActivity());
        var shortcuts = new ArrayList<ShortcutInfo>();
        for (int i = 0; i < apps.size() && i < max; i++)
            shortcuts.add(build(context, apps.get(i)));
        manager.setDynamicShortcuts(shortcuts);
    }

    /**
     * Requests a real home-screen icon. Android intentionally leaves final approval to the
     * launcher/user for ordinary apps; the later System Edition can integrate more deeply without
     * making the core design depend on OEM platform-signature permissions.
     */
    public static boolean requestPinned(
        @NonNull Context context,
        @NonNull LinuxAppDescriptor app
    ) {
        var manager = context.getSystemService(ShortcutManager.class);
        if (manager == null || !manager.isRequestPinShortcutSupported()) return false;
        return manager.requestPinShortcut(build(context, app), null);
    }

    @NonNull
    private static ShortcutInfo build(
        @NonNull Context context,
        @NonNull LinuxAppDescriptor app
    ) {
        var launch = new Intent(context, LinuxAppLaunchActivity.class)
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_NEW_DOCUMENT |
                Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            .putExtra(LinuxAppLaunchActivity.EXTRA_VM_ID, app.vmId)
            .putExtra(LinuxAppLaunchActivity.EXTRA_APP_ID, app.appId);

        var label = app.name.isEmpty() ? app.appId : app.name;
        var builder = new ShortcutInfo.Builder(context, app.shortcutId())
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(launch);
        return builder.build();
    }
}
