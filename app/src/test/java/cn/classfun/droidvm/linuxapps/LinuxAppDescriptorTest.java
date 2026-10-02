// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.linuxapps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class LinuxAppDescriptorTest {
    private static final String VM = "01234567-89ab-cdef-0123-456789abcdef";

    @Test
    public void jsonRoundTripDoesNotCarryExec() throws Exception {
        var app = new LinuxAppDescriptor(
            VM, "org.example.Editor", "Editor", "Text Editor", "editor",
            false, true, true
        );
        JSONObject json = app.toJson();
        assertTrue(!json.has("exec"));

        var restored = new LinuxAppDescriptor(json);
        assertEquals(app.vmId, restored.vmId);
        assertEquals(app.appId, restored.appId);
        assertEquals(app.name, restored.name);
        assertEquals(app.shortcutId(), restored.shortcutId());
    }

    @Test
    public void appIdRejectsShellSyntax() {
        assertThrows(IllegalArgumentException.class, () -> new LinuxAppDescriptor(
            VM, "org.example.App;rm", "bad", "", "", false, false, false
        ));
    }
}
