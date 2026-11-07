package yblk.moves.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yblk.moves.parkour.ParkourController;

@Mixin(Minecraft.class)
public class MinecraftGrabMixin {

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void moves$grabInsteadOfUse(CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && ParkourController.shouldBlockUse(player)) {
            ci.cancel();
        }
    }
}
