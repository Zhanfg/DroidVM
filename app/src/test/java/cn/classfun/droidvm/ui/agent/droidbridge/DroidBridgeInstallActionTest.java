// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.agent.droidbridge;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.classfun.droidvm.ui.agent.base.AgentVM;

public final class DroidBridgeInstallActionTest {
    @Test
    public void installScriptSupportsBothRescueShareBackends() {
        var vm = new AgentVM();
        var action = new DroidBridgeInstallAction(vm);
        var script = action.buildActionScript();

        assertTrue(script.contains("mount -t virtiofs droidbridge-tools"));
        assertTrue(script.contains("mount -t 9p -o trans=virtio,version=9p2000.L,ro"));
        assertTrue(script.contains("/usr/libexec/droidbridge-agent"));
        assertTrue(script.contains("droidbridge-agent.service"));
        assertTrue(script.contains("Conflicts=serial-getty@ttyS3.service"));
        assertTrue(script.contains("Environment=DROIDBRIDGE_SERIAL=/dev/ttyS3"));
        assertTrue(script.contains("#!/sbin/openrc-run"));
        assertTrue(script.contains("export DROIDBRIDGE_SERIAL=/dev/ttyS3"));
    }
}
