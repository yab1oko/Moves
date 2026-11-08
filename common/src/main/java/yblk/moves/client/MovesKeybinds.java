package yblk.moves.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import yblk.moves.mixin.KeyMappingAccessor;

/**
 * Extra parkour keys. Prone/Slide default unbound so they never steal vanilla
 * Sprint/Sneak. Unbound → Ctrl+Shift chords in {@link ClientParkourInput}.
 *
 * <p>Grab defaults to RMB, same as vanilla Use. Forge tracks several KeyMappings
 * per physical key; Fabric/vanilla {@code MAP} keeps only one — so {@link KeyMapping#isDown()}
 * for Grab is often stuck false. {@link #isActive} polls GLFW as a fallback.
 */
public final class MovesKeybinds {

    public static final KeyMapping DODGE = new KeyMapping(
            "key.moves.dodge",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_LEFT_ALT,
            "key.categories.moves"
    );

    public static final KeyMapping GRAB = new KeyMapping(
            "key.moves.grab",
            InputConstants.Type.MOUSE,
            GLFW.GLFW_MOUSE_BUTTON_RIGHT,
            "key.categories.moves"
    );

    /** Unbound by default — Ctrl+Shift while standing. */
    public static final KeyMapping PRONE = new KeyMapping(
            "key.moves.prone",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            "key.categories.moves"
    );

    /** Unbound by default — Shift while sprinting (Ctrl held). */
    public static final KeyMapping SLIDE = new KeyMapping(
            "key.moves.slide",
            InputConstants.Type.KEYSYM,
            InputConstants.UNKNOWN.getValue(),
            "key.categories.moves"
    );

    private MovesKeybinds() {
    }

    public static InputConstants.Key boundKey(KeyMapping mapping) {
        try {
            var method = mapping.getClass().getMethod("getKey");
            Object key = method.invoke(mapping);
            if (key instanceof InputConstants.Key input) {
                return input;
            }
        } catch (ReflectiveOperationException ignored) {
        }
        return ((KeyMappingAccessor) mapping).moves$getKey();
    }

    /**
     * True while the bound key/mouse button is held, even if vanilla Use (or another
     * KeyMapping) owns the shared {@code MAP} slot for that physical input.
     */
    public static boolean isActive(KeyMapping mapping) {
        if (mapping.isDown()) {
            return true;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.screen != null || minecraft.getWindow() == null) {
            return false;
        }
        InputConstants.Key key = boundKey(mapping);
        if (key.getValue() == InputConstants.UNKNOWN.getValue()) {
            return false;
        }
        long window = minecraft.getWindow().getWindow();
        if (key.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, key.getValue()) == GLFW.GLFW_PRESS;
        }
        return InputConstants.isKeyDown(window, key.getValue());
    }

    public static boolean isBound(KeyMapping mapping) {
        return boundKey(mapping).getValue() != InputConstants.UNKNOWN.getValue();
    }

    public static boolean isCtrlKey(KeyMapping mapping) {
        int value = boundKey(mapping).getValue();
        return value == GLFW.GLFW_KEY_LEFT_CONTROL || value == GLFW.GLFW_KEY_RIGHT_CONTROL;
    }

    public static boolean isShiftKey(KeyMapping mapping) {
        int value = boundKey(mapping).getValue();
        return value == GLFW.GLFW_KEY_LEFT_SHIFT || value == GLFW.GLFW_KEY_RIGHT_SHIFT;
    }
}
