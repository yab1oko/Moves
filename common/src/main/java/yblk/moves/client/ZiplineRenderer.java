package yblk.moves.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import yblk.moves.Constants;
import yblk.moves.entity.ZiplineEntity;

/**
 * Chain links are chain-block models with a stable twist. Rope is short cubes
 * aimed along the cable, textured as a solid brown cord. Anchors are gray cubes.
 */
public class ZiplineRenderer extends EntityRenderer<ZiplineEntity> {

    private static final ResourceLocation ROPE = new ResourceLocation(Constants.MOD_ID, "textures/entity/zipline_rope.png");

    public ZiplineRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ZiplineEntity line, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int packedLight) {
        Vec3 origin = line.position();
        int count = steps(line.endA(), line.endB(), line.chain());
        Vec3 previous = line.point(0.0);
        float traveled = 0.0F;
        for (int i = 0; i <= count; i++) {
            double t = i / (double) count;
            Vec3 point = line.point(t);
            if (!line.chain() && i > 0) {
                float len = (float) previous.distanceTo(point);
                ropeBetween(pose, buffers, previous.subtract(origin), point.subtract(origin),
                        lightAt(line, point), traveled, traveled + len, i);
                traveled += len;
            } else if (line.chain()) {
                Vec3 dir = i == 0 ? line.point(1.0 / count).subtract(point) : point.subtract(previous);
                if (dir.lengthSqr() < 1.0E-8) {
                    dir = new Vec3(0.0, -1.0, 0.0);
                }
                link(pose, buffers, point.subtract(origin), dir.normalize(), true, lightAt(line, point), (i & 1) * 45);
            }
            previous = point;
        }
        renderEnd(pose, buffers, line, line.endA(), outgoing(line.endA(), line.point(0.04)), origin);
        renderEnd(pose, buffers, line, line.endB(), outgoing(line.endB(), line.point(0.96)), origin);
        ListTag lanterns = line.lanterns();
        for (int i = 0; i < lanterns.size(); i++) {
            CompoundTag one = lanterns.getCompound(i);
            Vec3 at = line.point(one.getFloat("t"));
            renderLantern(pose, buffers, line, at, one.getBoolean("s"));
        }
        super.render(line, yaw, partial, pose, buffers, packedLight);
    }

    public static int steps(Vec3 a, Vec3 b, boolean chain) {
        double length = a.distanceTo(b);
        // Chain links must not stack in the same spot or the model z-fights and blinks.
        return Math.max(4, (int) (length / (chain ? 0.50 : 0.18)));
    }

    public static void link(PoseStack pose, MultiBufferSource buffers, Vec3 local, Vec3 dir, boolean chain, int light) {
        link(pose, buffers, local, dir, chain, light, 0);
    }

    public static void link(PoseStack pose, MultiBufferSource buffers, Vec3 local, Vec3 dir, boolean chain, int light, int spin) {
        if (chain) {
            renderBlock(pose, buffers, local, dir, 0.56F, Blocks.CHAIN.defaultBlockState(), light, spin);
            return;
        }
        ropeBetween(pose, buffers, local, local.add(dir.scale(0.18)), light, 0.0F, 0.18F, 0);
    }

    /** Overlapping cubes with a flowing UV so the cord reads as one rope, not a row of blocks. */
    public static void ropeBetween(PoseStack pose, MultiBufferSource buffers, Vec3 a, Vec3 b, int light) {
        ropeBetween(pose, buffers, a, b, light, 0.0F, (float) a.distanceTo(b), 0);
    }

    public static void ropeBetween(PoseStack pose, MultiBufferSource buffers, Vec3 a, Vec3 b, int light,
                                    float from, float to, int index) {
        Vec3 delta = b.subtract(a);
        double len = delta.length();
        if (len < 1.0E-4) {
            return;
        }
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(ROPE));
        float thick = (index & 1) == 0 ? 0.040F : 0.034F;
        orientedBox(pose.last(), consumer, light, a.add(delta.scale(0.5)), delta,
                (float) (len * 0.62), thick, from * 0.85F, to * 0.85F);
    }

    private static boolean postEnd(ZiplineEntity line, Vec3 end) {
        if (line.level() == null) {
            return false;
        }
        var state = line.level().getBlockState(BlockPos.containing(end));
        return state.is(net.minecraft.tags.BlockTags.FENCES)
                || state.is(net.minecraft.tags.BlockTags.WALLS)
                || state.is(net.minecraft.tags.BlockTags.FENCE_GATES);
    }

    private static void renderEnd(PoseStack pose, MultiBufferSource buffers, ZiplineEntity line, Vec3 end, Vec3 out, Vec3 origin) {
        Vec3 local = end.subtract(origin);
        int light = anchorLight(line, end);
        if (postEnd(line, end)) {
            renderWrap(pose, buffers, line, end, origin, light);
            return;
        }
        renderAnchor(pose, buffers, local.add(out.scale(0.04)), light);
    }

    /** A ring of rope or chain around a fence post, outside the wood. */
    private static void renderWrap(PoseStack pose, MultiBufferSource buffers, ZiplineEntity line, Vec3 end, Vec3 origin, int light) {
        int pieces = line.chain() ? 6 : 6;
        double radius = line.chain() ? 0.32 : 0.2;
        for (int i = 0; i < pieces; i++) {
            double a0 = i * Math.PI * 2.0 / pieces;
            double a1 = (i + 1) * Math.PI * 2.0 / pieces;
            Vec3 p0 = new Vec3(end.x + Math.cos(a0) * radius, end.y, end.z + Math.sin(a0) * radius);
            Vec3 p1 = new Vec3(end.x + Math.cos(a1) * radius, end.y, end.z + Math.sin(a1) * radius);
            if (line.chain()) {
                Vec3 mid = p0.add(p1).scale(0.5);
                Vec3 dir = p1.subtract(p0);
                if (dir.lengthSqr() < 1.0E-8) {
                    continue;
                }
                renderBlock(pose, buffers, mid.subtract(origin), dir.normalize(), 0.55F,
                        Blocks.CHAIN.defaultBlockState(), light, (i & 1) * 90);
            } else {
                ropeBetween(pose, buffers, p0.subtract(origin), p1.subtract(origin), light, 0.0F, 0.4F, i);
            }
        }
    }

    private static Vec3 outgoing(Vec3 end, Vec3 towardLine) {
        Vec3 dir = towardLine.subtract(end);
        if (dir.lengthSqr() < 1.0E-6) {
            return new Vec3(0.0, -1.0, 0.0);
        }
        return dir.normalize();
    }

    private static void renderAnchor(PoseStack pose, MultiBufferSource buffers, Vec3 local, int light) {
        pose.pushPose();
        pose.translate(local.x, local.y, local.z);
        pose.scale(0.24F, 0.24F, 0.24F);
        pose.translate(-0.5, -0.5, -0.5);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(
                Blocks.POLISHED_ANDESITE.defaultBlockState(), pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    private static void renderLantern(PoseStack pose, MultiBufferSource buffers, ZiplineEntity line, Vec3 world, boolean soul) {
        double hang = 1.0;
        if (line.level() != null) {
            BlockPos column = BlockPos.containing(world.x, world.y - 0.25, world.z);
            for (int i = 0; i <= 2; i++) {
                BlockPos pos = column.below(i);
                var shape = line.level().getBlockState(pos).getCollisionShape(line.level(), pos);
                if (shape.isEmpty()) {
                    continue;
                }
                double top = pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
                double room = world.y - top;
                if (room < 1.05) {
                    hang = Math.max(0.32, room - 0.06);
                }
                break;
            }
        }
        var state = (soul ? Blocks.SOUL_LANTERN : Blocks.LANTERN).defaultBlockState()
                .setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true);
        pose.pushPose();
        pose.translate(world.x - line.position().x, world.y - line.position().y, world.z - line.position().z);
        pose.translate(-0.5, -hang, -0.5);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, buffers, 0x00F000F0, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    private static void renderBlock(PoseStack pose, MultiBufferSource buffers, Vec3 local, Vec3 dir,
                                    float scale, net.minecraft.world.level.block.state.BlockState state, int light, int spin) {
        pose.pushPose();
        pose.translate(local.x, local.y, local.z);
        Vector3f to = new Vector3f((float) dir.x, (float) dir.y, (float) dir.z);
        if (to.lengthSquared() > 1.0E-6F) {
            to.normalize();
            pose.mulPose(new Quaternionf().rotationTo(new Vector3f(0.0F, 1.0F, 0.0F), to));
        }
        if (spin != 0) {
            pose.mulPose(new Quaternionf().rotationY((float) Math.toRadians(spin)));
        }
        pose.scale(scale, scale, scale);
        pose.translate(-0.5, -0.5, -0.5);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    private static int anchorLight(ZiplineEntity line, Vec3 world) {
        int light = lightAt(line, world);
        int block = Math.max(LightTexture.block(light), 8);
        int sky = Math.max(LightTexture.sky(light), 10);
        return LightTexture.pack(block, sky);
    }

    private static void orientedBox(PoseStack.Pose entry, VertexConsumer consumer, int light,
                                    Vec3 center, Vec3 dir, float halfAlong, float halfThick, float v0, float v1) {
        Vec3 axis = dir.lengthSqr() < 1.0E-8 ? new Vec3(0.0, -1.0, 0.0) : dir.normalize();
        Vec3 ref = Math.abs(axis.y) > 0.92 ? new Vec3(1.0, 0.0, 0.0) : new Vec3(0.0, 1.0, 0.0);
        Vec3 sideN = ref.cross(axis);
        if (sideN.lengthSqr() < 1.0E-8) {
            return;
        }
        sideN = sideN.normalize();
        Vec3 upN = sideN.cross(axis).normalize();
        Vec3 s = sideN.scale(halfThick);
        Vec3 u = upN.scale(halfThick);
        Vec3 a = center.subtract(axis.scale(halfAlong));
        Vec3 b = center.add(axis.scale(halfAlong));
        quad(entry, consumer, light, a.add(s).add(u), b.add(s).add(u), b.subtract(s).add(u), a.subtract(s).add(u), v0, v1);
        quad(entry, consumer, light, a.subtract(s).subtract(u), b.subtract(s).subtract(u), b.add(s).subtract(u), a.add(s).subtract(u), v0, v1);
        quad(entry, consumer, light, a.subtract(s).add(u), b.subtract(s).add(u), b.subtract(s).subtract(u), a.subtract(s).subtract(u), v0, v1);
        quad(entry, consumer, light, a.add(s).subtract(u), b.add(s).subtract(u), b.add(s).add(u), a.add(s).add(u), v0, v1);
        quad(entry, consumer, light, a.add(s).add(u), a.subtract(s).add(u), a.subtract(s).subtract(u), a.add(s).subtract(u), 0.0F, 1.0F);
        quad(entry, consumer, light, b.subtract(s).add(u), b.add(s).add(u), b.add(s).subtract(u), b.subtract(s).subtract(u), 0.0F, 1.0F);
    }

    private static void quad(PoseStack.Pose entry, VertexConsumer consumer, int light,
                             Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float v0, float v1) {
        Matrix4f matrix = entry.pose();
        Matrix3f normal = entry.normal();
        vertex(consumer, matrix, normal, light, p0, 0.0F, v0);
        vertex(consumer, matrix, normal, light, p1, 0.0F, v1);
        vertex(consumer, matrix, normal, light, p2, 1.0F, v1);
        vertex(consumer, matrix, normal, light, p3, 1.0F, v0);
        vertex(consumer, matrix, normal, light, p0, 0.0F, v0);
        vertex(consumer, matrix, normal, light, p3, 1.0F, v0);
        vertex(consumer, matrix, normal, light, p2, 1.0F, v1);
        vertex(consumer, matrix, normal, light, p1, 0.0F, v1);
    }

    private static int lightAt(ZiplineEntity line, Vec3 world) {
        if (line.level() == null) {
            return 0x00F000F0;
        }
        return LevelRenderer.getLightColor(line.level(), BlockPos.containing(world));
    }

    private static void vertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normal, int light,
                               Vec3 pos, float u, float v) {
        consumer.vertex(matrix, (float) pos.x, (float) pos.y, (float) pos.z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(light)
                .normal(normal, 0.0F, 1.0F, 0.0F)
                .endVertex();
    }

    @Override
    public ResourceLocation getTextureLocation(ZiplineEntity line) {
        return ROPE;
    }
}
