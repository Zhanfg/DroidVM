// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.linuxapps;

import static java.nio.charset.StandardCharsets.UTF_8;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Android-side, non-executable description of one Linux desktop application.
 *
 * <p>Deliberately does not carry the desktop entry's Exec= line. Android stores only an opaque
 * app id; command resolution stays inside the guest agent so a launcher shortcut can never become
 * an arbitrary shell-command transport.</p>
 */
public final class LinuxAppDescriptor {
    private static final int MAX_TEXT = 512;
    private static final int MAX_APP_ID = 255;

    public final String vmId;
    public final String appId;
    public final String name;
    public final String genericName;
    public final String iconKey;
    public final boolean terminal;
    public final boolean supportsFiles;
    public final boolean supportsUris;

    public LinuxAppDescriptor(
        @NonNull String vmId,
        @NonNull String appId,
        @NonNull String name,
        @NonNull String genericName,
        @NonNull String iconKey,
        boolean terminal,
        boolean supportsFiles,
        boolean supportsUris
    ) {
        this.vmId = validateVmId(vmId);
        this.appId = validateAppId(appId);
        this.name = bounded(name, "name");
        this.genericName = bounded(genericName, "genericName");
        this.iconKey = bounded(iconKey, "iconKey");
        this.terminal = terminal;
        this.supportsFiles = supportsFiles;
        this.supportsUris = supportsUris;
    }

    public LinuxAppDescriptor(@NonNull JSONObject obj) throws JSONException {
        this(
            obj.getString("vm_id"),
            obj.getString("app_id"),
            obj.getString("name"),
            obj.optString("generic_name", ""),
            obj.optString("icon_key", ""),
            obj.optBoolean("terminal", false),
            obj.optBoolean("supports_files", false),
            obj.optBoolean("supports_uris", false)
        );
    }

    @NonNull
    public JSONObject toJson() throws JSONException {
        var out = new JSONObject();
        out.put("vm_id", vmId);
        out.put("app_id", appId);
        out.put("name", name);
        if (!genericName.isEmpty()) out.put("generic_name", genericName);
        if (!iconKey.isEmpty()) out.put("icon_key", iconKey);
        if (terminal) out.put("terminal", true);
        if (supportsFiles) out.put("supports_files", true);
        if (supportsUris) out.put("supports_uris", true);
        return out;
    }

    /**
     * Stable Android shortcut id without leaking a potentially long/untrusted desktop-entry id.
     */
    @NonNull
    public String shortcutId() {
        return "linux-" + UUID.nameUUIDFromBytes((vmId + "\0" + appId).getBytes(UTF_8));
    }

    @NonNull
    private static String validateVmId(@NonNull String value) {
        UUID.fromString(value);
        return value;
    }

    @NonNull
    private static String validateAppId(@NonNull String value) {
        if (value.isEmpty() || value.length() > MAX_APP_ID ||
            !value.matches("[A-Za-z0-9._:+@\\-]+"))
            throw new IllegalArgumentException("Invalid Linux app id");
        return value;
    }

    @NonNull
    private static String bounded(@NonNull String value, @NonNull String field) {
        if (value.length() > MAX_TEXT)
            throw new IllegalArgumentException(field + " is too long");
        return value;
    }
}
