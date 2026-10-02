// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.linuxapps;\n\nimport static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import cn.classfun.droidvm.DroidVMApp;
import cn.classfun.droidvm.lib.daemon.DaemonConnection;
import cn.classfun.droidvm.lib.daemon.ForegroundCallback;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMState;
import cn.classfun.droidvm.lib.store.vm.VMStore;
import cn.classfun.droidvm.lib.ui.UIContext;
import cn.classfun.droidvm.lib.utils.ThreadUtils;
import cn.classfun.droidvm.ui.vm.VMActions;

/**
 * Android task representing one Linux application launch.
 *
 * <p>M1/M2 owns the lifecycle and safe VM wake path. The rendering surface and live DroidBridge
 * transport are layered into this Activity next; keeping this Activity as the stable launcher
 * target means pinned home-screen icons will not need to be recreated when the renderer evolves.</p>
 */
public final class LinuxAppLaunchActivity extends AppCompatActivity implements ForegroundCallback {
    public static final String EXTRA_VM_ID = "droidterminal.vm_id";
    public static final String EXTRA_APP_ID = "droidterminal.app_id";

    private static final int MAX_DAEMON_QUERY_RETRIES = 8;
    private static final long RETRY_DELAY_MS = 750;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean wantOpenConsole = new AtomicBoolean(false);
    private final String callbackId =
        fmt("LinuxAppLaunchActivity:%s", Integer.toHexString(System.identityHashCode(this)));

    private TextView status;
    private ProgressBar progress;
    private LinuxAppDescriptor app;
    private VMConfig vm;
    private UUID vmId;
    private boolean launchQueued = false;

    @Override
    protected void onCreate(@Nullable Bundle state) {
        super.onCreate(state);
        buildPlaceholderSurface();
        loadLaunchTarget();
    }

    @Override
    protected void onStart() {
        super.onStart();
        var handler = ((DroidVMApp) getApplication()).getVMEventHandler();
        if (handler != null) handler.addForegroundCallback(callbackId, this);
    }

    @Override
    protected void onStop() {
        var handler = ((DroidVMApp) getApplication()).getVMEventHandler();
        if (handler != null) handler.removeForegroundCallback(callbackId);
        super.onStop();
    }

    private void buildPlaceholderSurface() {
        var root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);

        progress = new ProgressBar(this);
        root.addView(progress, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setTextSize(16);
        var lp = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        lp.topMargin = pad;
        root.addView(status, lp);

        setContentView(root);
        showStatus("Preparing Linux application...", true);
    }

    private void loadLaunchTarget() {
        var rawVmId = getIntent().getStringExtra(EXTRA_VM_ID);
        var appId = getIntent().getStringExtra(EXTRA_APP_ID);
        if (rawVmId == null || appId == null) {
            fail("Invalid Linux application shortcut.");
            return;
        }

        ThreadUtils.runOnPool(() -> {
            try {
                vmId = UUID.fromString(rawVmId);
                app = new LinuxAppRegistry(this).find(rawVmId, appId);
                var store = new VMStore();
                if (!store.load(this)) throw new IllegalStateException("VM registry is unavailable");
                vm = store.findById(vmId);
                if (app == null) throw new IllegalStateException(
                    "This Linux application is no longer installed");
                if (vm == null) throw new IllegalStateException(
                    "The Linux environment no longer exists");
                mainHandler.post(() -> {
                    setTitle(app.name);
                    showStatus(fmt("Waking %s...", app.name), true);
                    DaemonConnection.getInstance().connect();
                    queryVmState(0);
                });
            } catch (Exception e) {
                mainHandler.post(() -> fail(e.getMessage() == null
                    ? "Unable to resolve Linux application" : e.getMessage()));
            }
        });
    }

    private void queryVmState(int attempt) {
        if (isFinishing() || vmId == null) return;
        DaemonConnection.getInstance().buildRequest("vm_list")
            .onResponse(resp -> {
                VMState state = VMState.STOPPED;
                var arr = resp.optJSONArray("data");
                if (arr != null) for (int i = 0; i < arr.length(); i++) {
                    var item = arr.optJSONObject(i);
                    if (item == null || !vmId.toString().equals(item.optString("id", "")))
                        continue;
                    try {
                        state = VMState.valueOf(
                            item.optString("state", "stopped").toUpperCase());
                    } catch (Exception ignored) {
                        state = VMState.STOPPED;
                    }
                    break;
                }
                var resolved = state;
                mainHandler.post(() -> handleVmState(resolved));
            })
            .onUnsuccessful(resp -> mainHandler.post(() ->
                retryOrFail(attempt, resp.optString("message", "VM daemon rejected state query"))))
            .onError(error -> mainHandler.post(() ->
                retryOrFail(attempt, "Waiting for DroidTerminal daemon...")))
            .invoke();
    }

    private void retryOrFail(int attempt, @NonNull String message) {
        if (attempt >= MAX_DAEMON_QUERY_RETRIES) {
            fail(message);
            return;
        }
        showStatus(message, true);
        mainHandler.postDelayed(() -> queryVmState(attempt + 1), RETRY_DELAY_MS);
    }

    private void handleVmState(@NonNull VMState state) {
        if (launchQueued || vm == null || vmId == null) return;
        switch (state) {
            case RUNNING:
                enqueueLaunch();
                break;
            case SUSPENDED:
                showStatus("Resuming Linux environment...", true);
                VMActions.sendCommand(
                    "vm_resume", vmId, mainHandler, UIContext.fromActivity(this));
                break;
            case STARTING:
            case REBOOTING:
                showStatus("Waiting for Linux environment...", true);
                break;
            case STOPPING:
                showStatus("Linux environment is stopping; waiting...", true);
                mainHandler.postDelayed(() -> queryVmState(0), 800);
                break;
            case STOPPED:
            default:
                showStatus("Starting Linux environment...", true);
                // Reuse the mature preflight chain: disk-safety, guest protection, module, lend
                // mode and huge-page checks all run before the daemon sees the VM.
                VMActions.createAndStart(
                    vm, mainHandler, UIContext.fromActivity(this),
                    wantOpenConsole, null
                );
                break;
        }
    }

    @Override
    public void onVMStateChanged(UUID id, VMState state) {
        if (vmId == null || !vmId.equals(id)) return;
        if (state == VMState.RUNNING) {
            enqueueLaunch();
        } else if (state == VMState.STOPPED && !launchQueued) {
            fail("Linux environment stopped before the application could start.");
        }
    }

    @Override
    public void onVMExited(UUID id, String vmName, int exitCode, JSONObject data) {
        if (vmId != null && vmId.equals(id) && !launchQueued)
            fail(fmt("Linux environment exited (%d).", exitCode));
    }

    private void enqueueLaunch() {
        if (launchQueued || app == null) return;
        launchQueued = true;
        try {
            var launchId = DroidBridgeLaunchQueue.enqueue(this, app);
            // M2 replaces this queue-only handoff with a live vsock request. The Activity remains
            // open because it will become the app's actual Android surface in M4.
            showStatus(
                fmt("%s is queued.
DroidBridge transport is the next implementation step.
Launch ID: %s", app.name, launchId),
                false
            );
        } catch (Exception e) {
            fail("Unable to queue Linux application launch.");
        }
    }

    private void fail(@NonNull String message) {
        showStatus(message, false);
    }

    private void showStatus(@NonNull String message, boolean busy) {
        if (isFinishing() || isDestroyed()) return;
        status.setText(message);
        progress.setVisibility(busy ? ProgressBar.VISIBLE : ProgressBar.GONE);
    }
}
