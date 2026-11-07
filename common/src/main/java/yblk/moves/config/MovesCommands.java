package yblk.moves.config;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** In-world switches. {@code /moves} prints the list, {@code /moves <key> <value>} writes the config. */
public final class MovesCommands {

    private MovesCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("moves")
                .requires(source -> source.hasPermission(2) || source.getServer().isSingleplayer())
                .executes(ctx -> show(ctx.getSource()))
                .then(Commands.literal("reload").executes(ctx -> {
                    MovesConfig.reload();
                    ctx.getSource().sendSuccess(() -> Component.literal("Moves: config reloaded."), false);
                    return 1;
                }))
                .then(Commands.argument("key", StringArgumentType.word())
                        .then(Commands.argument("value", StringArgumentType.word())
                                .executes(ctx -> set(
                                        ctx.getSource(),
                                        StringArgumentType.getString(ctx, "key"),
                                        StringArgumentType.getString(ctx, "value"))))));
    }

    private static int show(CommandSourceStack source) {
        for (String line : MovesConfig.get().summary().split("\n")) {
            String shown = line.isEmpty() ? " " : line;
            source.sendSuccess(() -> Component.literal(shown), false);
        }
        return 1;
    }

    private static int set(CommandSourceStack source, String key, String value) {
        if (!MovesConfig.get().set(key, value)) {
            source.sendFailure(Component.literal("Unknown key " + key + ", or the value could not be read. /moves lists them."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Moves: " + key + " = " + value), true);
        return 1;
    }
}
