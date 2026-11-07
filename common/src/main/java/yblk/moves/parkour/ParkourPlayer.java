package yblk.moves.parkour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Per-entity parkour state. Client and server players are different objects,
 * so they do not share one entry even in singleplayer.
 */
public final class ParkourPlayer {

    private static final Map<Player, ParkourPlayer> DATA = new WeakHashMap<>();

    public final ParkourInput input = new ParkourInput();
    public float stamina = ParkourTuning.MAX_STAMINA;
    public int regenDelay;
    public ParkourMode mode = ParkourMode.NONE;
    public boolean prone;
    public boolean enhancedQueued;
    /** 0 none, 1 normal jump, 2 enhanced jump. Cleared on landing. */
    public int jumpKind;
    public double jumpStartY;
    public int airTicks;
    public int actionTicks;
    public int dodgeCooldown;
    /** Camera look frozen for the dodge so the view does not swing with the step. */
    public float dodgeYaw;
    public float dodgePitch;
    public boolean rollArmed;
    public boolean wallRunUsed;
    public int wallSide;
    public Direction facing = Direction.NORTH;
    public BlockPos anchor = BlockPos.ZERO;
    public Vec3 motion = Vec3.ZERO;
    /** Target hold position (Smart Moving–style velocity chase). */
    public Vec3 goalPos;
    /** Pull-up path start, fixed for the whole animation. */
    public Vec3 pullStart;
    public boolean lockPosition;
    public double lockX;
    public double lockY;
    public double lockZ;
    public int boostTicks;
    public int climbBoostTicks;
    /** Keeps the player planted after a pull-up so collision cannot fling them. */
    public int settleTicks;
    public float queuedVelocity;
    public double anchorTop;
    public double along;
    public String anim = "";
    public float syncedStamina = ParkourTuning.MAX_STAMINA;
    public ParkourMode syncedMode = ParkourMode.NONE;
    public boolean syncedProne;
    /** True only for the client player that owns the keyboard. */
    public boolean local;
    public int lastLeftTap = -100;
    public int lastRightTap = -100;
    public int lastBackTap = -100;
    public float prevStrafe;
    public float prevForward;
    public boolean proneLatch;
    public boolean prevProneCombo;
    public boolean prevProneKey;
    public boolean prevJump;
    public boolean prevDodgeKey;
    public boolean prevSlideKey;
    public boolean prevSneak;
    public int jumpCharge;
    /** Cooldown after a slide before another can start. */
    public int slideCooldown;
    /** Remembers sprint briefly — vanilla clears sprint the tick you press Shift. */
    public int recentSprintTicks;
    /** Recent ground speed². Shift clears live velocity before the slide check. */
    public double runSpeedSqr;
    /** Continuous horizontal offset while climbing a fence/bar wall (blocks). */
    public double climbAlong;
    /** Outside corner the body swings through so the path doesn't cut the block. */
    public Vec3 cornerVia;
    public BlockPos cornerAnchor;
    public Direction cornerFacing;
    public double cornerAlong;
    /** Body yaw while walking around a ledge corner. NaN when not turning. */
    public float turnYaw = Float.NaN;
    public float turnToYaw;
    /** Frames of corner-reach animation after turning onto another ledge axis. */
    public int cornerTicks;
    /** After a corner, +1 or -1: that along-direction is blocked until the player walks inward. */
    public int cornerLock;
    /** Grace ticks before releasing when hold scan misses for a frame. */
    public int missHoldTicks;
    /** Body yaw while orbiting a chain/pole (degrees). */
    public float climbYaw;
    /** Angle around the pole, in degrees. Kept on its own so a missed step cannot undo the turn. */
    public float orbitAngle = Float.NaN;
    /** Remaining downward slide (blocks) after grabbing mid-fall. */
    public double climbSlide;
    /** Rising-edge latch for climb jump-off. */
    public boolean climbJumpWasDown;
    /** Ticks to ignore re-grab after jumping off a chain. */
    public int grabIgnoreTicks;
    /** How long the current pull-up should take. Set from look pitch when it starts. */
    public int pullDuration = 20;
    /** Blocks of rise when the ledge was grabbed — scales pull-up length. */
    public double grabRise;
    /** Space must be released before another pull-up can spend stamina. */
    public boolean pullLocked;
    /** How many ticks space has been up during a pull-up. A one-tick gap must not restart the clip. */
    public int pullRelease;
    /** Fence/bar wall (shimmy) rather than a pole. Drives which climb animation plays. */
    public boolean climbWall;
    /** Crawl clip stays on until input actually stops, so the arms don't pop on a flicker. */
    public boolean crawlStepping;
    /** Flat-wall climb with a hoe or pickaxe in each hand. */
    public boolean toolClimb;
    /** Ticks left in the current pickaxe swing. The block step happens mid-swing. */
    public int toolStrike;
    /** 0 right hand strikes, 1 left hand. */
    public int toolArm;
    public int toolStepX;
    public int toolStepY;
    /** Pause between pickaxe steps so each plant reads on its own. */
    public int toolWait;
    /** Blocks another stair transfer until this hits zero. */
    public int moveLock;
    public int ziplineId = -1;
    public double ziplineT = 0.5;
    public double ziplineSpeed;
    /** 0 bare hands, 1 pickaxe on the line, 2 sword flat across it. */
    public int ziplineGrip;
    /** Ticks RMB has been up during a pull-up. One missed tick must not drop the climb. */
    public int pullGrabMiss;

    private ParkourPlayer() {
    }

    public static ParkourPlayer get(Player player) {
        synchronized (DATA) {
            return DATA.computeIfAbsent(player, key -> new ParkourPlayer());
        }
    }

    public int grabReach() {
        if (this.jumpKind == 2) {
            return ParkourTuning.GRAB_ENHANCED;
        }
        if (this.jumpKind == 1) {
            return ParkourTuning.GRAB_JUMP;
        }
        return ParkourTuning.GRAB_STANDING;
    }

    public boolean relaxMovementCheck() {
        return this.mode.scripted() || this.jumpKind == 2 || this.rollArmed || this.boostTicks > 0;
    }

    public void clearMotionLock() {
        this.lockPosition = false;
        this.motion = Vec3.ZERO;
        this.goalPos = null;
        this.along = 0.0;
        this.cornerVia = null;
        this.cornerAnchor = null;
        this.cornerFacing = null;
        this.cornerLock = 0;
        this.turnYaw = Float.NaN;
        this.toolClimb = false;
        this.toolStrike = 0;
        this.toolArm = 0;
        this.toolStepX = 0;
        this.toolStepY = 0;
        this.toolWait = 0;
        this.moveLock = 0;
        this.ziplineId = -1;
        this.ziplineSpeed = 0.0;
        this.orbitAngle = Float.NaN;
    }
}
