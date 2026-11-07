package yblk.moves.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

/**
 * Extra parkour keys. Registered from Forge/Fabric client setup.
 */
public final class MovesKeybinds {

    public static final KeyMapping DODGE = new KeyMapping(
            "key.moves.dodge",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            "key.categories.moves"
    );

    private MovesKeybinds() {
    }
}
