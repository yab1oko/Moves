package yblk.moves;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.fml.common.Mod;

import yblk.moves.config.MovesCommands;
import yblk.moves.network.ForgeMovesNet;

@Mod(Constants.MOD_ID)
public class Moves {
    
    public Moves() {
        Constants.LOG.info("Hello Forge world!");
        CommonClass.init();
        MovesForgeContent.register();
        ForgeMovesNet.register();
        MinecraftForge.EVENT_BUS.addListener(this::onCommands);
    }

    private void onCommands(RegisterCommandsEvent event) {
        MovesCommands.register(event.getDispatcher());
    }
}
