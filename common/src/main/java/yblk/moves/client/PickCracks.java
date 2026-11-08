package yblk.moves.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** One jagged crack where the pick that just swung actually hit. */
public final class PickCracks {

    private static final List<Mark> MARKS = new ArrayList<>();
    private static final int LIFE = 160;
    private static int nextVariant;

    private PickCracks() {
    }

    private static final int VARIANTS = 4;

    public static void add(Level level, BlockPos pos, Direction face, double feetY, boolean leftHand) {
        if (!level.isClientSide() || face == null || !face.getAxis().isHorizontal()) {
            return;
        }
        int top = Mth.floor(feetY + 2.15);
        BlockPos hands = new BlockPos(pos.getX(), top, pos.getZ());
        if (level.getBlockState(hands).getCollisionShape(level, hands).isEmpty()) {
            BlockPos below = hands.below();
            if (!level.getBlockState(below).getCollisionShape(level, below).isEmpty()) {
                hands = below;
            }
        }
        // Pick swing hits ~hand height; 6px (6/16) below the old mark lines up with the head.
        float y = 0.62F - 6.0F / 16.0F;
        int variant = Math.floorMod(nextVariant++, VARIANTS);
        MARKS.add(new Mark(hands.immutable(), face, y, leftHand ? 0.24F : -0.24F, variant, level.getGameTime()));
        if (MARKS.size() > 24) {
            MARKS.subList(0, MARKS.size() - 24).clear();
        }
    }

    public static void render(PoseStack pose, Vec3 camera) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || MARKS.isEmpty()) {
            return;
        }
        long now = minecraft.level.getGameTime();
        Iterator<Mark> it = MARKS.iterator();
        var buffers = minecraft.renderBuffers().bufferSource();
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        while (it.hasNext()) {
            Mark mark = it.next();
            long age = now - mark.start;
            if (age < 0 || age >= LIFE) {
                it.remove();
                continue;
            }
            int alpha = (int) (220 * (1.0 - age / (double) LIFE));
            int light = LevelRenderer.getLightColor(minecraft.level, mark.pos);
            ResourceLocation texture = new ResourceLocation("moves", "textures/entity/crack_" + mark.variant + ".png");
            VertexConsumer consumer = buffers.getBuffer(RenderType.entityTranslucent(texture));
            cracks(pose.last(), consumer, light, mark, alpha);
            buffers.endBatch(RenderType.entityTranslucent(texture));
        }
        pose.popPose();
    }

    private static void cracks(PoseStack.Pose entry, VertexConsumer consumer, int light, Mark mark, int alpha) {
        Direction face = mark.face;
        Direction side = face.getClockWise();
        double cx = mark.pos.getX() + 0.5 + face.getStepX() * 0.502;
        double cy = mark.pos.getY() + mark.y;
        double cz = mark.pos.getZ() + 0.5 + face.getStepZ() * 0.502;
        Vec3 n = new Vec3(face.getStepX(), 0.0, face.getStepZ());
        Vec3 right = new Vec3(side.getStepX(), 0.0, side.getStepZ());
        Vec3 up = new Vec3(0.0, 1.0, 0.0);
        double size = 0.42;
        double x0 = mark.along - size * 0.5;
        double y0 = -size * 0.5;
        stroke(entry, consumer, light, alpha, new Vec3(cx, cy, cz), right, up, n, x0, y0, size, size);
    }

    private static void stroke(PoseStack.Pose entry, VertexConsumer consumer, int light, int alpha,
                               Vec3 origin, Vec3 right, Vec3 up, Vec3 n,
                               double x, double y, double w, double h) {
        Vec3 p0 = origin.add(right.scale(x)).add(up.scale(y));
        Vec3 p1 = origin.add(right.scale(x + w)).add(up.scale(y));
        Vec3 p2 = origin.add(right.scale(x + w)).add(up.scale(y + h));
        Vec3 p3 = origin.add(right.scale(x)).add(up.scale(y + h));
        Matrix4f matrix = entry.pose();
        Matrix3f normal = entry.normal();
        float nx = (float) n.x;
        float nz = (float) n.z;
        vertex(consumer, matrix, normal, light, alpha, p0, 0.0F, 1.0F, nx, nz);
        vertex(consumer, matrix, normal, light, alpha, p1, 1.0F, 1.0F, nx, nz);
        vertex(consumer, matrix, normal, light, alpha, p2, 1.0F, 0.0F, nx, nz);
        vertex(consumer, matrix, normal, light, alpha, p3, 0.0F, 0.0F, nx, nz);
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normal, int light, int alpha,
                               Vec3 pos, float u, float v, float nx, float nz) {
        consumer.vertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(255, 255, 255, alpha)
                .uv(u, v)
                .overlayCoords(net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(normal, nx, 0.0F, nz)
                .endVertex();
    }

    private static final class Mark {
        final BlockPos pos;
        final Direction face;
        final float y;
        final float along;
        final int variant;
        final long start;

        Mark(BlockPos pos, Direction face, float y, float along, int variant, long start) {
            this.pos = pos;
            this.face = face;
            this.y = y;
            this.along = along;
            this.variant = variant;
            this.start = start;
        }
    }
}
