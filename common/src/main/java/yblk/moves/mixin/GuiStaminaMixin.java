package yblk.moves.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yblk.moves.client.StaminaHud;

@Mixin(Gui.class)
public class GuiStaminaMixin {

    @Inject(method = "render", at = @At("HEAD"))
    private void moves$staminaFrame(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        StaminaHud.beginFrame();
    }

    @Inject(method = "renderPlayerHealth", at = @At("TAIL"))
    private void moves$staminaHealth(GuiGraphics graphics, CallbackInfo ci) {
        StaminaHud.render(graphics, Minecraft.getInstance().getFrameTime());
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void moves$staminaEnd(GuiGraphics graphics, float partialTick, CallbackInfo ci) {
        StaminaHud.render(graphics, partialTick);
    }
}
