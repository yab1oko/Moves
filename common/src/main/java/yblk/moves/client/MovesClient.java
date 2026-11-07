package yblk.moves.client;

import yblk.moves.Constants;
import yblk.moves.platform.Services;

/**
 * Loads animation hooks only when Player Animator is actually installed.
 */
public final class MovesClient {

    private MovesClient() {
    }

    public static void start() {
        // Fabric uses id "player-animator", Forge uses "playeranimator".
        if (!Services.PLATFORM.isModLoaded("playeranimator")
                && !Services.PLATFORM.isModLoaded("player-animator")) {
            Constants.LOG.info("Player Animator is not installed; parkour poses are skipped.");
            return;
        }
        try {
            Class.forName("yblk.moves.client.MovesAnimationHooks").getMethod("register").invoke(null);
            Constants.LOG.info("Player Animator hooks registered.");
        } catch (ReflectiveOperationException exception) {
            Constants.LOG.error("Could not register Player Animator hooks", exception);
        }
    }
}
