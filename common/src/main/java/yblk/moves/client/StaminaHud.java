package yblk.moves.client;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import yblk.moves.Constants;
import yblk.moves.config.MovesConfig;
import yblk.moves.parkour.ParkourController;
import yblk.moves.parkour.ParkourPlayer;
import yblk.moves.parkour.ParkourTuning;

/**
 * Player bolt sheet. While charging, spent bolts shake and crossfade into the
 * red warn sheet (same 3-tone detail, mapped yellow→red).
 */
public final class StaminaHud {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(Constants.MOD_ID, "textures/gui/stamina.png");
    private static final ResourceLocation TEXTURE_WARN =
            new ResourceLocation(Constants.MOD_ID, "textures/gui/stamina_warn.png");
    private static final int ICON = 9;
    private static final int SHEET_W = 27;
    private static final int SHEET_H = 9;

    private static boolean drawnThisFrame;

    private StaminaHud() {
    }

    public static void beginFrame() {
        drawnThisFrame = false;
    }

    public static void render(GuiGraphics graphics) {
        render(graphics, Minecraft.getInstance().getFrameTime());
    }

    public static void render(GuiGraphics graphics, float partialTick) {
        if (drawnThisFrame) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui || minecraft.level == null) {
            return;
        }
        if (minecraft.gameMode != null && !minecraft.gameMode.canHurtPlayer()) {
            return;
        }
        if (!MovesConfig.get().stamina) {
            return;
        }
        drawnThisFrame = true;

        ParkourPlayer data = ParkourPlayer.get(minecraft.player);
        int width = minecraft.getWindow().getGuiScaledWidth();
        int height = minecraft.getWindow().getGuiScaledHeight();
        int right = width / 2 + 91;
        int y = height - 49;
        if (minecraft.player.getAirSupply() < minecraft.player.getMaxAirSupply()) {
            y -= 10;
        }

        float stamina = Math.max(0.0F, Math.min(ParkourTuning.MAX_STAMINA, data.stamina));
        int charge = Math.max(data.jumpCharge, data.input.jumpCharge);
        boolean charging = MovesConfig.get().chargedJump && charge > 0;
        float previewCost = charging ? Math.max(1.0F, ParkourController.chargedJumpCost(data)) : 0.0F;
        float afterJump = Math.max(0.0F, stamina - previewCost);
        float chargeT = charging
                ? Mth.clamp(charge / (float) ParkourTuning.JUMP_CHARGE_TICKS, 0.0F, 1.0F)
                : 0.0F;
        // Millis stays in float-safe range (nanoTime broke sin precision → no visible shake).
        float anim = Util.getMillis() * 0.05F;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();

        for (int icon = 0; icon < 10; icon++) {
            int baseX = right - icon * 8 - 9;
            float iconLo = icon * 10.0F;
            float iconHi = iconLo + 10.0F;
            float filled = stamina - iconLo;

            boolean spent = charging
                    && iconHi > afterJump + 0.01F
                    && iconLo < stamina - 0.01F;

            int drawX = baseX;
            int drawY = y;
            if (spent) {
                float amp = 0.2F + chargeT * 0.5F;
                drawX += Math.round(Mth.sin(anim + icon * 2.15F) * amp);
                drawY += Math.round(Mth.cos(anim * 1.35F + icon * 1.7F) * amp * 0.7F);
            }

            int style = filled >= 10.0F ? 2 : filled >= 5.0F ? 1 : 0;
            graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

            if (spent && style > 0) {
                // Early charge: yellow + fading red on top. Late: solid red warn sheet.
                if (chargeT < 0.55F) {
                    graphics.blit(TEXTURE, drawX, drawY, style * ICON, 0, ICON, ICON, SHEET_W, SHEET_H);
                    float warnA = Mth.clamp(chargeT / 0.55F, 0.0F, 1.0F);
                    graphics.setColor(1.0F, 1.0F, 1.0F, warnA);
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, warnA);
                    graphics.blit(TEXTURE_WARN, drawX, drawY, style * ICON, 0, ICON, ICON, SHEET_W, SHEET_H);
                    graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
                    RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
                } else {
                    graphics.blit(TEXTURE_WARN, drawX, drawY, style * ICON, 0, ICON, ICON, SHEET_W, SHEET_H);
                }
            } else {
                graphics.blit(TEXTURE, drawX, drawY, style * ICON, 0, ICON, ICON, SHEET_W, SHEET_H);
            }
        }
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        graphics.setColor(1.0F, 1.0F, 1.0F, 1.0F);
    }
}
