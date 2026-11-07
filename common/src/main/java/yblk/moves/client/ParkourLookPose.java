package yblk.moves.client;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;
import yblk.moves.parkour.ParkourController;
import yblk.moves.parkour.ParkourMode;
import yblk.moves.parkour.ParkourPlayer;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Per-player smoothed look-around offsets for grab poses (ledge / chain / ceiling).
 * Applied client-side via Player Animator {@code AdjustmentModifier}.
 */
public final class ParkourLookPose {

    private static final Map<Player, State> STATES = new WeakHashMap<>();

    private ParkourLookPose() {
    }

    public static State of(Player player) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(player, key -> new State());
        }
    }

    public static void tick(Player player) {
        of(player).update(player);
    }

    public static final class State {
        public float active;
        public float headYaw;
        public float headPitch;
        public float bodyYaw;
        public float bodyPitch;
        public float rightArmPitch;
        public float leftArmPitch;
        public float rightArmYaw;
        public float leftArmYaw;

        private void update(Player player) {
            ParkourPlayer data = ParkourPlayer.get(player);
            float tActive = 0.0F;
            float tHeadYaw = 0.0F;
            float tHeadPitch = 0.0F;
            float tBodyYaw = 0.0F;
            float tBodyPitch = 0.0F;
            float tRAPitch = 0.0F;
            float tLAPitch = 0.0F;
            float tRAYaw = 0.0F;
            float tLAYaw = 0.0F;

            boolean moving = Math.abs(data.input.forward) > 0.15F || Math.abs(data.input.strafe) > 0.15F;

            if (data.mode == ParkourMode.CLIMB && data.toolClimb) {
                // Tool climb owns the head. Any leftover look lerp made it shudder.
                this.active = 0.0F;
                this.headYaw = this.headPitch = this.bodyYaw = this.bodyPitch = 0.0F;
                this.rightArmPitch = this.leftArmPitch = this.rightArmYaw = this.leftArmYaw = 0.0F;
                return;
            } else if (data.mode == ParkourMode.LEDGE || data.mode == ParkourMode.CLIMB) {
                tActive = moving ? 0.45F : 1.0F;
                float body = ParkourController.lockedBodyYaw(player);
                if (!Float.isNaN(body)) {
                    float delta = Mth.wrapDegrees(player.getYRot() - body);
                    tHeadYaw = Mth.clamp(delta, -90.0F, 90.0F);
                    tHeadPitch = Mth.clamp(player.getXRot() * 0.35F, -35.0F, 40.0F);
                    tBodyYaw = tHeadYaw * (data.mode == ParkourMode.CLIMB ? 0.18F : 0.28F);
                    if (data.mode == ParkourMode.CLIMB) {
                        tBodyPitch = 0.0F;
                    } else {
                        tBodyPitch = 4.0F;
                    }
                    if (data.mode != ParkourMode.CLIMB) {
                        float bias = tHeadYaw / 90.0F;
                        if (bias > 0.12F) {
                            tRAPitch = bias * 18.0F;
                            tRAYaw = bias * 14.0F;
                        } else if (bias < -0.12F) {
                            float b = -bias;
                            tLAPitch = b * 18.0F;
                            tLAYaw = -b * 14.0F;
                        }
                    }
                }
            } else if (data.mode == ParkourMode.HANG) {
                tActive = moving ? 0.35F : 1.0F;
                float body = player.yBodyRot;
                float delta = Mth.wrapDegrees(player.getYRot() - body);
                tHeadYaw = Mth.clamp(delta, -90.0F, 90.0F);
                tHeadPitch = Mth.clamp(player.getXRot() * 0.45F, -50.0F, 55.0F);
                tBodyYaw = tHeadYaw * 0.25F;
                tBodyPitch = 2.0F;
                float bias = tHeadYaw / 90.0F;
                if (bias > 0.15F) {
                    tRAPitch = bias * 12.0F;
                    tRAYaw = bias * 10.0F;
                } else if (bias < -0.15F) {
                    float b = -bias;
                    tLAPitch = b * 12.0F;
                    tLAYaw = -b * 10.0F;
                }
            } else if (data.mode == ParkourMode.PULLUP) {
                tActive = 0.25F;
                tHeadPitch = Mth.clamp(player.getXRot() * 0.2F, -20.0F, 25.0F);
            } else if (data.mode == ParkourMode.DODGE) {
                tActive = 1.0F;
                tHeadYaw = Mth.wrapDegrees(player.getYRot() - player.yBodyRot);
                tHeadPitch = player.getXRot();
            }

            boolean posing = data.mode == ParkourMode.LEDGE || data.mode == ParkourMode.CLIMB
                    || data.mode == ParkourMode.HANG || data.mode == ParkourMode.PULLUP
                    || data.mode == ParkourMode.DODGE;
            if (!posing) {
                this.active = 0.0F;
                this.headYaw = this.headPitch = this.bodyYaw = this.bodyPitch = 0.0F;
                this.rightArmPitch = this.leftArmPitch = this.rightArmYaw = this.leftArmYaw = 0.0F;
                return;
            }
            float s = data.mode == ParkourMode.DODGE ? 1.0F : 0.20F;
            this.active = Mth.lerp(s, this.active, tActive);
            this.headYaw = Mth.lerp(s, this.headYaw, tHeadYaw);
            this.headPitch = Mth.lerp(s, this.headPitch, tHeadPitch);
            this.bodyYaw = Mth.lerp(s, this.bodyYaw, tBodyYaw);
            this.bodyPitch = Mth.lerp(s, this.bodyPitch, tBodyPitch);
            this.rightArmPitch = Mth.lerp(s, this.rightArmPitch, tRAPitch);
            this.leftArmPitch = Mth.lerp(s, this.leftArmPitch, tLAPitch);
            this.rightArmYaw = Mth.lerp(s, this.rightArmYaw, tRAYaw);
            this.leftArmYaw = Mth.lerp(s, this.leftArmYaw, tLAYaw);

            if (this.active < 0.01F && tActive < 0.01F) {
                this.headYaw = this.headPitch = this.bodyYaw = this.bodyPitch = 0.0F;
                this.rightArmPitch = this.leftArmPitch = this.rightArmYaw = this.leftArmYaw = 0.0F;
            }
        }

        public float headYawOut() {
            return this.headYaw * this.active;
        }

        public float headPitchOut() {
            return this.headPitch * this.active;
        }

        public float bodyYawOut() {
            return this.bodyYaw * this.active;
        }

        public float bodyPitchOut() {
            return this.bodyPitch * this.active;
        }

        public float rightArmPitchOut() {
            return this.rightArmPitch * this.active;
        }

        public float leftArmPitchOut() {
            return this.leftArmPitch * this.active;
        }

        public float rightArmYawOut() {
            return this.rightArmYaw * this.active;
        }

        public float leftArmYawOut() {
            return this.leftArmYaw * this.active;
        }
    }
}
