package yblk.moves.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import yblk.moves.parkour.ParkourController;
import yblk.moves.parkour.ParkourMode;
import yblk.moves.parkour.ParkourPlayer;

public final class ClientStateApplier {

    private ClientStateApplier() {
    }

    public static void apply(byte[] payload) {
        FriendlyByteBuf buf = new FriendlyByteBuf(io.netty.buffer.Unpooled.wrappedBuffer(payload));
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        int id = buf.readVarInt();
        float stamina = buf.readFloat();
        int modeOrdinal = buf.readUnsignedByte();
        boolean prone = buf.readBoolean();
        int wallSide = buf.readByte();
        if (!(minecraft.level.getEntity(id) instanceof Player player)) {
            return;
        }
        ParkourPlayer data = ParkourPlayer.get(player);
        // Local player owns stamina/mode prediction — only mirror for other players.
        if (player == minecraft.player) {
            return;
        }
        data.stamina = stamina;
        ParkourMode[] modes = ParkourMode.values();
        if (modeOrdinal >= 0 && modeOrdinal < modes.length) {
            data.mode = modes[modeOrdinal];
        }
        data.prone = prone;
        data.wallSide = wallSide;
        String anim = ParkourController.animationFor(data);
        if (!anim.equals(data.anim)) {
            data.anim = anim;
            if (anim.isEmpty()) {
                MovesAnims.stop(player);
            } else {
                MovesAnims.play(player, anim);
            }
        }
    }
}
