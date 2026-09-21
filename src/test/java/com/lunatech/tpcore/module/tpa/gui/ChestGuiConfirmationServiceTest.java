package com.lunatech.tpcore.module.tpa.gui;

import com.lunatech.tpcore.config.model.TpaConfig;
import com.lunatech.tpcore.module.tpa.gui.impl.ChestGuiConfirmationService;
import com.lunatech.tpcore.module.tpa.model.TpaRequest;
import com.lunatech.tpcore.module.tpa.model.TpaType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ChestGuiConfirmationServiceTest {

    @Test
    void testConfirmationHolderStoresRequest() {
        TpaRequest request = new TpaRequest(UUID.randomUUID(), UUID.randomUUID(), TpaType.TPA_TO, System.currentTimeMillis());
        TpaConfirmationHolder holder = new TpaConfirmationHolder(request);

        assertNotNull(holder.getRequest());
        assertEquals(request.senderId(), holder.getRequest().senderId());
        assertNull(holder.getInventory());
    }

    @Test
    void testChestGuiInstantiation() {
        ChestGuiConfirmationService service = new ChestGuiConfirmationService(TpaConfig::createDefault);
        assertNotNull(service);
    }
}
