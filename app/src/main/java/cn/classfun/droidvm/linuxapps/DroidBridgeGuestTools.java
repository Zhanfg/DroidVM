// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.linuxapps;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Materializes the static Linux guest agent from the APK into a tiny host-side payload directory.
 *
 * <p>The directory is later exposed only to a temporary rescue VM through virtio-fs. It is made
 * non-writable after extraction so guest root cannot mutate the copy that future VMs trust.</p>
 */
public final class DroidBridgeGuestTools {
    public static final String SHARE_TAG = "droidbridge-tools";
    public static final String AGENT_NAME = "droidbridge-agent";
    private static final String ASSET =
        "droidbridge/arm64-v8a/droidbridge-agent";
    private static final String PAYLOAD_DIR = "droidbridge-payload";

    private DroidBridgeGuestTools() {
    }

    @NonNull
    public static File preparePayloadDir(@NonNull Context context) throws IOException {
        if (!supportsCurrentAbi())
            throw new IOException("DroidBridge guest tools are available only for arm64 guests");

        var dir = new File(context.getFilesDir(), PAYLOAD_DIR);
        if (dir.exists() && !dir.setWritable(true, true))
            throw new IOException("Cannot make DroidBridge payload directory writable");
        if (!dir.exists() && !dir.mkdirs())
            throw new IOException("Cannot create DroidBridge payload directory");

        var target = new File(dir, AGENT_NAME);
        byte[] payload;
        try (var input = context.getAssets().open(ASSET)) {
            payload = input.readAllBytes();
        } catch (IOException e) {
            throw new IOException(
                "This APK does not contain the DroidBridge guest agent", e);
        }
        if (payload.length == 0)
            throw new IOException("DroidBridge guest agent asset is empty");

        boolean current = false;
        if (target.isFile()) {
            try {
                current = sha256(target).equals(sha256(payload));
            } catch (Exception ignored) {
                current = false;
            }
        }

        if (!current) {
            if (target.exists()) target.setWritable(true, true);
            var temp = new File(dir, fmt("%s.tmp", AGENT_NAME));
            try (var out = new FileOutputStream(temp, false)) {
                out.write(payload);
                out.flush();
                out.getFD().sync();
            }
            if (!temp.setExecutable(true, false))
                throw new IOException("Cannot mark temporary DroidBridge agent executable");
            if (target.exists() && !target.delete())
                throw new IOException("Cannot replace old DroidBridge guest agent");
            if (!temp.renameTo(target))
                throw new IOException("Cannot install DroidBridge guest agent payload");
        }

        // Rescue virtio-fs serves this directory as the Android app uid, so read+execute is enough.
        if (!target.setReadable(true, false) || !target.setExecutable(true, false))
            throw new IOException("Cannot expose DroidBridge guest agent payload");
        target.setWritable(false, false);
        dir.setWritable(false, false);
        dir.setReadable(true, false);
        dir.setExecutable(true, false);
        return dir;
    }

    private static boolean supportsCurrentAbi() {
        for (var abi : Build.SUPPORTED_ABIS)
            if ("arm64-v8a".equals(abi.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    @NonNull
    private static String sha256(@NonNull File file) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        try (var in = new java.io.FileInputStream(file)) {
            var buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    @NonNull
    private static String sha256(@NonNull byte[] data) throws Exception {
        var md = MessageDigest.getInstance("SHA-256");
        return hex(md.digest(data));
    }

    @NonNull
    private static String hex(@NonNull byte[] data) {
        var out = new StringBuilder(data.length * 2);
        for (byte b : data) out.append(fmt("%02x", b & 0xff));
        return out.toString();
    }
}
