// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.linuxapps;

import static java.nio.charset.StandardCharsets.UTF_8;
import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

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

    public static final class Capabilities {
        public final boolean terminal;
        public final boolean supportsFiles;
        public final boolean supportsUris;

        public Capabilities(boolean terminal, boolean supportsFiles, boolean supportsUris) {
            this.terminal = terminal;
            this.supportsFiles = supportsFiles;
            this.supportsUris = supportsUris;
        }
    }

    public LinuxAppDescriptor(
        @NonNull String vmId,
        @NonNull String appId,
        @NonNull String name,
        @NonNull String genericName,
        @NonNull String iconKey,
        @NonNull Capabilities capabilities
    ) {
        this.vmId = validateVmId(vmId);
        this.appId = validateAppId(appId);
        this.name = bounded(name, "name");
        this.genericName = bounded(genericName, "genericName");
        this.iconKey = bounded(iconKey, "iconKey");
        this.terminal = capabilities.terminal;
        this.supportsFiles = capabilities.supportsFiles;
        this.supportsUris = capabilities.supportsUris;
    }

    public LinuxAppDescriptor(@NonNull JSONObject obj) throws JSONException {
        this(
            obj.getString("vm_id"),
            obj.getString("app_id"),
            obj.getString("name"),
            obj.optString("generic_name", ""),
            obj.optString("icon_key", ""),
            new Capabilities(
                obj.optBoolean("terminal", false),
                obj.optBoolean("supports_files", false),
                obj.optBoolean("supports_uris", false)
            )
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

    /** Stable shortcut id without exposing a long/untrusted desktop-entry id to the launcher. */
    @NonNull
    public String shortcutId() {
        var material = fmt("%s|%s", vmId, appId).getBytes(UTF_8);
        return fmt("linux-%s", UUID.nameUUIDFromBytes(material));
    }

    @NonNull
    private static String validateVmId(@NonNull String value) {
        UUID.fromString(value);
        return value;
    }

    public static boolean isValidAppId(@NonNull String value) {
        return !value.isEmpty() && value.length() <= MAX_APP_ID
            && value.matches("[A-Za-z0-9._:+@-]+");
    }

    @NonNull
    private static String validateAppId(@NonNull String value) {
        if (!isValidAppId(value))
            throw new IllegalArgumentException("Invalid Linux app id");
        return value;
    }

    @NonNull
    private static String bounded(@NonNull String value, @NonNull String field) {
        if (value.length() > MAX_TEXT)
            throw new IllegalArgumentException(fmt("%s is too long", field));
        return value;
    }
}
