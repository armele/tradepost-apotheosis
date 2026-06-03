package com.deathfrog.tradepostapo;

import org.slf4j.Logger;

import com.deathfrog.mctradepost.compat.recycling.OptionalRecyclingProviders;
import com.deathfrog.tradepostapo.compat.recycling.ApotheosisRecyclingProvider;
import com.mojang.logging.LogUtils;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;

@Mod(TradePostAPOCompat.MODID)
public final class TradePostAPOCompat 
{
    public static final String MODID = "tradepostapo";
    public static final Logger LOGGER = LogUtils.getLogger();

    public TradePostAPOCompat(final IEventBus modEventBus, final ModContainer modContainer) 
    {
        modEventBus.addListener(this::commonSetup);
    }

    private void commonSetup(final FMLCommonSetupEvent event) 
    {
        event.enqueueWork(() -> {
            OptionalRecyclingProviders.register(new ApotheosisRecyclingProvider());
            LOGGER.info("Registered Apotheosis recycler compat provider with Trade Post for MineColonies.");
        });
    }
}
