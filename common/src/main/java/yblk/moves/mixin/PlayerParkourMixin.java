package yblk.moves.mixin;

import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yblk.moves.parkour.ParkourController;
import yblk.moves.parkour.ParkourPlayer;

@Mixin(Player.class)
public class PlayerParkourMixin {

    /**
     * Grab: freeze standing pose (stops eye-height zoom).
     * Crawl: force swimming pose on ground.
     */
    @Inject(method = "updatePlayerPose", at = @At("HEAD"), cancellable = true)
    private void moves$pose(CallbackInfo ci) {
        Player player = (Player) (Object) this;
        ParkourPlayer data = ParkourPlayer.get(player);
        if (data.mode.locksBody() || data.mode == yblk.moves.parkour.ParkourMode.HANG) {
            player.setPose(Pose.STANDING);
            player.setSwimming(false);
            ci.cancel();
            return;
        }
        if (data.mode == yblk.moves.parkour.ParkourMode.SLIDE) {
            player.setShiftKeyDown(false);
            player.setPose(Pose.STANDING);
            player.setSwimming(false);
            ci.cancel();
            return;
        }
        if (data.prone) {
            player.setShiftKeyDown(false);
            player.setPose(Pose.SWIMMING);
            player.setSwimming(true);
            ci.cancel();
        }
    }

    @Inject(method = "aiStep", at = @At("RETURN"))
    private void moves$clearShiftAfterTick(CallbackInfo ci) {
        Player player = (Player) (Object) this;
        ParkourPlayer data = ParkourPlayer.get(player);
        if (data.mode != yblk.moves.parkour.ParkourMode.SLIDE && !data.prone) {
            ParkourController.stickGrab(player);
            return;
        }
        player.setShiftKeyDown(false);
        if (data.mode == yblk.moves.parkour.ParkourMode.SLIDE) {
            player.setPose(Pose.STANDING);
            player.setSwimming(false);
        }
        ParkourController.stickGrab((Player) (Object) this);
    }

    @Inject(method = "updatePlayerPose", at = @At("TAIL"))
    private void moves$poseTail(CallbackInfo ci) {
        ParkourController.afterPose((Player) (Object) this);
    }

    @Inject(method = "getDimensions", at = @At("HEAD"), cancellable = true)
    private void moves$box(Pose pose, CallbackInfoReturnable<EntityDimensions> cir) {
        ParkourPlayer data = ParkourPlayer.get((Player) (Object) this);
        if (data.mode.locksBody() || data.mode == yblk.moves.parkour.ParkourMode.HANG) {
            cir.setReturnValue(Player.STANDING_DIMENSIONS);
            return;
        }
        if (data.prone) {
            cir.setReturnValue(EntityDimensions.scalable(0.6F, 0.6F));
        }
    }

    @ModifyVariable(method = "causeFallDamage", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float moves$fall(float fallDistance) {
        return ParkourController.modifyFallDistance((Player) (Object) this, fallDistance);
    }
}
