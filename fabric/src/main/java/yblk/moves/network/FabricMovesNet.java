package yblk.moves.network;

import io.netty.buffer.Unpooled;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import yblk.moves.Constants;
import yblk.moves.parkour.ParkourInput;

public final class FabricMovesNet {

    public static final ResourceLocation INPUT = new ResourceLocation(Constants.MOD_ID, "input");
    public static final ResourceLocation STATE = new ResourceLocation(Constants.MOD_ID, "state");

    private FabricMovesNet() {
    }

    public static void registerServer() {
        ServerPlayNetworking.registerGlobalReceiver(INPUT, (server, player, handler, buf, response) -> {
            ParkourInput input = ParkourInput.read(buf);
            server.execute(() -> MovesNet.applyInput(player, input));
        });
        MovesNet.IMPL = new MovesNet.Impl() {
            @Override
            public void sendToServer(FriendlyByteBuf buf) {
            }

            @Override
            public void sendState(ServerPlayer target, FriendlyByteBuf buf) {
                ServerPlayNetworking.send(target, STATE, copy(buf));
                for (ServerPlayer watcher : PlayerLookup.tracking(target)) {
                    ServerPlayNetworking.send(watcher, STATE, copy(buf));
                }
            }
        };
    }

    public static void registerClient() {
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.registerGlobalReceiver(STATE, (client, handler, buf, response) -> {
            byte[] payload = new byte[buf.readableBytes()];
            buf.readBytes(payload);
            client.execute(() -> {
                try {
                    Class.forName("yblk.moves.client.ClientStateApplier")
                            .getMethod("apply", byte[].class)
                            .invoke(null, (Object) payload);
                } catch (ReflectiveOperationException exception) {
                    Constants.LOG.error("Failed to apply parkour state", exception);
                }
            });
        });
        MovesNet.Impl server = MovesNet.IMPL;
        MovesNet.IMPL = new MovesNet.Impl() {
            @Override
            public void sendToServer(FriendlyByteBuf buf) {
                net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(INPUT, buf);
            }

            @Override
            public void sendState(ServerPlayer target, FriendlyByteBuf buf) {
                if (server != null) {
                    server.sendState(target, buf);
                }
            }
        };
    }

    private static FriendlyByteBuf copy(FriendlyByteBuf buf) {
        return new FriendlyByteBuf(Unpooled.copiedBuffer(buf.nioBuffer()));
    }
}
