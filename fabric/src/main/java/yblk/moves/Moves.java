package yblk.moves;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import yblk.moves.config.MovesCommands;
import yblk.moves.network.FabricMovesNet;

public class Moves implements ModInitializer {
    
    @Override
    public void onInitialize() {
        Constants.LOG.info("Hello Fabric world!");
        CommonClass.init();
        MovesFabricContent.register();
        FabricMovesNet.registerServer();
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) ->
                MovesCommands.register(dispatcher));
    }
}
