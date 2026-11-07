package yblk.moves.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import yblk.moves.entity.ZiplineEntity;

/**
 * First right-click marks a face. The second stretches a line to the new face.
 * Sneak on the second click keeps the line straight. Otherwise it sags.
 */
public class ZiplineItem extends Item {

    public static final int STYLE_ROPE = 0;
    public static final int STYLE_CHAIN = 1;
    public static final int STYLE_DARK_ROPE = 2;

    private static final double END_BUSY = 0.28;

    private final int style;

    public ZiplineItem(boolean chain, Properties properties) {
        this(chain ? STYLE_CHAIN : STYLE_ROPE, properties);
    }

    public ZiplineItem(int style, Properties properties) {
        super(properties);
        this.style = style;
    }

    public int style() {
        return this.style;
    }

    public boolean chain() {
        return this.style == STYLE_CHAIN;
    }

    public boolean darkRope() {
        return this.style == STYLE_DARK_ROPE;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, net.minecraft.world.entity.Entity entity, int slot, boolean selected) {
        CompoundTag tag = stack.getTag();
        if (tag != null && tag.isEmpty()) {
            stack.setTag(null);
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.PASS;
        }
        BlockPos clicked = context.getClickedPos();
        Vec3 click = context.getClickLocation();
        BlockPos pos = nearestPost(level, clicked, click);
        Direction face = pos.equals(clicked) ? context.getClickedFace() : Direction.UP;
        ItemStack stack = context.getItemInHand();
        CompoundTag tag = stack.getOrCreateTag();
        if (!tag.contains("Ax")) {
            Vec3 firstPoint = attachPoint(level, pos, face, click);
            writePoint(tag, "A", pos, face, firstPoint.y);
            burst(level, firstPoint);
            player.displayClientMessage(Component.translatable("moves.zipline.first"), true);
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        BlockPos first = new BlockPos(tag.getInt("Ax"), tag.getInt("Ay"), tag.getInt("Az"));
        // Only search for another post when the click still resolves to the first one.
        if (pos.equals(first)) {
            BlockPos looked = postAlongLook(player, first);
            if (looked != null) {
                pos = looked;
                face = Direction.UP;
                double keepY = tag.contains("Ah") ? tag.getFloat("Ah") : pos.getY() + 0.78;
                click = new Vec3(pos.getX() + 0.5, keepY, pos.getZ() + 0.5);
            } else {
                BlockPos other = nearestPostExcept(level, clicked, click, first);
                if (other == null) {
                    player.displayClientMessage(Component.translatable("moves.zipline.need_other"), true);
                    return InteractionResult.FAIL;
                }
                pos = other;
                face = Direction.UP;
                double keepY = tag.contains("Ah") ? tag.getFloat("Ah") : pos.getY() + 0.78;
                click = new Vec3(pos.getX() + 0.5, keepY, pos.getZ() + 0.5);
            }
        }
        if (pos.equals(first)) {
            player.displayClientMessage(Component.translatable("moves.zipline.need_other"), true);
            return InteractionResult.FAIL;
        }
        if (first.distSqr(pos) > 48.0 * 48.0) {
            player.displayClientMessage(Component.translatable("moves.zipline.far"), true);
            return InteractionResult.FAIL;
        }
        Direction firstFace = Direction.values()[tag.getByte("Af")];
        Vec3 a = tag.contains("Ah")
                ? restoreAttach(level, first, firstFace, tag.getFloat("Ah"))
                : facePoint(level, first, firstFace);
        Vec3 b = attachPoint(level, pos, face, click);
        if (a.distanceToSqr(b) < 0.36) {
            player.displayClientMessage(Component.translatable("moves.zipline.need_other"), true);
            return InteractionResult.FAIL;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        if (conflicts(level, a, b)) {
            player.displayClientMessage(Component.translatable("moves.zipline.busy"), true);
            return InteractionResult.FAIL;
        }
        double span = a.distanceTo(b);
        float sag = player.isShiftKeyDown() ? 0.0F : (float) Math.min(3.2, Math.max(0.6, span * 0.16));
        ZiplineEntity line = new ZiplineEntity(level, a, b, this.style, sag);
        level.addFreshEntity(line);
        burst(level, a);
        burst(level, b);
        if (!player.getAbilities().instabuild) {
            stack.shrink(1);
        }
        tag.remove("Ax");
        tag.remove("Ay");
        tag.remove("Az");
        tag.remove("Af");
        tag.remove("Ah");
        if (tag.isEmpty()) {
            stack.setTag(null);
        }
        return InteractionResult.CONSUME;
    }

    private static BlockPos nearestPost(Level level, BlockPos clicked, Vec3 click) {
        if (isPost(level, clicked)) {
            return clicked;
        }
        BlockPos best = null;
        double bestDist = 1.35 * 1.35;
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos pos = clicked.offset(dx, dy, dz);
                    if (!isPost(level, pos)) {
                        continue;
                    }
                    double dist = click.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.55, pos.getZ() + 0.5);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos;
                    }
                }
            }
        }
        return best != null ? best : clicked;
    }

    private static BlockPos nearestPostExcept(Level level, BlockPos clicked, Vec3 click, BlockPos skip) {
        BlockPos best = null;
        double bestDist = 3.5 * 3.5;
        for (int dy = -1; dy <= 3; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos pos = clicked.offset(dx, dy, dz);
                    if (pos.equals(skip) || !isPost(level, pos)) {
                        continue;
                    }
                    double dist = click.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.55, pos.getZ() + 0.5);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private static BlockPos postAlongLook(Player player, BlockPos skip) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 dir = player.getLookAngle();
        BlockPos last = null;
        BlockPos best = null;
        double bestAlong = Double.MAX_VALUE;
        for (double t = 0.35; t <= 48.0; t += 0.2) {
            BlockPos pos = BlockPos.containing(eye.add(dir.scale(t)));
            if (pos.equals(last)) {
                continue;
            }
            last = pos;
            if (pos.equals(skip) || !isPost(level, pos)) {
                continue;
            }
            Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.78, pos.getZ() + 0.5);
            double along = center.subtract(eye).dot(dir);
            if (along < 0.2) {
                continue;
            }
            Vec3 lateral = center.subtract(eye).subtract(dir.scale(along));
            if (lateral.lengthSqr() > 1.35 * 1.35) {
                continue;
            }
            if (along < bestAlong) {
                bestAlong = along;
                best = pos;
            }
        }
        return best;
    }

    private static boolean isPost(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES);
    }

    private static void writePoint(CompoundTag tag, String prefix, BlockPos pos, Direction face, double height) {
        tag.putInt(prefix + "x", pos.getX());
        tag.putInt(prefix + "y", pos.getY());
        tag.putInt(prefix + "z", pos.getZ());
        tag.putByte(prefix + "f", (byte) face.ordinal());
        tag.putFloat(prefix + "h", (float) height);
    }

    public static Vec3 facePoint(BlockPos pos, Direction face) {
        return Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    public static Vec3 facePoint(Level level, BlockPos pos, Direction face) {
        return attachPoint(level, pos, face, null);
    }

    /**
     * Posts always attach — never reject with "too close to the ground".
     * Height follows the click, clamped to the post body.
     */
    public static Vec3 attachPoint(Level level, BlockPos pos, Direction face, Vec3 click) {
        BlockState state = level.getBlockState(pos);
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES)) {
            double y = click != null ? click.y : pos.getY() + 0.78;
            y = Mth.clamp(y, pos.getY() + 0.35, pos.getY() + 0.92);
            return new Vec3(pos.getX() + 0.5, y, pos.getZ() + 0.5);
        }
        Vec3 point = facePoint(pos, face);
        if (click != null && face.getAxis().isHorizontal()) {
            point = new Vec3(point.x, click.y, point.z);
        }
        double floor = floorTop(level, point.x, point.y, point.z);
        if (point.y < floor + 0.35) {
            point = new Vec3(point.x, floor + 0.35, point.z);
        }
        return point;
    }

    private static Vec3 restoreAttach(Level level, BlockPos pos, Direction face, double height) {
        if (isPost(level, pos)) {
            double y = Mth.clamp(height, pos.getY() + 0.35, pos.getY() + 0.92);
            return new Vec3(pos.getX() + 0.5, y, pos.getZ() + 0.5);
        }
        Vec3 point = facePoint(pos, face);
        return new Vec3(point.x, height, point.z);
    }

    private static double floorTop(Level level, double x, double y, double z) {
        BlockPos under = BlockPos.containing(x, y - 0.05, z);
        double best = level.getMinBuildHeight();
        for (int i = 0; i <= 3; i++) {
            BlockPos pos = under.below(i);
            var shape = level.getBlockState(pos).getCollisionShape(level, pos);
            if (shape.isEmpty() || isPost(level, pos)) {
                continue;
            }
            best = Math.max(best, pos.getY() + shape.max(Direction.Axis.Y));
            break;
        }
        return best;
    }

    private static boolean conflicts(Level level, Vec3 a, Vec3 b) {
        AABB area = new AABB(a, b).inflate(2.0);
        for (ZiplineEntity line : level.getEntitiesOfClass(ZiplineEntity.class, area)) {
            if (endsMatch(line, a, b)) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsMatch(ZiplineEntity line, Vec3 a, Vec3 b) {
        return (near(line.endA(), a) && near(line.endB(), b))
                || (near(line.endA(), b) && near(line.endB(), a));
    }

    private static boolean near(Vec3 a, Vec3 b) {
        return a.distanceToSqr(b) < END_BUSY * END_BUSY;
    }

    private static void burst(Level level, Vec3 at) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        server.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 10, 0.15, 0.15, 0.15, 0.02);
        server.sendParticles(ParticleTypes.WAX_ON, at.x, at.y, at.z, 18, 0.25, 0.25, 0.25, 0.01);
    }
}
