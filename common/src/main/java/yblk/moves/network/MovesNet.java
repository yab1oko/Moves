package yblk.moves.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import yblk.moves.parkour.ParkourInput;
import yblk.moves.parkour.ParkourPlayer;

public final class MovesNet {

    public interface Impl {
        void sendToServer(FriendlyByteBuf buf);

        void sendState(ServerPlayer target, FriendlyByteBuf buf);
    }

    public static Impl IMPL;

    private MovesNet() {
    }

    public static void sendInput(ParkourInput input) {
        if (IMPL == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        input.write(buf);
        IMPL.sendToServer(buf);
    }

    public static void broadcastState(ServerPlayer player, ParkourPlayer data) {
        if (IMPL == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeVarInt(player.getId());
        buf.writeFloat(data.stamina);
        buf.writeByte(data.mode.ordinal());
        buf.writeBoolean(data.prone);
        buf.writeByte(data.wallSide);
        IMPL.sendState(player, buf);
    }

    public static void applyInput(Player player, ParkourInput input) {
        ParkourPlayer data = ParkourPlayer.get(player);
        data.input.forward = input.forward;
        data.input.strafe = input.strafe;
        data.input.jump = input.jump;
        data.input.sneak = input.sneak;
        data.input.sprint = input.sprint;
        data.input.prone = input.prone;
        data.input.enhanced = input.enhanced;
        data.input.grab = input.grab;
        data.input.dodge = input.dodge;
        data.input.jumpCharge = input.jumpCharge;
        data.jumpCharge = input.jumpCharge;
        // enhanced is consumed once in ParkourController.fireChargedJump — do not pre-mark jumpKind.
    }
}
