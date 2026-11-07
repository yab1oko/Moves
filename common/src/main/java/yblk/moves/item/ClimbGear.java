package yblk.moves.item;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** A hoe or a pickaxe in each hand. Right mouse climbs a flat wall. */
public final class ClimbGear {

    private ClimbGear() {
    }

    public static boolean equipped(Player player) {
        return pair(player, true) || pair(player, false);
    }

    private static boolean pair(Player player, boolean hoe) {
        return matches(player.getMainHandItem(), hoe) && matches(player.getOffhandItem(), hoe);
    }

    private static boolean matches(ItemStack stack, boolean hoe) {
        if (stack.isEmpty()) {
            return false;
        }
        return stack.is(hoe ? ItemTags.HOES : ItemTags.PICKAXES);
    }
}
