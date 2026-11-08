package yblk.moves.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yblk.moves.client.MovesKeybinds;
import yblk.moves.parkour.ParkourController;
import yblk.moves.parkour.ParkourPlayer;

@Mixin(Minecraft.class)
public class MinecraftGrabMixin {

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void moves$grabInsteadOfUse(CallbackInfo ci) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        // handleKeybinds runs before aiStep — seed grab from the live key so Fabric
        // RMB (shared with Use) still cancels use on the first press tick.
        if (MovesKeybinds.isActive(MovesKeybinds.GRAB)) {
            ParkourPlayer.get(player).input.grab = true;
        }
        if (ParkourController.shouldBlockUse(player)) {
            ci.cancel();
        }
    }
}
