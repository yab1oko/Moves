package yblk.moves.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yblk.moves.parkour.ParkourMode;
import yblk.moves.parkour.ParkourPlayer;

/**
 * Shift must not crouch during a slide or a crawl. The key still starts those moves;
 * once they are active, sneak pose and sneak speed stay off.
 */
@Mixin(Entity.class)
public class EntityShiftMixin {

    @Inject(method = "isCrouching", at = @At("HEAD"), cancellable = true)
    private void moves$noCrouchPose(CallbackInfoReturnable<Boolean> cir) {
        if (moves$parkourBlocksSneak()) {
            cir.setReturnValue(false);
        }
    }

    @Inject(method = "isShiftKeyDown", at = @At("HEAD"), cancellable = true)
    private void moves$noSneakWhileParkour(CallbackInfoReturnable<Boolean> cir) {
        if (moves$parkourBlocksSneak()) {
            cir.setReturnValue(false);
        }
    }

    private boolean moves$parkourBlocksSneak() {
        if (!((Object) this instanceof Player player)) {
            return false;
        }
        ParkourPlayer data = ParkourPlayer.get(player);
        return data.prone || data.mode == ParkourMode.SLIDE;
    }
}
