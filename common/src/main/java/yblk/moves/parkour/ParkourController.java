package yblk.moves.parkour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import yblk.moves.client.MovesAnims;
import yblk.moves.client.ParkourLookPose;
import yblk.moves.config.MovesConfig;

/**
 * Decides and applies parkour movement. Called on both sides for the
 * authoritative player (the local client player, and every server player).
 */
public final class ParkourController {

    private ParkourController() {
    }

    public static void beforeAiStep(Player player) {
        MovesConfig.poll();
        if (player.isSpectator() || player.isSleeping() || player.isPassenger()) {
            return;
        }
        ParkourPlayer data = ParkourPlayer.get(player);
        if (player.level().isClientSide() && !data.local) {
            return;
        }
        if (player.getAbilities().flying || player.isFallFlying()) {
            release(player, data);
            return;
        }

        trackGround(player, data);
        if (!data.mode.scripted()) {
            nudgeOut(player);
        }
        drainSprint(player, data);
        regenerate(player, data);

        if (data.dodgeCooldown > 0) {
            data.dodgeCooldown--;
        }
        if (data.settleTicks > 0) {
            data.settleTicks--;
            player.setDeltaMovement(player.getDeltaMovement().multiply(0.0, 1.0, 0.0));
            player.fallDistance = 0.0F;
        }

        // Crawl: Ctrl+Shift latch → swimming pose on ground (animation only differs).
        boolean wasProne = data.prone;
        if (!MovesConfig.get().crawl) {
            data.prone = false;
            data.input.prone = false;
        } else if (data.input.prone != data.prone && data.mode == ParkourMode.NONE) {
            data.prone = data.input.prone;
        }
        if (data.prone && (player.isInWater() || data.mode.scripted())) {
            data.prone = false;
        }
        if (data.prone && data.input.jump) {
            data.prone = false;
        }
        if (wasProne != data.prone) {
            player.refreshDimensions();
            markDirty(data);
        }

        // Keep vanilla jump suppressed the whole charge (input.tick may re-set it).
        if (data.input.jumpCharge > 0 || data.jumpCharge > 0 || data.input.enhanced) {
            player.setJumping(false);
            data.jumpCharge = Math.max(data.jumpCharge, data.input.jumpCharge);
        }
        // Smart Moving: charged jump fires on Space release after sneak-charge.
        if (data.input.enhanced && MovesConfig.get().chargedJump
                && player.getMainHandItem().isEmpty()
                && data.mode == ParkourMode.NONE && !data.prone && data.settleTicks <= 0) {
            if (player.onGround()) {
                fireChargedJump(player, data);
            }
            data.input.enhanced = false;
        }
        if (data.boostTicks > 0) {
            data.boostTicks--;
        }
        if (data.climbBoostTicks > 0 && data.mode == ParkourMode.NONE) {
            data.climbBoostTicks--;
        }
        if (data.cornerTicks > 0) {
            data.cornerTicks--;
        }
        // While RMB is held after a pull-up, do not count down — looking down would grab the lip again.
        if (data.grabIgnoreTicks > 0 && !data.input.grab) {
            data.grabIgnoreTicks--;
        }
        yblk.moves.entity.ZiplineEntity.breakLookedAt(player);
        if (data.slideCooldown > 0) {
            data.slideCooldown--;
        }
        if (data.input.sprint || player.isSprinting()) {
            data.recentSprintTicks = 8;
        } else if (data.recentSprintTicks > 0) {
            data.recentSprintTicks--;
        }

        if (!player.getMainHandItem().isEmpty() && !yblk.moves.item.ClimbGear.equipped(player)
                && data.mode != ParkourMode.ZIPLINE) {
            data.input.enhanced = false;
            if (data.mode != ParkourMode.NONE && data.mode != ParkourMode.SLIDE && data.mode != ParkourMode.DODGE) {
                release(player, data);
            }
            if (data.mode != ParkourMode.SLIDE && data.mode != ParkourMode.DODGE
                    && !(data.input.grab && tryBeginZipline(player, data))) {
                data.input.grab = false;
            }
        }

        switch (data.mode) {
            case PULLUP -> tickPullup(player, data);
            case DODGE -> tickDodge(player, data);
            case LEDGE -> tickLedge(player, data);
            case HANG -> tickHang(player, data);
            case CLIMB -> tickClimb(player, data);
            case ZIPLINE -> tickZipline(player, data);
            case WALL_RUN -> tickWallRun(player, data);
            case SLIDE -> tickSlide(player, data);
            case NONE -> tickFree(player, data);
        }

        // Apply grab motion HERE — do not rely on LivingEntity.travel (chain climbable fights it).
        applyGrabMotion(player, data);
        lockBody(player, data);

        if (data.stamina <= 0.0F) {
            player.setSprinting(false);
        }
        if (player.getAbilities().instabuild) {
            data.stamina = ParkourTuning.MAX_STAMINA;
        }
        rememberRunSpeed(player, data);
        syncIfNeeded(player, data);
        playLocalAnim(player, data);
    }

    /** Ground sprint speed from the last ticks. Shift zeroes live velocity in the same tick. */
    private static void rememberRunSpeed(Player player, ParkourPlayer data) {
        if (data.mode != ParkourMode.NONE || !player.onGround() || data.input.sneak) {
            return;
        }
        Vec3 vel = player.getDeltaMovement();
        double spd = vel.x * vel.x + vel.z * vel.z;
        if (spd > data.runSpeedSqr) {
            data.runSpeedSqr = spd;
        } else {
            data.runSpeedSqr = data.runSpeedSqr * 0.85 + spd * 0.15;
        }
    }

    /**
     * Step toward goalPos with real collision. noPhysics + setPos is what shoved the
     * body through walls, chains and floors — Smart Moving never teleports into a block.
     */
    /** Put the body back on the grab point after vanilla chain/ladder code. */
    public static void stickGrab(Player player) {
        ParkourPlayer data = ParkourPlayer.get(player);
        if (data.goalPos == null) {
            return;
        }
        if (data.mode != ParkourMode.CLIMB && data.mode != ParkourMode.HANG
                && data.mode != ParkourMode.PULLUP && data.mode != ParkourMode.ZIPLINE) {
            return;
        }
        Vec3 goal = data.goalPos;
        player.setPos(goal.x, goal.y, goal.z);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
    }

    private static boolean isGrabMode(ParkourPlayer data) {
        return data.mode.locksBody() || data.mode == ParkourMode.HANG || data.mode == ParkourMode.ZIPLINE;
    }

    private static void applyGrabMotion(Player player, ParkourPlayer data) {
        if (!isGrabMode(data)) {
            return;
        }
        player.noPhysics = false;
        player.fallDistance = 0.0F;
        player.setDeltaMovement(Vec3.ZERO);

        Vec3 goal = data.goalPos;
        if (goal == null) {
            return;
        }
        Vec3 cur = player.position();
        // Climb and hang stand against a thin block on purpose. Pushing back out is the in-place spin.
        if (!bodyFits(player, cur) && data.mode != ParkourMode.CLIMB && data.mode != ParkourMode.HANG) {
            Vec3 freed = escapeSolids(player, cur, data.facing);
            if (freed != null) {
                player.setPos(freed.x, freed.y, freed.z);
                cur = freed;
            }
        }
        Vec3 delta = goal.subtract(cur);
        double dist = delta.length();
        if (dist < 1.0E-4) {
            player.setDeltaMovement(Vec3.ZERO);
            return;
        }
        double step = data.mode == ParkourMode.PULLUP ? ParkourTuning.PULLUP_STEP : ParkourTuning.GRAB_STEP;
        if (data.mode == ParkourMode.LEDGE) {
            step = data.cornerTicks > 0 ? 0.12 : 0.08;
        }
        if (data.mode == ParkourMode.ZIPLINE
                || (data.mode == ParkourMode.CLIMB && (data.toolClimb
                || HangBlocks.isPole(player.level().getBlockState(data.anchor))))) {
            step = dist;
        }
        Vec3 next = dist <= step ? goal : cur.add(delta.scale(step / dist));
        if (data.mode == ParkourMode.CLIMB || data.mode == ParkourMode.HANG
                || data.mode == ParkourMode.PULLUP || data.mode == ParkourMode.ZIPLINE) {
            // Chain, fence, the lip and the zipline. Their collision is what yanks a turn back to the post.
            player.setPos(next.x, next.y, next.z);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            player.noPhysics = false;
            player.hasImpulse = true;
            return;
        } else if (data.mode == ParkourMode.LEDGE) {
            if (grabFits(player, next, data.anchor, false) && segmentGrabFits(player, cur, next, data.anchor, false)) {
                player.setPos(next.x, next.y, next.z);
                player.setDeltaMovement(Vec3.ZERO);
                player.fallDistance = 0.0F;
                player.noPhysics = false;
                player.hasImpulse = true;
                return;
            }
            next = clipGrabStep(player, cur, next);
        } else {
            next = clipGrabStep(player, cur, next);
        }
        Vec3 motion = next.subtract(cur);
        if (motion.lengthSqr() > 1.0E-8) {
            player.move(MoverType.SELF, motion);
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.noPhysics = false;
        player.hasImpulse = true;
    }

    private static AABB bodyBox(Vec3 pos) {
        return Player.STANDING_DIMENSIONS.makeBoundingBox(pos.x, pos.y, pos.z);
    }

    /** True when a standing player at {@code pos} does not intersect solid collision. */
    private static boolean bodyFits(Player player, Vec3 pos) {
        return player.level().noCollision(player, bodyBox(pos).deflate(0.02));
    }

    /** Shrink a step until it stays in air. Never accepts a point inside a block. */
    private static Vec3 clipGrabStep(Player player, Vec3 cur, Vec3 next) {
        if (bodyFits(player, next) && segmentFits(player, cur, next)) {
            return next;
        }
        Vec3 xz = new Vec3(next.x, cur.y, next.z);
        if (bodyFits(player, xz) && segmentFits(player, cur, xz)) {
            return xz;
        }
        // Split the horizontal step so a corner slides along the face instead of sticking on the edge.
        Vec3 xOnly = new Vec3(next.x, cur.y, cur.z);
        if (bodyFits(player, xOnly) && segmentFits(player, cur, xOnly)) {
            return xOnly;
        }
        Vec3 zOnly = new Vec3(cur.x, cur.y, next.z);
        if (bodyFits(player, zOnly) && segmentFits(player, cur, zOnly)) {
            return zOnly;
        }
        Vec3 yOnly = new Vec3(cur.x, next.y, cur.z);
        if (bodyFits(player, yOnly) && segmentFits(player, cur, yOnly)) {
            return yOnly;
        }
        Vec3 delta = next.subtract(cur);
        for (double scale = 0.5; scale >= 0.15; scale *= 0.5) {
            Vec3 partial = cur.add(delta.scale(scale));
            if (bodyFits(player, partial) && segmentFits(player, cur, partial)) {
                return partial;
            }
        }
        return cur;
    }

    /**
     * Standing box at {@code pos}. {@code ignore} is the block the hands are on (a lip or a pole).
     * {@code ignoreColumn} also skips the rest of that XZ column (chain / fence / bars).
     */
    private static boolean grabFits(Player player, Vec3 pos, BlockPos ignore, boolean ignoreColumn) {
        AABB box = Player.STANDING_DIMENSIONS.makeBoundingBox(pos.x, pos.y, pos.z).deflate(0.03);
        Level level = player.level();
        int x0 = Mth.floor(box.minX);
        // Feet rest on the floor. Testing that block makes a ground grab fail and an air grab work.
        int y0 = Mth.floor(box.minY + 0.08);
        int z0 = Mth.floor(box.minZ);
        int x1 = Mth.floor(box.maxX);
        int y1 = Mth.floor(box.maxY);
        int z1 = Mth.floor(box.maxZ);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    BlockPos at = new BlockPos(x, y, z);
                    var state = level.getBlockState(at);
                    if (ignore != null && x == ignore.getX() && z == ignore.getZ()
                            && (ignoreColumn || y == ignore.getY())) {
                        continue;
                    }
                    // The slab or stair on the block being held is the lip, not a wall to climb.
                    if (ignore != null && HangBlocks.isPartialLip(state)
                            && x == ignore.getX() && z == ignore.getZ() && y == ignore.getY() + 1) {
                        continue;
                    }
                    // Fence, bars and chains are the thing being held. They must not block a shimmy or an orbit.
                    if (ignoreColumn && (HangBlocks.isFenceOrBars(state) || HangBlocks.isPole(state) || state.is(net.minecraft.tags.BlockTags.WALLS))) {
                        continue;
                    }
                    var shape = state.getCollisionShape(level, at);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    // The floor under the feet. A ground grab and a chain orbit both clip this skin.
                    if (ignoreColumn && at.getY() + shape.max(Direction.Axis.Y) <= pos.y + 0.05) {
                        continue;
                    }
                    for (AABB part : shape.toAabbs()) {
                        if (part.move(at.getX(), at.getY(), at.getZ()).intersects(box)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    private static boolean segmentGrabFits(Player player, Vec3 from, Vec3 to, BlockPos ignore, boolean ignoreColumn) {
        Vec3 delta = to.subtract(from);
        double len = delta.length();
        if (len < 1.0E-4) {
            return grabFits(player, to, ignore, ignoreColumn);
        }
        int steps = Mth.ceil(len / 0.2);
        for (int i = 1; i <= steps; i++) {
            if (!grabFits(player, from.add(delta.scale(i / (double) steps)), ignore, ignoreColumn)) {
                return false;
            }
        }
        return true;
    }

    private static boolean segmentFits(Player player, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double len = delta.length();
        if (len < 1.0E-4) {
            return bodyFits(player, to);
        }
        int steps = Mth.ceil(len / 0.2);
        for (int i = 1; i <= steps; i++) {
            if (!bodyFits(player, from.add(delta.scale(i / (double) steps)))) {
                return false;
            }
        }
        return true;
    }

    /** If a previous tick left the body inside a block, step back out — never further in. */
    private static Vec3 escapeSolids(Player player, Vec3 cur, Direction intoWall) {
        Direction out = intoWall != null ? intoWall.getOpposite() : player.getDirection().getOpposite();
        Vec3[] tries = {
                cur.add(out.getStepX() * 0.35, 0.05, out.getStepZ() * 0.35),
                cur.add(out.getStepX() * 0.6, 0.1, out.getStepZ() * 0.6),
                cur.add(0.0, 0.4, 0.0),
                cur.add(0.45, 0.2, 0.0),
                cur.add(-0.45, 0.2, 0.0),
                cur.add(0.0, 0.2, 0.45),
                cur.add(0.0, 0.2, -0.45)
        };
        for (Vec3 t : tries) {
            if (bodyFits(player, t)) {
                return t;
            }
        }
        return null;
    }

    /** Stamina that the current charged jump would spend (HUD preview). */
    public static float chargedJumpCost(ParkourPlayer data) {
        float cost = MovesConfig.get().enhancedJumpCost;
        if (data.climbBoostTicks > 0) {
            cost += 16.0F;
        }
        return cost;
    }

    private static void fireChargedJump(Player player, ParkourPlayer data) {
        float cost = chargedJumpCost(data);
        float velocity = data.climbBoostTicks > 0
                ? 0.88F
                : ParkourTuning.ENHANCED_JUMP_VELOCITY;
        // Spend happens on release/jump — not while charging.
        if (!spend(player, data, cost)) {
            return;
        }
        data.jumpKind = 2;
        data.boostTicks = 16;
        data.climbBoostTicks = 0;
        data.jumpCharge = 0;
        data.input.jumpCharge = 0;
        data.jumpStartY = player.getY();
        Vec3 motion = player.getDeltaMovement();
        player.setDeltaMovement(motion.x * 0.15, velocity, motion.z * 0.15);
        player.hasImpulse = true;
        player.setOnGround(false);
        player.hurtMarked = true;
        markDirty(data);
    }

    /** NaN when body should follow look; otherwise absolute body yaw facing the wall/hold. */
    public static float lockedBodyYaw(Player player) {
        ParkourPlayer data = ParkourPlayer.get(player);
        if (!data.mode.locksBody()) {
            return Float.NaN;
        }
        if (data.mode == ParkourMode.CLIMB || data.mode == ParkourMode.PULLUP) {
            if (!Float.isNaN(data.climbYaw)) {
                return data.climbYaw;
            }
        }
        if (data.mode == ParkourMode.LEDGE && data.cornerVia != null && !Float.isNaN(data.turnYaw)) {
            return data.turnYaw;
        }
        if (data.facing == null) {
            return Float.NaN;
        }
        return data.facing.toYRot();
    }

    private static void lockBody(Player player, ParkourPlayer data) {
        float yaw = lockedBodyYaw(player);
        if (Float.isNaN(yaw)) {
            return;
        }
        // Only set current body yaw — do NOT snap yBodyRotO (that kills interp → camera shake).
        player.yBodyRot = yaw;
        player.setYBodyRot(yaw);
    }

    /**
     * Feet on the open side of a fence/bar wall. Side comes from climbYaw so an edge
     * jitter across the thin post cannot flip the body 180°.
     */
    private static Vec3 wallCling(Player player, BlockPos hold, Direction.Axis axis, double feetY, float climbYaw) {
        double off = ParkourTuning.CLING_GAP;
        double sign = Math.signum(sideFromYaw(climbYaw, axis, 1.0));
        if (sign == 0.0) {
            double center = axis == Direction.Axis.X ? hold.getZ() + 0.5 : hold.getX() + 0.5;
            double playerSide = axis == Direction.Axis.X ? player.getZ() : player.getX();
            sign = playerSide >= center ? 1.0 : -1.0;
        }
        double along = axis == Direction.Axis.X ? player.getX() : player.getZ();
        along = Mth.clamp(along, (axis == Direction.Axis.X ? hold.getX() : hold.getZ()) + 0.05,
                (axis == Direction.Axis.X ? hold.getX() : hold.getZ()) + 0.95);
        double lat = sign * off;
        double gx = axis == Direction.Axis.X ? along : hold.getX() + 0.5 + lat;
        double gz = axis == Direction.Axis.Z ? along : hold.getZ() + 0.5 + lat;
        return new Vec3(gx, feetY, gz);
    }

    /** Feet position on the open side of a chain, or just outside a fence/bar block. */
    private static Vec3 climbStandPos(Level level, BlockPos hold, float climbYaw, double feetY) {
        float rad = climbYaw * Mth.DEG_TO_RAD;
        double off = climbOffset(level, hold);
        double ox = Mth.sin(rad) * off;
        double oz = -Mth.cos(rad) * off;
        return new Vec3(hold.getX() + 0.5 + ox, feetY, hold.getZ() + 0.5 + oz);
    }

    /** Visual gap past the link. The hold's own hitbox is ignored, so this can sit against the mesh. */
    private static double climbOffset(Level level, BlockPos hold) {
        if (HangBlocks.isPole(level.getBlockState(hold))) {
            return ParkourTuning.POLE_GAP;
        }
        return ParkourTuning.CLING_GAP;
    }

    public static void afterJump(Player player) {
        ParkourPlayer data = ParkourPlayer.get(player);
        if (player.level().isClientSide() && !data.local) {
            return;
        }
        if (data.enhancedQueued && data.queuedVelocity > 0.0F) {
            float velocity = data.queuedVelocity;
            MobEffectInstance boost = player.getEffect(MobEffects.JUMP);
            if (boost != null) {
                velocity += 0.1F * (boost.getAmplifier() + 1);
            }
            Vec3 motion = player.getDeltaMovement();
            player.setDeltaMovement(motion.x, velocity, motion.z);
            data.jumpKind = 2;
            data.jumpStartY = player.getY();
            data.boostTicks = Math.max(data.boostTicks, 20);
            player.hasImpulse = true;
            data.enhancedQueued = false;
            data.queuedVelocity = 0.0F;
        } else if (!data.mode.scripted()) {
            data.jumpKind = 1;
            data.jumpStartY = player.getY();
            spend(player, data, MovesConfig.get().jumpCost);
        }
    }

    /**
     * @return true when vanilla travel should be skipped
     */
    public static boolean handleTravel(Player player, Vec3 input) {
        ParkourPlayer data = ParkourPlayer.get(player);
        if (!data.mode.scripted() || (player.level().isClientSide() && !data.local)) {
            return false;
        }
        // Grab motion already applied in beforeAiStep — only cancel vanilla gravity/climbable.
        if (isGrabMode(data)) {
            player.noPhysics = false;
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            return true;
        }
        Vec3 step = data.motion;
        if (data.mode == ParkourMode.SLIDE) {
            step = new Vec3(data.motion.x, Math.min(data.motion.y, -0.08), data.motion.z);
        }
        player.setDeltaMovement(step);
        if (step.lengthSqr() > 1.0E-6) {
            player.move(MoverType.SELF, step);
        }
        player.setDeltaMovement(new Vec3(step.x, 0.0, step.z));
        return true;
    }

    public static void afterAiStep(Player player) {
        ParkourPlayer data = ParkourPlayer.get(player);
        if (data.settleTicks > 0) {
            player.setDeltaMovement(Vec3.ZERO);
            player.setOnGround(true);
            player.fallDistance = 0.0F;
        }
        if (isGrabMode(data)) {
            player.noPhysics = false;
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0.0F;
            lockBody(player, data);
        }
        if (data.prone || data.mode == ParkourMode.SLIDE) {
            player.setShiftKeyDown(false);
            if (data.prone) {
                player.setSwimming(true);
            }
        }
    }

    public static void afterPose(Player player) {
        ParkourPlayer data = ParkourPlayer.get(player);
        if (data.mode.locksBody() || data.mode == ParkourMode.HANG) {
            player.setPose(Pose.STANDING);
            player.setSwimming(false);
            return;
        }
        if (data.mode == ParkourMode.SLIDE) {
            player.setShiftKeyDown(false);
            player.setPose(Pose.STANDING);
            player.setSwimming(false);
        } else if (data.prone) {
            player.setShiftKeyDown(false);
            player.setPose(Pose.SWIMMING);
            player.setSwimming(true);
        }
    }

    public static float modifyFallDistance(Player player, float fallDistance) {
        ParkourPlayer data = ParkourPlayer.get(player);
        if (!MovesConfig.get().dodge || !data.rollArmed || fallDistance < ParkourTuning.ROLL_MIN_FALL) {
            return fallDistance;
        }
        data.rollArmed = false;
        data.anim = "";
        if (player.level().isClientSide() && data.local) {
            MovesAnims.play(player, "roll");
            data.anim = "roll";
        }
        return fallDistance * ParkourTuning.ROLL_FALL_KEEP;
    }

    public static Vec3 scaleFreeMove(Player player, Vec3 travel) {
        ParkourPlayer data = ParkourPlayer.get(player);
        if (data.settleTicks > 0) {
            return Vec3.ZERO;
        }
        if (data.prone && data.mode == ParkourMode.NONE) {
            return travel.scale(0.72);
        }
        return travel;
    }

    private static void tickFreeBody(Player player, ParkourPlayer data) {
        data.clearMotionLock();
        data.actionTicks = 0;
        trySlide(player, data);
        if (data.mode != ParkourMode.NONE) {
            return;
        }
        tryDodge(player, data);
        if (data.mode != ParkourMode.NONE) {
            return;
        }
        if (MovesConfig.get().toolClimb && yblk.moves.item.ClimbGear.equipped(player)
                && data.input.grab && data.grabIgnoreTicks <= 0) {
            tryToolWall(player, data);
            return;
        }
        if (!player.getMainHandItem().isEmpty()) {
            return;
        }
        tryWallRun(player, data);
        if (data.mode != ParkourMode.NONE) {
            return;
        }
        tryGrab(player, data);
    }

    /**
     * Slide key (or Sprint+Shift pulse from the client) → ground slide keeping run momentum.
     */
    private static void trySlide(Player player, ParkourPlayer data) {
        MovesConfig cfg = MovesConfig.get();
        data.prevSneak = data.input.sneak;
        if (!cfg.slide || !data.input.slide) {
            return;
        }
        if (data.mode != ParkourMode.NONE || data.prone || data.slideCooldown > 0 || data.settleTicks > 0) {
            return;
        }
        if (!player.onGround() || data.airTicks > 1 || data.input.jump || data.input.grab || data.input.prone) {
            return;
        }
        if (data.input.jumpCharge > 0) {
            return;
        }
        Vec3 vel = player.getDeltaMovement();
        double live = vel.x * vel.x + vel.z * vel.z;
        double speedSqr = Math.max(live, data.runSpeedSqr);
        // input.sprint is sampled before vanilla clears sprint on the Shift tick.
        boolean wasSprinting = data.input.sprint || player.isSprinting() || data.recentSprintTicks > 0;
        double speed = Math.sqrt(speedSqr);
        if (wasSprinting) {
            speed = Math.max(speed, ParkourTuning.SLIDE_LAUNCH_SPEED);
        }
        if (speed * speed < ParkourTuning.SLIDE_MIN_SPEED_SQR) {
            return;
        }
        if (!spend(player, data, MovesConfig.get().slideCost)) {
            return;
        }
        Vec3 horiz = new Vec3(vel.x, 0.0, vel.z);
        if (horiz.lengthSqr() < 1.0E-4) {
            horiz = horizontalLook(player);
        }
        horiz = horiz.normalize().scale(speed);
        data.mode = ParkourMode.SLIDE;
        data.actionTicks = ParkourTuning.SLIDE_TICKS;
        data.slideCooldown = ParkourTuning.SLIDE_TICKS + ParkourTuning.SLIDE_COOLDOWN;
        data.motion = new Vec3(horiz.x, 0.0, horiz.z);
        data.lockPosition = false;
        data.goalPos = null;
        data.runSpeedSqr = 0.0;
        data.prone = false;
        data.input.prone = false;
        data.airTicks = 0;
        player.setSprinting(false);
        player.setDeltaMovement(data.motion);
        player.hasImpulse = true;
        markDirty(data);
    }

    private static void tickSlide(Player player, ParkourPlayer data) {
        data.actionTicks--;
        player.setJumping(false);
        drain(player, data, MovesConfig.get().slideDrain);
        if (data.stamina <= 0.0F && !player.getAbilities().instabuild) {
            data.actionTicks = 0;
        }
        data.motion = new Vec3(
                data.motion.x * ParkourTuning.SLIDE_FRICTION,
                0.0,
                data.motion.z * ParkourTuning.SLIDE_FRICTION
        );
        data.lockPosition = false;
        // Don't kill the slide on the same tick pose/ground flaps. A real wall or a hop does.
        boolean hitWall = player.horizontalCollision && data.actionTicks < ParkourTuning.SLIDE_TICKS - 6;
        boolean leftGround = data.airTicks > 4;
        boolean done = data.actionTicks <= 0
                || hitWall
                || leftGround
                || data.input.jump
                || data.motion.horizontalDistanceSqr() < 0.004;
        if (done) {
            data.mode = ParkourMode.NONE;
            data.clearMotionLock();
            player.refreshDimensions();
            // Jump out of slide keeps a bit of the remaining momentum.
            if (data.input.jump && player.onGround()) {
                Vec3 keep = data.motion.scale(0.55);
                player.setDeltaMovement(keep.x, Math.max(0.36, player.getDeltaMovement().y), keep.z);
            }
            markDirty(data);
        }
    }

    private static void tickFree(Player player, ParkourPlayer data) {
        tickFreeBody(player, data);
    }

    private static void tryDodge(Player player, ParkourPlayer data) {
        if (!MovesConfig.get().dodge) {
            return;
        }
        if (data.input.dodge == 0 || data.dodgeCooldown > 0 || data.input.grab || data.prone
                || data.mode.scripted() || data.settleTicks > 0) {
            if (data.input.dodge != 0 && !data.input.grab && !player.onGround()
                    && player.fallDistance >= ParkourTuning.ROLL_MIN_FALL) {
                if (spend(player, data, MovesConfig.get().dodgeCost)) {
                    data.rollArmed = true;
                    beginDodge(player, data);
                }
            }
            return;
        }
        if (!player.onGround() && data.airTicks < 2) {
            return;
        }
        if (!player.onGround() && player.fallDistance >= ParkourTuning.ROLL_MIN_FALL) {
            data.rollArmed = true;
        }
        if (!spend(player, data, MovesConfig.get().dodgeCost)) {
            return;
        }
        beginDodge(player, data);
    }

    private static void beginDodge(Player player, ParkourPlayer data) {
        Vec3 look = horizontalLook(player);
        Vec3 left = leftOf(look);
        Vec3 dir = switch (data.input.dodge) {
            case -1 -> left;
            case 1 -> left.scale(-1);
            case 2 -> look.scale(-1);
            case 3 -> look;
            default -> look;
        };
        if (dir.lengthSqr() < 1.0E-4) {
            dir = look;
        }
        dir = dir.normalize();
        data.mode = ParkourMode.DODGE;
        data.actionTicks = ParkourTuning.DODGE_STEP_TICKS;
        data.dodgeCooldown = ParkourTuning.DODGE_STEP_TICKS + 36;
        player.invulnerableTime = Math.max(player.invulnerableTime, ParkourTuning.DODGE_STEP_TICKS + 2);
        // Sharp step — no spin. Slight lift so it reads as a dash.
        double speed = ParkourTuning.DODGE_SPEED;
        data.motion = new Vec3(dir.x * speed, player.onGround() ? 0.08 : 0.02, dir.z * speed);
        data.lockPosition = false;
        data.wallSide = data.input.dodge;
        markDirty(data);
    }

    private static void tickDodge(Player player, ParkourPlayer data) {
        player.invulnerableTime = Math.max(player.invulnerableTime, 4);
        data.actionTicks--;
        data.motion = data.motion.multiply(0.78, 0.7, 0.78);
        data.lockPosition = false;
        if (data.actionTicks <= 0 || player.horizontalCollision) {
            player.invulnerableTime = 0;
            data.mode = ParkourMode.NONE;
            data.clearMotionLock();
            markDirty(data);
        }
    }

    private static void tryGrab(Player player, ParkourPlayer data) {
        if (!data.input.grab || data.prone || player.isInWater() || data.grabIgnoreTicks > 0) {
            return;
        }
        if (MovesConfig.get().toolClimb && yblk.moves.item.ClimbGear.equipped(player)) {
            tryToolWall(player, data);
            return;
        }
        if (tryBeginZipline(player, data)) {
            return;
        }
        MovesConfig cfg = MovesConfig.get();
        // 1) Ceiling hang (iron bars above head) — Smart Moving ceiling climb.
        BlockPos ceiling = cfg.ceilingHang ? findCeiling(player) : null;
        if (ceiling != null) {
            beginHang(player, data, ceiling);
            return;
        }
        int reach = Math.max(2, data.grabReach());
        Ledge ledge = cfg.ledgeGrab ? findLedge(player, data, reach) : null;
        if (ledge != null && HangBlocks.isPartialLip(player.level().getBlockState(ledge.pos().above()))) {
            beginLedge(player, data, ledge);
            return;
        }
        BlockPos lookedBars = lookedFenceOrBars(player);
        if (cfg.climb && lookedBars != null && !HangBlocks.isCeilingHang(player.level(), lookedBars)) {
            beginClimb(player, data, lookedBars);
            return;
        }
        BlockPos hold = (cfg.climb || cfg.ceilingHang) ? findHold(player) : null;
        if (cfg.ceilingHang && hold != null && HangBlocks.isCeilingHang(player.level(), hold)) {
            beginHang(player, data, hold);
            return;
        }
        boolean preferLedge = ledge != null && Math.abs(player.getXRot()) < 55.0F;
        if (preferLedge) {
            beginLedge(player, data, ledge);
            return;
        }
        // 2) Vertical climbable (chain / ladder / pole).
        if (cfg.climb && hold != null && HangBlocks.preferClimb(player.level(), hold)) {
            beginClimb(player, data, hold);
            return;
        }
        if (cfg.ceilingHang && hold != null) {
            beginHang(player, data, hold);
            return;
        }
        // 3) Ledge grab (airborne / looking up at a high lip).
        if (ledge != null) {
            beginLedge(player, data, ledge);
        }
    }

    /** Already on top of this lip. Looking down must not pull the player back off it. */
    private static boolean standingOnLip(Player player, Ledge ledge) {
        if (player.getY() < ledge.top - 0.35) {
            return false;
        }
        double dx = player.getX() - (ledge.pos.getX() + 0.5);
        double dz = player.getZ() - (ledge.pos.getZ() + 0.5);
        return dx * dx + dz * dz < 1.2;
    }

    private static void beginLedge(Player player, ParkourPlayer data, Ledge ledge) {
        ledge = supportUnderLip(player.level(), player, ledge);
        if (ledge == null || standingOnLip(player, ledge)) {
            return;
        }
        data.facing = ledge.facing;
        data.anchor = ledge.pos.immutable();
        data.along = 0.0;
        data.anchorTop = lip(player.level(), ledge.pos(), ledge.facing(), 0.0);
        Vec3 rawLedge = seatOnLip(player.level(), ledge.pos(), ledge.facing(), ledgePoint(data));
        Vec3 fittedLedge = fitCling(player, rawLedge, ledge.facing.getOpposite(), ledge.pos);
        // No free spot = do not start a grab that freezes the body in the stair/wall.
        if (fittedLedge == null) {
            data.anchor = null;
            data.facing = null;
            data.anchorTop = 0.0;
            return;
        }
        if (!spend(player, data, MovesConfig.get().grabCost)) {
            data.anchor = null;
            data.facing = null;
            data.anchorTop = 0.0;
            return;
        }
        double baseY = player.onGround() ? player.getY() : data.jumpStartY;
        data.grabRise = Math.max(0.5, data.anchorTop - baseY);
        data.mode = ParkourMode.LEDGE;
        // Drop stale climb yaw — PULLUP body lock prefers climbYaw and would spin 180°.
        data.climbYaw = Float.NaN;
        data.actionTicks = 0;
        data.goalPos = fittedLedge;
        data.motion = Vec3.ZERO;
        data.cornerTicks = 0;
        data.cornerLock = 0;
        data.cornerVia = null;
        data.cornerAnchor = null;
        data.cornerFacing = null;
        data.turnYaw = Float.NaN;
        data.climbJumpWasDown = data.input.jump;
        data.moveLock = 8;
        player.noPhysics = false;
        player.setJumping(false);
        markDirty(data);
    }

    private static BlockPos findCeiling(Player player) {
        Level level = player.level();
        int x = Mth.floor(player.getX());
        int z = Mth.floor(player.getZ());
        int base = Mth.floor(player.getY());
        BlockPos found = null;
        for (int dy = 2; dy <= 3; dy++) {
            BlockPos pos = new BlockPos(x, base + dy, z);
            if (!HangBlocks.isCeilingHang(level, pos)) {
                continue;
            }
            double gap = pos.getY() - player.getY();
            if (gap >= 1.75 && gap <= 2.35) {
                found = pos;
            }
        }
        return found == null ? null : found.immutable();
    }

    /** The grip has to be near the hands, not a chain or bar across the room. */
    private static boolean ceilingInReach(Player player, BlockPos pos) {
        if (pos == null) {
            return false;
        }
        return player.getEyePosition().distanceTo(Vec3.atCenterOf(pos)) <= ParkourTuning.GRAB_ARM_REACH + 0.35;
    }

    private static BlockPos ceilingAlong(Player player, Vec3 from, Vec3 dir, double reach) {
        if (dir.lengthSqr() < 1.0E-4) {
            return null;
        }
        Level level = player.level();
        Vec3 stepDir = dir.normalize();
        // Chains have no collision, so a collider ray flies through them into the roof.
        BlockPos seen = null;
        for (double t = 0.15; t <= reach; t += 0.12) {
            BlockPos pos = BlockPos.containing(from.add(stepDir.scale(t)));
            if (pos.equals(seen)) {
                continue;
            }
            seen = pos;
            if (HangBlocks.isCeilingHang(level, pos)) {
                return pos.immutable();
            }
            if (level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)) {
                break;
            }
        }
        Vec3 end = from.add(stepDir.scale(reach));
        HitResult hit = level.clip(new ClipContext(from, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (!(hit instanceof BlockHitResult block) || block.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        BlockPos hitPos = block.getBlockPos();
        BlockPos outside = hitPos.relative(block.getDirection());
        for (BlockPos pos : new BlockPos[]{hitPos, outside, hitPos.below(), outside.below()}) {
            if (HangBlocks.isCeilingHang(level, pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private static void beginHang(Player player, ParkourPlayer data, BlockPos hold) {
        double dx = player.getX() - (hold.getX() + 0.5);
        double dz = player.getZ() - (hold.getZ() + 0.5);
        double gap = hold.getY() - player.getY();
        if (dx * dx + dz * dz > 1.35 * 1.35 || gap < 1.35 || gap > 3.0) {
            return;
        }
        if (data.mode == ParkourMode.HANG && data.anchor.equals(hold)) {
            return;
        }
        if (data.mode != ParkourMode.HANG && !spend(player, data, MovesConfig.get().grabCost)) {
            return;
        }
        data.mode = ParkourMode.HANG;
        data.facing = player.getDirection();
        data.anchor = hold.immutable();
        data.actionTicks = 0;
        data.along = 0.0;
        data.goalPos = hangPoint(hold);
        data.motion = Vec3.ZERO;
        data.missHoldTicks = 0;
        player.noPhysics = false;
        player.setJumping(false);
        markDirty(data);
    }

    private static void beginClimb(Player player, ParkourPlayer data, BlockPos hold) {
        if (data.mode == ParkourMode.CLIMB && data.anchor.equals(hold)) {
            return;
        }
        if (data.mode != ParkourMode.CLIMB && !spend(player, data, MovesConfig.get().grabCost)) {
            return;
        }
        data.mode = ParkourMode.CLIMB;
        data.facing = Direction.getNearest(
                hold.getX() + 0.5 - player.getX(), 0.0, hold.getZ() + 0.5 - player.getZ());
        if (data.facing.getAxis().isVertical()) {
            data.facing = player.getDirection();
        }
        data.climbYaw = data.facing.toYRot();
        data.orbitAngle = Float.NaN;
        hold = wallPost(player.level(), hold, data.climbYaw);
        data.anchor = hold.immutable();
        data.actionTicks = 0;
        data.along = 0.0;
        data.climbAlong = 0.0;
        data.missHoldTicks = 0;
        data.climbJumpWasDown = data.input.jump;
        // Fall momentum → slide down the chain roughly as far as the drop.
        double fall = Math.max(player.fallDistance, Math.max(0.0, -player.getDeltaMovement().y * 18.0));
        if (fall > 0.25) {
            data.climbSlide = Mth.clamp(fall * 1.35, 1.6, ParkourTuning.CLIMB_SLIDE_MAX);
        } else {
            data.climbSlide = 0.0;
        }
        Direction.Axis placeAxis = wallAxis(player.level(), hold, data.climbYaw);
        if (placeAxis != null && HangBlocks.isFenceOrBars(player.level().getBlockState(hold))) {
            data.climbWall = true;
            data.goalPos = wallCling(player, hold, placeAxis, player.getY(), data.climbYaw);
        } else {
            data.climbWall = false;
            data.goalPos = climbStandPos(player.level(), hold, data.climbYaw, player.getY());
        }
        data.motion = Vec3.ZERO;
        player.noPhysics = false;
        player.setJumping(false);
        markDirty(data);
    }

    private static void tickLedge(Player player, ParkourPlayer data) {
        Level level = player.level();
        if (!data.input.grab || !HangBlocks.sturdyLedge(level, data.anchor) || data.stamina <= 0.0F) {
            release(player, data);
            return;
        }
        player.setJumping(false);
        player.noPhysics = false;
        data.actionTicks++;
        if (data.moveLock > 0) {
            data.moveLock--;
        }
        // The jump held while grabbing is not a pull. The next press is.
        boolean pressed = data.input.jump && !data.climbJumpWasDown;
        data.climbJumpWasDown = data.input.jump;
        if (!data.input.jump) {
            data.pullLocked = false;
        }
        boolean onStair = level.getBlockState(data.anchor).getBlock() instanceof net.minecraft.world.level.block.StairBlock;
        // Full ledges: Space pull-up. Stair walls: only at the top (no higher stair).
        boolean stairTopMount = onStair && canPullUp(level, data.anchor)
                && !(level.getBlockState(data.anchor.above()).getBlock() instanceof net.minecraft.world.level.block.StairBlock);
        if (pressed && !data.pullLocked && data.cornerTicks <= 0
                && ((!onStair && canPullUp(level, data.anchor)) || stairTopMount)
                && spend(player, data, MovesConfig.get().pullUpCost)) {
            data.pullDuration = pullTicksForRise(data.grabRise);
            data.pullLocked = true;
            beginLedgePullup(data, player.position());
            markDirty(data);
            return;
        }
        // Hold + A/D only. Sneak, look, and W never change height on full ledges.
        Direction alongAxis = data.facing.getCounterClockWise();
        Vec3 alongVec = new Vec3(alongAxis.getStepX(), 0.0, alongAxis.getStepZ());
        Vec3 camLeft = leftOf(horizontalLook(player));
        double axisSign = Math.signum(camLeft.dot(alongVec));
        if (Math.abs(axisSign) < 0.1) {
            axisSign = 1.0;
        }
        int shimmy = 0;
        if (data.input.strafe > 0.25F) {
            shimmy = (int) axisSign;
        } else if (data.input.strafe < -0.25F) {
            shimmy = (int) -axisSign;
        }
        if (data.cornerVia != null) {
            if (!Float.isNaN(data.turnYaw)) {
                data.turnYaw = stepYaw(data.turnYaw, data.turnToYaw, 6.5F);
            }
            if (shimmy == 0) {
                data.goalPos = player.position();
                data.motion = Vec3.ZERO;
                return;
            }
            Vec3 to = data.cornerVia.subtract(player.position());
            float yawLeft = Float.isNaN(data.turnYaw) ? 0.0F : Math.abs(Mth.wrapDegrees(data.turnToYaw - data.turnYaw));
            if (to.length() < 0.16 && yawLeft < 18.0F) {
                if (data.cornerAnchor != null && data.cornerFacing != null) {
                    takeLedge(level, data, data.cornerAnchor, data.cornerFacing);
                    data.along = projectAlong(data.anchor, data.facing, player.position());
                }
                data.cornerVia = null;
                data.cornerAnchor = null;
                data.cornerFacing = null;
                data.turnYaw = Float.NaN;
                data.cornerLock = 0;
            } else if (to.length() >= 0.16) {
                data.goalPos = player.position().add(to.normalize().scale(0.08));
                data.motion = Vec3.ZERO;
                return;
            } else {
                data.goalPos = player.position();
                data.motion = Vec3.ZERO;
                return;
            }
        }
        if (shimmy != 0) {
            drain(player, data, MovesConfig.get().shimmyDrain);
            // Same sideways speed on stairs as on a full ledge.
            data.along += shimmy * ParkourTuning.SHIMMY_SPEED;
            Direction travel = shimmy > 0 ? alongAxis : alongAxis.getOpposite();
            boolean past = shimmy > 0 ? data.along > 0.5 : data.along < -0.5;
            if (past) {
                double over = shimmy > 0 ? data.along - 0.5 : data.along + 0.5;
                if (advanceStraight(level, data, travel)) {
                    data.along = shimmy > 0 ? -0.5 + over : 0.5 + over;
                } else if (!onStair && queueCorner(level, player, data, travel)) {
                    // Stairs keep facing; only full ledges wrap around a corner.
                    data.along = shimmy > 0 ? 0.48 : -0.48;
                    data.goalPos = player.position();
                    data.motion = Vec3.ZERO;
                    return;
                } else {
                    data.along = shimmy > 0 ? 0.48 : -0.48;
                }
            }
        }
        boolean stair = data.cornerVia == null
                && level.getBlockState(data.anchor).getBlock() instanceof net.minecraft.world.level.block.StairBlock;
        if (stair) {
            data.anchorTop = lip(level, data.anchor, data.facing, data.along);
        }
        if (stair && data.moveLock <= 0) {
            boolean settled = player.position().distanceTo(data.goalPos == null ? player.position() : data.goalPos) < 0.18;
            int vertical = 0;
            if (data.input.forward > 0.55F) {
                vertical = 1;
            } else if (data.input.forward < -0.55F || data.input.sneak) {
                vertical = -1;
            }
            if (vertical > 0 && settled) {
                if (stepStair(level, player, data, 1)) {
                    data.moveLock = 10;
                    Vec3 stepped = seatOnLip(level, data.anchor, data.facing, ledgePoint(data));
                    Vec3 fittedStep = fitCling(player, stepped, data.facing.getOpposite(), data.anchor);
                    data.goalPos = fittedStep != null ? fittedStep : stepped;
                    data.motion = Vec3.ZERO;
                    return;
                }
                // Top of the stair wall — W mounts onto the lip like a pull-up.
                if (canPullUp(level, data.anchor) && !data.pullLocked
                        && spend(player, data, MovesConfig.get().pullUpCost)) {
                    data.pullDuration = pullTicksForRise(data.grabRise);
                    data.pullLocked = true;
                    beginLedgePullup(data, player.position());
                    markDirty(data);
                    return;
                }
            } else if (vertical < 0 && settled && stepStair(level, player, data, -1)) {
                data.moveLock = 10;
                Vec3 stepped = seatOnLip(level, data.anchor, data.facing, ledgePoint(data));
                Vec3 fittedStep = fitCling(player, stepped, data.facing.getOpposite(), data.anchor);
                data.goalPos = fittedStep != null ? fittedStep : stepped;
                data.motion = Vec3.ZERO;
                return;
            }
        }
        Vec3 raw = seatOnLip(level, data.anchor, data.facing, ledgePoint(data));
        Vec3 fitted = fitCling(player, raw, data.facing.getOpposite(), data.anchor);
        if (fitted == null) {
            release(player, data);
            return;
        }
        data.goalPos = fitted;
        data.motion = Vec3.ZERO;
    }

    /** One tread up or down. Facing never changes — a stair wall has nowhere to turn. */
    private static boolean stepStair(Level level, Player player, ParkourPlayer data, int vertical) {
        BlockPos anchor = data.anchor;
        Direction face = data.facing;
        // Only the stair column. relative(face).above() was a full block one step too high.
        BlockPos[] tries = vertical > 0
                ? new BlockPos[]{anchor.above()}
                : new BlockPos[]{anchor.below()};
        for (BlockPos pos : tries) {
            if (!(level.getBlockState(pos).getBlock() instanceof net.minecraft.world.level.block.StairBlock)) {
                continue;
            }
            if (!HangBlocks.sturdyLedge(level, pos) || !lipApproach(level, pos, face)) {
                continue;
            }
            data.along = Mth.clamp(data.along, -0.28, 0.28);
            data.cornerVia = null;
            data.cornerAnchor = null;
            data.cornerFacing = null;
            takeLedge(level, data, pos, face);
            data.facing = face;
            return true;
        }
        return false;
    }

    /**
     * Push a cling point out of the wall, then up. Never lowers it into the floor —
     * that was the idle sink through blocks.
     */
    private static Vec3 fitCling(Player player, Vec3 desired, Direction out) {
        return fitCling(player, desired, out, null);
    }

    /**
     * Hands may overlap {@code ignore} (the slab, stair or lip). A short lip sits barely
     * above the floor, so the nominal hang (lip − 1.62) is inside that floor — keep raising
     * until the body clears it.
     */
    private static Vec3 fitCling(Player player, Vec3 desired, Direction out, BlockPos ignore) {
        if (out == null) {
            out = Direction.UP;
        }
        if (grabFits(player, desired, ignore, false)) {
            return desired;
        }
        for (int i = 1; i <= 8; i++) {
            Vec3 pushed = desired.add(out.getStepX() * 0.08 * i, 0.0, out.getStepZ() * 0.08 * i);
            if (grabFits(player, pushed, ignore, false)) {
                return pushed;
            }
        }
        for (int i = 1; i <= 22; i++) {
            Vec3 raised = desired.add(0.0, 0.08 * i, 0.0);
            if (grabFits(player, raised, ignore, false)) {
                return raised;
            }
            Vec3 both = raised.add(out.getStepX() * 0.12 * Math.min(i, 6), 0.0, out.getStepZ() * 0.12 * Math.min(i, 6));
            if (grabFits(player, both, ignore, false)) {
                return both;
            }
        }
        return null;
    }

    /** Same lip plane — no stepping down onto a sheer wall. */
    private static boolean sameLip(Level level, BlockPos pos, double lipY) {
        double diff = Math.abs(lip(level, pos) - lipY);
        double frac = lipY - Math.floor(lipY);
        boolean stairJoin = HangBlocks.isPartialLip(level.getBlockState(pos)) || Math.abs(frac - 0.5) < 0.08;
        return diff <= (stairJoin ? 0.55 : 0.34);
    }

    private static boolean ledgeSpotOk(Level level, Player player, BlockPos pos, Direction facing, double lipY) {
        if (!HangBlocks.sturdyLedge(level, pos) || !sameLip(level, pos, lipY) || !hangSpaceClear(level, pos, facing)) {
            return false;
        }
        Vec3 point = ledgePoint(pos, facing, lip(level, pos, facing, 0.0), 0.0, 0);
        return fitCling(player, point, facing.getOpposite(), pos) != null;
    }

    private static void takeLedge(Level level, ParkourPlayer data, BlockPos pos, Direction facing) {
        data.facing = facing;
        data.anchor = pos.immutable();
        data.anchorTop = lip(level, pos, facing, data.along);
    }

    /**
     * Straight along the lip, then the concave (inner) turn, then the convex (outer) wrap.
     * A turn parks a waypoint so the body goes around the block instead of through it.
     */
    private static boolean advanceAlongLedge(Level level, Player player, ParkourPlayer data, Direction travel) {
        double lipY = data.anchorTop > 0.0 ? data.anchorTop : data.anchor.getY() + 1.0;
        Direction old = data.facing;
        BlockPos straight = data.anchor.relative(travel);
        if (tryLedgeStep(level, player, data, straight, old, travel, lipY, false)) {
            return true;
        }
        // Inner corner: the wall ahead blocks this face. The lip continues on the
        // block in front of us, faced along the direction we were shimmying.
        if (!HangBlocks.open(level, straight)) {
            BlockPos inner = straight.relative(old.getOpposite());
            if (tryLedgeStep(level, player, data, inner, travel, travel, lipY, true)) {
                return true;
            }
        }
        BlockPos notch = data.anchor.relative(old.getOpposite()).relative(travel);
        if (tryLedgeStep(level, player, data, notch, travel.getOpposite(), travel, lipY, true)) {
            return true;
        }
        if (tryLedgeStep(level, player, data, notch, old, travel, lipY, true)) {
            return true;
        }
        if (tryLedgeStep(level, player, data, data.anchor, travel.getOpposite(), travel, lipY, true)) {
            return true;
        }
        return false;
    }

    private static boolean tryLedgeStep(Level level, Player player, ParkourPlayer data, BlockPos pos,
                                        Direction facing, Direction travel, double lipY, boolean corner) {
        if (!HangBlocks.sturdyLedge(level, pos) || !sameLip(level, pos, lipY) || !faceOpen(level, pos, facing)) {
            return false;
        }
        Vec3 point = ledgePoint(pos, facing, lip(level, pos, facing, 0.0), 0.0, 0);
        Vec3 delta = point.subtract(player.position());
        Vec3 travelVec = new Vec3(travel.getStepX(), 0.0, travel.getStepZ());
        BlockPos before = data.anchor;
        Direction previous = data.facing;
        boolean concave = corner && pos.equals(before.relative(travel).relative(previous.getOpposite()));
        double align = delta.dot(travelVec);
        if (!concave && align < -0.2) {
            return false;
        }
        takeLedge(level, data, pos, facing);
        if (corner && previous != facing && concave) {
            data.cornerVia = insideCorner(before, previous, lipY);
            if (data.cornerVia != null && !bodyFits(player, data.cornerVia)) {
                data.cornerVia = null;
            }
        } else if (corner && previous != facing) {
            // Outer corner stays on the shared edge (cornerAlong). A waypoint jumped to the next face center.
            data.cornerVia = null;
        }
        return true;
    }

    /** Along-offset on the new face that sits on the corner the player just reached, not the middle. */
    private static double cornerAlong(Direction oldFacing, Direction newFacing) {
        Direction stoodOn = oldFacing.getOpposite();
        Direction slide = newFacing.getCounterClockWise();
        double dot = stoodOn.getStepX() * slide.getStepX() + stoodOn.getStepZ() * slide.getStepZ();
        if (Math.abs(dot) < 0.1) {
            return 0.0;
        }
        return Math.signum(dot) * 0.49;
    }

    /** Stay in the open cell of a concave corner instead of swinging outside the blocks. */
    private static Vec3 insideCorner(BlockPos anchor, Direction oldFacing, double lipY) {
        BlockPos pocket = anchor.relative(oldFacing.getOpposite());
        return new Vec3(pocket.getX() + 0.5, lipY - 1.62, pocket.getZ() + 0.5);
    }

    /** Point clearly outside both faces, so the swing doesn't clip the corner. */
    private static Vec3 outsideCorner(BlockPos anchor, Direction oldFacing, Direction travel, double lipY) {
        double off = 0.5 + ParkourTuning.LEDGE_STAND_OFF + 0.28;
        Direction out = oldFacing.getOpposite();
        return new Vec3(
                anchor.getX() + 0.5 + out.getStepX() * off + travel.getStepX() * off,
                lipY - 1.62,
                anchor.getZ() + 0.5 + out.getStepZ() * off + travel.getStepZ() * off
        );
    }

    private static boolean faceOpen(Level level, BlockPos solid, Direction intoWall) {
        BlockPos hang = solid.relative(intoWall.getOpposite());
        return !level.getBlockState(hang).isCollisionShapeFullBlock(level, hang);
    }

    /** The hang side is open, or only the floor blocks it and the space by the lip is clear. */
    private static boolean lipApproach(Level level, BlockPos solid, Direction intoWall) {
        if (faceOpen(level, solid, intoWall)) {
            return true;
        }
        BlockPos beside = solid.relative(intoWall.getOpposite());
        return HangBlocks.open(level, beside.above());
    }

    private static boolean hangSpaceClear(Level level, BlockPos solid, Direction intoWall) {
        BlockPos hang = solid.relative(intoWall.getOpposite());
        if (level.getBlockState(hang).isCollisionShapeFullBlock(level, hang)) {
            return false;
        }
        // Slab / stair / trapdoor: the shelf is short, the cell in front only has to be passable.
        if (HangBlocks.isPartialLip(level.getBlockState(solid))) {
            return true;
        }
        return HangBlocks.open(level, hang)
                && (HangBlocks.open(level, hang.above()) || HangBlocks.open(level, solid.above()));
    }

    /** Ledge/stair hang → pull-up. Keep climbYaw = into-wall so body lock matches the hang. */
    private static void beginLedgePullup(ParkourPlayer data, Vec3 start) {
        if (data.facing != null && data.facing.getAxis().isHorizontal()) {
            data.climbYaw = data.facing.toYRot();
        }
        data.mode = ParkourMode.PULLUP;
        data.actionTicks = 0;
        data.pullStart = start;
    }

    private static void tickPullup(Player player, ParkourPlayer data) {
        data.actionTicks++;
        player.noPhysics = false;
        player.setJumping(false);
        // Space already started the climb. Letting go of RMB must not drop them off the lip.
        data.pullGrabMiss = 0;
        double top = data.anchorTop > 0.0 ? data.anchorTop : data.anchor.getY() + 1.0;
        // Final stand is on top of the lip — approach from the hang side so we never lerp through the wall.
        Direction into = data.facing != null ? data.facing : Direction.NORTH;
        Vec3 stand = new Vec3(
                data.anchor.getX() + 0.5 - into.getStepX() * 0.12,
                top + 0.02,
                data.anchor.getZ() + 0.5 - into.getStepZ() * 0.12
        );
        if (data.actionTicks == 1 || data.pullStart == null) {
            data.pullStart = player.position();
        }
        Vec3 start = data.pullStart;
        Vec3 outsideTop = new Vec3(
                data.anchor.getX() + 0.5 - into.getStepX() * (0.5 + ParkourTuning.LEDGE_STAND_OFF),
                top,
                data.anchor.getZ() + 0.5 - into.getStepZ() * (0.5 + ParkourTuning.LEDGE_STAND_OFF)
        );
        int span = data.pullDuration > 0 ? data.pullDuration : pullTicksForRise(data.grabRise);
        double t = Mth.clamp(data.actionTicks / (float) span, 0.0F, 1.0F);
        double ease = t * t * (3.0 - 2.0 * t);
        Vec3 goal = ease < 0.55
                ? start.lerp(outsideTop, ease / 0.55)
                : outsideTop.lerp(stand, (ease - 0.55) / 0.45);
        data.goalPos = goal;
        data.motion = Vec3.ZERO;
        if (data.actionTicks >= span) {
            finishPullup(player, data, stand);
        }
    }

    private static void finishPullup(Player player, ParkourPlayer data, Vec3 stand) {
        Vec3 here = player.position();
        if (here.distanceTo(stand) > 0.05) {
            player.setPos(stand.x, stand.y, stand.z);
        }
        player.setDeltaMovement(Vec3.ZERO);
        player.setOnGround(true);
        player.fallDistance = 0.0F;
        player.setPose(Pose.STANDING);
        data.mode = ParkourMode.NONE;
        data.climbBoostTicks = 80;
        data.settleTicks = 6;
        data.grabIgnoreTicks = 1;
        data.pullStart = null;
        data.clearMotionLock();
        player.noPhysics = false;
        data.anim = "";
        if (player.level().isClientSide() && data.local) {
            MovesAnims.stop(player);
        }
        markDirty(data);
    }

    private static void tickHang(Player player, ParkourPlayer data) {
        Level level = player.level();
        if (!data.input.grab || data.stamina <= 0.0F) {
            release(player, data);
            return;
        }
        BlockPos ceiling = findCeiling(player);
        if (ceiling == null && !HangBlocks.isCeilingHang(level, data.anchor) && !HangBlocks.canHold(level, data.anchor)) {
            if (++data.missHoldTicks > 8) {
                release(player, data);
            }
            return;
        }
        data.missHoldTicks = 0;
        if (!HangBlocks.isCeilingHang(level, data.anchor)) {
            if (ceiling != null) {
                data.anchor = ceiling.immutable();
            } else if (++data.missHoldTicks > 8) {
                release(player, data);
                return;
            } else {
                return;
            }
        }
        player.setJumping(false);
        player.noPhysics = false;

        double gx = player.getX();
        double gz = player.getZ();
        double gy = data.anchor.getY() - ParkourTuning.HANG_DROP;
        Vec3 look = horizontalLook(player);
        Vec3 wish = look.scale(data.input.forward).add(leftOf(look).scale(data.input.strafe));
        BlockPos hangOn = data.anchor;
        boolean stepped = false;
        if (wish.lengthSqr() > 0.04) {
            wish = wish.normalize().scale(ParkourTuning.HANG_MOVE_SPEED);
            double nx = gx + wish.x;
            double nz = gz + wish.z;
            BlockPos next = ceilingCell(level, data.anchor, nx, nz);
            if (next == null) {
                if (ceilingCell(level, data.anchor, nx, gz) != null) {
                    next = ceilingCell(level, data.anchor, nx, gz);
                    nz = gz;
                } else if (ceilingCell(level, data.anchor, gx, nz) != null) {
                    next = ceilingCell(level, data.anchor, gx, nz);
                    nx = gx;
                }
            }
            if (next != null) {
                Vec3 step = placeUnderCeiling(level, next, nx, nz);
                hangOn = next;
                gx = step.x;
                gz = step.z;
                gy = step.y;
                stepped = true;
            }
        }
        data.anchor = hangOn.immutable();
        data.goalPos = new Vec3(gx, gy, gz);
        data.motion = Vec3.ZERO;
        data.actionTicks++;
        drain(player, data, stepped ? MovesConfig.get().hangDrain : MovesConfig.get().hangDrain * 0.15F);
    }

    /** Ceiling cell under this point, or the one we're already hanging on. */
    private static BlockPos ceilingCell(Level level, BlockPos anchor, double x, double z) {
        BlockPos at = BlockPos.containing(x, anchor.getY(), z);
        if (HangBlocks.isCeilingHang(level, at)) {
            return at;
        }
        return at.equals(anchor) ? anchor : null;
    }

    /** Under the hold, close to it. Along a chain the body stays on the link; it is not clamped to one block. */
    private static Vec3 placeUnderCeiling(Level level, BlockPos anchor, double x, double z) {
        double y = anchor.getY() - ParkourTuning.HANG_DROP;
        var state = level.getBlockState(anchor);
        if (state.is(net.minecraft.world.level.block.Blocks.CHAIN)) {
            var axis = state.getValue(net.minecraft.world.level.block.ChainBlock.AXIS);
            if (axis == net.minecraft.core.Direction.Axis.X) {
                z = anchor.getZ() + 0.5;
            } else if (axis == net.minecraft.core.Direction.Axis.Z) {
                x = anchor.getX() + 0.5;
            }
        }
        return new Vec3(x, y, z);
    }

    /** Nearest spot to a chain center. The link's own column does not push the body away. */
    private static Vec3 closestToPole(Player player, BlockPos anchor, double y) {
        double cx = anchor.getX() + 0.5;
        double cz = anchor.getZ() + 0.5;
        Vec3 center = new Vec3(cx, y, cz);
        if (grabFits(player, center, anchor, true)) {
            return center;
        }
        for (int i = 1; i <= 8; i++) {
            double r = 0.04 * i;
            for (int k = 0; k < 8; k++) {
                double a = k * (Math.PI / 4.0);
                Vec3 at = new Vec3(cx + Math.cos(a) * r, y, cz + Math.sin(a) * r);
                if (grabFits(player, at, anchor, true)) {
                    return at;
                }
            }
        }
        return fitHang(player, cx, y, cz);
    }

    /** Highest spot under the bar that still clears the floor. */
    private static Vec3 fitHang(Player player, double x, double y, double z) {
        Vec3 at = new Vec3(x, y, z);
        if (bodyFits(player, at)) {
            return at;
        }
        for (int i = 1; i <= 5; i++) {
            Vec3 lower = new Vec3(x, y - 0.06 * i, z);
            if (bodyFits(player, lower)) {
                return lower;
            }
        }
        for (int i = 1; i <= 4; i++) {
            Vec3 higher = new Vec3(x, y + 0.06 * i, z);
            if (bodyFits(player, higher)) {
                return higher;
            }
        }
        return null;
    }

    private static BlockPos findCeilingAt(Level level, double x, double headY, double z) {
        int ix = Mth.floor(x);
        int iz = Mth.floor(z);
        int iy = Mth.floor(headY);
        for (int dy = -1; dy <= 2; dy++) {
            BlockPos pos = new BlockPos(ix, iy + dy, iz);
            if (HangBlocks.isCeilingHang(level, pos)) {
                return pos.immutable();
            }
        }
        return null;
    }

    private static void tickClimb(Player player, ParkourPlayer data) {
        Level level = player.level();
        if (!data.input.grab || data.stamina <= 0.0F) {
            release(player, data);
            return;
        }
        if (data.toolClimb) {
            tickToolWall(player, data);
            return;
        }
        if (!data.input.jump) {
            data.pullLocked = false;
        }
        BlockPos hold = findClimbNear(player, data.anchor);
        if (hold == null) {
            if (++data.missHoldTicks > 8) {
                release(player, data);
            } else {
                data.goalPos = climbStandPos(level, data.anchor, data.climbYaw, player.getY());
            }
            return;
        }
        data.missHoldTicks = 0;
        player.noPhysics = false;

        boolean jumpDown = data.input.jump;
        BlockPos capNow = hold.above();
        if (MovesConfig.get().pullUp && jumpDown && !data.pullLocked
                && !HangBlocks.open(level, capNow)
                && HangBlocks.open(level, capNow.above())
                && player.getBoundingBox().maxY >= capNow.getY() - 0.35) {
            data.anchor = capNow.immutable();
            data.anchorTop = lip(level, capNow);
            // climbYaw is already into-wall; keep facing + body lock on that yaw.
            data.facing = Direction.fromYRot(data.climbYaw);
            data.climbYaw = data.facing.toYRot();
            data.pullDuration = pullTicksForLook(player);
            data.pullLocked = true;
            data.pullGrabMiss = 0;
            data.mode = ParkourMode.PULLUP;
            data.actionTicks = 0;
            data.pullStart = player.position();
            markDirty(data);
            return;
        }
        if (jumpDown && !data.climbJumpWasDown) {
            climbJumpOff(player, data, hold);
            return;
        }
        data.climbJumpWasDown = jumpDown;
        player.setJumping(false);

        double dy = 0.0;
        // Fall-slide only right after a mid-air grab — never while just holding on.
        if (data.climbSlide > 0.0 && data.actionTicks < 40) {
            dy = -ParkourTuning.CLIMB_SLIDE_SPEED;
            data.climbSlide = Math.max(0.0, data.climbSlide - ParkourTuning.CLIMB_SLIDE_SPEED);
        } else {
            data.climbSlide = 0.0;
            if (data.input.forward > 0.05F) {
                dy = ParkourTuning.CLIMB_MOVE_SPEED;
            } else if (data.input.sneak || data.input.forward < -0.05F) {
                dy = -ParkourTuning.CLIMB_MOVE_SPEED;
            }
        }

        double nextY = player.getY() + dy;
        if (dy > 0.0) {
            BlockPos hands = new BlockPos(hold.getX(), Mth.floor(player.getY() + 1.15), hold.getZ());
            boolean canRise = HangBlocks.canHold(level, hands.above())
                    || HangBlocks.canHold(level, hold.above())
                    || player.getY() + 0.2 < hold.getY();
            if (!canRise) {
                BlockPos cap = hold.above();
                if (MovesConfig.get().pullUp && data.input.jump && !data.pullLocked && !HangBlocks.open(level, cap)
                        && HangBlocks.open(level, cap.above())
                        && player.getBoundingBox().maxY >= cap.getY() - 0.2) {
                    data.anchor = cap.immutable();
                    data.anchorTop = lip(level, cap);
                    // climbYaw is already into-wall; keep facing + body lock on that yaw.
                    data.facing = Direction.fromYRot(data.climbYaw);
                    data.climbYaw = data.facing.toYRot();
                    data.pullDuration = pullTicksForLook(player);
                    data.pullLocked = true;
                    data.mode = ParkourMode.PULLUP;
                    data.actionTicks = 0;
                    data.pullStart = player.position();
                    markDirty(data);
                    return;
                }
                dy = 0.0;
                nextY = player.getY();
            }
        } else if (dy < 0.0) {
            int bottom = columnBottom(level, hold);
            boolean floor = solidUnderFeet(level, player.getX(), nextY, player.getZ());
            BlockPos atHands = new BlockPos(hold.getX(), Mth.floor(nextY + 1.2), hold.getZ());
            boolean handsOnClimbable = HangBlocks.canHold(level, atHands) || HangBlocks.canHold(level, hold);
            // Stop at the floor or the end of the chain. Do not release — releasing here fell through the block.
            if (floor || nextY < bottom - 0.05 || !handsOnClimbable) {
                data.climbSlide = 0.0;
                dy = 0.0;
                nextY = player.getY();
            }
        }
        if (dy != 0.0 || Math.abs(data.input.strafe) > 0.2F) {
            drain(player, data, MovesConfig.get().climbDrain);
        } else {
            drain(player, data, MovesConfig.get().climbDrain * 0.12F);
        }

        // Poles orbit. Fence/bar walls (including the end post) only shimmy — never orbit.
        double off = climbOffset(level, hold);
        Direction.Axis axis = wallAxis(level, hold, data.climbYaw);
        boolean barsOrFence = HangBlocks.isFenceOrBars(level.getBlockState(hold));
        boolean pole = HangBlocks.isPole(level.getBlockState(hold));
        boolean wallRun = barsOrFence && axis != null;
        data.climbWall = wallRun;
        if (wallRun) {
            Direction into = Direction.fromYRot(data.climbYaw);
            // Keep the latched wall-facing yaw. Only re-derive if it somehow runs along the wall.
            if (into.getAxis() == axis) {
                double side = axis == Direction.Axis.X
                        ? player.getZ() - (hold.getZ() + 0.5)
                        : player.getX() - (hold.getX() + 0.5);
                if (Math.abs(side) < 0.02) {
                    side = 1.0;
                }
                into = axis == Direction.Axis.X
                        ? (side >= 0.0 ? Direction.NORTH : Direction.SOUTH)
                        : (side >= 0.0 ? Direction.WEST : Direction.EAST);
                data.climbYaw = into.toYRot();
            }
            data.facing = into;
        }
        boolean strafing = Math.abs(data.input.strafe) > 0.2F && data.climbSlide <= 0.0;
        double sign = data.input.strafe > 0.0F ? 1.0 : -1.0;
        BlockPos nextHold = hold;
        Vec3 goal = null;
        if (pole || !wallRun && axis == null) {
            goal = poleStep(hold, player, data, off, nextY, strafing, sign);
        } else if (strafing && axis != null) {
            Vec3 camLeft = leftOf(horizontalLook(player));
            double cam = axis == Direction.Axis.X ? camLeft.x : camLeft.z;
            int dir;
            if (Math.abs(cam) > 0.2) {
                dir = (int) Math.signum(cam * data.input.strafe);
            } else {
                Direction slide = Direction.fromYRot(data.climbYaw).getCounterClockWise();
                double onAxis = axis == Direction.Axis.X ? slide.getStepX() : slide.getStepZ();
                dir = (int) Math.signum(onAxis * data.input.strafe);
            }
            if (dir != 0) {
                // Prefer latched climbYaw — position across a thin end post flips and spins the body.
                double sideSign = Math.signum(sideFromYaw(data.climbYaw, axis, off));
                if (sideSign == 0.0) {
                    double centerSide = axis == Direction.Axis.X ? hold.getZ() + 0.5 : hold.getX() + 0.5;
                    double playerSide = axis == Direction.Axis.X ? player.getZ() : player.getX();
                    sideSign = Math.signum(playerSide - centerSide);
                }
                if (sideSign == 0.0) {
                    sideSign = 1.0;
                }
                double lateral = sideSign * off;
                double center = axis == Direction.Axis.X ? hold.getX() + 0.5 : hold.getZ() + 0.5;
                double coord = (axis == Direction.Axis.X ? player.getX() : player.getZ()) + dir * ParkourTuning.CLIMB_SHIMMY;
                BlockPos neighbor = neighborHold(level, hold, axis, dir, nextY);
                double limit = center + dir * (neighbor != null ? 0.52 : 0.42);
                coord = dir > 0 ? Math.min(coord, limit) : Math.max(coord, limit);
                BlockPos at = hold;
                if (neighbor != null && Math.abs(coord - center) > 0.5) {
                    at = neighbor;
                }
                for (double extra = 0.0; extra <= 0.08 && goal == null; extra += 0.08) {
                    double lat = lateral + sideSign * extra;
                    for (double lift = 0.0; lift <= 0.2 && goal == null; lift += 0.1) {
                        double gx = axis == Direction.Axis.X ? coord : at.getX() + 0.5 + (axis == Direction.Axis.Z ? lat : 0.0);
                        double gz = axis == Direction.Axis.Z ? coord : at.getZ() + 0.5 + (axis == Direction.Axis.X ? lat : 0.0);
                        Vec3 line = new Vec3(gx, nextY + lift, gz);
                        if (climbFits(player, line, at)) {
                            goal = line;
                            nextHold = at;
                        }
                    }
                }
                if (goal == null) {
                    double gx = axis == Direction.Axis.X ? coord : player.getX();
                    double gz = axis == Direction.Axis.Z ? coord : player.getZ();
                    Vec3 slide = new Vec3(gx, nextY, gz);
                    if (climbFits(player, slide, at)) {
                        goal = slide;
                        nextHold = at;
                    }
                }
            }
        }
        if (goal == null) {
            Vec3 stay = axis != null
                    ? wallCling(player, hold, axis, nextY, data.climbYaw)
                    : climbStandPos(level, hold, data.climbYaw, nextY);
            // A failed fit used to leave the body inside the link. Thin grips are placed at the gap anyway.
            if (climbFits(player, stay, hold) || pole || barsOrFence) {
                goal = stay;
            }
        }
        if (goal == null) {
            data.goalPos = player.position();
        } else {
            data.anchor = nextHold.immutable();
            data.facing = Direction.fromYRot(data.climbYaw);
            data.goalPos = goal;
            data.climbAlong = 0.0;
        }
        data.motion = Vec3.ZERO;
        data.actionTicks++;
    }

    /** Lateral offset from the side the body is facing, so a fence post can't flip the sign. */
    private static double sideFromYaw(float climbYaw, Direction.Axis axis, double off) {
        float rad = climbYaw * Mth.DEG_TO_RAD;
        double ox = Mth.sin(rad) * off;
        double oz = -Mth.cos(rad) * off;
        return axis == Direction.Axis.X ? oz : ox;
    }

    /** Fence/bar post in this column that actually has a wall to slide along. */
    private static BlockPos wallPost(Level level, BlockPos hold, float climbYaw) {
        if (!HangBlocks.isFenceOrBars(level.getBlockState(hold))) {
            return hold;
        }
        if (wallAxis(level, hold, climbYaw) != null) {
            return hold;
        }
        for (int dy = 1; dy <= 2; dy++) {
            BlockPos up = hold.above(dy);
            if (HangBlocks.isFenceOrBars(level.getBlockState(up)) && wallAxis(level, up, climbYaw) != null) {
                return up;
            }
            BlockPos down = hold.below(dy);
            if (HangBlocks.isFenceOrBars(level.getBlockState(down)) && wallAxis(level, down, climbYaw) != null) {
                return down;
            }
        }
        return hold;
    }

    /** Move around a chain/pole from where the body already is. W/S only changes height. */
    private static Vec3 poleStep(BlockPos hold, Player player, ParkourPlayer data, double off, double feetY,
                                 boolean orbit, double sign) {
        double cx = hold.getX() + 0.5;
        double cz = hold.getZ() + 0.5;
        double px = player.getX() - cx;
        double pz = player.getZ() - cz;
        double len = Math.sqrt(px * px + pz * pz);
        boolean inside = len < off - 0.05;
        if (len < 0.08) {
            float rad = data.climbYaw * Mth.DEG_TO_RAD;
            px = Mth.sin(rad) * off;
            pz = -Mth.cos(rad) * off;
            len = Math.max(off, 0.08);
            inside = true;
        }
        double ux = px / len;
        double uz = pz / len;
        if (Float.isNaN(data.orbitAngle)) {
            data.orbitAngle = (float) Math.toDegrees(Math.atan2(pz, px));
        }
        if (orbit) {
            data.orbitAngle += (float) (sign * 5.0);
            double a = Math.toRadians(data.orbitAngle);
            Vec3 goal = new Vec3(cx + Math.cos(a) * off, feetY, cz + Math.sin(a) * off);
            data.climbYaw = yawToward(hold, goal);
            return goal;
        }
        double a = Math.toRadians(data.orbitAngle);
        Vec3 ring = new Vec3(cx + Math.cos(a) * off, feetY, cz + Math.sin(a) * off);
        data.climbYaw = yawToward(hold, ring);
        boolean thin = HangBlocks.isPole(player.level().getBlockState(hold))
                || HangBlocks.isFenceOrBars(player.level().getBlockState(hold));
        if (thin || climbFits(player, ring, hold)) {
            return ring;
        }
        if (inside) {
            double base = Math.atan2(pz, px);
            for (int k = 1; k <= 8; k++) {
                double ang = base + (k * Math.PI / 8.0) * ((k & 1) == 0 ? 1.0 : -1.0);
                Vec3 at = new Vec3(cx + Math.cos(ang) * off, feetY, cz + Math.sin(ang) * off);
                if (climbFits(player, at, hold)) {
                    data.climbYaw = yawToward(hold, at);
                    return at;
                }
            }
            data.climbYaw = yawToward(hold, ring);
            return ring;
        }
        Vec3 vertical = new Vec3(player.getX(), feetY, player.getZ());
        if (climbFits(player, vertical, hold)) {
            return vertical;
        }
        return new Vec3(player.getX(), player.getY(), player.getZ());
    }

    /** Next lip on the same face. A corner does not turn by itself. */
    private static boolean advanceStraight(Level level, ParkourPlayer data, Direction travel) {
        BlockPos next = data.anchor.relative(travel);
        double lipY = data.anchorTop > 0.0 ? data.anchorTop : data.anchor.getY() + 1.0;
        if (!HangBlocks.sturdyLedge(level, next) || !faceOpen(level, next, data.facing)) {
            return false;
        }
        // Enter from the near edge so a stair's low/high half lines up with this lip.
        Direction alongAxis = data.facing.getCounterClockWise();
        double entryAlong = travel == alongAxis ? -0.42 : 0.42;
        double nextLip = lip(level, next, data.facing, entryAlong);
        if (Math.abs(nextLip - lipY) > 0.55 && !sameLip(level, next, lipY)) {
            return false;
        }
        takeLedge(level, data, next, data.facing);
        return true;
    }

    /** Remember a corner and let the held key walk onto it. Does not move the anchor yet. */
    private static boolean queueCorner(Level level, Player player, ParkourPlayer data, Direction travel) {
        BlockPos anchor = data.anchor;
        Direction facing = data.facing;
        double lipY = data.anchorTop > 0.0 ? data.anchorTop : anchor.getY() + 1.0;
        BlockPos straight = anchor.relative(travel);
        BlockPos newPos = null;
        Direction newFace = null;
        boolean outer = false;
        if (HangBlocks.open(level, straight)) {
            Direction sideFace = travel.getOpposite();
            if (ledgeFaceOk(level, anchor, sideFace, lipY)) {
                newPos = anchor;
                newFace = sideFace;
                outer = true;
            }
        } else {
            BlockPos inner = straight.relative(facing.getOpposite());
            if (ledgeFaceOk(level, inner, travel, lipY)) {
                newPos = inner;
                newFace = travel;
            }
        }
        if (newPos == null || newFace == null) {
            return false;
        }
        double entry = projectAlong(newPos, newFace, player.position());
        data.cornerVia = outer
                ? outsideCorner(anchor, facing, travel, lipY)
                : ledgePoint(newPos, newFace, lipY, entry, 0);
        data.cornerAnchor = newPos.immutable();
        data.cornerFacing = newFace;
        data.cornerAlong = entry;
        data.turnYaw = facing.toYRot();
        data.turnToYaw = newFace.toYRot();
        return true;
    }

    private static boolean ledgeFaceOk(Level level, BlockPos pos, Direction face, double lipY) {
        return HangBlocks.sturdyLedge(level, pos) && sameLip(level, pos, lipY) && lipApproach(level, pos, face);
    }

    /** Along-offset of {@code from} on this face, clamped to the block. */
    private static double projectAlong(BlockPos pos, Direction facing, Vec3 from) {
        Direction axis = facing.getCounterClockWise();
        double along = (from.x - (pos.getX() + 0.5)) * axis.getStepX()
                + (from.z - (pos.getZ() + 0.5)) * axis.getStepZ();
        return Mth.clamp(along, -0.42, 0.42);
    }

    private static float stepYaw(float current, float target, float maxStep) {
        float delta = Mth.wrapDegrees(target - current);
        if (delta > maxStep) {
            delta = maxStep;
        } else if (delta < -maxStep) {
            delta = -maxStep;
        }
        return current + delta;
    }

    /**
     * A slab / trapdoor is only a thin shelf — hang on the solid under it.
     * Stairs are the wall itself; redirecting under them grabs the lower block and traps you.
     */
    private static Ledge supportUnderLip(Level level, Player player, Ledge ledge) {
        if (ledge == null || !HangBlocks.isPartialLip(level.getBlockState(ledge.pos()))) {
            return ledge;
        }
        if (level.getBlockState(ledge.pos()).getBlock() instanceof net.minecraft.world.level.block.StairBlock) {
            return ledge;
        }
        BlockPos support = ledge.pos().below();
        var under = level.getBlockState(support);
        if (under.isAir() || HangBlocks.isPartialLip(under) || HangBlocks.canHold(level, support)
                || under.getCollisionShape(level, support).isEmpty()) {
            return ledge;
        }
        Direction face = ledge.facing();
        if (!lipApproach(level, support, face)) {
            face = lipFace(level, support, player, horizontalLook(player));
        }
        if (face == null || !HangBlocks.sturdyLedge(level, support)) {
            return null;
        }
        return new Ledge(support.immutable(), face, lip(level, support));
    }

    /** Feet height under a ceiling hold. Raises off the floor instead of snapping to the cell center. */
    private static double hangFeetY(Player player, BlockPos anchor, double x, double z) {
        double base = anchor.getY() - 1.85;
        for (int i = 0; i <= 10; i++) {
            double y = base + i * 0.05;
            if (grabFits(player, new Vec3(x, y, z), anchor, true)) {
                return y;
            }
        }
        if (grabFits(player, new Vec3(x, player.getY(), z), anchor, true)) {
            return player.getY();
        }
        return base;
    }

    /** The fence or iron bars the crosshair is on, from the ground or in the air. */
    private static BlockPos lookedFenceOrBars(Player player) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        BlockPos seen = null;
        for (double t = 0.15; t <= ParkourTuning.GRAB_ARM_REACH; t += 0.1) {
            BlockPos pos = BlockPos.containing(eye.add(look.scale(t)));
            if (pos.equals(seen)) {
                continue;
            }
            seen = pos;
            if (HangBlocks.isFenceOrBars(level.getBlockState(pos))) {
                return pos.immutable();
            }
            if (level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)) {
                break;
            }
        }
        return null;
    }

    /** Which post this shimmy point belongs to. Stays put when the next post is missing. */
    private static BlockPos postAt(Level level, BlockPos hold, Direction.Axis axis, Vec3 pos, double feetY) {
        double coord = axis == Direction.Axis.X ? pos.x : pos.z;
        double center = axis == Direction.Axis.X ? hold.getX() + 0.5 : hold.getZ() + 0.5;
        if (coord > center + 0.5) {
            BlockPos next = neighborHold(level, hold, axis, 1, feetY);
            return next != null ? next : hold;
        }
        if (coord < center - 0.5) {
            BlockPos next = neighborHold(level, hold, axis, -1, feetY);
            return next != null ? next : hold;
        }
        return hold;
    }

    private static Vec3 lineAlongFence(Player player, Level level, BlockPos hold, Direction.Axis axis, double sign,
                                       Vec3 camLeft, double off, double feetY) {
        double along = axis == Direction.Axis.X ? camLeft.x * sign : camLeft.z * sign;
        if (Math.abs(along) < 0.15) {
            return null;
        }
        int dir = along > 0.0 ? 1 : -1;
        double delta = dir * ParkourTuning.CLIMB_SHIMMY;
        double side = sideOf(player, hold, axis, off);
        double center = axis == Direction.Axis.X ? hold.getX() + 0.5 : hold.getZ() + 0.5;
        double coord = (axis == Direction.Axis.X ? player.getX() : player.getZ()) + delta;
        BlockPos neighbor = neighborHold(level, hold, axis, dir, feetY);
        double limit = center + dir * (neighbor != null ? 0.62 : 0.42);
        if (dir > 0) {
            coord = Math.min(coord, limit);
        } else {
            coord = Math.max(coord, limit);
        }
        BlockPos at = neighbor != null && Math.abs(coord - center) > 0.5 ? neighbor : hold;
        double sideX = axis == Direction.Axis.Z ? side : 0.0;
        double sideZ = axis == Direction.Axis.X ? side : 0.0;
        if (!at.equals(hold)) {
            side = sideOf(player, at, axis, off);
            sideX = axis == Direction.Axis.Z ? side : 0.0;
            sideZ = axis == Direction.Axis.X ? side : 0.0;
        }
        double gx = axis == Direction.Axis.X ? coord : at.getX() + 0.5 + sideX;
        double gz = axis == Direction.Axis.Z ? coord : at.getZ() + 0.5 + sideZ;
        Vec3 line = new Vec3(gx, feetY, gz);
        return climbFits(player, line, hold) ? line : null;
    }

    private static boolean climbFits(Player player, Vec3 pos, BlockPos hold) {
        AABB box = Player.STANDING_DIMENSIONS.makeBoundingBox(pos.x, pos.y, pos.z).deflate(0.03);
        Level level = player.level();
        int x0 = Mth.floor(box.minX);
        int y0 = Mth.floor(box.minY + 0.08);
        int z0 = Mth.floor(box.minZ);
        int x1 = Mth.floor(box.maxX);
        int y1 = Mth.floor(box.maxY);
        int z1 = Mth.floor(box.maxZ);
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    BlockPos at = new BlockPos(x, y, z);
                    if (hold != null && x == hold.getX() && z == hold.getZ()) {
                        continue;
                    }
                    var state = level.getBlockState(at);
                    if (HangBlocks.isFenceOrBars(state) || HangBlocks.isPole(state) || state.is(net.minecraft.tags.BlockTags.WALLS)) {
                        continue;
                    }
                    var shape = state.getCollisionShape(level, at);
                    if (shape.isEmpty()) {
                        continue;
                    }
                    if (at.getY() + shape.max(Direction.Axis.Y) <= pos.y + 0.15) {
                        continue;
                    }
                    for (AABB part : shape.toAabbs()) {
                        if (part.move(at.getX(), at.getY(), at.getZ()).intersects(box)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
    }

    /**
     * Axis of a fence/bar wall. Null for a freestanding pole (orbit around it).
     * A corner uses the axis that runs along the side the body is already facing.
     */
    private static Direction.Axis wallAxis(Level level, BlockPos pos, float climbYaw) {
        var state = level.getBlockState(pos);
        if (HangBlocks.isPole(state)) {
            return null;
        }
        if (HangBlocks.isFenceOrBars(state)) {
            boolean x = sideHold(level, pos, Direction.EAST) || sideHold(level, pos, Direction.WEST);
            boolean z = sideHold(level, pos, Direction.NORTH) || sideHold(level, pos, Direction.SOUTH);
            if (x && !z) {
                return Direction.Axis.X;
            }
            if (z && !x) {
                return Direction.Axis.Z;
            }
            if (x) {
                Direction face = Direction.fromYRot(climbYaw);
                return face.getAxis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
            }
            return null;
        }
        Direction.Axis axis = holdAxis(level, pos);
        if (axis != null) {
            return axis;
        }
        if (!HangBlocks.isFenceOrBars(state)) {
            return null;
        }
        boolean x = HangBlocks.canHold(level, pos.east()) || HangBlocks.canHold(level, pos.west());
        boolean z = HangBlocks.canHold(level, pos.north()) || HangBlocks.canHold(level, pos.south());
        if (x && z) {
            Direction face = Direction.fromYRot(climbYaw);
            return face.getAxis() == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X;
        }
        if (x) {
            return Direction.Axis.X;
        }
        if (z) {
            return Direction.Axis.Z;
        }
        return null;
    }

    /** Bars/fence beside this post, including the course just above or below (ground grab). */
    private static boolean sideHold(Level level, BlockPos pos, Direction dir) {
        return HangBlocks.canHold(level, pos.relative(dir))
                || HangBlocks.canHold(level, pos.above().relative(dir))
                || HangBlocks.canHold(level, pos.below().relative(dir));
    }

    /** X or Z when this post connects to another. Null for a chain or a lone fence post. */
    private static Direction.Axis holdAxis(Level level, BlockPos pos) {
        boolean x = HangBlocks.canHold(level, pos.east()) || HangBlocks.canHold(level, pos.west());
        boolean z = HangBlocks.canHold(level, pos.north()) || HangBlocks.canHold(level, pos.south());
        if (x && !z) {
            return Direction.Axis.X;
        }
        if (z && !x) {
            return Direction.Axis.Z;
        }
        return null;
    }

    private static BlockPos neighborHold(Level level, BlockPos hold, Direction.Axis axis, int step, double feetY) {
        if (step == 0) {
            return null;
        }
        BlockPos next = axis == Direction.Axis.X ? hold.offset(step, 0, 0) : hold.offset(0, 0, step);
        int handsY = Mth.floor(feetY + 1.05);
        BlockPos hands = new BlockPos(next.getX(), handsY, next.getZ());
        if (HangBlocks.canHold(level, hands)) {
            return hands.immutable();
        }
        if (HangBlocks.canHold(level, next)) {
            return next.immutable();
        }
        return null;
    }

    /** Signed distance off the fence line, on the side the player is already standing. */
    private static double sideOf(Player player, BlockPos hold, Direction.Axis axis, double offset) {
        double delta = axis == Direction.Axis.X
                ? player.getZ() - (hold.getZ() + 0.5)
                : player.getX() - (hold.getX() + 0.5);
        double side = Math.signum(delta);
        if (side == 0.0) {
            side = 1.0;
        }
        return side * offset;
    }

    /** Yaw that looks from {@code from} toward the hold (body faces the chain). */
    private static float yawToward(BlockPos hold, Vec3 from) {
        double dx = hold.getX() + 0.5 - from.x;
        double dz = hold.getZ() + 0.5 - from.z;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    /** True when a motion-blocking block is under the feet at the given Y. */
    private static boolean solidUnderFeet(Level level, double x, double feetY, double z) {
        BlockPos below = BlockPos.containing(x, feetY - 0.02, z);
        var state = level.getBlockState(below);
        if (state.isAir() || HangBlocks.canHold(level, below)) {
            return false;
        }
        var shape = state.getCollisionShape(level, below);
        if (shape.isEmpty()) {
            return false;
        }
        return feetY <= below.getY() + shape.max(Direction.Axis.Y) + 0.01;
    }

    /** Lowest climbable Y in this column. */
    private static int columnBottom(Level level, BlockPos hold) {
        int y = hold.getY();
        while (y > level.getMinBuildHeight() && HangBlocks.canHold(level, new BlockPos(hold.getX(), y - 1, hold.getZ()))) {
            y--;
        }
        return y;
    }

    private static void climbJumpOff(Player player, ParkourPlayer data, BlockPos hold) {
        float rad = data.climbYaw * Mth.DEG_TO_RAD;
        // Same side as stand-off — push further off the pole (~1.5 blocks).
        Vec3 away = new Vec3(Mth.sin(rad), 0.0, -Mth.cos(rad)).scale(ParkourTuning.CLIMB_JUMP_OFF);
        data.grabIgnoreTicks = 22;
        data.climbSlide = 0.0;
        release(player, data);
        player.noPhysics = false;
        player.setDeltaMovement(away.x, ParkourTuning.CLIMB_JUMP_UP, away.z);
        player.hasImpulse = true;
        player.fallDistance = 0.0F;
        player.setOnGround(false);
    }

    private static BlockPos findClimbNear(Player player, BlockPos hint) {
        Level level = player.level();
        // Prefer continuous column from the current anchor first.
        if (HangBlocks.canHold(level, hint)) {
            BlockPos atHands = new BlockPos(hint.getX(), Mth.floor(player.getY() + 1.0), hint.getZ());
            if (HangBlocks.isPole(level.getBlockState(hint))) {
                if (HangBlocks.isPole(level.getBlockState(atHands))) {
                    return atHands.immutable();
                }
                if (HangBlocks.isPole(level.getBlockState(hint.above())) && player.getY() > hint.getY() + 0.55) {
                    return hint.above().immutable();
                }
                return hint.immutable();
            }
            if (HangBlocks.canHold(level, atHands)) {
                return atHands.immutable();
            }
            if (HangBlocks.canHold(level, hint.above()) && player.getY() > hint.getY() + 0.55) {
                return hint.above().immutable();
            }
            // Do NOT auto-step below while still clinging — that caused floor-clip loops.
            return hint.immutable();
        }
        // Anchor lost: search nearby, prefer same XZ column then horizontal neighbors.
        BlockPos origin = BlockPos.containing(hint.getX() + 0.5, player.getY() + 1.0, hint.getZ() + 0.5);
        BlockPos best = null;
        double bestDist = ParkourTuning.GRAB_ARM_REACH + 0.5;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!HangBlocks.canHold(level, pos)) {
                        continue;
                    }
                    double dist = player.position().add(0.0, 1.0, 0.0).distanceTo(Vec3.atCenterOf(pos));
                    // Prefer staying in the hint column.
                    if (pos.getX() == hint.getX() && pos.getZ() == hint.getZ()) {
                        dist -= 0.35;
                    }
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos;
                    }
                }
            }
        }
        return best == null ? null : best.immutable();
    }

    private static void tryWallRun(Player player, ParkourPlayer data) {
        if (!MovesConfig.get().wallRun) {
            return;
        }
        if (player.onGround() || data.wallRunUsed || !data.input.sprint || !player.horizontalCollision) {
            return;
        }
        if (data.jumpKind == 0) {
            return;
        }
        Direction wall = wallBeside(player);
        if (wall == null) {
            return;
        }
        Vec3 look = horizontalLook(player);
        Vec3 normal = new Vec3(wall.getStepX(), 0.0, wall.getStepZ());
        if (Math.abs(look.dot(normal)) > 0.65) {
            return;
        }
        data.mode = ParkourMode.WALL_RUN;
        data.facing = wall;
        data.wallRunUsed = true;
        data.actionTicks = ParkourTuning.WALL_RUN_TICKS;
        Vec3 side = new Vec3(-normal.z, 0.0, normal.x);
        if (side.dot(look) < 0) {
            side = side.scale(-1);
        }
        data.wallSide = side.x * wall.getStepZ() - side.z * wall.getStepX() >= 0 ? 1 : -1;
        data.motion = new Vec3(side.x * 0.32, 0.06, side.z * 0.32);
        data.lockPosition = false;
        markDirty(data);
    }

    private static void tickWallRun(Player player, ParkourPlayer data) {
        if (!MovesConfig.get().wallRun) {
            release(player, data);
            return;
        }
        data.actionTicks--;
        if (data.actionTicks <= 0 || data.input.sneak || !player.horizontalCollision || !spend(player, data, MovesConfig.get().wallRunDrain)) {
            release(player, data);
            return;
        }
        data.motion = new Vec3(data.motion.x, 0.02, data.motion.z);
        data.lockPosition = false;
    }

    private static void setGoal(ParkourPlayer data, Vec3 goal) {
        data.goalPos = goal;
        data.lockPosition = false;
    }

    private static void approach(Player player, ParkourPlayer data, Vec3 goal, double speed) {
        setGoal(data, goal);
    }

    private static void nudgeOut(Player player) {
        Level level = player.level();
        if (level.noCollision(player)) {
            return;
        }
        Vec3[] steps = {
                new Vec3(0.0, 0.25, 0.0),
                new Vec3(0.25, 0.1, 0.0),
                new Vec3(-0.25, 0.1, 0.0),
                new Vec3(0.0, 0.1, 0.25),
                new Vec3(0.0, 0.1, -0.25),
                new Vec3(0.0, 0.55, 0.0)
        };
        for (Vec3 step : steps) {
            if (level.noCollision(player, player.getBoundingBox().move(step))) {
                player.move(MoverType.SELF, step);
                return;
            }
        }
    }

    /** Keep the same hang as a full block. Only lift if the feet are actually inside a floor. */
    private static Vec3 seatOnLip(Level level, BlockPos anchor, Direction facing, Vec3 raw) {
        if (anchor == null || facing == null || !HangBlocks.isPartialLip(level.getBlockState(anchor))) {
            return raw;
        }
        double y = raw.y;
        BlockPos under = BlockPos.containing(raw.x, y - 0.02, raw.z);
        if (!under.equals(anchor)) {
            var shape = level.getBlockState(under).getCollisionShape(level, under);
            if (!shape.isEmpty()) {
                double floor = under.getY() + shape.max(Direction.Axis.Y);
                if (y < floor + 0.01) {
                    y = floor + 0.01;
                }
            }
        }
        return new Vec3(raw.x, y, raw.z);
    }

    private static Vec3 ledgePoint(ParkourPlayer data) {
        double top = data.anchorTop > 0.0 ? data.anchorTop : data.anchor.getY() + 1.0;
        return ledgePoint(data.anchor, data.facing, top, data.along, data.cornerTicks);
    }

    private static Vec3 ledgePoint(BlockPos anchor, Direction facing, double top, double along, int cornerTicks) {
        double out = ParkourTuning.LEDGE_STAND_OFF;
        if (cornerTicks > 0) {
            out += ParkourTuning.LEDGE_CORNER_EXTRA;
        }
        Direction alongAxis = facing.getCounterClockWise();
        double x = anchor.getX() + 0.5 - facing.getStepX() * (0.5 + out) + alongAxis.getStepX() * along;
        double z = anchor.getZ() + 0.5 - facing.getStepZ() * (0.5 + out) + alongAxis.getStepZ() * along;
        // Eyes at the lip. Closer than the body radius clips the wall; fitCling pushes the rest.
        return new Vec3(x, top - 1.62, z);
    }

    private static Vec3 hangPoint(BlockPos anchor) {
        // Below the bar so the head/camera stays out of iron bars / trapdoors.
        return new Vec3(anchor.getX() + 0.5, anchor.getY() - ParkourTuning.HANG_DROP, anchor.getZ() + 0.5);
    }

    private static Vec3 climbPoint(ParkourPlayer data) {
        return new Vec3(data.anchor.getX() + 0.5, data.anchor.getY(), data.anchor.getZ() + 0.5);
    }

    /** Looking straight up is one second. Looking straight down is two. In between, it blends. */
    private static int pullTicksForLook(Player player) {
        float pitch = Mth.clamp(player.getXRot(), -90.0F, 90.0F);
        float down = (pitch + 90.0F) / 180.0F;
        return Math.round(Mth.lerp(down, 20.0F, 40.0F));
    }

    /** A 2-block grab mounts faster than a 3–4 block grab. */
    private static int pullTicksForRise(double rise) {
        float t = (float) Mth.clamp((rise - 1.6) / 2.0, 0.0, 1.0);
        return Math.round(Mth.lerp(t, 24.0F, 44.0F));
    }

    private static boolean canPullUp(Level level, BlockPos solid) {
        return HangBlocks.open(level, solid.above()) && HangBlocks.open(level, solid.above(2));
    }

    private static BlockPos findHold(Player player) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double reach = ParkourTuning.GRAB_ARM_REACH;
        BlockPos best = null;
        double bestScore = -1.0;
        BlockPos origin = BlockPos.containing(player.getX(), player.getY() + 0.9, player.getZ());
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 2; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = origin.offset(dx, dy, dz);
                    if (!HangBlocks.canHold(level, pos)) {
                        continue;
                    }
                    double score = holdScore(eye, look, pos, reach);
                    if (score > bestScore) {
                        bestScore = score;
                        best = pos;
                    }
                }
            }
        }
        for (double t = 0.2; t <= reach; t += 0.15) {
            BlockPos pos = BlockPos.containing(eye.add(look.scale(t)));
            if (!HangBlocks.canHold(level, pos)) {
                continue;
            }
            double score = holdScore(eye, look, pos, reach) + 0.35;
            if (score > bestScore) {
                bestScore = score;
                best = pos;
            }
        }
        return best == null ? null : best.immutable();
    }

    private static double holdScore(Vec3 eye, Vec3 look, BlockPos pos, double reach) {
        Vec3 center = Vec3.atCenterOf(pos);
        double distance = eye.distanceTo(center);
        if (distance > reach) {
            return -1.0;
        }
        Vec3 toward = center.subtract(eye);
        double towardLength = toward.length();
        if (towardLength < 1.0E-4) {
            return reach - distance;
        }
        double facingAmount = toward.scale(1.0 / towardLength).dot(look);
        // Must roughly face the hold unless it's right next to the hands.
        if (facingAmount < 0.15 && distance > 1.0) {
            return -1.0;
        }
        return facingAmount * 1.5 + (reach - distance);
    }

    public static boolean shouldBlockUse(Player player) {
        ParkourPlayer data = ParkourPlayer.get(player);
        if (data.mode == ParkourMode.ZIPLINE) {
            return true;
        }
        if (MovesConfig.get().toolClimb && yblk.moves.item.ClimbGear.equipped(player)) {
            return true;
        }
        if (!player.getMainHandItem().isEmpty()) {
            return false;
        }
        if (!data.input.grab) {
            return false;
        }
        if (data.mode == ParkourMode.LEDGE || data.mode == ParkourMode.HANG
                || data.mode == ParkourMode.CLIMB || data.mode == ParkourMode.PULLUP) {
            return true;
        }
        return findCeiling(player) != null || findHold(player) != null
                || findLedge(player, data, player.onGround() ? ParkourTuning.GRAB_STANDING : data.grabReach()) != null;
    }

    private static void release(Player player, ParkourPlayer data) {
        if (data.mode == ParkourMode.NONE && !data.lockPosition) {
            return;
        }
        data.mode = ParkourMode.NONE;
        data.cornerTicks = 0;
        data.cornerLock = 0;
        data.missHoldTicks = 0;
        data.clearMotionLock();
        data.anim = "";
        if (player.level().isClientSide() && data.local) {
            MovesAnims.stop(player);
        }
        player.setDeltaMovement(player.getDeltaMovement().x, Math.min(player.getDeltaMovement().y, 0.0), player.getDeltaMovement().z);
        player.noPhysics = false;
        markDirty(data);
    }

    private static double lip(Level level, BlockPos pos) {
        return lip(level, pos, null, 0.0);
    }

    /**
     * Hands go on the edge in front of the player. A stair's high half is 1.0 and its
     * low half is 0.5, so a side step meets the neighboring ledge instead of floating.
     */
    private static double lip(Level level, BlockPos pos, Direction facing, double along) {
        var state = level.getBlockState(pos);
        if (facing != null && state.getBlock() instanceof net.minecraft.world.level.block.StairBlock) {
            return pos.getY() + stairEdge(state, facing, along);
        }
        var shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return pos.getY() + 1.0;
        }
        return pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
    }

    private static double stairEdge(net.minecraft.world.level.block.state.BlockState state, Direction facing, double along) {
        if (state.getValue(net.minecraft.world.level.block.StairBlock.HALF)
                == net.minecraft.world.level.block.state.properties.Half.TOP) {
            return 1.0;
        }
        Direction front = state.getValue(net.minecraft.world.level.block.StairBlock.FACING);
        Direction alongAxis = facing.getCounterClockWise();
        double clamped = Mth.clamp(along, -0.49, 0.49);
        double lx = 0.5 + facing.getStepX() * 0.49 + alongAxis.getStepX() * clamped;
        double lz = 0.5 + facing.getStepZ() * 0.49 + alongAxis.getStepZ() * clamped;
        double towardFront = (lx - 0.5) * front.getStepX() + (lz - 0.5) * front.getStepZ();
        return towardFront >= 0.0 ? 0.5 : 1.0;
    }

    private static Ledge findLedge(Player player, ParkourPlayer data, int maxBlocks) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        // Horizontal arm reach only — maxBlocks is vertical rise, not distance from the wall.
        double reach = ParkourTuning.GRAB_ARM_REACH + 0.25;
        double base = player.onGround() ? player.getY() : data.jumpStartY;
        Ledge partial = findPartialLedge(level, player, eye, look, base, maxBlocks);
        if (partial != null) {
            return partial;
        }
        // A bottom slab or stair step sits under the ray. Check that cell before a full block stops us.
        // Do not pick the nearest partial on the whole ray — that grabs the floor slab.
        BlockPos seen = null;
        for (double t = 0.2; t <= reach; t += 0.1) {
            Vec3 sample = eye.add(look.scale(t));
            BlockPos pos = BlockPos.containing(sample);
            if (pos.equals(seen)) {
                continue;
            }
            seen = pos;
            Ledge under = partialUnderRay(level, pos, sample.y, player, base, maxBlocks);
            if (under != null) {
                return under;
            }
            if (HangBlocks.isPartialLip(level.getBlockState(pos))) {
                Ledge onRay = ledgeAt(level, pos, player, base, maxBlocks);
                if (onRay != null) {
                    return onRay;
                }
            }
            var state = level.getBlockState(pos);
            if (state.isCollisionShapeFullBlock(level, pos)) {
                break;
            }
        }
        HitResult outline = level.clip(new ClipContext(
                eye, eye.add(look.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (outline instanceof BlockHitResult outlineHit && outlineHit.getType() == HitResult.Type.BLOCK
                && HangBlocks.isPartialLip(level.getBlockState(outlineHit.getBlockPos()))) {
            Ledge fromOutline = tryMakeLedge(level, outlineHit, player, base, maxBlocks);
            if (fromOutline != null) {
                return fromOutline;
            }
        }
        HitResult hit = level.clip(new ClipContext(
                eye, eye.add(look.scale(reach)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (!(hit instanceof BlockHitResult block) || block.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        Ledge looked = tryMakeLedge(level, block, player, base, maxBlocks);
        if (looked != null) {
            return looked;
        }
        BlockPos hitPos = block.getBlockPos();
        BlockPos front = hitPos.relative(block.getDirection());
        for (BlockPos off : new BlockPos[]{hitPos.below(), front, front.below(), hitPos.above()}) {
            if (!HangBlocks.isPartialLip(level.getBlockState(off))) {
                continue;
            }
            double top = lip(level, off);
            if (top < base + 0.2) {
                continue;
            }
            Ledge near = ledgeAt(level, off, player, base, maxBlocks);
            if (near != null) {
                return near;
            }
        }
        return null;
    }

    /** Slab, stair or trapdoor the crosshair passes through. Does not require a solid hit. */
    private static Ledge findPartialLedge(Level level, Player player, Vec3 eye, Vec3 look, double base, int maxBlocks) {
        double reach = ParkourTuning.GRAB_ARM_REACH + 0.25;
        HitResult outline = level.clip(new ClipContext(
                eye, eye.add(look.scale(reach)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        if (outline instanceof BlockHitResult outlineHit && outlineHit.getType() == HitResult.Type.BLOCK) {
            BlockPos hitPos = outlineHit.getBlockPos();
            Ledge aimed = partialFromPos(level, hitPos, player, eye, look, base, maxBlocks, reach);
            if (aimed != null) {
                return aimed;
            }
            BlockPos ahead = hitPos.relative(outlineHit.getDirection());
            Ledge inFront = partialFromPos(level, ahead, player, eye, look, base, maxBlocks, reach);
            if (inFront != null) {
                return inFront;
            }
            // Only drop to the block under for slabs/trapdoors — never for stair walls.
            boolean stairAim = level.getBlockState(hitPos).getBlock() instanceof net.minecraft.world.level.block.StairBlock
                    || level.getBlockState(ahead).getBlock() instanceof net.minecraft.world.level.block.StairBlock;
            if (!stairAim) {
                Ledge lipBelow = partialFromPos(level, ahead.below(), player, eye, look, base, maxBlocks, reach);
                if (lipBelow != null) {
                    return lipBelow;
                }
                Ledge underHit = partialFromPos(level, hitPos.below(), player, eye, look, base, maxBlocks, reach);
                if (underHit != null) {
                    return underHit;
                }
            }
        }
        Ledge found = null;
        double bestScore = 0.45;
        for (double t = 0.25; t <= reach; t += 0.15) {
            Vec3 sample = eye.add(look.scale(t));
            BlockPos cell = BlockPos.containing(sample);
            // Prefer the cell on the ray. Checking below(2) grabbed the lower stair and stuck you.
            for (BlockPos pos : new BlockPos[]{cell, cell.below()}) {
                Ledge partial = partialFromPos(level, pos, player, eye, look, base, maxBlocks, reach);
                if (partial == null) {
                    continue;
                }
                Vec3 lipAt = new Vec3(partial.pos().getX() + 0.5, partial.top(), partial.pos().getZ() + 0.5);
                Vec3 rel = lipAt.subtract(eye);
                double along = rel.dot(look);
                if (along < 0.1) {
                    continue;
                }
                double side = rel.subtract(look.scale(along)).lengthSqr();
                double score = look.dot(rel.normalize()) - side * 0.35;
                if (score > bestScore) {
                    bestScore = score;
                    found = partial;
                }
            }
        }
        return found;
    }

    private static Ledge partialFromPos(Level level, BlockPos pos, Player player, Vec3 eye, Vec3 look,
                                        double base, int maxBlocks, double reach) {
        var state = level.getBlockState(pos);
        if (!HangBlocks.isPartialLip(state) || !HangBlocks.sturdyLedge(level, pos)) {
            return null;
        }
        double top = lip(level, pos);
        double rise = top - base;
        if (rise < 0.2 || rise > maxBlocks + 1.25) {
            return null;
        }
        // Stair walls hang on the stair. Only slabs/trapdoors redirect to the block under.
        if (state.getBlock() instanceof net.minecraft.world.level.block.StairBlock) {
            Direction face = lipFace(level, pos, player, look);
            if (face == null || eye.distanceTo(Vec3.atCenterOf(pos)) > reach + 0.15) {
                return null;
            }
            return new Ledge(pos.immutable(), face, top);
        }
        BlockPos support = pos.below();
        var under = level.getBlockState(support);
        if (under.isAir() || HangBlocks.isPartialLip(under) || HangBlocks.canHold(level, support)
                || under.getCollisionShape(level, support).isEmpty()) {
            return null;
        }
        Direction face = lipFace(level, support, player, look);
        if (face == null) {
            return null;
        }
        if (eye.distanceTo(Vec3.atCenterOf(support)) > reach + 0.15) {
            return null;
        }
        return new Ledge(support.immutable(), face, lip(level, support));
    }

    /** Side of a slab or stair the player is actually standing on. */
    private static Direction lipFace(Level level, BlockPos pos, Player player, Vec3 look) {
        Direction toward = Direction.getNearest(
                pos.getX() + 0.5 - player.getX(), 0.0, pos.getZ() + 0.5 - player.getZ());
        if (toward.getAxis().isHorizontal() && faceOpen(level, pos, toward)) {
            return toward;
        }
        Direction looking = Direction.getNearest(look.x, 0.0, look.z);
        if (looking.getAxis().isHorizontal() && faceOpen(level, pos, looking)) {
            return looking;
        }
        Direction best = null;
        double bestDist = 64.0;
        for (Direction face : Direction.Plane.HORIZONTAL) {
            if (!faceOpen(level, pos, face)) {
                continue;
            }
            BlockPos hang = pos.relative(face.getOpposite());
            double dist = player.position().distanceToSqr(Vec3.atCenterOf(hang));
            if (dist < bestDist) {
                bestDist = dist;
                best = face;
            }
        }
        return best;
    }

    /** Partial lip in this cell or the one below, only when the ray is actually at the lip. */
    private static Ledge partialUnderRay(Level level, BlockPos cell, double sampleY, Player player, double base, int maxBlocks) {
        BlockPos below = cell.below();
        if (!HangBlocks.isPartialLip(level.getBlockState(below))) {
            return null;
        }
        double top = lip(level, below);
        if (sampleY > top + 0.45 || sampleY < top - 0.55) {
            return null;
        }
        return ledgeAt(level, below, player, base, maxBlocks);
    }

    private static Ledge ledgeAt(Level level, BlockPos solid, Player player, double base, int maxBlocks) {
        if (!HangBlocks.sturdyLedge(level, solid)) {
            return null;
        }
        if (player.getEyePosition().distanceTo(Vec3.atCenterOf(solid)) > ParkourTuning.GRAB_ARM_REACH + 0.35) {
            return null;
        }
        double top = lip(level, solid);
        double rise = top - base;
        boolean shortLip = HangBlocks.isPartialLip(level.getBlockState(solid))
                || !level.getBlockState(solid).isCollisionShapeFullBlock(level, solid);
        double minRise = shortLip ? 0.04 : 0.55;
        if (rise < minRise || rise > maxBlocks + 0.6) {
            return null;
        }
        Direction into = ledgeApproachFace(level, solid, player, null);
        if (into == null || !faceOpen(level, solid, into)) {
            return null;
        }
        return new Ledge(solid.immutable(), into, top);
    }

    /** Horizontal face to hang from. A slab/stair ray often hits the top, not the side. */
    private static Direction ledgeApproachFace(Level level, BlockPos solid, Player player, Direction hitFace) {
        if (hitFace != null && hitFace.getAxis().isHorizontal()) {
            Direction into = hitFace.getOpposite();
            if (faceOpen(level, solid, into)) {
                return into;
            }
        }
        Vec3 look = horizontalLook(player);
        Direction looking = Direction.getNearest(look.x, 0.0, look.z);
        if (faceOpen(level, solid, looking)) {
            return looking;
        }
        Direction nearest = Direction.getNearest(
                solid.getX() + 0.5 - player.getX(), 0.0, solid.getZ() + 0.5 - player.getZ());
        if (nearest.getAxis().isHorizontal() && faceOpen(level, solid, nearest)) {
            return nearest;
        }
        for (Direction face : Direction.Plane.HORIZONTAL) {
            if (faceOpen(level, solid, face)) {
                return face;
            }
        }
        return null;
    }

    private static Ledge tryMakeLedge(Level level, BlockHitResult hit, Player player, double base, int maxBlocks) {
        BlockPos solid = hit.getBlockPos();
        if (!HangBlocks.sturdyLedge(level, solid)) {
            return null;
        }
        if (player.getEyePosition().distanceTo(Vec3.atCenterOf(solid)) > ParkourTuning.GRAB_ARM_REACH + 0.35) {
            return null;
        }
        double top = lip(level, solid);
        double rise = top - base;
        var state = level.getBlockState(solid);
        boolean shortLip = HangBlocks.isPartialLip(state) || !state.isCollisionShapeFullBlock(level, solid);
        double minRise = shortLip ? 0.05 : 0.55;
        if (rise < minRise || rise > maxBlocks + 0.6) {
            return null;
        }
        Direction into = ledgeApproachFace(level, solid, player, hit.getDirection());
        if (into == null || !faceOpen(level, solid, into)) {
            return null;
        }
        return new Ledge(solid.immutable(), into, top);
    }

    private static Direction wallBeside(Player player) {
        Level level = player.level();
        Direction best = null;
        double bestDist = 0.7;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos pos = player.blockPosition().relative(direction);
            if (!level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)
                    && !level.getBlockState(pos.above()).isCollisionShapeFullBlock(level, pos.above())) {
                continue;
            }
            if (HangBlocks.canHold(level, pos)) {
                continue;
            }
            double dist = switch (direction) {
                case EAST -> pos.getX() - player.getX();
                case WEST -> player.getX() - (pos.getX() + 1);
                case SOUTH -> pos.getZ() - player.getZ();
                default -> player.getZ() - (pos.getZ() + 1);
            };
            if (dist >= 0 && dist < bestDist) {
                bestDist = dist;
                best = direction;
            }
        }
        return best;
    }

    private static Direction moveDirection(Player player, ParkourPlayer data) {
        if (Math.abs(data.input.forward) < 0.25F && Math.abs(data.input.strafe) < 0.25F) {
            return null;
        }
        Vec3 look = horizontalLook(player);
        Vec3 left = leftOf(look);
        Vec3 wish = look.scale(data.input.forward).add(left.scale(data.input.strafe));
        if (wish.lengthSqr() < 1.0E-4) {
            return null;
        }
        return Direction.getNearest(wish.x, 0.0, wish.z);
    }

    private static Vec3 horizontalLook(Player player) {
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        Vec3 look = new Vec3(-Mth.sin(yaw), 0.0, Mth.cos(yaw));
        double length = look.length();
        return length < 1.0E-4 ? new Vec3(0.0, 0.0, 1.0) : look.scale(1.0 / length);
    }

    /** Player-left relative to look (A key). */
    private static Vec3 leftOf(Vec3 look) {
        return new Vec3(look.z, 0.0, -look.x);
    }

    private static void trackGround(Player player, ParkourPlayer data) {
        if (player.onGround()) {
            data.jumpStartY = player.getY();
            data.airTicks = 0;
            if (data.mode == ParkourMode.NONE) {
                data.jumpKind = 0;
                data.wallRunUsed = false;
            }
        } else {
            data.airTicks++;
        }
    }

    private static void drainSprint(Player player, ParkourPlayer data) {
        if (!MovesConfig.get().stamina) {
            return;
        }
        if ((!player.isSprinting() && !data.input.sprint) || data.mode.scripted()) {
            return;
        }
        Vec3 motion = player.getDeltaMovement();
        if (motion.x * motion.x + motion.z * motion.z < 0.01) {
            return;
        }
        if (!spend(player, data, MovesConfig.get().sprintDrain)) {
            player.setSprinting(false);
        }
    }

    private static void regenerate(Player player, ParkourPlayer data) {
        if (!MovesConfig.get().stamina) {
            data.stamina = ParkourTuning.MAX_STAMINA;
            return;
        }
        if (data.regenDelay > 0) {
            data.regenDelay--;
            return;
        }
        if (data.stamina < ParkourTuning.MAX_STAMINA) {
            float amount = MovesConfig.get().regen;
            if (isMoving(player, data)) {
                amount *= 0.5F;
            }
            data.stamina = Math.min(ParkourTuning.MAX_STAMINA, data.stamina + amount);
        }
    }

    /** Walking, riding and climbing refill slower than standing still. */
    private static boolean isMoving(Player player, ParkourPlayer data) {
        Vec3 v = player.getDeltaMovement();
        if (v.x * v.x + v.z * v.z > 0.0002) {
            return true;
        }
        if (Math.abs(data.ziplineSpeed) > 0.001) {
            return true;
        }
        if (data.mode == ParkourMode.SLIDE || data.mode == ParkourMode.DODGE || data.mode == ParkourMode.WALL_RUN) {
            return true;
        }
        return (data.mode == ParkourMode.CLIMB || data.mode == ParkourMode.LEDGE
                || data.mode == ParkourMode.HANG || data.mode == ParkourMode.ZIPLINE)
                && (Math.abs(data.input.forward) > 0.08F || Math.abs(data.input.strafe) > 0.08F || data.toolStrike > 0);
    }

    /** Continuous cost. Skipped when the stamina bar is off or the player is in creative. */
    private static void drain(Player player, ParkourPlayer data, float cost) {
        if (!MovesConfig.get().stamina || player.getAbilities().instabuild || cost <= 0.0F) {
            return;
        }
        data.stamina = Math.max(0.0F, data.stamina - cost);
        data.regenDelay = MovesConfig.get().regenDelay;
    }

    private static boolean spend(Player player, ParkourPlayer data, float cost) {
        if (!MovesConfig.get().stamina || player.getAbilities().instabuild) {
            return true;
        }
        if (data.stamina < cost) {
            return false;
        }
        data.stamina -= cost;
        data.regenDelay = MovesConfig.get().regenDelay;
        return true;
    }

    private static void markDirty(ParkourPlayer data) {
        data.syncedMode = null;
    }

    private static void syncIfNeeded(Player player, ParkourPlayer data) {
        if (player.level().isClientSide() || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
            return;
        }
        boolean changed = data.syncedMode != data.mode
                || data.syncedProne != data.prone
                || Math.abs(data.syncedStamina - data.stamina) >= 0.5F;
        if (!changed) {
            return;
        }
        data.syncedMode = data.mode;
        data.syncedProne = data.prone;
        data.syncedStamina = data.stamina;
        yblk.moves.network.MovesNet.broadcastState(serverPlayer, data);
    }

    private static void playLocalAnim(Player player, ParkourPlayer data) {
        if (!player.level().isClientSide() || !data.local) {
            return;
        }
        // Smooth look-around offsets for grab poses (applied by AdjustmentModifier).
        ParkourLookPose.tick(player);
        String anim = animationFor(data);
        if (anim.isEmpty()) {
            MovesAnims.stop(player);
            data.anim = "";
            return;
        }
        if (anim.equals(data.anim)) {
            return;
        }
        if (MovesAnims.play(player, anim)) {
            data.anim = anim;
        }
    }

    public static String animationFor(ParkourPlayer data) {
        return switch (data.mode) {
            case LEDGE -> {
                if (data.cornerTicks > 0) {
                    yield "ledge_corner";
                }
                yield Math.abs(data.input.strafe) > 0.2F ? "ledge_move" : "ledge";
            }
            case PULLUP -> "pullup";
            case HANG -> Math.abs(data.input.forward) > 0.2F || Math.abs(data.input.strafe) > 0.2F ? "hang_move" : "hang";
            case CLIMB -> {
            if (data.toolClimb) {
                if (data.toolStrike > 0) {
                    if (data.toolStepX != 0) {
                        yield data.toolStepX > 0 ? "tool_swing_l" : "tool_swing_r";
                    }
                    yield data.toolArm == 0 ? "tool_rise_r" : "tool_rise_l";
                }
                yield "tool_idle";
            }
            if (data.climbWall) {
                    if (Math.abs(data.input.forward) > Math.abs(data.input.strafe) && Math.abs(data.input.forward) > 0.15F) {
                        yield "climb_move";
                    }
                    yield Math.abs(data.input.strafe) > 0.2F ? "ledge_move" : "ledge";
                }
                yield Math.abs(data.input.forward) > 0.15F || Math.abs(data.input.strafe) > 0.2F
                        || data.climbSlide > 0.0 ? "climb_move" : "climb";
            }
            case DODGE -> data.wallSide == 1 ? "dodge_right" : "dodge";
            case WALL_RUN -> data.wallSide >= 0 ? "wall_right" : "wall_left";
            case SLIDE -> "slide";
            case ZIPLINE -> switch (data.ziplineGrip) {
                case 1 -> "zipline_pick";
                case 2 -> "zipline_sword";
                default -> Math.abs(data.ziplineSpeed) > 0.008 || Math.abs(data.input.forward) > 0.15F ? "hang_move" : "hang";
            };
            case NONE -> {
                if (!data.prone) {
                    yield "";
                }
                boolean moving = Math.abs(data.input.forward) > 0.15F || Math.abs(data.input.strafe) > 0.15F;
                boolean still = Math.abs(data.input.forward) < 0.04F && Math.abs(data.input.strafe) < 0.04F;
                if (moving) {
                    data.crawlStepping = true;
                } else if (still) {
                    data.crawlStepping = false;
                }
                yield data.crawlStepping ? "prone_crawl" : "prone";
            }
        };
    }

    private static boolean tryBeginZipline(Player player, ParkourPlayer data) {
        if (!MovesConfig.get().zipline) {
            return false;
        }
        if (data.mode == ParkourMode.ZIPLINE) {
            return true;
        }
        yblk.moves.entity.ZiplineEntity line = yblk.moves.entity.ZiplineEntity.nearest(
                player.level(), player.getEyePosition(), 1.6);
        if (line == null) {
            return false;
        }
        data.mode = ParkourMode.ZIPLINE;
        data.ziplineId = line.getId();
        data.ziplineT = line.closestT(player.getEyePosition());
        data.ziplineSpeed = 0.0;
        data.actionTicks = 0;
        data.goalPos = line.point(data.ziplineT).add(0.0, -ParkourTuning.HANG_DROP, 0.0);
        data.motion = Vec3.ZERO;
        player.setJumping(false);
        markDirty(data);
        return true;
    }

    private static void tickZipline(Player player, ParkourPlayer data) {
        if (!MovesConfig.get().zipline) {
            release(player, data);
            return;
        }
        if (!data.input.grab) {
            Vec3 leave = Vec3.ZERO;
            if (player.level().getEntity(data.ziplineId) instanceof yblk.moves.entity.ZiplineEntity line) {
                leave = line.tangent(data.ziplineT).scale(data.ziplineSpeed);
            }
            release(player, data);
            player.setDeltaMovement(leave.x, leave.y, leave.z);
            return;
        }
        if (!(player.level().getEntity(data.ziplineId) instanceof yblk.moves.entity.ZiplineEntity line)) {
            release(player, data);
            return;
        }
        double slope = line.slopeAt(data.ziplineT);
        double span = Math.max(4.0, line.endA().distanceTo(line.endB()));
        Vec3 tangent = line.tangent(data.ziplineT);
        double lookAlong = player.getLookAngle().dot(tangent);
        double wish = 0.0;
        if (data.input.forward > 0.1F) {
            wish = Math.abs(lookAlong) < 0.2 ? 1.0 : Math.signum(lookAlong);
        } else if (data.input.forward < -0.1F) {
            wish = Math.abs(lookAlong) < 0.2 ? -1.0 : -Math.signum(lookAlong);
        }
        double chosenSlope = wish * slope;
        if (Math.abs(wish) < 0.1) {
            data.ziplineSpeed += -slope * 0.018;
        } else if (chosenSlope < -0.04) {
            data.ziplineSpeed += wish * (0.018 + Math.abs(slope) * 0.035);
        } else {
            data.ziplineSpeed += wish * Math.max(0.004, 0.014 - Math.max(0.0, chosenSlope) * 0.02);
        }
        data.ziplineSpeed *= Math.abs(wish) < 0.1 ? 0.994 : 0.992;
        double cap = Math.abs(wish) < 0.1
                ? 0.08 + Math.abs(slope) * 0.16
                : (chosenSlope < -0.04 ? 0.16 + Math.abs(slope) * 0.26 : 0.06);
        data.ziplineSpeed = Mth.clamp(data.ziplineSpeed, -cap, cap);
        double nextT = Mth.clamp(data.ziplineT + data.ziplineSpeed / span, 0.0, 1.0);
        if (nextT <= 0.001 || nextT >= 0.999) {
            data.ziplineT = nextT;
            double leave = Math.abs(data.ziplineSpeed) < 0.04
                    ? Math.copySign(0.04, data.ziplineSpeed == 0.0 ? 1.0 : data.ziplineSpeed)
                    : data.ziplineSpeed;
            Vec3 kick = line.tangent(data.ziplineT).scale(leave);
            release(player, data);
            player.setDeltaMovement(kick.x, kick.y, kick.z);
            player.setOnGround(false);
            return;
        }
        Vec3 hang = line.point(nextT).add(0.0, -ParkourTuning.HANG_DROP, 0.0);
        if (!player.level().noCollision(player, bodyBox(player.position()))
                || !player.level().noCollision(player, bodyBox(hang))) {
            data.grabIgnoreTicks = 12;
            double kick = -Math.signum(Math.abs(data.ziplineSpeed) < 1.0E-4 ? -slope : data.ziplineSpeed) * 0.1;
            Vec3 push = tangent.scale(kick);
            release(player, data);
            player.setDeltaMovement(push.x, 0.04, push.z);
            return;
        }
        data.ziplineT = nextT;
        ItemStack hand = player.getMainHandItem();
        if (hand.is(net.minecraft.tags.ItemTags.PICKAXES)) {
            data.ziplineGrip = 1;
        } else if (hand.getItem() instanceof net.minecraft.world.item.SwordItem) {
            data.ziplineGrip = 2;
        } else {
            data.ziplineGrip = 0;
        }
        float rideCost = data.ziplineGrip == 0 ? MovesConfig.get().ziplineDrain : MovesConfig.get().ziplineToolDrain;
        drain(player, data, rideCost);
        if (data.stamina <= 0.0F && !player.getAbilities().instabuild) {
            Vec3 leave = tangent.scale(data.ziplineSpeed);
            release(player, data);
            player.setDeltaMovement(leave.x, leave.y, leave.z);
            return;
        }
        data.goalPos = hang;
        data.motion = Vec3.ZERO;
        data.actionTicks++;
        player.setJumping(false);
        player.fallDistance = 0.0F;
    }

    private static void tryToolWall(Player player, ParkourPlayer data) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        BlockHitResult hit = level.clip(new ClipContext(
                eye, eye.add(look.scale(4.2)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        Direction face = hit.getDirection();
        BlockPos found = hit.getBlockPos();
        if (!face.getAxis().isHorizontal() || !toolSolid(level, found)) {
            return;
        }
        double gap = toolGap(player, found, face, player.getY());
        if (gap < 0.0) {
            return;
        }
        data.mode = ParkourMode.CLIMB;
        data.toolClimb = true;
        data.climbWall = false;
        data.anchor = found.immutable();
        data.facing = face;
        data.climbYaw = face.getOpposite().toYRot();
        data.actionTicks = 0;
        data.climbSlide = 0.0;
        data.climbAlong = 0.0;
        data.toolStrike = 0;
        data.toolWait = 4;
        data.climbJumpWasDown = data.input.jump;
        data.goalPos = toolCling(found, face, player.getY(), 0.0, gap);
        data.motion = Vec3.ZERO;
        player.setJumping(false);
        markDirty(data);
    }

    private static void tickToolWall(Player player, ParkourPlayer data) {
        if (!MovesConfig.get().toolClimb || !data.input.grab || !yblk.moves.item.ClimbGear.equipped(player)) {
            release(player, data);
            return;
        }
        Direction face = data.facing;
        if (face == null || !face.getAxis().isHorizontal() || data.anchor == null) {
            release(player, data);
            return;
        }
        Level level = player.level();
        if (!data.input.jump) {
            data.climbJumpWasDown = false;
        } else if (!data.climbJumpWasDown) {
            data.climbJumpWasDown = true;
            if (!toolSolid(level, data.anchor.above()) && HangBlocks.open(level, data.anchor.above())) {
                beginToolPullup(player, data);
                return;
            }
            data.grabIgnoreTicks = 14;
            Vec3 away = new Vec3(face.getStepX() * 0.5, 0.24, face.getStepZ() * 0.5);
            release(player, data);
            player.setDeltaMovement(away);
            player.setOnGround(false);
            return;
        }
        float into = face.getOpposite().toYRot();
        data.climbYaw = stepYaw(Float.isNaN(data.climbYaw) ? into : data.climbYaw, into, 12.0F);
        if (!toolSolid(level, data.anchor)) {
            release(player, data);
            return;
        }
        double feetY = data.goalPos != null ? data.goalPos.y : player.getY();
        double rest = toolGap(player, data.anchor, face, feetY);
        if (rest < 0.0) {
            rest = 1.02;
        }
        if (data.toolStrike > 0) {
            data.toolStrike--;
            if (data.toolStrike == 8) {
                commitToolStep(player, data, level, face, feetY);
            }
            double heldY = data.goalPos != null ? data.goalPos.y : feetY;
            data.goalPos = toolCling(data.anchor, face, heldY, data.climbAlong, rest);
            data.motion = Vec3.ZERO;
            player.setJumping(false);
            player.fallDistance = 0.0F;
            return;
        }
        Direction side = face.getClockWise();
        // Strafe is already player-left/right. Do not remap through the camera or a
        // slight look offset keeps inventing a step to one side.
        int stepX = 0;
        if (Math.abs(data.input.strafe) > 0.45F) {
            stepX = data.input.strafe > 0.0F ? 1 : -1;
        }
        int stepY = 0;
        if (data.input.forward > 0.45F) {
            stepY = 1;
        } else if (data.input.sneak || data.input.forward < -0.45F) {
            stepY = -1;
        }
        if (stepX != 0 && stepY != 0) {
            if (Math.abs(data.input.forward) >= Math.abs(data.input.strafe)) {
                stepX = 0;
            } else {
                stepY = 0;
            }
        }
        data.goalPos = toolCling(data.anchor, face, feetY, data.climbAlong, rest);
        data.motion = Vec3.ZERO;
        player.setJumping(false);
        player.fallDistance = 0.0F;
        if (stepY > 0 && !toolSolid(level, data.anchor.above()) && HangBlocks.open(level, data.anchor.above())) {
            beginToolPullup(player, data);
            return;
        }
        if (stepX == 0 && stepY == 0) {
            return;
        }
        ToolHop hop = planToolHop(level, player, data, face, side, feetY, stepX, stepY);
        if (hop == null) {
            return;
        }
        data.cornerAnchor = hop.pos;
        data.cornerFacing = hop.face;
        data.toolStepX = stepX;
        data.toolStepY = stepY;
        data.toolArm = 1 - data.toolArm;
        data.toolStrike = 16;
        data.anim = "";
    }

    private static void beginToolPullup(Player player, ParkourPlayer data) {
        if (!spend(player, data, MovesConfig.get().pullUpCost)) {
            return;
        }
        // Tool climb stores the hit face (outward). Pull-up / body lock use into-wall facing —
        // without the flip the pullup clip yaws the body 180°.
        if (data.facing != null && data.facing.getAxis().isHorizontal()) {
            data.facing = data.facing.getOpposite();
        }
        if (data.facing != null) {
            data.climbYaw = data.facing.toYRot();
        }
        data.anchorTop = data.anchor.getY() + 1.0;
        data.pullDuration = pullTicksForLook(player);
        data.pullLocked = true;
        data.mode = ParkourMode.PULLUP;
        data.actionTicks = 0;
        data.pullStart = player.position();
        data.toolClimb = false;
        data.toolStrike = 0;
        markDirty(data);
    }

    private static final class ToolHop {
        final BlockPos pos;
        final Direction face;

        ToolHop(BlockPos pos, Direction face) {
            this.pos = pos;
            this.face = face;
        }
    }

    /** Straight along the wall, then an inner corner, then an outer wrap. Never into the ground. */
    private static ToolHop planToolHop(Level level, Player player, ParkourPlayer data, Direction face,
                                        Direction side, double feetY, int stepX, int stepY) {
        if (stepY != 0) {
            BlockPos next = stepY > 0 ? data.anchor.above() : data.anchor.below();
            double nextY = feetY + stepY;
            if (!toolSolid(level, next) || !toolStandOk(player, next, face, nextY)) {
                return null;
            }
            if (stepY < 0) {
                Vec3 dest = toolCling(next, face, nextY, 0.0, 1.0);
                BlockPos under = BlockPos.containing(dest.x, dest.y - 0.02, dest.z);
                if (!player.level().getBlockState(under).getCollisionShape(player.level(), under).isEmpty()) {
                    return null;
                }
            }
            return new ToolHop(next, face);
        }
        Direction move = stepX > 0 ? side : side.getOpposite();
        BlockPos straight = data.anchor.relative(move);
        if (toolSolid(level, straight)) {
            if (toolStandOk(player, straight, face, feetY)) {
                return new ToolHop(straight, face);
            }
            // The straight block is the wall itself. The inner corner is the face that turns toward the player.
            BlockPos turned = straight.relative(face);
            if (toolSolid(level, turned) && toolStandOk(player, turned, move.getOpposite(), feetY)) {
                return new ToolHop(turned, move.getOpposite());
            }
            return null;
        }
        BlockPos inner = straight.relative(face);
        if (toolSolid(level, inner) && toolStandOk(player, inner, move.getOpposite(), feetY)) {
            return new ToolHop(inner, move.getOpposite());
        }
        if (toolStandOk(player, data.anchor, move, feetY)) {
            return new ToolHop(data.anchor, move);
        }
        return null;
    }

    private static boolean toolStandOk(Player player, BlockPos pos, Direction face, double feetY) {
        return toolFit(player, pos, face, feetY) != null;
    }

    /** Gap and along-face shift that keep the body out of the corner. */
    private static double[] toolFit(Player player, BlockPos pos, Direction face, double feetY) {
        double[] alongs = {0.0, 0.2, -0.2, 0.36, -0.36};
        for (double along : alongs) {
            for (double gap = 0.90; gap <= 1.36; gap += 0.04) {
                if (bodyFits(player, toolCling(pos, face, feetY, along, gap))) {
                    return new double[]{gap, along};
                }
            }
        }
        return null;
    }

    /** The swing's hit frame. One arm, one block, then the clip finishes. */
    private static void commitToolStep(Player player, ParkourPlayer data, Level level, Direction face, double feetY) {
        BlockPos next = data.cornerAnchor;
        Direction nextFace = data.cornerFacing;
        int stepX = data.toolStepX;
        int stepY = data.toolStepY;
        boolean leftHand = stepX != 0 ? stepX > 0 : data.toolArm != 0;
        data.toolStepX = 0;
        data.toolStepY = 0;
        data.cornerAnchor = null;
        data.cornerFacing = null;
        double nextY = feetY + stepY;
        double[] fit = next == null || nextFace == null ? null : toolFit(player, next, nextFace, nextY);
        if (fit == null) {
            return;
        }
        data.anchor = next.immutable();
        data.facing = nextFace;
        data.climbAlong = fit[1];
        data.goalPos = toolCling(next, nextFace, nextY, fit[1], fit[0]);
        drain(player, data, MovesConfig.get().toolClimbCost);
        strikeWall(player, next, nextFace, nextY, leftHand);
    }

    /** Crack on the client (the breaker never receives the server packet), sound and durability on the server. */
    private static void strikeWall(Player player, BlockPos pos, Direction face, double feetY, boolean leftHand) {
        Level level = player.level();
        var state = level.getBlockState(pos);
        Vec3 at = Vec3.atCenterOf(pos).add(face.getStepX() * 0.52, 0.0, face.getStepZ() * 0.52);
        if (level.isClientSide()) {
            yblk.moves.client.PickCracks.add(level, pos, face, feetY, leftHand);
            return;
        }
        level.playSound(null, at.x, at.y, at.z, state.getSoundType().getHitSound(),
                net.minecraft.sounds.SoundSource.BLOCKS, 0.85F, 0.75F);
        if (level instanceof net.minecraft.server.level.ServerLevel server) {
            server.sendParticles(new net.minecraft.core.particles.BlockParticleOption(
                    net.minecraft.core.particles.ParticleTypes.BLOCK, state),
                    at.x, at.y, at.z, 12, 0.12, 0.12, 0.12, 0.04);
        }
        wearTool(player, net.minecraft.world.InteractionHand.MAIN_HAND);
        wearTool(player, net.minecraft.world.InteractionHand.OFF_HAND);
    }

    private static void wearTool(Player player, net.minecraft.world.InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!stack.isDamageableItem() || !(player instanceof net.minecraft.server.level.ServerPlayer server)) {
            return;
        }
        if (stack.hurt(1, player.getRandom(), server)) {
            stack.shrink(1);
            stack.setDamageValue(0);
            player.broadcastBreakEvent(hand);
        }
    }

    private static boolean toolSolid(Level level, BlockPos pos) {
        return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }

    /** Smallest stand-off that keeps the body out of the wall. Negative when nothing fits. */
    private static double toolGap(Player player, BlockPos pos, Direction face, double feetY) {
        for (double gap = 0.90; gap <= 1.28; gap += 0.04) {
            if (bodyFits(player, toolCling(pos, face, feetY, 0.0, gap))) {
                return gap;
            }
        }
        return -1.0;
    }

    /** {@code face} is the block face normal, pointing out toward the player. */
    private static Vec3 toolCling(BlockPos pos, Direction face, double feetY, double along, double gap) {
        Direction side = face.getClockWise();
        return new Vec3(
                pos.getX() + 0.5 + face.getStepX() * gap + side.getStepX() * along,
                feetY,
                pos.getZ() + 0.5 + face.getStepZ() * gap + side.getStepZ() * along
        );
    }

    private record Ledge(BlockPos pos, Direction facing, double top) {
    }
}
