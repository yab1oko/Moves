package yblk.moves.mixin;

import net.minecraft.client.model.HumanoidModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yblk.moves.parkour.ParkourMode;
import yblk.moves.parkour.ParkourPlayer;

/**
 * Vanilla applies the sneak pose inside {@code setupAnim}, after the tick.
 * Clear it before that read so a slide or a crawl keeps the Blockbench pose.
 */
@Mixin(HumanoidModel.class)
public class HumanoidCrouchMixin {

    @Inject(method = "setupAnim(Lnet/minecraft/world/entity/LivingEntity;FFFFF)V", at = @At("HEAD"))
    private void moves$noSneakPose(LivingEntity entity, float limbSwing, float limbSwingAmount, float age, float yaw, float pitch, CallbackInfo ci) {
        if (!(entity instanceof Player player)) {
            return;
        }
        ParkourPlayer data = ParkourPlayer.get(player);
        if (data.mode == ParkourMode.SLIDE) {
            player.setShiftKeyDown(false);
            player.setPose(Pose.STANDING);
        } else if (data.prone) {
            player.setShiftKeyDown(false);
            player.setPose(Pose.SWIMMING);
            player.setSwimming(true);
        }
    }
}
