package yblk.moves.client;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import yblk.moves.Constants;

import java.util.HashSet;
import java.util.Set;

/**
 * Bridge to Player Animator. The loader client binds a sink when that mod is installed.
 */
public final class MovesAnims {

    public interface Sink {
        boolean play(Player player, ResourceLocation id);

        void stop(Player player);
    }

    private static Sink sink;
    private static final Set<String> MISSING = new HashSet<>();

    private MovesAnims() {
    }

    public static void bind(Sink value) {
        sink = value;
    }

    public static boolean ready() {
        return sink != null;
    }

    public static boolean play(Player player, String path) {
        if (sink == null || path == null || path.isEmpty()) {
            return false;
        }
        boolean played = sink.play(player, new ResourceLocation(Constants.MOD_ID, path));
        if (!played && MISSING.add(path)) {
            Constants.LOG.warn("Player Animator did not start clip moves:{}", path);
        }
        return played;
    }

    public static void stop(Player player) {
        if (sink != null) {
            sink.stop(player);
        }
    }
}
