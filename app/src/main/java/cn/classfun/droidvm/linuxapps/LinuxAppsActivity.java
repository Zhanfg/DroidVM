// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.linuxapps;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.concurrent.atomic.AtomicInteger;

import cn.classfun.droidvm.R;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.store.vm.DroidBridgeConfig;
import cn.classfun.droidvm.lib.store.vm.VMStore;

/**
 * Android-facing catalog of Linux desktop applications discovered through DroidBridge.
 *
 * <p>The persistent registry is useful even while a VM is stopped. Refresh talks only to running
 * DroidBridge-enabled VMs; launching one app delegates wake/start to LinuxAppLaunchActivity.</p>
 */
public final class LinuxAppsActivity extends AppCompatActivity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private MaterialToolbar toolbar;
    private LinearProgressIndicator progress;
    private LinearLayout appList;
    private TextView empty;
    private boolean refreshing;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_linux_apps);
        toolbar = findViewById(R.id.toolbar);
        progress = findViewById(R.id.progress);
        appList = findViewById(R.id.app_list);
        empty = findViewById(R.id.empty);

        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.inflateMenu(R.menu.menu_linux_apps);
        toolbar.setOnMenuItemClickListener(this::onToolbarMenuItem);
        render();
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private boolean onToolbarMenuItem(@NonNull MenuItem item) {
        if (item.getItemId() != R.id.menu_refresh) return false;
        refreshRunningVms();
        return true;
    }

    private void render() {
        var registry = new LinuxAppRegistry(this);
        var apps = registry.listAll();
        apps.sort(Comparator
            .comparing((LinuxAppDescriptor a) -> a.name.toLowerCase())
            .thenComparing(a -> a.appId));

        var store = new VMStore();
        store.load(this);
        var vmNames = new HashMap<String, String>();
        store.forEach((id, vm) -> vmNames.put(id.toString(), vm.getName()));

        appList.removeAllViews();
        empty.setVisibility(apps.isEmpty() ? VISIBLE : GONE);
        for (var app : apps)
            appList.addView(buildAppCard(app, vmNames.getOrDefault(app.vmId, app.vmId)));
    }

    @NonNull
    private MaterialCardView buildAppCard(
        @NonNull LinuxAppDescriptor app,
        @NonNull String vmName
    ) {
        int pad = dp(16);
        var card = new MaterialCardView(this);
        var cardParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        cardParams.bottomMargin = dp(12);
        card.setLayoutParams(cardParams);

        var body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(pad, pad, pad, pad);
        card.addView(body);

        var title = new TextView(this);
        title.setText(app.name.isEmpty() ? app.appId : app.name);
        title.setTextSize(18);
        body.addView(title);

        var detail = new TextView(this);
        var description = app.genericName.isEmpty()
            ? getString(R.string.linux_apps_vm, vmName)
            : getString(R.string.linux_apps_detail, app.genericName, vmName);
        detail.setText(description);
        detail.setPadding(0, dp(4), 0, dp(8));
        body.addView(detail);

        var actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(android.view.Gravity.END);
        body.addView(actions);

        var launch = new MaterialButton(
            this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        launch.setText(R.string.linux_apps_launch);
        launch.setOnClickListener(v -> launch(app));
        actions.addView(launch);

        var pin = new MaterialButton(this);
        pin.setText(R.string.linux_apps_pin);
        pin.setOnClickListener(v -> pin(app));
        var pinParams = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        pinParams.setMarginStart(dp(8));
        actions.addView(pin, pinParams);
        return card;
    }

    private void launch(@NonNull LinuxAppDescriptor app) {
        var intent = new Intent(this, LinuxAppLaunchActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(LinuxAppLaunchActivity.EXTRA_VM_ID, app.vmId);
        intent.putExtra(LinuxAppLaunchActivity.EXTRA_APP_ID, app.appId);
        startActivity(intent);
    }

    private void pin(@NonNull LinuxAppDescriptor app) {
        if (!LinuxShortcutPublisher.requestPinned(this, app))
            Toast.makeText(this, R.string.linux_apps_pin_unavailable, Toast.LENGTH_SHORT).show();
    }

    private void refreshRunningVms() {
        if (refreshing) return;
        refreshing = true;
        progress.setVisibility(VISIBLE);

        DaemonConnection.getInstance().buildRequest("vm_list")
            .onResponse(this::onVmList)
            .onUnsuccessful(resp -> mainHandler.post(() ->
                finishRefresh(0, 1)))
            .onError(error -> mainHandler.post(() ->
                finishRefresh(0, 1)))
            .invoke();
    }

    private void onVmList(@NonNull JSONObject response) {
        var running = new HashSet<String>();
        var arr = response.optJSONArray("data");
        if (arr != null) for (int i = 0; i < arr.length(); i++) {
            var item = arr.optJSONObject(i);
            if (item == null) continue;
            if ("running".equalsIgnoreCase(item.optString("state", "")))
                running.add(item.optString("id", ""));
        }

        var store = new VMStore();
        store.load(this);
        var targets = new ArrayList<String>();
        store.forEach((id, vm) -> {
            var idString = id.toString();
            if (running.contains(idString) && DroidBridgeConfig.isEnabled(vm.item))
                targets.add(idString);
        });

        if (targets.isEmpty()) {
            mainHandler.post(() -> {
                refreshing = false;
                progress.setVisibility(GONE);
                render();
                Toast.makeText(
                    this, R.string.linux_apps_refresh_none, Toast.LENGTH_SHORT).show();
            });
            return;
        }

        var pending = new AtomicInteger(targets.size());
        var success = new AtomicInteger();
        var failed = new AtomicInteger();
        for (var vmId : targets) {
            DroidBridgeClient.syncApps(
                this,
                vmId,
                apps -> {
                    success.incrementAndGet();
                    if (pending.decrementAndGet() == 0)
                        mainHandler.post(() -> finishRefresh(success.get(), failed.get()));
                },
                message -> {
                    failed.incrementAndGet();
                    if (pending.decrementAndGet() == 0)
                        mainHandler.post(() -> finishRefresh(success.get(), failed.get()));
                }
            );
        }
    }

    private void finishRefresh(int success, int failed) {
        refreshing = false;
        progress.setVisibility(GONE);
        render();
        Toast.makeText(
            this,
            getString(R.string.linux_apps_refresh_result, success, failed),
            Toast.LENGTH_SHORT
        ).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
