// SPDX-License-Identifier: GPL-3.0-or-later
package cn.classfun.droidvm.lib.store.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.UUID;

public final class DroidBridgeConfigTest {
    @Test
    public void cidIsStableAndOutsideReservedRange() {
        var id = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        long a = DroidBridgeConfig.cidFor(id);
        long b = DroidBridgeConfig.cidFor(id);
        assertEquals(a, b);
        assertTrue(a >= 1024);
        assertTrue(a <= 0x400003ffL);
    }

    @Test
    public void ordinaryDistinctIdsDoNotCollapse() {
        long a = DroidBridgeConfig.cidFor(
            UUID.fromString("01234567-89ab-cdef-0123-456789abcdef"));
        long b = DroidBridgeConfig.cidFor(
            UUID.fromString("fedcba98-7654-3210-fedc-ba9876543210"));
        assertNotEquals(a, b);
    }
}
