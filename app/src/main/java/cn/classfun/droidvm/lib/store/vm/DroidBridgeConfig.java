// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;

import java.io.File;

import java.util.UUID;

import cn.classfun.droidvm.lib.store.base.DataItem;

/**
 * Stable configuration for the optional DroidBridge host/guest control plane.
 *
 * <p>The vsock CID is derived from the VM UUID instead of allocated from global mutable state.
 * That keeps config compact, survives export/import without another counter, and is deterministic
 * on both the UI and daemon sides. The 30-bit payload plus a 1024 base stays clear of the
 * well-known host/reserved CIDs while remaining representable by Android's sockaddr_vm.</p>
 */
public final class DroidBridgeConfig {
    public static final String KEY_ENABLED = "droidbridge_enabled";
    public static final String KEY_SERIAL_FALLBACK = "_droidbridge_serial_fallback";
    public static final String SERIAL_STREAM = "serial4";
    public static final String SERIAL_GUEST_DEVICE = "/dev/ttyS3";
    public static final int AGENT_PORT = 4050;
    private static final long CID_MASK = 0x3fff_ffffL;
    private static final long CID_BASE = 1024L;

    private DroidBridgeConfig() {
    }

    public static boolean isEnabled(@NonNull DataItem item) {
        return item.optBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(@NonNull DataItem item, boolean enabled) {
        item.set(KEY_ENABLED, enabled);
    }

    /**
     * The current Linux/Android crosvm vsock backend is vhost-vsock, so the host node must exist.
     * This is a pure capability check: it never opens the device and is safe to call from UI or
     * daemon code.
     */
    public static boolean hostVsockAvailable() {
        return new File("/dev/vhost-vsock").exists();
    }

    /**
     * Selects the host/guest control transport for one daemon-side VM session.
     *
     * <p>When vhost-vsock is unavailable, Serial 4 is borrowed only if it is still the historical
     * sink. User-configured serial ports are never overwritten. The marker is session-only because
     * the daemon receives a copy of the persisted VM config.</p>
     */
    public static boolean prepareSessionTransport(@NonNull DataItem item) {
        return prepareSessionTransport(item, hostVsockAvailable());
    }

    static boolean prepareSessionTransport(@NonNull DataItem item, boolean vsockAvailable) {
        item.remove(KEY_SERIAL_FALLBACK);
        if (!isEnabled(item) || vsockAvailable) return false;

        VMSerialConfig.ensureDefaults(item);
        for (var port : VMSerialConfig.listOf(item)) {
            if (port.getHardware() != SerialHardware.SERIAL || port.getNum() != 4) continue;
            if (port.isConsole() || port.getBackend() != SerialBackend.SINK) return false;
            port.setBackend(SerialBackend.APP_CONSOLE);
            item.set(KEY_SERIAL_FALLBACK, true);
            return true;
        }
        return false;
    }

    public static boolean isSerialFallbackActive(@NonNull DataItem item) {
        return item.optBoolean(KEY_SERIAL_FALLBACK, false);
    }

    /**
     * Derives one stable guest CID. Collisions are rare but not impossible; the daemon checks
     * active DroidBridge VMs before start once multi-VM seamless apps are enabled.
     */
    public static long cidFor(@NonNull UUID id) {
        long x = id.getMostSignificantBits() ^ Long.rotateLeft(id.getLeastSignificantBits(), 29);
        x ^= x >>> 33;
        x *= 0xff51afd7ed558ccdL;
        x ^= x >>> 33;
        return CID_BASE + (x & CID_MASK);
    }

    public static long cidFor(@NonNull VMConfig config) {
        return cidFor(config.getId());
    }
}
