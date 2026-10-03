// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.ui.agent.base;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import cn.classfun.droidvm.lib.store.disk.DiskStore;

public final class AgentVMSharedDirTest {
    @Test
    public void sharedDirSurvivesSerializationAndBuild() throws Exception {
        var vm = new AgentVM();
        vm.setOperationConsole("uart", "/dev/ttyAMA0");
        vm.addSharedDir("droidbridge-tools", "/data/local/tmp/payload");

        var json = vm.toJson();
        assertEquals(
            "/data/local/tmp/payload",
            json.getJSONObject("shared_dirs").getString("droidbridge-tools")
        );

        var restored = new AgentVM(new DiskStore(), json);
        var built = restored.buildVM();
        var dirs = built.item.opt("shared_dirs", null);
        assertNotNull(dirs);
        assertEquals(1, dirs.size());
        var dir = dirs.get(0);
        assertEquals("droidbridge-tools", dir.optString("tag", ""));
        assertEquals("/data/local/tmp/payload", dir.optString("path", ""));
        assertTrue(dir.optBoolean("readonly", false));
    }
}
