package yblk.moves.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import yblk.moves.item.ZiplineItem;

/**
 * While the first anchor is stored on the item, draw the knot and the line that the next click would place.
 */
public final class ZiplinePreview {

    private ZiplinePreview() {
    }

    public static void render(PoseStack pose, Vec3 camera) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null || minecraft.level == null) {
            return;
        }
        ItemStack stack = player.getMainHandItem();
        if (!(stack.getItem() instanceof ZiplineItem item) || !stack.hasTag() || !stack.getTag().contains("Ax")) {
            return;
        }
        ResourceLocation ropeTex = ZiplineRenderer.ropeTex(item.darkRope());
        CompoundTag tag = stack.getTag();
        BlockPos first = new BlockPos(tag.getInt("Ax"), tag.getInt("Ay"), tag.getInt("Az"));
        byte faceId = tag.getByte("Af");
        if (faceId < 0 || faceId >= Direction.values().length) {
            return;
        }
        double startY = tag.contains("Ah") ? tag.getFloat("Ah") : first.getY() + 0.78;
        Vec3 start = ZiplineItem.attachPoint(minecraft.level, first, Direction.values()[faceId],
                new Vec3(first.getX() + 0.5, startY, first.getZ() + 0.5));
        if (start == null) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(8.0));
        HitResult hit = minecraft.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        Vec3 target = end;
        if (hit instanceof BlockHitResult block && block.getType() == HitResult.Type.BLOCK) {
            ZiplineItem.Target resolved = ZiplineItem.resolveTarget(
                    minecraft.level, block.getBlockPos(), block.getDirection(), block.getLocation());
            Vec3 at = ZiplineItem.attachPoint(minecraft.level, resolved.pos(), resolved.face(), block.getLocation());
            if (at != null) {
                target = at;
            }
        }
        double span = start.distanceTo(target);
        float sag = player.isShiftKeyDown() ? 0.0F : (float) Math.min(3.2, Math.max(0.6, span * 0.16));
        if (span > 48.0) {
            sag = 0.0F;
        }
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        int count = ZiplineRenderer.steps(start, target, item.chain());
        Vec3 previous = start;
        for (int i = 0; i <= count; i++) {
            double t = i / (double) count;
            Vec3 point = curve(start, target, sag, t);
            if (!item.chain() && i > 0) {
                ZiplineRenderer.ropeBetween(pose, buffers, previous, point, 0x00F000F0, ropeTex);
            } else if (item.chain()) {
                Vec3 dir = i == 0 ? curve(start, target, sag, 1.0 / count).subtract(point) : point.subtract(previous);
                if (dir.lengthSqr() > 1.0E-8) {
                    ZiplineRenderer.link(pose, buffers, point, dir.normalize(), true, 0x00F000F0, (i & 1) * 45);
                }
            }
            previous = point;
        }
        pose.popPose();
        buffers.endBatch();
    }

    private static Vec3 curve(Vec3 a, Vec3 b, float sag, double t) {
        double hang = sag * 4.0 * t * (1.0 - t);
        return new Vec3(
                a.x + (b.x - a.x) * t,
                a.y + (b.y - a.y) * t - hang,
                a.z + (b.z - a.z) * t
        );
    }
}
