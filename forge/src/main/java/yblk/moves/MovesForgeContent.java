package yblk.moves;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import yblk.moves.entity.ZiplineEntity;
import yblk.moves.item.ZiplineItem;

public final class MovesForgeContent {

    private static final DeferredRegister<Item> ITEMS = DeferredRegister.create(ForgeRegistries.ITEMS, Constants.MOD_ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, Constants.MOD_ID);

    private static final RegistryObject<Item> ROPE = ITEMS.register("zipline_rope",
            () -> new ZiplineItem(false, new Item.Properties().stacksTo(64)));
    private static final RegistryObject<Item> CHAIN = ITEMS.register("zipline_chain",
            () -> new ZiplineItem(true, new Item.Properties().stacksTo(64)));
    public static final RegistryObject<EntityType<ZiplineEntity>> ZIPLINE = ENTITIES.register("zipline",
            () -> EntityType.Builder.<ZiplineEntity>of(ZiplineEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(64)
                    .updateInterval(10)
                    .build(Constants.MOD_ID + ":zipline"));

    private MovesForgeContent() {
    }

    public static void register() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        ITEMS.register(bus);
        ENTITIES.register(bus);
        bus.addListener(MovesForgeContent::creative);
        bus.addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent event) -> event.enqueueWork(() -> {
            MovesContent.ZIPLINE_ROPE = ROPE.get();
            MovesContent.ZIPLINE_CHAIN = CHAIN.get();
            MovesContent.ZIPLINE = ZIPLINE.get();
        }));
    }

    private static void creative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ROPE);
            event.accept(CHAIN);
        }
    }
}
