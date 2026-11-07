package yblk.moves.parkour;

/**
 * Tunable numbers for the parkour system. Heights are in blocks above the
 * feet position where the jump started.
 */
public final class ParkourTuning {

    public static final float MAX_STAMINA = 100.0F;

    /** Pure sprint empties the bar in about 20 seconds. */
    public static final float SPRINT_DRAIN = 0.25F;
    public static final float REGEN = 0.4F;
    public static final int REGEN_DELAY_TICKS = 20;

    public static final float ENHANCED_JUMP_COST = 22.0F;
    /** A normal jump. Sprint keeps draining in the air, so hopping is not cheaper than running. */
    public static final float JUMP_COST = 6.0F;
    /** Clears a 2-block step. Vanilla 0.42 reaches about 1.25. */
    public static final float ENHANCED_JUMP_VELOCITY = 0.68F;
    /** Hold Shift+Space this long before release fires the enhanced jump. */
    /** Smart Moving default charge maximum is 20 ticks. */
    public static final int JUMP_CHARGE_TICKS = 20;
    /** Horizontal ceiling-hang speed (blocks/tick). */
    public static final double HANG_MOVE_SPEED = 0.10;
    /** Feet below the ceiling block. Head then sits just under the bars, not half a block down. */
    public static final double HANG_DROP = 1.90;
    /** Vertical free-climb speed — Smart Moving SlowUpMotion ≈ 0.1. */
    public static final double CLIMB_MOVE_SPEED = 0.10;
    /** Horizontal stand-off from chain/pole center (player radius is 0.3 — must stay outside). */
    public static final double CLIMB_STAND_OFF = 0.62;
    /** A/D orbit speed around a pole (degrees/tick). */
    public static final float CLIMB_ORBIT_DEG = 5.0F;
    /** Push-off horizontal (~1.5 blocks air travel max). */
    public static final double CLIMB_JUMP_OFF = 0.26;
    public static final double CLIMB_JUMP_UP = 0.28;
    /** Max fall-momentum slide down a chain (blocks). */
    public static final double CLIMB_SLIDE_MAX = 6.0;
    public static final double CLIMB_SLIDE_SPEED = 0.28;
    /** Max eye→hold distance for grab (arm reach). */
    public static final double GRAB_ARM_REACH = 1.55;
    /** Ledge shimmy along-speed (was 0.12 — too fast). */
    public static final double SHIMMY_SPEED = 0.045;
    /** Same gap for a fence and iron bars. Body radius is 0.3. */
    public static final double CLING_GAP = 0.50;
    /** Chain, bamboo and rods. Farther out so the body is not in the link. */
    public static final double POLE_GAP = 0.60;
    /** Gap past a ledge face. 0.3 is the body radius. */
    public static final double LEDGE_STAND_OFF = 0.32;
    /** Tiny extra clearance only while the facing is changing. */
    public static final double LEDGE_CORNER_EXTRA = 0.06;
    /** Horizontal shimmy along a fence/bar line (blocks/tick). */
    public static final double CLIMB_SHIMMY = 0.08;
    /** Forward must be held this hard for vertical ledge transfer (not pull-up). */
    public static final float PULLUP_HOLD = 0.90F;
    /** Per-tick max step toward grab goal (keeps camera smooth). */
    public static final double GRAB_STEP = 0.12;
    public static final double PULLUP_STEP = 0.16;

    public static final float DODGE_SPEED = 1.05F;

    public static final float GRAB_COST = 10.0F;
    public static final float PULLUP_COST = 14.0F;
    public static final float SHIMMY_DRAIN = 0.08F;
    public static final float HANG_DRAIN = 0.1F;
    public static final float CLIMB_DRAIN = 0.18F;
    public static final float DODGE_COST = 20.0F;

    /** Standing grab. */
    public static final int GRAB_STANDING = 2;
    /** Grab during a normal jump. */
    public static final int GRAB_JUMP = 3;
    /** Grab during an enhanced jump. */
    public static final int GRAB_ENHANCED = 4;

    public static final int DODGE_COOLDOWN = 14;
    public static final int DODGE_TICKS = 10;
    public static final int PULLUP_TICKS = 32;
    public static final int DODGE_STEP_TICKS = 10;
    public static final int CORNER_ANIM_TICKS = 14;
    /** Ground sprint-slide duration. */
    public static final int SLIDE_TICKS = 22;
    /** Ticks after a slide before another can start. */
    public static final int SLIDE_COOLDOWN = 48;
    /** Min horizontal speed² to start a sprint slide. */
    public static final double SLIDE_MIN_SPEED_SQR = 0.02;
    /** Multiplier applied to current horizontal velocity when the slide starts. */
    public static final double SLIDE_BOOST = 1.0;
    /** Per-tick horizontal friction while sliding. High enough to actually travel. */
    public static final double SLIDE_FRICTION = 0.97;
    /** Blocks/tick the slide is launched at, even if vanilla already ate the sprint speed. */
    public static final double SLIDE_LAUNCH_SPEED = 0.62;
    public static final int WALL_RUN_TICKS = 16;

    /** --- Added on top of the requested moves --- */
    public static final float WALL_RUN_DRAIN = 0.35F;
    public static final float SLIDE_COST = 18.0F;
    /** Landing roll keeps this fraction of the fall distance. */
    public static final float ROLL_FALL_KEEP = 0.35F;
    public static final float ROLL_MIN_FALL = 3.5F;

    private ParkourTuning() {
    }
}
