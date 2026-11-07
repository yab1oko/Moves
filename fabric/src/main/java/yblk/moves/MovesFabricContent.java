package yblk.moves;

import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import yblk.moves.entity.ZiplineEntity;
import yblk.moves.item.ZiplineItem;

public final class MovesFabricContent {

    private MovesFabricContent() {
    }

    public static void register() {
        MovesContent.ZIPLINE_ROPE = Registry.register(BuiltInRegistries.ITEM, id("zipline_rope"),
                new ZiplineItem(ZiplineItem.STYLE_ROPE, new Item.Properties().stacksTo(64)));
        MovesContent.ZIPLINE_ROPE_DARK = Registry.register(BuiltInRegistries.ITEM, id("zipline_rope_dark"),
                new ZiplineItem(ZiplineItem.STYLE_DARK_ROPE, new Item.Properties().stacksTo(64)));
        MovesContent.ZIPLINE_CHAIN = Registry.register(BuiltInRegistries.ITEM, id("zipline_chain"),
                new ZiplineItem(ZiplineItem.STYLE_CHAIN, new Item.Properties().stacksTo(64)));
        MovesContent.ZIPLINE = Registry.register(BuiltInRegistries.ENTITY_TYPE, id("zipline"),
                EntityType.Builder.<ZiplineEntity>of(ZiplineEntity::new, MobCategory.MISC)
                        .sized(0.5F, 0.5F)
                        .clientTrackingRange(64)
                        .updateInterval(10)
                        .build(Constants.MOD_ID + ":zipline"));
        ItemGroupEvents.modifyEntriesEvent(CreativeModeTabs.TOOLS_AND_UTILITIES).register(entries -> {
            entries.accept(MovesContent.ZIPLINE_ROPE);
            entries.accept(MovesContent.ZIPLINE_ROPE_DARK);
            entries.accept(MovesContent.ZIPLINE_CHAIN);
        });
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation(Constants.MOD_ID, path);
    }
}
