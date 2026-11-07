package yblk.moves;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import yblk.moves.client.MovesClient;
import yblk.moves.client.MovesKeybinds;
import yblk.moves.network.FabricMovesNet;

public class MovesClientMod implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        FabricMovesNet.registerClient();
        KeyBindingHelper.registerKeyBinding(MovesKeybinds.DODGE);
        KeyBindingHelper.registerKeyBinding(MovesKeybinds.GRAB);
        KeyBindingHelper.registerKeyBinding(MovesKeybinds.PRONE);
        KeyBindingHelper.registerKeyBinding(MovesKeybinds.SLIDE);
        MovesClient.start();
        net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry.register(
                yblk.moves.MovesContent.ZIPLINE, yblk.moves.client.ZiplineRenderer::new);
        net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents.AFTER_ENTITIES.register(context -> {
            yblk.moves.client.ZiplinePreview.render(context.matrixStack(), context.camera().getPosition());
            yblk.moves.client.PickCracks.render(context.matrixStack(), context.camera().getPosition());
        });
    }
}
