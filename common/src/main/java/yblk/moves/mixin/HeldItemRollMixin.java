package yblk.moves.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yblk.moves.parkour.ParkourMode;
import yblk.moves.parkour.ParkourPlayer;

/**
 * Yes — item roll lives here. Arms stay straight up; only the held model turns.
 * Sword: laid flat, tip to the player's left. Pick: head hooked onto the cable.
 */
@Mixin(ItemInHandLayer.class)
public class HeldItemRollMixin {

    @Inject(method = "renderArmWithItem", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/entity/ItemRenderer;renderStatic(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/item/ItemDisplayContext;ZLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;Lnet/minecraft/world/level/Level;III)V"))
    private void moves$rollHeldItem(LivingEntity entity, ItemStack stack, ItemDisplayContext context, HumanoidArm arm,
                                    PoseStack pose, MultiBufferSource buffer, int light, CallbackInfo ci) {
        if (!(entity instanceof Player player) || stack.isEmpty()) {
            return;
        }
        ParkourPlayer data = ParkourPlayer.get(player);
        if (data.mode != ParkourMode.ZIPLINE) {
            return;
        }
        boolean left = arm == HumanoidArm.LEFT;
        if (stack.getItem() instanceof SwordItem || data.ziplineGrip == 2) {
            // From "pointing along the raised arm" → flat blade across, tip left.
            pose.mulPose(Axis.ZP.rotationDegrees(left ? 90.0F : -90.0F));
            pose.mulPose(Axis.YP.rotationDegrees(left ? 90.0F : -90.0F));
            pose.mulPose(Axis.XP.rotationDegrees(left ? 8.0F : -8.0F));
        } else if (stack.is(ItemTags.PICKAXES) || data.ziplineGrip == 1) {
            pose.mulPose(Axis.ZP.rotationDegrees(left ? -55.0F : 55.0F));
            pose.mulPose(Axis.XP.rotationDegrees(-40.0F));
            pose.mulPose(Axis.YP.rotationDegrees(left ? 12.0F : -12.0F));
        }
    }
}
