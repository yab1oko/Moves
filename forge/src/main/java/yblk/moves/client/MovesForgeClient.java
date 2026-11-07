package yblk.moves.client;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import yblk.moves.Constants;

@Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class MovesForgeClient {

    private MovesForgeClient() {
    }

    @SubscribeEvent
    public static void setup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> {
            MovesClient.start();
            net.minecraftforge.fml.ModLoadingContext.get().registerExtensionPoint(
                    net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory(
                            (minecraft, parent) -> new MovesConfigScreen(parent)));
        });
    }

    @SubscribeEvent
    public static void renderers(net.minecraftforge.client.event.EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(yblk.moves.MovesForgeContent.ZIPLINE.get(), yblk.moves.client.ZiplineRenderer::new);
    }

    @SubscribeEvent
    public static void keys(RegisterKeyMappingsEvent event) {
        event.register(MovesKeybinds.DODGE);
        event.register(MovesKeybinds.GRAB);
        event.register(MovesKeybinds.PRONE);
        event.register(MovesKeybinds.SLIDE);
    }

    @Mod.EventBusSubscriber(modid = Constants.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class Hud {
        private Hud() {
        }

        @SubscribeEvent(priority = EventPriority.LOW)
        public static void afterFood(RenderGuiOverlayEvent.Post event) {
            if (!event.getOverlay().id().equals(VanillaGuiOverlay.FOOD_LEVEL.id())) {
                return;
            }
            // Ensure we can draw even if Gui mixin beginFrame was skipped this path.
            StaminaHud.beginFrame();
            StaminaHud.render(event.getGuiGraphics());
        }

        @SubscribeEvent
        public static void ziplinePreview(net.minecraftforge.client.event.RenderLevelStageEvent event) {
            if (event.getStage() != net.minecraftforge.client.event.RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
                return;
            }
            yblk.moves.client.ZiplinePreview.render(event.getPoseStack(), event.getCamera().getPosition());
            yblk.moves.client.PickCracks.render(event.getPoseStack(), event.getCamera().getPosition());
        }
    }
}
