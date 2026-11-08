package yblk.moves.client;

import yblk.moves.Constants;
import yblk.moves.platform.Services;

/**
 * Loads animation hooks when Player Animator is present (installed or jar-in-jar).
 */
public final class MovesClient {

    private MovesClient() {
    }

    public static void start() {
        boolean listed = Services.PLATFORM.isModLoaded("playeranimator")
                || Services.PLATFORM.isModLoaded("player-animator");
        boolean classesPresent;
        try {
            Class.forName("dev.kosmx.playerAnim.minecraftApi.PlayerAnimationRegistry");
            classesPresent = true;
        } catch (ClassNotFoundException ignored) {
            classesPresent = false;
        }
        if (!listed && !classesPresent) {
            Constants.LOG.info("Player Animator is not installed; parkour poses are skipped.");
            return;
        }
        try {
            Class.forName("yblk.moves.client.MovesAnimationHooks").getMethod("register").invoke(null);
            Constants.LOG.info("Player Animator hooks registered (modListed={}, classes={})", listed, classesPresent);
        } catch (ReflectiveOperationException exception) {
            Constants.LOG.error("Could not register Player Animator hooks", exception);
        }
    }
}
