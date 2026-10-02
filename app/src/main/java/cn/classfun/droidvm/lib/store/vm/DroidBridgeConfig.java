// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.lib.store.vm;

import androidx.annotation.NonNull;

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
