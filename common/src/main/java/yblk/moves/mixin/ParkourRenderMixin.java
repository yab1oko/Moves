package yblk.moves.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yblk.moves.parkour.ParkourMode;
import yblk.moves.parkour.ParkourPlayer;

/**
 * Drops the whole model without moving limb pivots apart.
 * A per-bone Y offset sent the slide legs into the head.
 */
@Mixin(LivingEntityRenderer.class)
public class ParkourRenderMixin {

    @Inject(method = "setupRotations(Lnet/minecraft/world/entity/LivingEntity;Lcom/mojang/blaze3d/vertex/PoseStack;FFF)V",
            at = @At("RETURN"))
    private void moves$sitAndCrawl(LivingEntity entity, PoseStack poseStack, float age, float yaw, float partial,
                                   CallbackInfo ci) {
        if (!(entity instanceof Player player)) {
            return;
        }
        ParkourPlayer data = ParkourPlayer.get(player);
        if (data.mode == ParkourMode.SLIDE) {
            poseStack.translate(0.0D, -0.48D, 0.0D);
        } else if (data.prone) {
            poseStack.translate(0.0D, -0.06D, 0.0D);
        }
    }
}
