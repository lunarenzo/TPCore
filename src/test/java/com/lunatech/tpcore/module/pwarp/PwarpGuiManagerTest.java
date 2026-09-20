package com.lunatech.tpcore.module.pwarp;

import com.lunatech.tpcore.module.pwarp.gui.PwarpInventoryHolder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PwarpGuiManagerTest {

    @Test
    @DisplayName("PwarpInventoryHolder correctly retains page, totalPages and ViewType state")
    void testInventoryHolderState() {
        PwarpInventoryHolder holder = new PwarpInventoryHolder(PwarpInventoryHolder.ViewType.ALL_WARPS, 1, 5);

        assertEquals(PwarpInventoryHolder.ViewType.ALL_WARPS, holder.getViewType());
        assertEquals(1, holder.getPage());
        assertEquals(5, holder.getTotalPages());
        assertNull(holder.getInventory());
    }

    @Test
    @DisplayName("PwarpInventoryHolder supports MY_WARPS view mode")
    void testInventoryHolderOwnerMode() {
        PwarpInventoryHolder holder = new PwarpInventoryHolder(PwarpInventoryHolder.ViewType.MY_WARPS, 0, 1);

        assertEquals(PwarpInventoryHolder.ViewType.MY_WARPS, holder.getViewType());
        assertEquals(0, holder.getPage());
        assertEquals(1, holder.getTotalPages());
    }
}
