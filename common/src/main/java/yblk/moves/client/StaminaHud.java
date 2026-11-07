package yblk.moves.client;

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
 * Ten bolts above hunger, drawn from {@code textures/gui/stamina.png}
 * (empty / half / full, 9×9 each in a 27×9 strip).
 */
public final class StaminaHud {

    private static final ResourceLocation TEXTURE =
            new ResourceLocation(Constants.MOD_ID, "textures/gui/stamina.png");
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
        float previewCost = charging ? ParkourController.chargedJumpCost(data) : 0.0F;
        float afterJump = Math.max(0.0F, stamina - previewCost);
        float chargeT = charging
                ? Mth.clamp(charge / (float) ParkourTuning.JUMP_CHARGE_TICKS, 0.0F, 1.0F)
                : 0.0F;
        long time = minecraft.level.getGameTime();

        for (int icon = 0; icon < 10; icon++) {
            int baseX = right - icon * 8 - 9;
            float iconLo = icon * 10.0F;
            float iconHi = iconLo + 10.0F;
            float filled = stamina - iconLo;

            boolean committed = charging && previewCost > 0.0F
                    && iconHi > afterJump + 0.01F
                    && iconLo < stamina - 0.01F;

            int drawX = baseX;
            int drawY = y;
            if (committed) {
                float amp = 0.25F + chargeT * 0.55F;
                drawX += Math.round(Mth.sin((time + icon * 3) * 0.55F) * amp);
                drawY += Math.round(Mth.cos((time + icon * 5) * 0.65F) * amp * 0.45F);
            }

            int style = filled >= 10.0F ? 2 : filled >= 5.0F ? 1 : 0;
            graphics.blit(TEXTURE, drawX, drawY, style * ICON, 0, ICON, ICON, SHEET_W, SHEET_H);
        }
    }
}
