package yblk.moves.client;

import dev.kosmx.playerAnim.api.firstPerson.FirstPersonConfiguration;
import dev.kosmx.playerAnim.api.firstPerson.FirstPersonMode;
import dev.kosmx.playerAnim.api.layered.IAnimation;
import dev.kosmx.playerAnim.api.layered.KeyframeAnimationPlayer;
import dev.kosmx.playerAnim.api.layered.ModifierLayer;
import dev.kosmx.playerAnim.api.layered.modifier.AbstractFadeModifier;
import dev.kosmx.playerAnim.api.layered.modifier.AdjustmentModifier;
import dev.kosmx.playerAnim.core.data.KeyframeAnimation;
import dev.kosmx.playerAnim.core.util.Ease;
import dev.kosmx.playerAnim.core.util.Vec3f;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationAccess;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationFactory;
import dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import yblk.moves.Constants;

import java.util.Optional;

public final class MovesAnimationHooks {

    private static final ResourceLocation LAYER = new ResourceLocation(Constants.MOD_ID, "parkour");
    private static final int FADE_IN = 14;
    private static final int FADE_OUT = 3;

    private MovesAnimationHooks() {
    }

    public static void register() {
        PlayerAnimationFactory.ANIMATION_DATA_FACTORY.registerFactory(LAYER, 1000, player -> {
            ModifierLayer<IAnimation> layer = new ModifierLayer<>();
            layer.addModifierBefore(lookModifier(player));
            return layer;
        });
        Constants.LOG.info("Forge Player Animator layer registered at priority 1000");
        MovesAnims.bind(new MovesAnims.Sink() {
            @Override
            public boolean play(Player player, ResourceLocation id) {
                ModifierLayer<IAnimation> layer = layerOf(player);
                KeyframeAnimation animation = PlayerAnimationRegistry.getAnimation(id);
                if (layer == null || animation == null) {
                    return false;
                }
                KeyframeAnimationPlayer playback = new KeyframeAnimationPlayer(animation)
                        .setFirstPersonMode(id.getPath().startsWith("dodge")
                                ? FirstPersonMode.NONE
                                : FirstPersonMode.THIRD_PERSON_MODEL)
                        .setFirstPersonConfiguration(new FirstPersonConfiguration(
                                !id.getPath().startsWith("dodge"),
                                !id.getPath().startsWith("dodge"),
                                !id.getPath().startsWith("dodge"),
                                false));
                int fade = id.getPath().startsWith("tool_") ? 2 : FADE_IN;
                layer.replaceAnimationWithFade(AbstractFadeModifier.standardFadeIn(fade, Ease.INOUTSINE), playback);
                return true;
            }

            @Override
            public void stop(Player player) {
                ModifierLayer<IAnimation> layer = layerOf(player);
                if (layer != null) {
                    layer.setAnimation(null);
                }
            }
        });
    }

    private static AdjustmentModifier lookModifier(AbstractClientPlayer player) {
        return new AdjustmentModifier(partName -> {
            ParkourLookPose.tick(player);
            ParkourLookPose.State pose = ParkourLookPose.of(player);
            if (pose.active < 0.01F) {
                return Optional.empty();
            }
            return switch (partName) {
                case "head" -> Optional.of(new AdjustmentModifier.PartModifier(
                        rot(pose.headPitchOut(), pose.headYawOut(), 0.0F),
                        Vec3f.ZERO
                ));
                case "body", "torso" -> Optional.of(new AdjustmentModifier.PartModifier(
                        rot(pose.bodyPitchOut(), pose.bodyYawOut(), 0.0F),
                        Vec3f.ZERO
                ));
                case "rightArm" -> Optional.of(new AdjustmentModifier.PartModifier(
                        rot(pose.rightArmPitchOut(), pose.rightArmYawOut(), 0.0F),
                        Vec3f.ZERO
                ));
                case "leftArm" -> Optional.of(new AdjustmentModifier.PartModifier(
                        rot(pose.leftArmPitchOut(), pose.leftArmYawOut(), 0.0F),
                        Vec3f.ZERO
                ));
                default -> Optional.empty();
            };
        });
    }

    private static Vec3f rot(float pitchDeg, float yawDeg, float rollDeg) {
        return new Vec3f(
                (float) Math.toRadians(pitchDeg),
                (float) Math.toRadians(yawDeg),
                (float) Math.toRadians(rollDeg)
        );
    }

    @SuppressWarnings("unchecked")
    private static ModifierLayer<IAnimation> layerOf(Player player) {
        if (!(player instanceof AbstractClientPlayer clientPlayer)) {
            return null;
        }
        Object data = PlayerAnimationAccess.getPlayerAssociatedData(clientPlayer).get(LAYER);
        if (data instanceof ModifierLayer<?> layer) {
            return (ModifierLayer<IAnimation>) layer;
        }
        return null;
    }
}
