package yblk.moves.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import yblk.moves.entity.ZiplineEntity;
import yblk.moves.parkour.ParkourInput;
import yblk.moves.parkour.ParkourPlayer;

public final class MovesNet {

    public static final byte MSG_INPUT = 0;
    public static final byte MSG_STATE = 0;
    public static final byte MSG_SIGN_OPEN = 1;
    public static final byte MSG_SIGN_SAVE = 1;

    public interface Impl {
        void sendToServer(FriendlyByteBuf buf);

        void sendState(ServerPlayer target, FriendlyByteBuf buf);

        void sendToPlayer(ServerPlayer target, FriendlyByteBuf buf);
    }

    public static Impl IMPL;

    private MovesNet() {
    }

    public static void sendInput(ParkourInput input) {
        if (IMPL == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeByte(MSG_INPUT);
        input.write(buf);
        IMPL.sendToServer(buf);
    }

    public static void sendZiplineSign(int entityId, int index, String[] lines, boolean front) {
        if (IMPL == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeByte(MSG_SIGN_SAVE);
        buf.writeVarInt(entityId);
        buf.writeVarInt(index);
        buf.writeBoolean(front);
        for (int i = 0; i < 4; i++) {
            buf.writeUtf(i < lines.length && lines[i] != null ? lines[i] : "");
        }
        IMPL.sendToServer(buf);
    }

    public static void openZiplineSign(ServerPlayer player, int entityId, int index, String[] lines, boolean front) {
        if (IMPL == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeByte(MSG_SIGN_OPEN);
        buf.writeVarInt(entityId);
        buf.writeVarInt(index);
        buf.writeBoolean(front);
        for (int i = 0; i < 4; i++) {
            buf.writeUtf(i < lines.length && lines[i] != null ? lines[i] : "");
        }
        IMPL.sendToPlayer(player, buf);
    }

    public static void broadcastState(ServerPlayer player, ParkourPlayer data) {
        if (IMPL == null) {
            return;
        }
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer());
        buf.writeByte(MSG_STATE);
        buf.writeVarInt(player.getId());
        buf.writeFloat(data.stamina);
        buf.writeByte(data.mode.ordinal());
        buf.writeBoolean(data.prone);
        buf.writeByte(data.wallSide);
        IMPL.sendState(player, buf);
    }

    public static void handleServer(Player player, FriendlyByteBuf buf) {
        int type = buf.readUnsignedByte();
        if (type == MSG_INPUT) {
            applyInput(player, ParkourInput.read(buf));
            return;
        }
        if (type == MSG_SIGN_SAVE) {
            applySignSave(player, buf);
        }
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
        data.input.slide = input.slide;
        data.input.dodge = input.dodge;
        data.input.jumpCharge = input.jumpCharge;
        data.jumpCharge = input.jumpCharge;
    }

    private static void applySignSave(Player player, FriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        int index = buf.readVarInt();
        boolean front = buf.readBoolean();
        String[] lines = new String[4];
        for (int i = 0; i < 4; i++) {
            lines[i] = buf.readUtf(50);
        }
        if (!(player.level().getEntity(entityId) instanceof ZiplineEntity line)) {
            return;
        }
        if (player.distanceToSqr(line) > 64.0 * 64.0) {
            return;
        }
        line.setSignLines(index, lines, front);
    }
}
