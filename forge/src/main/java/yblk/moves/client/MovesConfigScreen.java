package yblk.moves.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import yblk.moves.config.MovesConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Mods-menu config. Booleans toggle. Numbers are editable. Done writes config/moves.json. */
public final class MovesConfigScreen extends Screen {

    private static final int ROW = 22;
    private final Screen parent;
    private final List<Row> rows = new ArrayList<>();
    private int scroll;

    public MovesConfigScreen(Screen parent) {
        super(Component.literal("Moves"));
        this.parent = parent;
        MovesConfig cfg = MovesConfig.get();
        rows.add(Row.toggle("Stamina", () -> cfg.stamina, v -> cfg.stamina = v));
        rows.add(Row.toggle("Ledge grab", () -> cfg.ledgeGrab, v -> cfg.ledgeGrab = v));
        rows.add(Row.toggle("Climb", () -> cfg.climb, v -> cfg.climb = v));
        rows.add(Row.toggle("Ceiling hang", () -> cfg.ceilingHang, v -> cfg.ceilingHang = v));
        rows.add(Row.toggle("Pull up", () -> cfg.pullUp, v -> cfg.pullUp = v));
        rows.add(Row.toggle("Dodge", () -> cfg.dodge, v -> cfg.dodge = v));
        rows.add(Row.toggle("Slide", () -> cfg.slide, v -> cfg.slide = v));
        rows.add(Row.toggle("Wall run", () -> cfg.wallRun, v -> cfg.wallRun = v));
        rows.add(Row.toggle("Crawl", () -> cfg.crawl, v -> cfg.crawl = v));
        rows.add(Row.toggle("Charged jump", () -> cfg.chargedJump, v -> cfg.chargedJump = v));
        rows.add(Row.toggle("Pickaxe climb", () -> cfg.toolClimb, v -> cfg.toolClimb = v));
        rows.add(Row.toggle("Zipline", () -> cfg.zipline, v -> cfg.zipline = v));
        rows.add(Row.number("Sprint / tick", "0.25", () -> cfg.sprintDrain, v -> cfg.sprintDrain = v));
        rows.add(Row.number("Jump", "6", () -> cfg.jumpCost, v -> cfg.jumpCost = v));
        rows.add(Row.number("Charged jump", "22", () -> cfg.enhancedJumpCost, v -> cfg.enhancedJumpCost = v));
        rows.add(Row.number("Grab", "10", () -> cfg.grabCost, v -> cfg.grabCost = v));
        rows.add(Row.number("Pull up", "14", () -> cfg.pullUpCost, v -> cfg.pullUpCost = v));
        rows.add(Row.number("Ledge move / tick", "0.08", () -> cfg.shimmyDrain, v -> cfg.shimmyDrain = v));
        rows.add(Row.number("Ceiling move / tick", "0.1", () -> cfg.hangDrain, v -> cfg.hangDrain = v));
        rows.add(Row.number("Climb / tick", "0.18", () -> cfg.climbDrain, v -> cfg.climbDrain = v));
        rows.add(Row.number("Dodge", "20", () -> cfg.dodgeCost, v -> cfg.dodgeCost = v));
        rows.add(Row.number("Slide start", "18", () -> cfg.slideCost, v -> cfg.slideCost = v));
        rows.add(Row.number("Slide / tick", "0.7", () -> cfg.slideDrain, v -> cfg.slideDrain = v));
        rows.add(Row.number("Wall run / tick", "0.35", () -> cfg.wallRunDrain, v -> cfg.wallRunDrain = v));
        rows.add(Row.number("Zipline / tick", "0.16", () -> cfg.ziplineDrain, v -> cfg.ziplineDrain = v));
        rows.add(Row.number("Zipline with tool / tick", "0.06", () -> cfg.ziplineToolDrain, v -> cfg.ziplineToolDrain = v));
        rows.add(Row.number("Pickaxe strike", "3.5", () -> cfg.toolClimbCost, v -> cfg.toolClimbCost = v));
        rows.add(Row.number("Regen / tick", "0.4", () -> cfg.regen, v -> cfg.regen = v));
        rows.add(Row.number("Regen delay (ticks)", "20", () -> (float) cfg.regenDelay, v -> cfg.regenDelay = Math.max(0, Math.round(v))));
    }

    @Override
    protected void init() {
        layout();
    }

    private void layout() {
        clearWidgets();
        int y0 = 32 - scroll;
        int labelX = this.width / 2 - 150;
        for (int i = 0; i < rows.size(); i++) {
            int y = y0 + i * ROW;
            if (y < 28 || y > this.height - 40) {
                continue;
            }
            Row row = rows.get(i);
            if (row.number) {
                EditBox box = new EditBox(this.font, this.width / 2 + 40, y, 72, 18, Component.literal(row.name));
                box.setMaxLength(12);
                box.setValue(row.draft);
                box.setResponder(text -> row.draft = text);
                addRenderableWidget(box);
            } else {
                addRenderableWidget(Button.builder(Component.literal(row.name + ": " + (row.read.get() ? "ON" : "OFF")), button -> {
                    row.writeBool.accept(!row.read.get());
                    MovesConfig.save();
                    layout();
                }).bounds(labelX, y, 260, 20).build());
            }
        }
        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(this.width / 2 - 50, this.height - 28, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        int y0 = 32 - scroll;
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (!row.number) {
                continue;
            }
            int y = y0 + i * ROW;
            if (y < 28 || y > this.height - 40) {
                continue;
            }
            graphics.drawString(this.font, row.name + "  (default " + row.fallback + ")", this.width / 2 - 150, y + 5, 0xE0E0E0);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = Math.max(0, rows.size() * ROW - (this.height - 80));
        scroll = Mth.clamp(scroll - (int) Math.signum(delta) * ROW, 0, max);
        layout();
        return true;
    }

    @Override
    public void onClose() {
        applyNumbers();
        MovesConfig.save();
        if (this.minecraft != null) {
            this.minecraft.setScreen(parent);
        }
    }

    private void applyNumbers() {
        for (Row row : rows) {
            if (!row.number) {
                continue;
            }
            try {
                row.write.accept(Float.parseFloat(row.draft.trim().replace(',', '.')));
            } catch (RuntimeException ignored) {
            }
        }
    }

    private static final class Row {
        final String name;
        final String fallback;
        final boolean number;
        final Supplier<Boolean> read;
        final Consumer<Boolean> writeBool;
        final Consumer<Float> write;
        String draft;

        private Row(String name, String fallback, boolean number, Supplier<Boolean> read,
                    Consumer<Boolean> writeBool, Consumer<Float> write, String draft) {
            this.name = name;
            this.fallback = fallback;
            this.number = number;
            this.read = read;
            this.writeBool = writeBool;
            this.write = write;
            this.draft = draft;
        }

        static Row toggle(String name, Supplier<Boolean> read, Consumer<Boolean> write) {
            return new Row(name, "", false, read, write, null, "");
        }

        static Row number(String name, String fallback, Supplier<Float> read, Consumer<Float> write) {
            String shown = trim(read.get());
            return new Row(name, fallback, true, null, null, write, shown);
        }

        private static String trim(float value) {
            if (value == (int) value) {
                return Integer.toString((int) value);
            }
            return Float.toString(value);
        }
    }
}
