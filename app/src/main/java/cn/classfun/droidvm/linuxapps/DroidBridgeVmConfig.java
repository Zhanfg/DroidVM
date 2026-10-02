// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.linuxapps;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.lib.store.vm.SerialBackend;
import cn.classfun.droidvm.lib.store.vm.SerialHardware;
import cn.classfun.droidvm.lib.store.vm.VMConfig;
import cn.classfun.droidvm.lib.store.vm.VMSerialConfig;

/**
 * Reserves one private serial channel for DroidBridge control traffic.
 *
 * <p>Serial 4 already exists in crosvm's fixed 16550 quartet. Reusing the normally-sink fourth
 * port avoids adding another guest device and gives Linux a deterministic /dev/ttyS3 endpoint.
 * A user-owned Serial 4 configuration is never overwritten.</p>
 */
public final class DroidBridgeVmConfig {
    public static final String ENABLED_KEY = "droidbridge_enabled";
    public static final String STREAM_KEY = "droidbridge_stream";
    public static final String DEVICE_KEY = "droidbridge_device";

    public static final String STREAM_NAME = "serial4";
    public static final String GUEST_DEVICE = "/dev/ttyS3";

    private DroidBridgeVmConfig() {
    }

    /**
     * Enables the private bridge channel in-memory.
     *
     * @return true when the config changed
     */
    public static boolean ensureEnabled(@NonNull VMConfig config) {
        VMSerialConfig.ensureDefaults(config.item);

        VMSerialConfig serial4 = null;
        for (var port : VMSerialConfig.listOf(config.item)) {
            if (port.getHardware() == SerialHardware.SERIAL && port.getNum() == 4) {
                serial4 = port;
                break;
            }
        }
        if (serial4 == null)
            throw new IllegalStateException("Serial 4 is unavailable");
        if (serial4.isConsole())
            throw new IllegalStateException("Serial 4 is the guest console");
        if (serial4.getBackend() != SerialBackend.SINK
            && serial4.getBackend() != SerialBackend.APP_CONSOLE)
            throw new IllegalStateException("Serial 4 is already assigned to another backend");

        boolean changed = false;
        if (serial4.getBackend() != SerialBackend.APP_CONSOLE) {
            serial4.setBackend(SerialBackend.APP_CONSOLE);
            changed = true;
        }
        if (!config.item.optBoolean(ENABLED_KEY, false)) {
            config.item.set(ENABLED_KEY, true);
            changed = true;
        }
        if (!STREAM_NAME.equals(config.item.optString(STREAM_KEY, ""))) {
            config.item.set(STREAM_KEY, STREAM_NAME);
            changed = true;
        }
        if (!GUEST_DEVICE.equals(config.item.optString(DEVICE_KEY, ""))) {
            config.item.set(DEVICE_KEY, GUEST_DEVICE);
            changed = true;
        }
        return changed;
    }

    public static boolean isEnabled(@NonNull VMConfig config) {
        return config.item.optBoolean(ENABLED_KEY, false)
            && STREAM_NAME.equals(config.item.optString(STREAM_KEY, ""));
    }
}
