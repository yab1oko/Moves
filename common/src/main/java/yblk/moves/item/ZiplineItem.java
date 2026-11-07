package yblk.moves.item;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import yblk.moves.entity.ZiplineEntity;

/**
 * First right-click marks a face. The second stretches a line to the new face.
 * Sneak on the second click keeps the line straight. Otherwise it sags.
 */
public class ZiplineItem extends Item {

    private final boolean chain;

    public ZiplineItem(boolean chain, Properties properties) {
        super(properties);
        this.chain = chain;
    }

    public boolean chain() {
        return this.chain;
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
            writePoint(tag, "A", pos, face);
            burst(level, facePoint(level, pos, face));
            player.displayClientMessage(Component.translatable("moves.zipline.first"), true);
            return InteractionResult.sidedSuccess(level.isClientSide());
        }
        BlockPos first = new BlockPos(tag.getInt("Ax"), tag.getInt("Ay"), tag.getInt("Az"));
        // Prefer a different post: the one clicked, then one along the look (straight fence runs),
        // then the nearest other post beside the click.
        if (pos.equals(first) || !isPost(level, pos)) {
            BlockPos looked = postAlongLook(player, first);
            if (looked != null) {
                pos = looked;
                face = Direction.UP;
            } else {
                BlockPos other = nearestPostExcept(level, clicked, click, first);
                if (other == null) {
                    return InteractionResult.CONSUME;
                }
                pos = other;
                face = Direction.UP;
            }
        }
        if (pos.equals(first)) {
            return InteractionResult.CONSUME;
        }
        if (first.distSqr(pos) > 48.0 * 48.0) {
            player.displayClientMessage(Component.translatable("moves.zipline.far"), true);
            return InteractionResult.FAIL;
        }
        Vec3 a = facePoint(level, first, Direction.values()[tag.getByte("Af")]);
        Vec3 b = facePoint(level, pos, face);
        if (a.distanceToSqr(b) < 0.36) {
            return InteractionResult.CONSUME;
        }
        if (level.isClientSide()) {
            return InteractionResult.SUCCESS;
        }
        double span = a.distanceTo(b);
        boolean posts = isPost(level, first) && isPost(level, pos);
        // Sneak = straight. Two fence posts at a similar height also stay straight —
        // that is the usual “line the posts” placement.
        boolean straight = player.isShiftKeyDown()
                || (posts && Math.abs(a.y - b.y) < 0.35);
        float sag = straight ? 0.0F : (float) Math.min(3.2, Math.max(0.6, span * 0.16));
        ZiplineEntity line = new ZiplineEntity(level, a, b, this.chain, sag);
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
        if (tag.isEmpty()) {
            stack.setTag(null);
        }
        return InteractionResult.CONSUME;
    }

    /** A click on the ground beside a post still counts as that post. */
    private static BlockPos nearestPost(Level level, BlockPos clicked, Vec3 click) {
        if (isPost(level, clicked)) {
            return clicked;
        }
        BlockPos best = null;
        double bestDist = 1.55 * 1.55;
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
        double bestDist = 3.0 * 3.0;
        for (int dy = -1; dy <= 2; dy++) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
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

    /** The fence the player is looking at, so a straight line can reach the far post. */
    private static BlockPos postAlongLook(Player player, BlockPos skip) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        Vec3 dir = player.getLookAngle();
        BlockPos last = null;
        BlockPos best = null;
        double bestAlong = Double.MAX_VALUE;
        for (double t = 0.35; t <= 48.0; t += 0.25) {
            BlockPos pos = BlockPos.containing(eye.add(dir.scale(t)));
            if (pos.equals(last)) {
                continue;
            }
            last = pos;
            if (pos.equals(skip) || !isPost(level, pos)) {
                continue;
            }
            Vec3 center = new Vec3(pos.getX() + 0.5, pos.getY() + 0.78, pos.getZ() + 0.5);
            Vec3 lateral = center.subtract(eye).subtract(dir.scale(center.subtract(eye).dot(dir)));
            if (lateral.lengthSqr() > 1.1 * 1.1) {
                continue;
            }
            if (t < bestAlong) {
                bestAlong = t;
                best = pos;
            }
        }
        return best;
    }

    private static boolean isPost(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES);
    }

    private static void writePoint(CompoundTag tag, String prefix, BlockPos pos, Direction face) {
        tag.putInt(prefix + "x", pos.getX());
        tag.putInt(prefix + "y", pos.getY());
        tag.putInt(prefix + "z", pos.getZ());
        tag.putByte(prefix + "f", (byte) face.ordinal());
    }

    public static Vec3 facePoint(BlockPos pos, Direction face) {
        return Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
    }

    /** Fences and walls attach on the post, not on the empty edge of the block cell. */
    public static Vec3 facePoint(Level level, BlockPos pos, Direction face) {
        BlockState state = level.getBlockState(pos);
        if (state.is(BlockTags.FENCES) || state.is(BlockTags.WALLS) || state.is(BlockTags.FENCE_GATES)) {
            return new Vec3(pos.getX() + 0.5, pos.getY() + 0.78, pos.getZ() + 0.5);
        }
        return facePoint(pos, face);
    }

    private static void burst(Level level, Vec3 at) {
        if (!(level instanceof ServerLevel server)) {
            return;
        }
        server.sendParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 10, 0.15, 0.15, 0.15, 0.02);
        server.sendParticles(ParticleTypes.WAX_ON, at.x, at.y, at.z, 18, 0.25, 0.25, 0.25, 0.01);
    }
}
