package yblk.moves.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;
import yblk.moves.Constants;
import yblk.moves.parkour.ParkourInput;

import java.util.function.Supplier;

public final class ForgeMovesNet {

    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Constants.MOD_ID, "main"),
            () -> VERSION,
            VERSION::equals,
            VERSION::equals
    );

    private ForgeMovesNet() {
    }

    public static void register() {
        CHANNEL.registerMessage(0, Payload.class, Payload::encode, Payload::decode, Payload::handle);
        MovesNet.IMPL = new MovesNet.Impl() {
            @Override
            public void sendToServer(FriendlyByteBuf buf) {
                CHANNEL.sendToServer(new Payload(false, toBytes(buf)));
            }

            @Override
            public void sendState(ServerPlayer target, FriendlyByteBuf buf) {
                CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> target), new Payload(true, toBytes(buf)));
            }
        };
    }

    private static byte[] toBytes(FriendlyByteBuf buf) {
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        return bytes;
    }

    public static final class Payload {
        private final boolean state;
        private final byte[] data;

        public Payload(boolean state, byte[] data) {
            this.state = state;
            this.data = data;
        }

        public static void encode(Payload msg, FriendlyByteBuf buf) {
            buf.writeBoolean(msg.state);
            buf.writeByteArray(msg.data);
        }

        public static Payload decode(FriendlyByteBuf buf) {
            return new Payload(buf.readBoolean(), buf.readByteArray());
        }

        public static void handle(Payload msg, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                if (context.getDirection() == NetworkDirection.PLAY_TO_SERVER) {
                    ServerPlayer sender = context.getSender();
                    if (sender != null) {
                        MovesNet.applyInput(sender, ParkourInput.read(new FriendlyByteBuf(Unpooled.wrappedBuffer(msg.data))));
                    }
                    return;
                }
                try {
                    Class.forName("yblk.moves.client.ClientStateApplier")
                            .getMethod("apply", byte[].class)
                            .invoke(null, (Object) msg.data);
                } catch (ReflectiveOperationException exception) {
                    Constants.LOG.error("Failed to apply parkour state", exception);
                }
            });
            context.setPacketHandled(true);
        }
    }
}
