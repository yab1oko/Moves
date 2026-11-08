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
 * - Charged jump: Shift + Space (full charge), works with items
 * - Grab: Grab key (default RMB); empty / climb-tool / zipline hands
 * - Dodge: WASD + Alt
 * - Prone: unbound → Ctrl+Shift; rebound → that key (Control bind still needs Shift)
 * - Slide: unbound → Shift while sprinting with Ctrl held; never steals plain sneak
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
        boolean dodgeKey = MovesKeybinds.isActive(MovesKeybinds.DODGE);
        boolean grabKey = MovesKeybinds.isActive(MovesKeybinds.GRAB);
        boolean proneKey = MovesKeybinds.isActive(MovesKeybinds.PRONE);
        boolean slideKey = MovesKeybinds.isActive(MovesKeybinds.SLIDE);
        boolean sprinting = player.isSprinting();

        MovesConfig cfg = MovesConfig.get();
        ItemStack main = player.getMainHandItem();
        boolean lineHands = data.mode == ParkourMode.ZIPLINE
                || main.is(net.minecraft.tags.ItemTags.PICKAXES)
                || main.getItem() instanceof net.minecraft.world.item.SwordItem;
        boolean holding = !main.isEmpty()
                && !yblk.moves.item.ClimbGear.equipped(player)
                && !lineHands;

        boolean wasProneCombo = data.prevProneCombo;
        boolean wasSneak = data.prevSneak;
        boolean wasProneKey = data.prevProneKey;
        boolean proneBound = MovesKeybinds.isBound(MovesKeybinds.PRONE);
        boolean slideBound = MovesKeybinds.isBound(MovesKeybinds.SLIDE);

        boolean proneEdge = false;
        if (cfg.crawl && !jump) {
            if (!proneBound) {
                // Default: Ctrl+Shift while standing.
                boolean chord = shift && ctrl && !sprinting
                        && data.recentSprintTicks <= 0
                        && data.mode != ParkourMode.SLIDE
                        && data.slideCooldown <= 0;
                proneEdge = chord && !wasProneCombo;
                data.prevProneCombo = chord;
            } else if (MovesKeybinds.isCtrlKey(MovesKeybinds.PRONE)) {
                boolean chord = proneKey && shift && !sprinting
                        && data.recentSprintTicks <= 0
                        && data.mode != ParkourMode.SLIDE
                        && data.slideCooldown <= 0;
                proneEdge = chord && !wasProneCombo;
                data.prevProneCombo = chord;
            } else {
                proneEdge = proneKey && !wasProneKey;
                data.prevProneCombo = false;
            }
        } else {
            data.prevProneCombo = false;
        }
        if (proneEdge) {
            data.proneLatch = !data.proneLatch;
        }
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
        boolean canCharge = cfg.chargedJump && shift && modeOk && player.onGround()
                && !player.isSprinting();
        if (canCharge && jump) {
            data.jumpCharge = Math.min(ParkourTuning.JUMP_CHARGE_TICKS, data.jumpCharge + 1);
            charging = true;
        } else if (data.prevJump && !jump && data.jumpCharge > 0) {
            if (cfg.chargedJump && modeOk && player.onGround()
                    && data.jumpCharge >= ParkourTuning.JUMP_CHARGE_TICKS && shift) {
                enhanced = true;
            }
            data.jumpCharge = 0;
        } else if (!jump && !canCharge) {
            data.jumpCharge = 0;
        }

        if (cfg.chargedJump && jump && !data.prevJump && modeOk && data.climbBoostTicks > 0
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

        // Slide must NOT fire on plain Shift — that stole vanilla sneak on Fabric.
        // Default / Shift-bound: need Ctrl still held (sprint key) + Shift edge while moving.
        boolean slidePulse = false;
        if (cfg.slide && !data.proneLatch && data.mode == ParkourMode.NONE) {
            if (!slideBound || MovesKeybinds.isShiftKey(MovesKeybinds.SLIDE)) {
                slidePulse = shift && ctrl && !wasSneak
                        && (sprinting || data.recentSprintTicks > 0)
                        && !jump && data.jumpCharge <= 0;
            } else {
                slidePulse = slideKey && !data.prevSlideKey;
            }
        }
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
