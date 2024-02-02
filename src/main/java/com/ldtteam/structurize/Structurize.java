package com.ldtteam.structurize;

import com.ldtteam.structurize.blueprints.v1.DataFixerUtils;
import com.ldtteam.structurize.blueprints.v1.DataVersion;
import com.ldtteam.structurize.config.ClientConfiguration;
import com.ldtteam.structurize.config.ServerConfiguration;
import com.ldtteam.common.config.Configurations;
import com.ldtteam.structurize.api.util.Log;
import com.ldtteam.structurize.api.util.constant.Constants;
import com.ldtteam.structurize.blocks.ModBlocks;
import com.ldtteam.structurize.event.ClientEventSubscriber;
import com.ldtteam.structurize.event.ClientLifecycleSubscriber;
import com.ldtteam.structurize.event.EventSubscriber;
import com.ldtteam.structurize.event.LifecycleSubscriber;
import com.ldtteam.structurize.items.ModItemGroups;
import com.ldtteam.structurize.items.ModItems;
import com.ldtteam.structurize.proxy.ClientProxy;
import com.ldtteam.structurize.proxy.IProxy;
import com.ldtteam.structurize.proxy.ServerProxy;
import com.ldtteam.structurize.blockentities.ModBlockEntities;
import com.ldtteam.structurize.storage.ClientFutureProcessor;
import com.ldtteam.structurize.storage.ServerFutureProcessor;
import com.ldtteam.structurize.storage.ClientStructurePackLoader;
import com.ldtteam.structurize.storage.ServerStructurePackLoader;
import com.ldtteam.structurize.storage.rendering.ServerPreviewDistributor;
import net.minecraft.util.datafix.DataFixers;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.DistExecutor;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.javafmlmod.FMLJavaModLoadingContext;
import net.neoforged.fml.javafmlmod.FMLModContainer;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.NotNull;

/**
 * Mod main class.
 * The value in annotation should match an entry in the META-INF/mods.toml file.
 */
@Mod(Constants.MOD_ID)
public class Structurize
{
    /**
     * The proxy.
     */
    public static final IProxy proxy = DistExecutor.safeRunForDist(() -> ClientProxy::new, () -> ServerProxy::new);

    /**
     * The config instance.
     */
    private static Configurations<ClientConfiguration, ServerConfiguration, ?> config;

    /**
     * Mod init, registers events to their respective busses
     */
    public Structurize(final FMLModContainer modContainer, final Dist dist)
    {
        final IEventBus modBus = modContainer.getEventBus();
        final IEventBus forgeBus = NeoForge.EVENT_BUS;
        
        config = new Configurations<>(modContainer, modBus, ClientConfiguration::new, ServerConfiguration::new, null);

        ModBlocks.BLOCKS.register(modBus);
        ModItems.ITEMS.register(modBus);
        ModBlockEntities.BLOCK_ENTITIES.register(modBus);
        ModItemGroups.TAB_REG.register(modBus);

        Mod.EventBusSubscriber.Bus.MOD.bus().get().register(LifecycleSubscriber.class);
        Mod.EventBusSubscriber.Bus.FORGE.bus().get().register(EventSubscriber.class);

        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> {
            ClientStructurePackLoader.onClientLoading();
            Mod.EventBusSubscriber.Bus.FORGE.bus().get().register(ClientStructurePackLoader.class);
            Mod.EventBusSubscriber.Bus.FORGE.bus().get().register(ClientFutureProcessor.class);
            Mod.EventBusSubscriber.Bus.MOD.bus().get().register(ClientLifecycleSubscriber.class);
            Mod.EventBusSubscriber.Bus.FORGE.bus().get().register(ClientEventSubscriber.class);
        });

        DistExecutor.unsafeRunWhenOn(Dist.DEDICATED_SERVER,  () -> ServerStructurePackLoader::onServerStarting);

        Mod.EventBusSubscriber.Bus.FORGE.bus().get().register(ServerStructurePackLoader.class);
        Mod.EventBusSubscriber.Bus.FORGE.bus().get().register(ServerPreviewDistributor.class);
        Mod.EventBusSubscriber.Bus.FORGE.bus().get().register(ServerFutureProcessor.class);


        Mod.EventBusSubscriber.Bus.MOD.bus().get().register(this.getClass());
        Mod.EventBusSubscriber.Bus.MOD.bus().get().register(ModItemGroups.class);

        if (DataFixerUtils.isVanillaDF)
        {
            if ((DataFixers.getDataFixer().getSchema(Integer.MAX_VALUE - 1).getVersionKey()) >= DataVersion.UPCOMING.getDataVersion() * 10)
            {
                throw new RuntimeException("You are trying to run old mod on much newer vanilla. Missing some newest data versions. Please update com/ldtteam/structures/blueprints/v1/DataVersion");
            }
            else if (!FMLEnvironment.production && DataVersion.CURRENT == DataVersion.UPCOMING)
            {
                throw new RuntimeException("Missing some newest data versions. Please update com/ldtteam/structures/blueprints/v1/DataVersion");
            }
        }
        else
        {
            Log.getLogger().error("----------------------------------------------------------------- \n "
                                    + "Invalid DataFixer detected, schematics might not paste correctly! \n"
                                    +  "The following DataFixer was added: " + DataFixers.getDataFixer().getClass() + "\n"
                                    + "-----------------------------------------------------------------");
        }
    }

    /**
     * Event handler for forge pre init event.
     *
     * @param event the forge pre init event.
     */
    @SubscribeEvent
    public static void preInit(@NotNull final FMLCommonSetupEvent event)
    {
        Network.getNetwork().registerCommonMessages();
    }


    /**
     * Get the config handler.
     *
     * @return the config handler.
     */
    public static Configurations<ClientConfiguration, ServerConfiguration, ?> getConfig()
    {
        return config;
    }
}
