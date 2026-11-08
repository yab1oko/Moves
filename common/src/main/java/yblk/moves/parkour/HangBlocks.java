package yblk.moves.parkour;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import yblk.moves.Constants;

/**
 * Smart Moving–style hangables:
 * - Vertical climb: chains, bamboo, rods, fence columns
 * - Ceiling hang: iron bars / trapdoors overhead
 * - Ledges: full blocks, slabs, stairs, closed trapdoors, wall recesses
 */
public final class HangBlocks {

    public static final TagKey<Block> HANGABLE = TagKey.create(Registries.BLOCK, new ResourceLocation(Constants.MOD_ID, "hangable"));

    private HangBlocks() {
    }

    public static boolean canHold(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || state.is(BlockTags.RAILS)) {
            return false;
        }
        if (state.is(BlockTags.SAPLINGS) || state.is(BlockTags.FLOWERS) || state.is(BlockTags.CROPS) || state.is(BlockTags.LEAVES)) {
            return false;
        }
        if (state.isCollisionShapeFullBlock(level, pos)) {
            return false;
        }
        if (state.is(HANGABLE) || state.is(BlockTags.FENCES) || state.is(BlockTags.FENCE_GATES)
                || state.is(Blocks.IRON_BARS) || state.is(Blocks.CHAIN)
                || state.is(Blocks.END_ROD) || state.is(Blocks.LIGHTNING_ROD) || state.is(Blocks.BAMBOO)
                || state.is(Blocks.LADDER) || state.is(Blocks.VINE) || state.is(Blocks.CAVE_VINES)
                || state.is(Blocks.CAVE_VINES_PLANT) || state.is(Blocks.TWISTING_VINES)
                || state.is(Blocks.TWISTING_VINES_PLANT) || state.is(Blocks.WEEPING_VINES)
                || state.is(Blocks.WEEPING_VINES_PLANT)) {
            return true;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            shape = state.getShape(level, pos);
        }
        if (shape.isEmpty()) {
            return false;
        }
        AABB box = shape.bounds();
        double height = box.maxY - box.minY;
        double widthX = box.maxX - box.minX;
        double widthZ = box.maxZ - box.minZ;
        double thin = Math.min(widthX, widthZ);
        double wide = Math.max(widthX, widthZ);
        if (height >= 0.6 && thin <= 0.5) {
            return true;
        }
        return height <= 0.45 && wide >= 0.4 && thin <= 0.55;
    }

    public static boolean isFenceOrBars(BlockState state) {
        return state.is(BlockTags.FENCES) || state.is(Blocks.IRON_BARS) || state.is(BlockTags.FENCE_GATES);
    }

    /**
     * A fence or bar run that touches a full block is a wall. A freestanding run can be circled.
     * Chains are not walls: a link only touches other links, or the one block it hangs from.
     */
    public static boolean runTouchesSolid(Level level, BlockPos start) {
        BlockState origin = level.getBlockState(start);
        if (isPole(origin) || !isFenceOrBars(origin)) {
            return false;
        }
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<BlockPos> seen = new java.util.HashSet<>();
        queue.add(start.immutable());
        int scanned = 0;
        while (!queue.isEmpty() && scanned++ < 48) {
            BlockPos pos = queue.removeFirst();
            if (!seen.add(pos)) {
                continue;
            }
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos side = pos.relative(dir);
                BlockState state = level.getBlockState(side);
                if (isFenceOrBars(state)) {
                    queue.add(side.immutable());
                } else if (state.isCollisionShapeFullBlock(level, side)) {
                    return true;
                }
            }
            BlockPos up = pos.above();
            if (isFenceOrBars(level.getBlockState(up))) {
                queue.add(up.immutable());
            }
            BlockPos down = pos.below();
            if (isFenceOrBars(level.getBlockState(down))) {
                queue.add(down.immutable());
            }
        }
        return false;
    }

    private static boolean solidFloor(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir() || isPole(state) || isFenceOrBars(state)) {
            return false;
        }
        return !state.getCollisionShape(level, pos).isEmpty();
    }

    public static boolean isPole(BlockState state) {
        return state.is(Blocks.CHAIN) || state.is(Blocks.BAMBOO)
                || state.is(Blocks.END_ROD) || state.is(Blocks.LIGHTNING_ROD);
    }

    /** Count horizontal climbable neighbors (fence/bar wall connectivity). */
    public static int horizontalHoldNeighbors(Level level, BlockPos pos) {
        int n = 0;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (canHold(level, pos.relative(dir))) {
                n++;
            }
        }
        return n;
    }

    /**
     * Full orbit: freestanding pole/chain, or a lone fence/bar post.
     * End of a fence/bar run: only orbit toward the open side (handled by caller via {@link #orbitOpenSign}).
     */
    public static boolean allowsOrbit(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        int sides = horizontalHoldNeighbors(level, pos);
        if (isPole(state)) {
            return sides == 0;
        }
        if (isFenceOrBars(state)) {
            return sides <= 1;
        }
        return false;
    }

    /**
     * For an end-post (1 neighbor): +1 = orbit only toward CounterClockWise of face-into-hold,
     * -1 = only ClockWise, 0 = both/none. Middle of a wall returns 0 and {@link #allowsOrbit} is false when sides≥2.
     */
    public static int orbitOpenSign(Level level, BlockPos pos, float climbYaw) {
        BlockState state = level.getBlockState(pos);
        if (!isFenceOrBars(state) && !isPole(state)) {
            return 0;
        }
        int sides = horizontalHoldNeighbors(level, pos);
        if (sides == 0) {
            return 0; // both ways
        }
        if (sides >= 2) {
            return 99; // no orbit
        }
        Direction face = Direction.fromYRot(climbYaw);
        Direction neighborDir = null;
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            if (canHold(level, pos.relative(dir))) {
                neighborDir = dir;
                break;
            }
        }
        if (neighborDir == null) {
            return 0;
        }
        // Open side is opposite the neighbor. Strafe+ (A) orbits using +sign on climbYaw.
        // Map open side to allowed strafe sign relative to facing the hold.
        Direction left = face.getCounterClockWise();
        if (neighborDir == left) {
            // neighbor on the left → may only orbit right (strafe −)
            return -1;
        }
        if (neighborDir == left.getOpposite()) {
            return 1;
        }
        // Neighbor along face axis — treat as both blocked for orbit
        return 99;
    }

    public static boolean preferClimb(Level level, BlockPos pos) {
        if (!canHold(level, pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.IRON_BARS)) {
            return canHold(level, pos.above()) || canHold(level, pos.below());
        }
        if (state.is(Blocks.CHAIN) || state.is(Blocks.BAMBOO) || state.is(Blocks.END_ROD)
                || state.is(Blocks.LIGHTNING_ROD) || state.is(Blocks.LADDER)
                || state.is(BlockTags.CLIMBABLE) || state.is(BlockTags.FENCES)) {
            return true;
        }
        return canHold(level, pos.above()) || canHold(level, pos.below());
    }

    public static boolean isCeilingHang(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        // Any chain with air under it is a ceiling grip (the bottom link you hang from).
        // A link with another chain below is a column and is climbed from the side.
        if (state.is(Blocks.CHAIN)) {
            if (canHold(level, pos.below())) {
                return false;
            }
            // A link with the floor right under it is not a ceiling you can ride along the ground.
            if (solidFloor(level, pos.below()) || solidFloor(level, pos.below(2))) {
                return false;
            }
            return true;
        }
        boolean roof = state.is(Blocks.IRON_BARS)
                || state.is(BlockTags.FENCES)
                || state.is(BlockTags.FENCE_GATES);
        if (state.is(BlockTags.TRAPDOORS)) {
            if (state.hasProperty(TrapDoorBlock.OPEN) && state.getValue(TrapDoorBlock.OPEN)) {
                return false;
            }
            roof = true;
        }
        if (!roof) {
            return false;
        }
        // A wall continues upward (the course just above the ground included). A ceiling is one horizontal layer.
        if (canHold(level, pos.above())) {
            return false;
        }
        return !canHold(level, pos.below());
    }

    public static boolean isPartialLip(BlockState state) {
        Block block = state.getBlock();
        return block instanceof SlabBlock || block instanceof StairBlock || block instanceof TrapDoorBlock
                || state.is(BlockTags.SLABS) || state.is(BlockTags.STAIRS) || state.is(BlockTags.TRAPDOORS);
    }

    /**
     * Grabable lip: full top, slab, stair (incl. upside-down), closed trapdoor as shelf,
     * or any partial collision top with a hang cavity.
     */
    public static boolean sturdyLedge(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }
        // Partials before canHold — a slab's thin box must not be treated as a pole.
        if (state.is(BlockTags.TRAPDOORS)) {
            if (state.hasProperty(TrapDoorBlock.OPEN) && state.getValue(TrapDoorBlock.OPEN)) {
                return false;
            }
            return hasHangApproach(level, pos);
        }
        if (state.is(BlockTags.SLABS) || state.is(BlockTags.STAIRS) || state.getBlock() instanceof SlabBlock
                || state.getBlock() instanceof StairBlock) {
            VoxelShape partial = state.getCollisionShape(level, pos);
            if (partial.isEmpty() || partial.max(Direction.Axis.Y) < 0.05) {
                return false;
            }
            // A lip only needs one open side. Air above is not required (slab under a wall).
            return hasHangApproach(level, pos);
        }
        if (canHold(level, pos)) {
            return false;
        }
        VoxelShape shape = state.getCollisionShape(level, pos);
        if (shape.isEmpty()) {
            return false;
        }
        double top = shape.max(Direction.Axis.Y);
        if (top < 0.45) {
            return false;
        }
        // Full blocks: a real lip (air above), or a slab/stair sitting on this block as the overhang.
        if (open(level, pos.above())) {
            return true;
        }
        return isPartialLip(level.getBlockState(pos.above())) && hasHangApproach(level, pos);
    }

    private static boolean hasHangApproach(Level level, BlockPos pos) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            BlockPos out = pos.relative(dir);
            if (open(level, out) || open(level, out.above())) {
                return true;
            }
        }
        return open(level, pos.above());
    }

    public static boolean open(BlockGetter level, BlockPos pos) {
        return level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
    }
}
