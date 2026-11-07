package yblk.moves;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import yblk.moves.entity.ZiplineEntity;

/** Filled in by the loader when the registries run. */
public final class MovesContent {

    public static Item ZIPLINE_ROPE;
    public static Item ZIPLINE_ROPE_DARK;
    public static Item ZIPLINE_CHAIN;
    public static EntityType<ZiplineEntity> ZIPLINE;

    private MovesContent() {
    }
}
