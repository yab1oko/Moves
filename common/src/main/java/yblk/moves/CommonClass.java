package yblk.moves;

import yblk.moves.config.MovesConfig;
import yblk.moves.platform.Services;

public class CommonClass {

    public static void init() {
        Constants.LOG.info("Moves init on {} ({})", Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName());
        MovesConfig.load();
    }
}
