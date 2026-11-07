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

    /** Default RMB — same as vanilla Use. Rebindable in Controls. */
    public static final KeyMapping GRAB = new KeyMapping(
            "key.moves.grab",
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_RIGHT,
            "key.categories.moves"
    );

    public static final KeyMapping PRONE = new KeyMapping(
            "key.moves.prone",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_C,
            "key.categories.moves"
    );

    public static final KeyMapping SLIDE = new KeyMapping(
            "key.moves.slide",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_X,
            "key.categories.moves"
    );

    private MovesKeybinds() {
    }
}
