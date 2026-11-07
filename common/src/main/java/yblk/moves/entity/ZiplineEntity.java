package yblk.moves.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import yblk.moves.MovesContent;

/**
 * A rope or chain between two points. {@code sag} is how far the middle hangs below the straight line.
 * Zero sag is a taut line the player walks like a ceiling hold.
 */
public class ZiplineEntity extends Entity {

    private static final EntityDataAccessor<Float> AX = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> AY = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> AZ = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BX = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BY = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BZ = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> SAG = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> CHAIN = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<CompoundTag> LANTERNS = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.COMPOUND_TAG);

    public ZiplineEntity(EntityType<? extends ZiplineEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public ZiplineEntity(Level level, Vec3 a, Vec3 b, boolean chain, float sag) {
        this(MovesContent.ZIPLINE, level);
        setEnds(a, b, sag, chain);
        Vec3 mid = point(0.5);
        setPos(mid.x, mid.y, mid.z);
        refreshBox();
    }

    public void setEnds(Vec3 a, Vec3 b, float sag, boolean chain) {
        this.entityData.set(AX, (float) a.x);
        this.entityData.set(AY, (float) a.y);
        this.entityData.set(AZ, (float) a.z);
        this.entityData.set(BX, (float) b.x);
        this.entityData.set(BY, (float) b.y);
        this.entityData.set(BZ, (float) b.z);
        this.entityData.set(SAG, sag);
        this.entityData.set(CHAIN, chain);
    }

    public Vec3 endA() {
        return new Vec3(this.entityData.get(AX), this.entityData.get(AY), this.entityData.get(AZ));
    }

    public Vec3 endB() {
        return new Vec3(this.entityData.get(BX), this.entityData.get(BY), this.entityData.get(BZ));
    }

    public float sag() {
        return this.entityData.get(SAG);
    }

    public boolean chain() {
        return this.entityData.get(CHAIN);
    }

    /** Point on the hanging line. {@code t} is 0 at A and 1 at B. */
    public Vec3 point(double t) {
        t = clamp01(t);
        Vec3 a = endA();
        Vec3 b = endB();
        double hang = sag() * 4.0 * t * (1.0 - t);
        return new Vec3(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t - hang,
                a.z + (b.z - a.z) * t
        );
    }

    public Vec3 tangent(double t) {
        Vec3 ahead = point(t + 0.02);
        Vec3 behind = point(t - 0.02);
        Vec3 delta = ahead.subtract(behind);
        if (delta.lengthSqr() < 1.0E-6) {
            return new Vec3(1.0, 0.0, 0.0);
        }
        return delta.normalize();
    }

    public double closestT(Vec3 pos) {
        double best = 0.0;
        double bestDist = Double.MAX_VALUE;
        for (int i = 0; i <= 24; i++) {
            double t = i / 24.0;
            double dist = point(t).distanceToSqr(pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = t;
            }
        }
        return best;
    }

    @Override
    protected void defineSynchedData() {
        this.entityData.define(AX, 0.0F);
        this.entityData.define(AY, 0.0F);
        this.entityData.define(AZ, 0.0F);
        this.entityData.define(BX, 0.0F);
        this.entityData.define(BY, 0.0F);
        this.entityData.define(BZ, 0.0F);
        this.entityData.define(SAG, 0.0F);
        this.entityData.define(CHAIN, false);
        this.entityData.define(LANTERNS, new CompoundTag());
    }

    @Override
    public void tick() {
        super.tick();
        refreshBox();
        if (!this.level().isClientSide() && this.tickCount % 20 == 0) {
            ensureLights();
        }
    }

    /** A small box on the part of the cable the crosshair is nearest. Not the whole span. */
    private void refreshBox() {
        Vec3 focus = point(0.5);
        if (this.level() != null) {
            AABB area = new AABB(endA(), endB()).inflate(6.0);
            double best = 0.85 * 0.85;
            for (Player player : this.level().getEntitiesOfClass(Player.class, area)) {
                double t = lookT(player, 8.0, 0.85);
                if (t < 0.0) {
                    continue;
                }
                Vec3 at = point(t);
                Vec3 rel = at.subtract(player.getEyePosition());
                Vec3 look = player.getLookAngle();
                double along = rel.dot(look);
                double side = along < 0.0 ? rel.lengthSqr() : rel.subtract(look.scale(along)).lengthSqr();
                if (side < best) {
                    best = side;
                    focus = at;
                }
            }
        }
        double r = 0.22;
        this.setBoundingBox(new AABB(focus.x - r, focus.y - r, focus.z - r, focus.x + r, focus.y + r, focus.z + r));
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        Vec3 a = endA();
        Vec3 b = endB();
        Vec3 mid = point(0.5);
        return new AABB(
                Math.min(a.x, Math.min(b.x, mid.x)) - 0.8,
                Math.min(a.y, Math.min(b.y, mid.y)) - 0.8,
                Math.min(a.z, Math.min(b.z, mid.z)) - 0.8,
                Math.max(a.x, Math.max(b.x, mid.x)) + 0.8,
                Math.max(a.y, Math.max(b.y, mid.y)) + 0.8,
                Math.max(a.z, Math.max(b.z, mid.z)) + 0.8);
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.getEntity() instanceof Player player && player.isShiftKeyDown()) {
            breakLine();
            return true;
        }
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setEnds(
                new Vec3(tag.getDouble("ax"), tag.getDouble("ay"), tag.getDouble("az")),
                new Vec3(tag.getDouble("bx"), tag.getDouble("by"), tag.getDouble("bz")),
                tag.getFloat("sag"),
                tag.getBoolean("chain")
        );
        if (tag.contains("lanterns")) {
            this.entityData.set(LANTERNS, tag.getCompound("lanterns").copy());
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        Vec3 a = endA();
        Vec3 b = endB();
        tag.putDouble("ax", a.x);
        tag.putDouble("ay", a.y);
        tag.putDouble("az", a.z);
        tag.putDouble("bx", b.x);
        tag.putDouble("by", b.y);
        tag.putDouble("bz", b.z);
        tag.putFloat("sag", sag());
        tag.putBoolean("chain", chain());
        tag.put("lanterns", lanternsTag().copy());
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 96.0 * 96.0;
    }

    /** Signed slope along the line. Negative means moving toward B goes downhill. */
    public double slopeAt(double t) {
        t = clamp01(t);
        Vec3 a = endA();
        Vec3 b = endB();
        double dy = (b.y - a.y) - sag() * 4.0 * (1.0 - 2.0 * t);
        double dx = b.x - a.x;
        double dz = b.z - a.z;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-4) {
            return 0.0;
        }
        return dy / len;
    }

    public void breakLine() {
        if (this.level().isClientSide()) {
            return;
        }
        clearLights();
        ItemStack drop = new ItemStack(chain() ? MovesContent.ZIPLINE_CHAIN : MovesContent.ZIPLINE_ROPE);
        if (!drop.isEmpty()) {
            spawnAtLocation(drop);
        }
        ListTag hanging = lanterns();
        for (int i = 0; i < hanging.size(); i++) {
            spawnAtLocation(new ItemStack(hanging.getCompound(i).getBoolean("s") ? Items.SOUL_LANTERN : Items.LANTERN));
        }
        discard();
    }

    public static void breakLookedAt(Player player) {
        if (!player.swinging || !player.isShiftKeyDown()) {
            return;
        }
        ZiplineEntity line = nearest(player.level(), player.getEyePosition(), 6.0);
        if (line != null && line.aimed(player, 5.0, 0.45)) {
            line.breakLine();
        }
    }

    /** True when the look ray passes near the cable, not only through the big bounding box. */
    public boolean aimed(Player player, double reach, double radius) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        for (int i = 0; i <= 48; i++) {
            Vec3 point = point(i / 48.0);
            Vec3 rel = point.subtract(eye);
            double along = rel.dot(look);
            if (along < 0.15 || along > reach) {
                continue;
            }
            if (rel.subtract(look.scale(along)).lengthSqr() <= radius * radius) {
                return true;
            }
        }
        return false;
    }

    public ListTag lanterns() {
        return lanternsTag().getList("L", 10);
    }

    private CompoundTag lanternsTag() {
        CompoundTag tag = this.entityData.get(LANTERNS);
        return tag == null ? new CompoundTag() : tag;
    }

    /** {@code t} on the cable nearest the crosshair, or -1 when the look misses the line. */
    public double lookT(Player player, double reach, double radius) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double bestT = -1.0;
        double best = radius * radius;
        for (int i = 0; i <= 64; i++) {
            double t = i / 64.0;
            Vec3 at = point(t);
            Vec3 rel = at.subtract(eye);
            double along = rel.dot(look);
            if (along < 0.15 || along > reach) {
                continue;
            }
            double side = rel.subtract(look.scale(along)).lengthSqr();
            if (side < best) {
                best = side;
                bestT = t;
            }
        }
        return bestT;
    }

    public void addLantern(double t, boolean soul) {
        t = clamp01(t);
        CompoundTag root = lanternsTag().copy();
        ListTag list = root.getList("L", 10);
        CompoundTag one = new CompoundTag();
        one.putFloat("t", (float) t);
        one.putBoolean("s", soul);
        if (!this.level().isClientSide()) {
            hangLight(one, t);
        }
        list.add(one);
        root.put("L", list);
        this.entityData.set(LANTERNS, root);
    }

    @Override
    public InteractionResult interactAt(Player player, Vec3 vec, InteractionHand hand) {
        return handleUse(player, hand, closestT(this.position().add(vec)));
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        double t = lookT(player, 6.0, 0.75);
        if (t < 0.0) {
            t = closestT(player.getEyePosition());
        }
        return handleUse(player, hand, t);
    }

    private int useTick = -100;

    private InteractionResult handleUse(Player player, InteractionHand hand, double t) {
        if (this.tickCount == this.useTick) {
            return InteractionResult.CONSUME;
        }
        ItemStack stack = player.getItemInHand(hand);
        boolean soul = stack.is(Items.SOUL_LANTERN);
        boolean lantern = soul || stack.is(Items.LANTERN);
        if (!lantern && !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (this.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player.isShiftKeyDown() && takeLantern(t)) {
            this.useTick = this.tickCount;
            return InteractionResult.CONSUME;
        }
        if (!lantern || lanterns().size() >= 16) {
            return InteractionResult.PASS;
        }
        addLantern(t, soul);
        this.useTick = this.tickCount;
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    private boolean takeLantern(double t) {
        ListTag list = lanterns();
        int best = -1;
        double bestDist = 0.08;
        for (int i = 0; i < list.size(); i++) {
            double dist = Math.abs(list.getCompound(i).getFloat("t") - t);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        if (best < 0) {
            return false;
        }
        CompoundTag one = list.getCompound(best);
        clearLight(one);
        spawnAtLocation(new ItemStack(one.getBoolean("s") ? Items.SOUL_LANTERN : Items.LANTERN));
        CompoundTag root = lanternsTag().copy();
        ListTag copy = root.getList("L", 10);
        copy.remove(best);
        root.put("L", copy);
        this.entityData.set(LANTERNS, root);
        return true;
    }

    private void hangLight(CompoundTag one, double t) {
        Vec3 body = point(t).add(0.0, -0.7, 0.0);
        BlockPos pos = BlockPos.containing(body);
        if (!this.level().getBlockState(pos).isAir()) {
            pos = BlockPos.containing(point(t));
        }
        if (!this.level().getBlockState(pos).isAir()) {
            return;
        }
        this.level().setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), 3);
        one.putInt("lx", pos.getX());
        one.putInt("ly", pos.getY());
        one.putInt("lz", pos.getZ());
    }

    private void clearLight(CompoundTag one) {
        if (!one.contains("ly") || this.level().isClientSide()) {
            return;
        }
        BlockPos pos = new BlockPos(one.getInt("lx"), one.getInt("ly"), one.getInt("lz"));
        if (this.level().getBlockState(pos).is(Blocks.LIGHT)) {
            this.level().setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private void clearLights() {
        ListTag hanging = lanterns();
        for (int i = 0; i < hanging.size(); i++) {
            clearLight(hanging.getCompound(i));
        }
    }

    private void ensureLights() {
        CompoundTag root = lanternsTag().copy();
        ListTag copy = root.getList("L", 10);
        boolean changed = false;
        for (int i = 0; i < copy.size(); i++) {
            CompoundTag one = copy.getCompound(i);
            if (!one.contains("ly")) {
                hangLight(one, one.getFloat("t"));
                changed = true;
                continue;
            }
            BlockPos pos = new BlockPos(one.getInt("lx"), one.getInt("ly"), one.getInt("lz"));
            if (this.level().isLoaded(pos) && this.level().getBlockState(pos).isAir()) {
                this.level().setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), 3);
            }
        }
        if (changed) {
            root.put("L", copy);
            this.entityData.set(LANTERNS, root);
        }
    }

    public static ZiplineEntity nearest(Level level, Vec3 pos, double reach) {
        if (MovesContent.ZIPLINE == null) {
            return null;
        }
        ZiplineEntity best = null;
        double bestDist = reach * reach;
        BlockPos around = BlockPos.containing(pos);
        for (ZiplineEntity line : level.getEntitiesOfClass(ZiplineEntity.class, new AABB(around).inflate(reach + 8.0))) {
            double dist = line.point(line.closestT(pos)).distanceToSqr(pos);
            if (dist < bestDist) {
                bestDist = dist;
                best = line;
            }
        }
        return best;
    }

    private static double clamp01(double t) {
        if (t < 0.0) {
            return 0.0;
        }
        if (t > 1.0) {
            return 1.0;
        }
        return t;
    }
}
