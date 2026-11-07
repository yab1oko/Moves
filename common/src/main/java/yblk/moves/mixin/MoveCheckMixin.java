package yblk.moves.mixin;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import yblk.moves.parkour.ParkourPlayer;

/**
 * While a move is scripted on the server, accept the matching client position.
 * The server still decides the mode from blocks and stamina, not from this packet.
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MoveCheckMixin {

    @Shadow
    public net.minecraft.server.level.ServerPlayer player;

    @Shadow
    private double firstGoodX;

    @Shadow
    private double firstGoodY;

    @Shadow
    private double firstGoodZ;

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void moves$relax(ServerboundMovePlayerPacket packet, CallbackInfo ci) {
        if (!packet.hasPosition() || !ParkourPlayer.get(this.player).relaxMovementCheck()) {
            return;
        }
        this.firstGoodX = packet.getX(this.player.getX());
        this.firstGoodY = packet.getY(this.player.getY());
        this.firstGoodZ = packet.getZ(this.player.getZ());
    }
}
