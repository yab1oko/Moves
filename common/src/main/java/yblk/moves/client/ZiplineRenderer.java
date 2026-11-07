package yblk.moves.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.WallBannerBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import yblk.moves.Constants;
import yblk.moves.entity.ZiplineEntity;

/**
 * Chain links are chain-block models. Rope is a textured cord. Fence wraps are a
 * continuous ring the cable meets in the same plane.
 */
public class ZiplineRenderer extends EntityRenderer<ZiplineEntity> {

    private static final ResourceLocation ROPE = new ResourceLocation(Constants.MOD_ID, "textures/entity/zipline_rope.png");
    private static final ResourceLocation ROPE_DARK = new ResourceLocation(Constants.MOD_ID, "textures/entity/zipline_rope_dark.png");
    private static final float ROPE_TILES_PER_BLOCK = 2.75F;
    private static final float ROPE_HALF_THICK = 0.036F;
    private static final double ROPE_WRAP_RADIUS = 0.155;
    private static final double CHAIN_WRAP_RADIUS = 0.24;

    public ZiplineRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public void render(ZiplineEntity line, float yaw, float partial, PoseStack pose, MultiBufferSource buffers, int packedLight) {
        Vec3 origin = line.position();
        double radius = wrapRadius(line);
        boolean postA = postEnd(line, line.endA());
        boolean postB = postEnd(line, line.endB());
        // Force cable ends onto the wrap ring so the joint never floats.
        Vec3 start = postA ? ringMeet(line, true, radius) : line.point(0.0);
        Vec3 end = postB ? ringMeet(line, false, radius) : line.point(1.0);
        int count = steps(start, end, line.chain());
        Vec3 previous = start;
        float traveled = 0.0F;
        for (int i = 0; i <= count; i++) {
            double t = i / (double) count;
            Vec3 curve = line.point(t);
            Vec3 point;
            if (i == 0) {
                point = start;
            } else if (i == count) {
                point = end;
            } else if (postA && t < 0.08) {
                point = start.lerp(curve, t / 0.08);
            } else if (postB && t > 0.92) {
                point = curve.lerp(end, (t - 0.92) / 0.08);
            } else {
                point = curve;
            }
            if (!line.chain() && i > 0) {
                float len = (float) previous.distanceTo(point);
                ropeBetween(pose, buffers, previous.subtract(origin), point.subtract(origin),
                        lightAt(line, point), traveled, traveled + len, ropeTex(line));
                traveled += len;
            } else if (line.chain() && i > 0) {
                // Links sit on the segment — length ≈ segment so faces don't overlap (Z-fight).
                Vec3 mid = previous.add(point).scale(0.5);
                Vec3 dir = point.subtract(previous);
                if (dir.lengthSqr() > 1.0E-8) {
                    float seg = (float) dir.length();
                    float scale = Mth.clamp(seg * 0.96F, 0.32F, 0.58F);
                    linkSized(pose, buffers, mid.subtract(origin), dir.normalize(), scale,
                            lightAt(line, mid), (i & 1) * 90);
                }
            }
            previous = point;
        }
        renderEnd(pose, buffers, line, line.endA(), origin, radius);
        renderEnd(pose, buffers, line, line.endB(), origin, radius);
        ListTag hangings = line.hangings();
        for (int i = 0; i < hangings.size(); i++) {
            renderHanging(pose, buffers, line, hangings.getCompound(i), partial);
        }
        super.render(line, yaw, partial, pose, buffers, packedLight);
    }

    public static int steps(Vec3 a, Vec3 b, boolean chain) {
        double length = a.distanceTo(b);
        // Match chain step to link scale (~0.48) so neighbors meet without stacking faces.
        return Math.max(4, (int) (length / (chain ? 0.48 : 0.22)));
    }

    public static double wrapRadius(ZiplineEntity line) {
        return line.chain() ? CHAIN_WRAP_RADIUS : ROPE_WRAP_RADIUS;
    }

    /**
     * Meet point sits slightly inside the wrap ring so the first/last chain link
     * overlaps the knot instead of stopping short of it.
     */
    private static Vec3 ringMeet(ZiplineEntity line, boolean fromA, double radius) {
        Vec3 end = fromA ? line.endA() : line.endB();
        Vec3 toward = line.point(fromA ? 0.08 : 0.92).subtract(end);
        Vec3 flat = new Vec3(toward.x, 0.0, toward.z);
        if (flat.lengthSqr() < 1.0E-8) {
            flat = toward;
        }
        if (flat.lengthSqr() < 1.0E-8) {
            flat = new Vec3(1.0, 0.0, 0.0);
        }
        // Slightly inside the ring so the joint meets without burying faces (Z-fight).
        return end.add(flat.normalize().scale(radius * 0.90));
    }

    public static void link(PoseStack pose, MultiBufferSource buffers, Vec3 local, Vec3 dir, boolean chain, int light) {
        link(pose, buffers, local, dir, chain, light, 0);
    }

    public static void link(PoseStack pose, MultiBufferSource buffers, Vec3 local, Vec3 dir, boolean chain, int light, int spin) {
        if (chain) {
            linkSized(pose, buffers, local, dir, 0.56F, light, spin);
            return;
        }
        ropeBetween(pose, buffers, local, local.add(dir.scale(0.18)), light, 0.0F, 0.18F);
    }

    private static void linkSized(PoseStack pose, MultiBufferSource buffers, Vec3 local, Vec3 dir, float scale, int light, int spin) {
        renderBlock(pose, buffers, local, dir, scale, Blocks.CHAIN.defaultBlockState(), light, spin);
    }

    public static ResourceLocation ropeTex(ZiplineEntity line) {
        return line.darkRope() ? ROPE_DARK : ROPE;
    }

    public static ResourceLocation ropeTex(boolean dark) {
        return dark ? ROPE_DARK : ROPE;
    }

    public static void ropeBetween(PoseStack pose, MultiBufferSource buffers, Vec3 a, Vec3 b, int light) {
        ropeBetween(pose, buffers, a, b, light, 0.0F, (float) a.distanceTo(b), ROPE);
    }

    public static void ropeBetween(PoseStack pose, MultiBufferSource buffers, Vec3 a, Vec3 b, int light,
                                    ResourceLocation tex) {
        ropeBetween(pose, buffers, a, b, light, 0.0F, (float) a.distanceTo(b), tex);
    }

    public static void ropeBetween(PoseStack pose, MultiBufferSource buffers, Vec3 a, Vec3 b, int light,
                                    float from, float to) {
        ropeBetween(pose, buffers, a, b, light, from, to, ROPE);
    }

    public static void ropeBetween(PoseStack pose, MultiBufferSource buffers, Vec3 a, Vec3 b, int light,
                                    float from, float to, ResourceLocation tex) {
        Vec3 delta = b.subtract(a);
        double len = delta.length();
        if (len < 1.0E-4) {
            return;
        }
        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(tex));
        orientedBox(pose.last(), consumer, light, a.add(delta.scale(0.5)), delta,
                (float) (len * 0.5), ROPE_HALF_THICK, from * ROPE_TILES_PER_BLOCK, to * ROPE_TILES_PER_BLOCK);
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

    private static void renderEnd(PoseStack pose, MultiBufferSource buffers, ZiplineEntity line, Vec3 end,
                                  Vec3 origin, double radius) {
        if (postEnd(line, end)) {
            renderWrap(pose, buffers, line, end, origin, lightAt(line, end), radius);
            return;
        }
        Vec3 out = outgoing(end, line.point(end.distanceToSqr(line.endA()) < end.distanceToSqr(line.endB()) ? 0.04 : 0.96));
        renderAnchor(pose, buffers, end.subtract(origin).add(out.scale(0.04)), anchorLight(line, end));
    }

    /** Single simple ring — overlaps so joints read as one knot. */
    private static void renderWrap(PoseStack pose, MultiBufferSource buffers, ZiplineEntity line, Vec3 end, Vec3 origin,
                                   int light, double radius) {
        if (line.chain()) {
            int pieces = 6;
            for (int i = 0; i < pieces; i++) {
                double a0 = i * Math.PI * 2.0 / pieces;
                double a1 = (i + 1) * Math.PI * 2.0 / pieces;
                // Tiny overlap only — enough to close gaps, not enough to Z-fight.
                Vec3 p0 = new Vec3(end.x + Math.cos(a0 - 0.04) * radius, end.y, end.z + Math.sin(a0 - 0.04) * radius);
                Vec3 p1 = new Vec3(end.x + Math.cos(a1 + 0.04) * radius, end.y, end.z + Math.sin(a1 + 0.04) * radius);
                Vec3 mid = p0.add(p1).scale(0.5);
                Vec3 dir = p1.subtract(p0);
                if (dir.lengthSqr() < 1.0E-8) {
                    continue;
                }
                renderBlock(pose, buffers, mid.subtract(origin), dir.normalize(), 0.42F,
                        Blocks.CHAIN.defaultBlockState(), light, (i & 1) * 90);
            }
            return;
        }
        int pieces = 10;
        for (int i = 0; i < pieces; i++) {
            double a0 = (i - 0.06) * Math.PI * 2.0 / pieces;
            double a1 = (i + 1.06) * Math.PI * 2.0 / pieces;
            Vec3 p0 = new Vec3(end.x + Math.cos(a0) * radius, end.y, end.z + Math.sin(a0) * radius);
            Vec3 p1 = new Vec3(end.x + Math.cos(a1) * radius, end.y, end.z + Math.sin(a1) * radius);
            Vec3 delta = p1.subtract(p0);
            double len = delta.length();
            if (len < 1.0E-4) {
                continue;
            }
            VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(ropeTex(line)));
            orientedBox(pose.last(), consumer, light, p0.add(delta.scale(0.5)).subtract(origin), delta,
                    (float) (len * 0.5), 0.040F, 0.0F, (float) len * ROPE_TILES_PER_BLOCK);
        }
    }

    private static void renderHanging(PoseStack pose, MultiBufferSource buffers, ZiplineEntity line, CompoundTag one,
                                      float partial) {
        float t = one.getFloat("t");
        Vec3 world = line.point(t);
        String kind = ZiplineEntity.hangingKind(one);
        int light = lightAt(line, world);
        switch (kind) {
            case "soul" -> renderLantern(pose, buffers, line, world, true, partial);
            case "lantern" -> renderLantern(pose, buffers, line, world, false, partial);
            case "banner" -> renderBanner(pose, buffers, line, world, one, light);
            case "sign" -> renderSign(pose, buffers, line, world, one, light, partial);
            default -> {
            }
        }
    }

    /** Soft pendulum sway from the cable attachment. Phase differs per world point. */
    private static void applySway(PoseStack pose, Vec3 world, float partial, float ampDeg) {
        Minecraft mc = Minecraft.getInstance();
        float time = (mc.level != null ? mc.level.getGameTime() : 0) + partial;
        float phase = (float) ((world.x * 13.7 + world.z * 9.1 + world.y * 3.3) * 10.0);
        float a = Mth.sin(time * 0.085F + phase) * ampDeg;
        float b = Mth.sin(time * 0.063F + phase * 1.3F) * ampDeg * 0.55F;
        pose.mulPose(Axis.ZP.rotationDegrees(a));
        pose.mulPose(Axis.XP.rotationDegrees(b));
    }

    /**
     * Wall-banner model (no pole). Always hangs vertically under the cable point;
     * yaw follows the cable (works on sagging / angled lines, not just cardinals).
     */
    private static void renderBanner(PoseStack pose, MultiBufferSource buffers, ZiplineEntity line, Vec3 world,
                                     CompoundTag one, int light) {
        ItemStack stack = ItemStack.of(one.getCompound("it"));
        if (stack.isEmpty() || !(stack.getItem() instanceof BannerItem banner)) {
            return;
        }
        // FACING locked to SOUTH — continuous yaw is applied in the pose instead.
        BlockState wall = wallBannerBlock(banner.getColor()).defaultBlockState()
                .setValue(WallBannerBlock.FACING, Direction.SOUTH);
        BannerBlockEntity be = new BannerBlockEntity(BlockPos.containing(world), wall);
        if (line.level() != null) {
            be.setLevel(line.level());
        }
        be.fromItem(stack, banner.getColor());
        float yaw = bannerYaw(line, one);
        pose.pushPose();
        pose.translate(world.x - line.position().x, world.y - line.position().y, world.z - line.position().z);
        // BER shifts Y by ≈ −0.48; −0.78 puts the crossbar just under the cable.
        pose.translate(0.0, -0.78, 0.0);
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        // Cancel WallBanner local −Z inset so cloth is centered under the cable.
        pose.translate(0.0, 0.0, 0.4375);
        pose.translate(-0.5, 0.0, -0.5);
        renderBlockEntity(be, pose, buffers, light);
        pose.popPose();
    }

    /** Yaw so the banner faces the preferred side, perpendicular to the cable's flat tangent. */
    private static float bannerYaw(ZiplineEntity line, CompoundTag one) {
        Vec3 tan = line.tangent(one.getFloat("t"));
        Vec3 flat = new Vec3(tan.x, 0.0, tan.z);
        if (flat.lengthSqr() < 1.0E-6) {
            flat = new Vec3(1.0, 0.0, 0.0);
        }
        flat = flat.normalize();
        Vec3 side = new Vec3(-flat.z, 0.0, flat.x);
        Direction prefer = Direction.SOUTH;
        if (one.contains("face")) {
            Direction face = Direction.from3DDataValue(one.getByte("face"));
            if (face.getAxis().isHorizontal()) {
                prefer = face;
            }
        }
        Vec3 want = new Vec3(prefer.getStepX(), 0.0, prefer.getStepZ());
        if (side.dot(want) < 0.0) {
            side = side.scale(-1.0);
        }
        return (float) Math.toDegrees(Math.atan2(-side.x, side.z));
    }

    private static net.minecraft.world.level.block.Block wallBannerBlock(DyeColor color) {
        return switch (color) {
            case ORANGE -> Blocks.ORANGE_WALL_BANNER;
            case MAGENTA -> Blocks.MAGENTA_WALL_BANNER;
            case LIGHT_BLUE -> Blocks.LIGHT_BLUE_WALL_BANNER;
            case YELLOW -> Blocks.YELLOW_WALL_BANNER;
            case LIME -> Blocks.LIME_WALL_BANNER;
            case PINK -> Blocks.PINK_WALL_BANNER;
            case GRAY -> Blocks.GRAY_WALL_BANNER;
            case LIGHT_GRAY -> Blocks.LIGHT_GRAY_WALL_BANNER;
            case CYAN -> Blocks.CYAN_WALL_BANNER;
            case PURPLE -> Blocks.PURPLE_WALL_BANNER;
            case BLUE -> Blocks.BLUE_WALL_BANNER;
            case BROWN -> Blocks.BROWN_WALL_BANNER;
            case GREEN -> Blocks.GREEN_WALL_BANNER;
            case RED -> Blocks.RED_WALL_BANNER;
            case BLACK -> Blocks.BLACK_WALL_BANNER;
            default -> Blocks.WHITE_WALL_BANNER;
        };
    }

    /** Ceiling hanging-sign — yaw from NBT applied in pose (state ROTATION forced 0). */
    private static void renderSign(PoseStack pose, MultiBufferSource buffers, ZiplineEntity line, Vec3 world,
                                   CompoundTag one, int light, float partial) {
        BlockState state = ZiplineEntity.signState(one);
        if (state.hasProperty(net.minecraft.world.level.block.CeilingHangingSignBlock.ROTATION)) {
            state = state.setValue(net.minecraft.world.level.block.CeilingHangingSignBlock.ROTATION, 0);
        }
        HangingSignBlockEntity be = new HangingSignBlockEntity(BlockPos.containing(world), state);
        if (line.level() != null) {
            be.setLevel(line.level());
        }
        applySignText(be, one);
        int rot = one.contains("rot") ? Mth.clamp(one.getInt("rot"), 0, 15) : 0;
        float yRot = rot * 22.5F;
        pose.pushPose();
        pose.translate(world.x - line.position().x, world.y - line.position().y + 0.02, world.z - line.position().z);
        pose.mulPose(Axis.YP.rotationDegrees(-yRot));
        applySway(pose, world, partial, 2.8F);
        pose.translate(-0.5, -1.0, -0.5);
        renderBlockEntity(be, pose, buffers, light);
        pose.popPose();
    }

    private static void applySignText(HangingSignBlockEntity be, CompoundTag one) {
        SignText frontBase = be.getText(true);
        SignText backBase = be.getText(false);
        net.minecraft.network.chat.Component[] front = new net.minecraft.network.chat.Component[4];
        net.minecraft.network.chat.Component[] back = new net.minecraft.network.chat.Component[4];
        for (int i = 0; i < 4; i++) {
            String f = one.contains("l" + i) ? one.getString("l" + i) : "";
            String b = one.contains("b" + i) ? one.getString("b" + i) : "";
            front[i] = net.minecraft.network.chat.Component.literal(f);
            back[i] = net.minecraft.network.chat.Component.literal(b);
        }
        be.setText(new SignText(front, front, frontBase.getColor(), frontBase.hasGlowingText()), true);
        be.setText(new SignText(back, back, backBase.getColor(), backBase.hasGlowingText()), false);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void renderBlockEntity(BlockEntity be, PoseStack pose, MultiBufferSource buffers, int light) {
        BlockEntityRenderer renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(be);
        if (renderer != null) {
            renderer.render(be, 0.0F, pose, buffers, light, OverlayTexture.NO_OVERLAY);
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

    private static void renderLantern(PoseStack pose, MultiBufferSource buffers, ZiplineEntity line, Vec3 world,
                                      boolean soul, float partial) {
        // Hanging lantern: top of the block model is the hook — pin that to the cable.
        double hang = 1.0;
        var state = (soul ? Blocks.SOUL_LANTERN : Blocks.LANTERN).defaultBlockState()
                .setValue(net.minecraft.world.level.block.LanternBlock.HANGING, true);
        pose.pushPose();
        pose.translate(world.x - line.position().x, world.y - line.position().y, world.z - line.position().z);
        applySway(pose, world, partial, 3.6F);
        pose.translate(-0.5, -hang, -0.5);
        Minecraft.getInstance().getBlockRenderer().renderSingleBlock(state, pose, buffers, 0x00F000F0, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    private static void renderBlock(PoseStack pose, MultiBufferSource buffers, Vec3 local, Vec3 dir,
                                    float scale, BlockState state, int light, int spin) {
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
                                    Vec3 center, Vec3 dir, float halfAlong, float halfThick, float u0, float u1) {
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
        quad(entry, consumer, light, a.add(s).add(u), b.add(s).add(u), b.subtract(s).add(u), a.subtract(s).add(u), u0, u1, 0.0F, 1.0F);
        quad(entry, consumer, light, a.subtract(s).subtract(u), b.subtract(s).subtract(u), b.add(s).subtract(u), a.add(s).subtract(u), u0, u1, 0.0F, 1.0F);
        quad(entry, consumer, light, a.subtract(s).add(u), b.subtract(s).add(u), b.subtract(s).subtract(u), a.subtract(s).subtract(u), u0, u1, 0.0F, 1.0F);
        quad(entry, consumer, light, a.add(s).subtract(u), b.add(s).subtract(u), b.add(s).add(u), a.add(s).add(u), u0, u1, 0.0F, 1.0F);
        quad(entry, consumer, light, a.add(s).add(u), a.subtract(s).add(u), a.subtract(s).subtract(u), a.add(s).subtract(u), u0, u0, 0.0F, 1.0F);
        quad(entry, consumer, light, b.subtract(s).add(u), b.add(s).add(u), b.add(s).subtract(u), b.subtract(s).subtract(u), u1, u1, 0.0F, 1.0F);
    }

    private static void quad(PoseStack.Pose entry, VertexConsumer consumer, int light,
                             Vec3 p0, Vec3 p1, Vec3 p2, Vec3 p3, float u0, float u1, float v0, float v1) {
        Matrix4f matrix = entry.pose();
        Matrix3f normal = entry.normal();
        vertex(consumer, matrix, normal, light, p0, u0, v0);
        vertex(consumer, matrix, normal, light, p1, u1, v0);
        vertex(consumer, matrix, normal, light, p2, u1, v1);
        vertex(consumer, matrix, normal, light, p3, u0, v1);
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
        return ropeTex(line);
    }
}
