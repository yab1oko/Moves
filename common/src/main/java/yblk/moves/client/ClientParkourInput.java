package yblk.moves.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import yblk.moves.config.MovesConfig;
import yblk.moves.network.MovesNet;
import yblk.moves.parkour.ParkourInput;
import yblk.moves.parkour.ParkourMode;
import yblk.moves.parkour.ParkourPlayer;
import yblk.moves.parkour.ParkourTuning;

/**
 * Smart Moving–style input:
 * - Charged jump: hold Shift + Space to charge, release Space to jump
 * - Grab: hold Grab key (default RMB)
 * - Dodge: WASD + Alt
 * - Prone: Prone key toggle (default C), or Ctrl+Shift
 * - Slide: Slide key (default X), or Sprint+Shift rising edge
 */
public final class ClientParkourInput {

    private ClientParkourInput() {
    }

    public static void capture(LocalPlayer player) {
        ParkourPlayer data = ParkourPlayer.get(player);
        data.local = true;
        Minecraft minecraft = Minecraft.getInstance();
        ItemStack heldMain = player.getMainHandItem();
        ItemStack heldOff = player.getOffhandItem();
        yblk.moves.entity.ZiplineEntity.SUPPRESS_PICK.set(
                heldMain.getItem() instanceof yblk.moves.item.ZiplineItem
                        || heldOff.getItem() instanceof yblk.moves.item.ZiplineItem);
        boolean shift = minecraft.options.keyShift.isDown();
        boolean ctrl = minecraft.options.keySprint.isDown();
        boolean jump = minecraft.options.keyJump.isDown();
        boolean dodgeKey = MovesKeybinds.DODGE.isDown();
        boolean grabKey = MovesKeybinds.GRAB.isDown();
        boolean proneKey = MovesKeybinds.PRONE.isDown();
        boolean slideKey = MovesKeybinds.SLIDE.isDown();
        boolean sprinting = player.isSprinting();
        // Ctrl+Shift toggles crawl only when standing. During a sprint or a slide it is the slide key.
        boolean combo = shift && ctrl && !sprinting
                && data.mode != ParkourMode.SLIDE
                && data.slideCooldown <= 0
                && data.recentSprintTicks <= 0;

        MovesConfig cfg = MovesConfig.get();
        // Anything in the main hand is for building/use. Parkour must not eat RMB or Shift.
        ItemStack main = player.getMainHandItem();
        boolean lineHands = data.mode == ParkourMode.ZIPLINE
                || main.is(net.minecraft.tags.ItemTags.PICKAXES)
                || main.getItem() instanceof net.minecraft.world.item.SwordItem;
        boolean holding = !main.isEmpty()
                && !yblk.moves.item.ClimbGear.equipped(player)
                && !lineHands;
        if (cfg.crawl && ((combo && !data.prevProneCombo) || (proneKey && !data.prevProneKey))) {
            data.proneLatch = !data.proneLatch;
        }
        data.prevProneCombo = combo;
        data.prevProneKey = proneKey;
        if (!cfg.crawl) {
            data.proneLatch = false;
        }
        if (jump && data.proneLatch) {
            data.proneLatch = false;
        }

        float strafe = player.input.leftImpulse;
        float forward = player.input.forwardImpulse;

        int dodge = 0;
        if (cfg.dodge && dodgeKey && !data.prevDodgeKey && !data.proneLatch) {
            if (Math.abs(forward) >= Math.abs(strafe) && Math.abs(forward) > 0.2F) {
                dodge = forward > 0.0F ? 3 : 2;
            } else if (Math.abs(strafe) > 0.2F) {
                dodge = strafe > 0.0F ? -1 : 1;
            } else {
                dodge = 3;
            }
        }
        data.prevDodgeKey = dodgeKey;

        boolean enhanced = false;
        boolean charging = false;
        boolean modeOk = data.mode == ParkourMode.NONE && !data.proneLatch;
        // Don't charge jump while sprinting — Shift then starts a slide instead.
        boolean canCharge = cfg.chargedJump && !holding && shift && !combo && modeOk && player.onGround()
                && !player.isSprinting();
        if (canCharge && jump) {
            data.jumpCharge = Math.min(ParkourTuning.JUMP_CHARGE_TICKS, data.jumpCharge + 1);
            charging = true;
        } else if (data.prevJump && !jump && data.jumpCharge > 0) {
            if (cfg.chargedJump && modeOk && player.onGround() && data.jumpCharge >= 4 && shift) {
                enhanced = true;
            }
            data.jumpCharge = 0;
        } else if (!jump && !canCharge) {
            data.jumpCharge = 0;
        }

        if (cfg.chargedJump && !holding && jump && !data.prevJump && modeOk && data.climbBoostTicks > 0
                && shift && player.onGround()) {
            enhanced = true;
            data.jumpCharge = 0;
            charging = false;
        }
        data.prevJump = jump;

        if (charging || enhanced) {
            player.input.jumping = false;
            player.setJumping(false);
        }

        boolean slidePulse = cfg.slide && !data.proneLatch && data.mode == ParkourMode.NONE
                && ((slideKey && !data.prevSlideKey)
                || (shift && !data.prevSneak && (sprinting || data.recentSprintTicks > 0) && !combo && !jump
                && data.jumpCharge <= 0));
        data.prevSlideKey = slideKey;
        data.prevSneak = shift;

        ParkourInput input = data.input;
        input.forward = forward;
        input.strafe = strafe;
        input.jump = jump && !charging && !enhanced;
        input.sneak = shift && !data.proneLatch;
        input.sprint = sprinting && !data.proneLatch;
        input.prone = data.proneLatch;
        input.enhanced = enhanced;
        input.grab = grabKey && !data.proneLatch && !holding;
        input.slide = slidePulse;
        input.dodge = dodge;
        input.jumpCharge = charging ? data.jumpCharge : 0;
        MovesNet.sendInput(input);
    }
}
