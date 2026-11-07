package yblk.moves.mixin;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import yblk.moves.parkour.ParkourController;
import yblk.moves.parkour.ParkourPlayer;

@Mixin(LivingEntity.class)
public class LivingEntityParkourMixin {

    @Shadow
    private float swimAmount;
    @Shadow
    private float swimAmountO;

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void moves$beforeAiStep(CallbackInfo ci) {
        if ((Object) this instanceof Player player) {
            ParkourController.beforeAiStep(player);
        }
    }

    @Inject(method = "aiStep", at = @At("RETURN"))
    private void moves$afterAiStep(CallbackInfo ci) {
        if ((Object) this instanceof Player player) {
            ParkourController.afterAiStep(player);
            ParkourPlayer data = ParkourPlayer.get(player);
            if (data.prone) {
                this.swimAmount = 1.0F;
                this.swimAmountO = 1.0F;
            } else if (data.mode == yblk.moves.parkour.ParkourMode.SLIDE) {
                this.swimAmount = 0.0F;
                this.swimAmountO = 0.0F;
            }
        }
    }

    /**
     * Vanilla keeps body within ~75° of look yaw — that fights wall lock and shakes the model.
     * Skip it while grabbing; camera (getYRot) stays free.
     */
    @Inject(method = "tickHeadTurn", at = @At("HEAD"), cancellable = true)
    private void moves$lockBodyToWall(float yRot, float animStep, CallbackInfoReturnable<Float> cir) {
        if ((Object) this instanceof Player player) {
            float locked = ParkourController.lockedBodyYaw(player);
            if (!Float.isNaN(locked)) {
                player.yBodyRot = locked;
                cir.setReturnValue(animStep);
            }
        }
    }

    @Inject(method = "jumpFromGround", at = @At("HEAD"), cancellable = true)
    private void moves$blockWhileCharging(CallbackInfo ci) {
        if ((Object) this instanceof Player player) {
            ParkourPlayer data = ParkourPlayer.get(player);
            if (data.jumpCharge > 0 || data.input.jumpCharge > 0 || data.input.enhanced) {
                ci.cancel();
            }
        }
    }

    @Inject(method = "jumpFromGround", at = @At("RETURN"))
    private void moves$afterJump(CallbackInfo ci) {
        if ((Object) this instanceof Player player) {
            ParkourController.afterJump(player);
        }
    }

    @ModifyVariable(method = "travel", at = @At("HEAD"), argsOnly = true)
    private Vec3 moves$proneSpeed(Vec3 travel) {
        if ((Object) this instanceof Player player) {
            return ParkourController.scaleFreeMove(player, travel);
        }
        return travel;
    }

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void moves$travel(Vec3 input, CallbackInfo ci) {
        if ((Object) this instanceof Player player && ParkourController.handleTravel(player, input)) {
            ci.cancel();
        }
    }
}
