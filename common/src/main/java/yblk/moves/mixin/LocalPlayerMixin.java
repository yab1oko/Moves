package yblk.moves.mixin;

import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yblk.moves.client.ClientParkourInput;
import yblk.moves.parkour.ParkourPlayer;

@Mixin(LocalPlayer.class)
public class LocalPlayerMixin {

    @Inject(method = "aiStep", at = @At("HEAD"))
    private void moves$input(CallbackInfo ci) {
        ClientParkourInput.capture((LocalPlayer) (Object) this);
    }

    /** input.tick() re-enables jumping — kill it again while charging. */
    @Inject(method = "aiStep", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/player/Input;tick(ZF)V", shift = At.Shift.AFTER))
    private void moves$suppressChargedJump(CallbackInfo ci) {
        LocalPlayer player = (LocalPlayer) (Object) this;
        ParkourPlayer data = ParkourPlayer.get(player);
        if (data.jumpCharge > 0 || data.input.jumpCharge > 0) {
            player.input.jumping = false;
            player.setJumping(false);
        }
        if (data.prone || data.mode == yblk.moves.parkour.ParkourMode.SLIDE) {
            player.input.shiftKeyDown = false;
            player.setShiftKeyDown(false);
            if (data.mode == yblk.moves.parkour.ParkourMode.SLIDE) {
                player.setPose(net.minecraft.world.entity.Pose.STANDING);
            }
        }
    }
}
