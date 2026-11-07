package yblk.moves.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.HangingSignItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CeilingHangingSignBlock;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import yblk.moves.MovesContent;
import yblk.moves.item.ZiplineItem;
import yblk.moves.network.MovesNet;

/**
 * A rope or chain between two points. {@code sag} is how far the middle hangs below the straight line.
 * Zero sag is a taut line the player walks like a ceiling hold.
 * Hangings: lanterns, banners (patterns kept), hanging signs (editable text).
 */
public class ZiplineEntity extends Entity {

    /** Client thread: true while holding a zipline item so clicks pass through to fences. */
    public static final ThreadLocal<Boolean> SUPPRESS_PICK = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private static final EntityDataAccessor<Float> AX = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> AY = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> AZ = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BX = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BY = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> BZ = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> SAG = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Boolean> CHAIN = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Boolean> DARK = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<CompoundTag> LANTERNS = SynchedEntityData.defineId(ZiplineEntity.class, EntityDataSerializers.COMPOUND_TAG);

    public ZiplineEntity(EntityType<? extends ZiplineEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public ZiplineEntity(Level level, Vec3 a, Vec3 b, boolean chain, float sag) {
        this(level, a, b, chain ? ZiplineItem.STYLE_CHAIN : ZiplineItem.STYLE_ROPE, sag);
    }

    public ZiplineEntity(Level level, Vec3 a, Vec3 b, int style, float sag) {
        this(MovesContent.ZIPLINE, level);
        setEnds(a, b, sag, style);
        Vec3 mid = point(0.5);
        setPos(mid.x, mid.y, mid.z);
        refreshBox();
    }

    public void setEnds(Vec3 a, Vec3 b, float sag, boolean chain) {
        setEnds(a, b, sag, chain ? ZiplineItem.STYLE_CHAIN : ZiplineItem.STYLE_ROPE);
    }

    public void setEnds(Vec3 a, Vec3 b, float sag, int style) {
        this.entityData.set(AX, (float) a.x);
        this.entityData.set(AY, (float) a.y);
        this.entityData.set(AZ, (float) a.z);
        this.entityData.set(BX, (float) b.x);
        this.entityData.set(BY, (float) b.y);
        this.entityData.set(BZ, (float) b.z);
        this.entityData.set(SAG, sag);
        this.entityData.set(CHAIN, style == ZiplineItem.STYLE_CHAIN);
        this.entityData.set(DARK, style == ZiplineItem.STYLE_DARK_ROPE);
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

    public boolean darkRope() {
        return !chain() && this.entityData.get(DARK);
    }

    public int style() {
        if (chain()) {
            return ZiplineItem.STYLE_CHAIN;
        }
        return darkRope() ? ZiplineItem.STYLE_DARK_ROPE : ZiplineItem.STYLE_ROPE;
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
        this.entityData.define(DARK, false);
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

    private void refreshBox() {
        Vec3 focus = point(0.5);
        double down = 0.35;
        double up = 0.35;
        double r = 0.35;
        ListTag list = hangings();
        for (int i = 0; i < list.size(); i++) {
            switch (hangingKind(list.getCompound(i))) {
                case "banner" -> {
                    down = Math.max(down, 2.7);
                    r = Math.max(r, 0.65);
                }
                case "sign" -> {
                    down = Math.max(down, 1.55);
                    r = Math.max(r, 0.55);
                }
                case "lantern", "soul" -> {
                    down = Math.max(down, 1.15);
                    r = Math.max(r, 0.45);
                }
                default -> {
                }
            }
        }
        if (this.level() != null) {
            AABB area = new AABB(endA(), endB()).inflate(6.0);
            double best = 1.1 * 1.1;
            for (Player player : this.level().getEntitiesOfClass(Player.class, area)) {
                double t = lookT(player, 8.0, 1.1);
                if (t < 0.0) {
                    continue;
                }
                Vec3 at = aimPointForLook(player, t);
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
        this.setBoundingBox(new AABB(focus.x - r, focus.y - down, focus.z - r, focus.x + r, focus.y + up, focus.z + r));
    }

    /** Prefer a hanging's body under the cable when the player is aiming at it. */
    private Vec3 aimPointForLook(Player player, double t) {
        Vec3 cable = point(t);
        ListTag list = hangings();
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double best = 0.7 * 0.7;
        Vec3 chosen = cable;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag one = list.getCompound(i);
            if (Math.abs(one.getFloat("t") - t) > 0.14) {
                continue;
            }
            Vec3 at = hangingAimPoint(one);
            Vec3 rel = at.subtract(eye);
            double along = rel.dot(look);
            if (along < 0.1) {
                continue;
            }
            double side = rel.subtract(look.scale(along)).lengthSqr();
            if (side < best) {
                best = side;
                chosen = at;
            }
        }
        return chosen;
    }

    private Vec3 hangingAimPoint(CompoundTag one) {
        Vec3 cable = point(one.getFloat("t"));
        return switch (hangingKind(one)) {
            case "banner" -> cable.add(0.0, -1.25, 0.0);
            case "sign" -> cable.add(0.0, -0.75, 0.0);
            case "lantern", "soul" -> cable.add(0.0, -0.5, 0.0);
            default -> cable;
        };
    }

    @Override
    public AABB getBoundingBoxForCulling() {
        Vec3 a = endA();
        Vec3 b = endB();
        Vec3 mid = point(0.5);
        double under = hangings().isEmpty() ? 1.4 : 2.9;
        return new AABB(
                Math.min(a.x, Math.min(b.x, mid.x)) - 0.8,
                Math.min(a.y, Math.min(b.y, mid.y)) - under,
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
        return !Boolean.TRUE.equals(SUPPRESS_PICK.get());
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (!(source.getEntity() instanceof Player player)) {
            return false;
        }
        double t = lookT(player, 6.0, 0.85);
        if (t < 0.0) {
            t = closestT(player.getEyePosition());
        }
        // LMB knocks off lanterns / banners / signs aimed at on the cable.
        if (takeHanging(t)) {
            return true;
        }
        if (player.isShiftKeyDown()) {
            breakLine();
            return true;
        }
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        int style = tag.contains("style")
                ? tag.getInt("style")
                : (tag.getBoolean("chain") ? ZiplineItem.STYLE_CHAIN
                : (tag.getBoolean("dark") ? ZiplineItem.STYLE_DARK_ROPE : ZiplineItem.STYLE_ROPE));
        setEnds(
                new Vec3(tag.getDouble("ax"), tag.getDouble("ay"), tag.getDouble("az")),
                new Vec3(tag.getDouble("bx"), tag.getDouble("by"), tag.getDouble("bz")),
                tag.getFloat("sag"),
                style
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
        tag.putBoolean("dark", darkRope());
        tag.putInt("style", style());
        tag.put("lanterns", hangingsTag().copy());
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 96.0 * 96.0;
    }

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
        ItemStack drop = new ItemStack(switch (style()) {
            case ZiplineItem.STYLE_CHAIN -> MovesContent.ZIPLINE_CHAIN;
            case ZiplineItem.STYLE_DARK_ROPE -> MovesContent.ZIPLINE_ROPE_DARK;
            default -> MovesContent.ZIPLINE_ROPE;
        });
        if (!drop.isEmpty()) {
            spawnAtLocation(drop);
        }
        ListTag hanging = hangings();
        for (int i = 0; i < hanging.size(); i++) {
            ItemStack item = hangingDrop(hanging.getCompound(i));
            if (!item.isEmpty()) {
                spawnAtLocation(item);
            }
        }
        discard();
    }

    public static void breakLookedAt(Player player) {
        if (!player.swinging) {
            return;
        }
        ZiplineEntity line = nearest(player.level(), player.getEyePosition(), 6.0);
        if (line == null || !line.aimed(player, 5.0, 0.45)) {
            return;
        }
        double t = line.lookT(player, 5.0, 0.45);
        if (t < 0.0) {
            t = line.closestT(player.getEyePosition());
        }
        if (line.takeHanging(t)) {
            return;
        }
        if (player.isShiftKeyDown()) {
            line.breakLine();
        }
    }

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

    public ListTag hangings() {
        return hangingsTag().getList("L", 10);
    }

    /** Old saves used lanterns(); keep the name as an alias. */
    public ListTag lanterns() {
        return hangings();
    }

    private CompoundTag hangingsTag() {
        CompoundTag tag = this.entityData.get(LANTERNS);
        return tag == null ? new CompoundTag() : tag;
    }

    public static String hangingKind(CompoundTag one) {
        if (one.contains("k")) {
            return one.getString("k");
        }
        return one.getBoolean("s") ? "soul" : "lantern";
    }

    public static BlockState signState(CompoundTag one) {
        ResourceLocation id = ResourceLocation.tryParse(one.getString("wood"));
        var block = id == null ? Blocks.OAK_HANGING_SIGN : BuiltInRegistries.BLOCK.get(id);
        int rot = one.contains("rot") ? Mth.clamp(one.getInt("rot"), 0, 15) : 0;
        if (!(block instanceof CeilingHangingSignBlock hanging)) {
            return Blocks.OAK_HANGING_SIGN.defaultBlockState()
                    .setValue(CeilingHangingSignBlock.ATTACHED, true)
                    .setValue(CeilingHangingSignBlock.ROTATION, rot);
        }
        return hanging.defaultBlockState()
                .setValue(CeilingHangingSignBlock.ATTACHED, true)
                .setValue(CeilingHangingSignBlock.ROTATION, rot);
    }

    /** Which text face the player is looking at, based on stored rotation. */
    public boolean isSignFront(Player player, CompoundTag one, double t) {
        int rot = one.contains("rot") ? one.getInt("rot") : 0;
        float yaw = rot * 22.5F;
        double rad = Math.toRadians(yaw);
        // Hanging-sign front normal (approx. south at rot 0).
        Vec3 out = new Vec3(-Math.sin(rad), 0.0, Math.cos(rad));
        Vec3 toPlayer = player.getEyePosition().subtract(point(t));
        toPlayer = new Vec3(toPlayer.x, 0.0, toPlayer.z);
        if (toPlayer.lengthSqr() < 1.0E-6) {
            return true;
        }
        return toPlayer.normalize().dot(out) >= 0.0;
    }

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
        // Also hit hanging bodies below the cable (signs/banners hang ~1 block down).
        ListTag list = hangings();
        for (int i = 0; i < list.size(); i++) {
            CompoundTag one = list.getCompound(i);
            Vec3 at = hangingAimPoint(one);
            Vec3 rel = at.subtract(eye);
            double along = rel.dot(look);
            if (along < 0.15 || along > reach) {
                continue;
            }
            double side = rel.subtract(look.scale(along)).lengthSqr();
            if (side < best) {
                best = side;
                bestT = one.getFloat("t");
            }
        }
        return bestT;
    }

    public int addLantern(double t, boolean soul) {
        CompoundTag one = new CompoundTag();
        one.putFloat("t", (float) clamp01(t));
        one.putString("k", soul ? "soul" : "lantern");
        one.putBoolean("s", soul);
        if (!this.level().isClientSide()) {
            hangLight(one, t);
        }
        return addHanging(one);
    }

    public int addBanner(double t, ItemStack stack, Player player) {
        CompoundTag one = new CompoundTag();
        one.putFloat("t", (float) clamp01(t));
        one.putString("k", "banner");
        one.put("it", stack.copyWithCount(1).save(new CompoundTag()));
        one.putByte("face", (byte) facingTowardPlayer(t, player).get3DDataValue());
        return addHanging(one);
    }

    public int addSign(double t, ItemStack stack, Player player) {
        CompoundTag one = new CompoundTag();
        one.putFloat("t", (float) clamp01(t));
        one.putString("k", "sign");
        if (stack.getItem() instanceof BlockItem blockItem) {
            one.putString("wood", BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()).toString());
        } else {
            one.putString("wood", "minecraft:oak_hanging_sign");
        }
        one.putInt("rot", rotationTowardPlayer(t, player));
        for (int i = 0; i < 4; i++) {
            one.putString("l" + i, "");
            one.putString("b" + i, "");
        }
        return addHanging(one);
    }

    /** Banner faces the player, perpendicular to the cable (front or back of the line). */
    private Direction facingTowardPlayer(double t, Player player) {
        Vec3 tan = tangent(t);
        Vec3 side = new Vec3(-tan.z, 0.0, tan.x);
        if (side.lengthSqr() < 1.0E-6) {
            side = new Vec3(1.0, 0.0, 0.0);
        }
        side = side.normalize();
        Vec3 toPlayer = player.getEyePosition().subtract(point(t));
        toPlayer = new Vec3(toPlayer.x, 0.0, toPlayer.z);
        if (toPlayer.lengthSqr() > 1.0E-6 && side.dot(toPlayer) < 0.0) {
            side = side.scale(-1.0);
        }
        Direction face = Direction.getNearest(side.x, 0.0, side.z);
        return face.getAxis().isVertical() ? Direction.SOUTH : face;
    }

    private int rotationTowardPlayer(double t, Player player) {
        // Full 16-step yaw from player look — not just N/E/S/W.
        Vec3 toPlayer = player.getEyePosition().subtract(point(t));
        if (toPlayer.lengthSqr() < 1.0E-6) {
            return Mth.floor(player.getYRot() * 16.0F / 360.0F + 0.5F) & 15;
        }
        float yaw = (float) (Math.toDegrees(Math.atan2(-toPlayer.x, toPlayer.z)));
        return Mth.floor(yaw * 16.0F / 360.0F + 0.5F) & 15;
    }

    private int addHanging(CompoundTag one) {
        CompoundTag root = hangingsTag().copy();
        ListTag list = root.getList("L", 10);
        list.add(one);
        root.put("L", list);
        this.entityData.set(LANTERNS, root);
        return list.size() - 1;
    }

    public void setSignLines(int index, String[] lines, boolean front) {
        CompoundTag root = hangingsTag().copy();
        ListTag list = root.getList("L", 10);
        if (index < 0 || index >= list.size()) {
            return;
        }
        CompoundTag one = list.getCompound(index).copy();
        if (!"sign".equals(hangingKind(one))) {
            return;
        }
        String prefix = front ? "l" : "b";
        for (int i = 0; i < 4; i++) {
            String line = i < lines.length && lines[i] != null ? lines[i] : "";
            if (line.length() > 50) {
                line = line.substring(0, 50);
            }
            one.putString(prefix + i, line);
        }
        list.set(index, one);
        root.put("L", list);
        this.entityData.set(LANTERNS, root);
    }

    public String[] signLines(int index, boolean front) {
        ListTag list = hangings();
        String[] lines = new String[]{"", "", "", ""};
        if (index < 0 || index >= list.size()) {
            return lines;
        }
        CompoundTag one = list.getCompound(index);
        String prefix = front ? "l" : "b";
        for (int i = 0; i < 4; i++) {
            // Front also accepts legacy l0–l3; back uses b0–b3.
            if (front && !one.contains(prefix + i) && one.contains("l" + i)) {
                lines[i] = one.getString("l" + i);
            } else {
                lines[i] = one.contains(prefix + i) ? one.getString(prefix + i) : "";
            }
        }
        return lines;
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
        // Placing another line must hit the block behind this entity.
        if (stack.getItem() instanceof yblk.moves.item.ZiplineItem) {
            return InteractionResult.PASS;
        }
        boolean soul = stack.is(Items.SOUL_LANTERN);
        boolean lantern = soul || stack.is(Items.LANTERN);
        boolean banner = stack.getItem() instanceof BannerItem;
        boolean sign = stack.getItem() instanceof HangingSignItem;
        boolean placeable = lantern || banner || sign;

        if (!placeable && !player.isShiftKeyDown() && stack.isEmpty()) {
            int signIndex = nearestHanging(t, "sign", 0.14);
            if (signIndex >= 0) {
                if (this.level().isClientSide()) {
                    return InteractionResult.SUCCESS;
                }
                if (player instanceof ServerPlayer server) {
                    CompoundTag one = hangings().getCompound(signIndex);
                    boolean front = isSignFront(player, one, one.getFloat("t"));
                    MovesNet.openZiplineSign(server, this.getId(), signIndex, signLines(signIndex, front), front);
                }
                this.useTick = this.tickCount;
                return InteractionResult.CONSUME;
            }
        }

        if (!placeable && !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (this.level().isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (player.isShiftKeyDown() && takeHanging(t)) {
            this.useTick = this.tickCount;
            return InteractionResult.CONSUME;
        }
        if (!placeable || hangings().size() >= 16) {
            return InteractionResult.PASS;
        }
        int index;
        if (banner) {
            index = addBanner(t, stack, player);
        } else if (sign) {
            index = addSign(t, stack, player);
            if (player instanceof ServerPlayer server) {
                MovesNet.openZiplineSign(server, this.getId(), index, signLines(index, true), true);
            }
        } else {
            index = addLantern(t, soul);
        }
        this.useTick = this.tickCount;
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        return InteractionResult.CONSUME;
    }

    private int nearestHanging(double t, String kind, double maxDist) {
        ListTag list = hangings();
        int best = -1;
        double bestDist = maxDist;
        for (int i = 0; i < list.size(); i++) {
            CompoundTag one = list.getCompound(i);
            if (kind != null && !kind.equals(hangingKind(one))) {
                continue;
            }
            double dist = Math.abs(one.getFloat("t") - t);
            if (dist < bestDist) {
                bestDist = dist;
                best = i;
            }
        }
        return best;
    }

    private boolean takeHanging(double t) {
        int best = nearestHanging(t, null, 0.14);
        if (best < 0) {
            return false;
        }
        CompoundTag one = hangings().getCompound(best);
        clearLight(one);
        ItemStack drop = hangingDrop(one);
        if (!drop.isEmpty()) {
            spawnAtLocation(drop);
        }
        CompoundTag root = hangingsTag().copy();
        ListTag copy = root.getList("L", 10);
        copy.remove(best);
        root.put("L", copy);
        this.entityData.set(LANTERNS, root);
        return true;
    }

    private static ItemStack hangingDrop(CompoundTag one) {
        return switch (hangingKind(one)) {
            case "soul" -> new ItemStack(Items.SOUL_LANTERN);
            case "lantern" -> new ItemStack(Items.LANTERN);
            case "banner" -> ItemStack.of(one.getCompound("it"));
            case "sign" -> {
                ResourceLocation id = ResourceLocation.tryParse(one.getString("wood"));
                var block = id == null ? Blocks.OAK_HANGING_SIGN : BuiltInRegistries.BLOCK.get(id);
                yield new ItemStack(block.asItem());
            }
            default -> ItemStack.EMPTY;
        };
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
        ListTag hanging = hangings();
        for (int i = 0; i < hanging.size(); i++) {
            clearLight(hanging.getCompound(i));
        }
    }

    private void ensureLights() {
        CompoundTag root = hangingsTag().copy();
        ListTag copy = root.getList("L", 10);
        boolean changed = false;
        for (int i = 0; i < copy.size(); i++) {
            CompoundTag one = copy.getCompound(i);
            String kind = hangingKind(one);
            if (!"lantern".equals(kind) && !"soul".equals(kind)) {
                continue;
            }
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
