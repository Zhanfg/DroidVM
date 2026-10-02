// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright DroidVM contributors
package cn.classfun.droidvm.ui.agent.droidbridge;

import static cn.classfun.droidvm.lib.utils.StringUtils.fmt;

import androidx.annotation.NonNull;

import cn.classfun.droidvm.linuxapps.DroidBridgeGuestTools;
import cn.classfun.droidvm.ui.agent.base.AgentActionSpec;
import cn.classfun.droidvm.ui.agent.base.AgentVM;
import cn.classfun.droidvm.ui.agent.base.BaseAction;

/** Installs and enables the static DroidBridge guest agent in a Linux root disk. */
public final class DroidBridgeInstallAction extends BaseAction {
    public static final String TYPE = "install-droidbridge";

    public DroidBridgeInstallAction(@NonNull AgentVM vm) {
        this(vm, vm.addAction(TYPE));
    }

    public DroidBridgeInstallAction(
        @NonNull AgentVM vm,
        @NonNull AgentActionSpec spec
    ) {
        super(vm, spec);
    }

    @NonNull
    @Override
    protected String buildActionScript() {
        return String.join("\n",
            "FILESYSTEMS=$(blkid)",
            "echo \"$FILESYSTEMS\" | grep -q 'TYPE=\"btrfs\"' && modprobe btrfs >/dev/null 2>&1 || true",
            "echo \"$FILESYSTEMS\" | grep -q 'TYPE=\"xfs\"' && modprobe xfs >/dev/null 2>&1 || true",
            "echo \"$FILESYSTEMS\" | grep -q 'TYPE=\"f2fs\"' && modprobe f2fs >/dev/null 2>&1 || true",
            "TARGET_DEVICE=$(cat /run/droidvm-root-device 2>/dev/null || true)",
            "if [ -n \"$TARGET_DEVICE\" ]; then",
            "    mount -o rw \"$TARGET_DEVICE\" /mnt >/dev/null 2>&1 || TARGET_DEVICE=",
            "fi",
            "if [ -z \"$TARGET_DEVICE\" ]; then",
            "    for dev in $(echo \"$FILESYSTEMS\" | grep -E 'TYPE=\"(ext2|ext3|ext4|btrfs|xfs|f2fs)\"' | cut -d: -f1); do",
            "        if mount -o rw \"$dev\" /mnt >/dev/null 2>&1; then",
            "            if [ -f /mnt/etc/passwd ]; then TARGET_DEVICE=$dev; break; fi",
            "            umount /mnt >/dev/null 2>&1 || true",
            "        fi",
            "    done",
            "fi",
            "[ -n \"$TARGET_DEVICE\" ] || fail DROIDBRIDGE_ROOT_NOT_FOUND",
            "printf '%s\\n' \"$TARGET_DEVICE\" > /run/droidvm-root-device",
            "PAYLOAD=/run/droidbridge-tools",
            "mkdir -p \"$PAYLOAD\"",
            "modprobe virtiofs >/dev/null 2>&1 || true",
            "modprobe 9pnet_virtio >/dev/null 2>&1 || true",
            "modprobe 9p >/dev/null 2>&1 || true",
            fmt("mount -t virtiofs %s \"$PAYLOAD\" >/dev/null 2>&1 || " +
                "mount -t 9p -o trans=virtio,version=9p2000.L,ro %s \"$PAYLOAD\" >/dev/null 2>&1 || " +
                "fail DROIDBRIDGE_PAYLOAD_MOUNT_FAILED",
                DroidBridgeGuestTools.SHARE_TAG, DroidBridgeGuestTools.SHARE_TAG),
            fmt("[ -x \"$PAYLOAD/%s\" ] || fail DROIDBRIDGE_PAYLOAD_MISSING",
                DroidBridgeGuestTools.AGENT_NAME),
            "mkdir -p /mnt/usr/libexec || fail DROIDBRIDGE_INSTALL_FAILED",
            fmt("cp \"$PAYLOAD/%s\" /mnt/usr/libexec/droidbridge-agent || fail DROIDBRIDGE_INSTALL_FAILED",
                DroidBridgeGuestTools.AGENT_NAME),
            "chmod 0755 /mnt/usr/libexec/droidbridge-agent || fail DROIDBRIDGE_INSTALL_FAILED",
            "SERVICE_KIND=",
            "if [ -d /mnt/etc/systemd/system ] || [ -d /mnt/usr/lib/systemd/system ]; then",
            "    mkdir -p /mnt/etc/systemd/system /mnt/etc/systemd/system/multi-user.target.wants",
            "    cat > /mnt/etc/systemd/system/droidbridge-agent.service <<'DROIDBRIDGE_SYSTEMD'",
            "[Unit]",
            "Description=DroidBridge guest agent",
            "After=systemd-modules-load.service",
            "",
            "[Service]",
            "Type=simple",
            "ExecStart=/usr/libexec/droidbridge-agent",
            "Restart=on-failure",
            "RestartSec=1",
            "",
            "[Install]",
            "WantedBy=multi-user.target",
            "DROIDBRIDGE_SYSTEMD",
            "    ln -sf ../droidbridge-agent.service /mnt/etc/systemd/system/multi-user.target.wants/droidbridge-agent.service",
            "    SERVICE_KIND=systemd",
            "elif [ -d /mnt/etc/init.d ] && [ -d /mnt/etc/runlevels ]; then",
            "    cat > /mnt/etc/init.d/droidbridge-agent <<'DROIDBRIDGE_OPENRC'",
            "#!/sbin/openrc-run",
            "description=\"DroidBridge guest agent\"",
            "command=/usr/libexec/droidbridge-agent",
            "command_background=true",
            "pidfile=/run/droidbridge-agent.pid",
            "depend() {",
            "    need localmount",
            "    after modules",
            "}",
            "DROIDBRIDGE_OPENRC",
            "    chmod 0755 /mnt/etc/init.d/droidbridge-agent || fail DROIDBRIDGE_INSTALL_FAILED",
            "    mkdir -p /mnt/etc/runlevels/default",
            "    ln -sf /etc/init.d/droidbridge-agent /mnt/etc/runlevels/default/droidbridge-agent",
            "    SERVICE_KIND=openrc",
            "else",
            "    fail DROIDBRIDGE_INIT_UNSUPPORTED",
            "fi",
            "sync",
            "umount \"$PAYLOAD\" >/dev/null 2>&1 || true",
            "umount /mnt >/dev/null 2>&1 || fail UNMOUNT_FAILED",
            "marker \"DROIDBRIDGE:INSTALLED:$SERVICE_KIND\"",
            ""
        );
    }
}
