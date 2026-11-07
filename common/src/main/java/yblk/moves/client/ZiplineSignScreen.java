package yblk.moves.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.HangingSignEditScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.entity.HangingSignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import yblk.moves.entity.ZiplineEntity;
import yblk.moves.network.MovesNet;

import java.lang.reflect.Field;

/**
 * Vanilla hanging-sign editor for one face (front or back) of a zipline sign.
 */
public class ZiplineSignScreen extends HangingSignEditScreen {

    private final int entityId;
    private final int hangingIndex;
    private final boolean front;

    public ZiplineSignScreen(int entityId, int hangingIndex, String[] lines, BlockState state, boolean front) {
        super(makeSign(state, lines, front), front, false);
        this.entityId = entityId;
        this.hangingIndex = hangingIndex;
        this.front = front;
    }

    private static HangingSignBlockEntity makeSign(BlockState state, String[] lines, boolean front) {
        Minecraft minecraft = Minecraft.getInstance();
        HangingSignBlockEntity be = new HangingSignBlockEntity(BlockPos.ZERO, state);
        if (minecraft.level != null) {
            be.setLevel(minecraft.level);
        }
        if (minecraft.player != null) {
            be.setAllowedPlayerEditor(minecraft.player.getUUID());
        }
        SignText base = be.getText(front);
        net.minecraft.network.chat.Component[] components = new net.minecraft.network.chat.Component[4];
        for (int i = 0; i < 4; i++) {
            String raw = lines != null && i < lines.length && lines[i] != null ? lines[i] : "";
            components[i] = net.minecraft.network.chat.Component.literal(raw);
        }
        be.setText(new SignText(components, components, base.getColor(), base.hasGlowingText()), front);
        return be;
    }

    @Override
    public void removed() {
        MovesNet.sendZiplineSign(this.entityId, this.hangingIndex, readMessages(), this.front);
    }

    @Override
    public void tick() {
        // Skip super — unbound BE would close the vanilla screen.
    }

    private String[] readMessages() {
        String[] lines = new String[]{"", "", "", ""};
        try {
            Field field = net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen.class
                    .getDeclaredField("messages");
            field.setAccessible(true);
            Object value = field.get(this);
            if (value instanceof String[] messages) {
                for (int i = 0; i < 4 && i < messages.length; i++) {
                    lines[i] = messages[i] != null ? messages[i] : "";
                }
            }
        } catch (ReflectiveOperationException ignored) {
            try {
                Field signField = net.minecraft.client.gui.screens.inventory.AbstractSignEditScreen.class
                        .getDeclaredField("sign");
                signField.setAccessible(true);
                if (signField.get(this) instanceof HangingSignBlockEntity be) {
                    for (int i = 0; i < 4; i++) {
                        lines[i] = be.getText(this.front).getMessage(i, false).getString();
                    }
                }
            } catch (ReflectiveOperationException ignored2) {
            }
        }
        return lines;
    }

    public static void open(int entityId, int index, String[] lines, boolean front) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        BlockState state = ZiplineEntity.signState(new CompoundTag());
        if (minecraft.level.getEntity(entityId) instanceof ZiplineEntity line) {
            var list = line.hangings();
            if (index >= 0 && index < list.size()) {
                state = ZiplineEntity.signState(list.getCompound(index));
            }
        }
        minecraft.setScreen(new ZiplineSignScreen(entityId, index, lines, state, front));
    }
}
