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

    private static final int ROW = 24;
    private static final int TOGGLE_W = 150;
    private static final int COL_GAP = 10;
    private static final int PANEL_W = 310;
    private static final int FIELD_W = 72;

    private final Screen parent;
    private final List<Row> toggles = new ArrayList<>();
    private final List<Row> numbers = new ArrayList<>();
    private int scroll;

    public MovesConfigScreen(Screen parent) {
        super(Component.literal("Moves"));
        this.parent = parent;
        MovesConfig cfg = MovesConfig.get();
        toggles.add(Row.toggle("Stamina", () -> cfg.stamina, v -> cfg.stamina = v));
        toggles.add(Row.toggle("Ledge grab", () -> cfg.ledgeGrab, v -> cfg.ledgeGrab = v));
        toggles.add(Row.toggle("Climb", () -> cfg.climb, v -> cfg.climb = v));
        toggles.add(Row.toggle("Ceiling hang", () -> cfg.ceilingHang, v -> cfg.ceilingHang = v));
        toggles.add(Row.toggle("Pull up", () -> cfg.pullUp, v -> cfg.pullUp = v));
        toggles.add(Row.toggle("Dodge", () -> cfg.dodge, v -> cfg.dodge = v));
        toggles.add(Row.toggle("Slide", () -> cfg.slide, v -> cfg.slide = v));
        toggles.add(Row.toggle("Wall run", () -> cfg.wallRun, v -> cfg.wallRun = v));
        toggles.add(Row.toggle("Crawl", () -> cfg.crawl, v -> cfg.crawl = v));
        toggles.add(Row.toggle("Charged jump", () -> cfg.chargedJump, v -> cfg.chargedJump = v));
        toggles.add(Row.toggle("Pickaxe climb", () -> cfg.toolClimb, v -> cfg.toolClimb = v));
        toggles.add(Row.toggle("Zipline", () -> cfg.zipline, v -> cfg.zipline = v));
        numbers.add(Row.number("Sprint / tick", "0.25", () -> cfg.sprintDrain, v -> cfg.sprintDrain = v));
        numbers.add(Row.number("Jump", "6", () -> cfg.jumpCost, v -> cfg.jumpCost = v));
        numbers.add(Row.number("Charged jump", "22", () -> cfg.enhancedJumpCost, v -> cfg.enhancedJumpCost = v));
        numbers.add(Row.number("Grab", "10", () -> cfg.grabCost, v -> cfg.grabCost = v));
        numbers.add(Row.number("Pull up", "14", () -> cfg.pullUpCost, v -> cfg.pullUpCost = v));
        numbers.add(Row.number("Ledge move / tick", "0.08", () -> cfg.shimmyDrain, v -> cfg.shimmyDrain = v));
        numbers.add(Row.number("Ceiling move / tick", "0.1", () -> cfg.hangDrain, v -> cfg.hangDrain = v));
        numbers.add(Row.number("Climb / tick", "0.18", () -> cfg.climbDrain, v -> cfg.climbDrain = v));
        numbers.add(Row.number("Dodge", "20", () -> cfg.dodgeCost, v -> cfg.dodgeCost = v));
        numbers.add(Row.number("Slide start", "18", () -> cfg.slideCost, v -> cfg.slideCost = v));
        numbers.add(Row.number("Slide / tick", "0.7", () -> cfg.slideDrain, v -> cfg.slideDrain = v));
        numbers.add(Row.number("Wall run / tick", "0.35", () -> cfg.wallRunDrain, v -> cfg.wallRunDrain = v));
        numbers.add(Row.number("Zipline / tick", "0.16", () -> cfg.ziplineDrain, v -> cfg.ziplineDrain = v));
        numbers.add(Row.number("Zipline with tool / tick", "0.06", () -> cfg.ziplineToolDrain, v -> cfg.ziplineToolDrain = v));
        numbers.add(Row.number("Pickaxe strike", "3.5", () -> cfg.toolClimbCost, v -> cfg.toolClimbCost = v));
        numbers.add(Row.number("Regen / tick", "0.4", () -> cfg.regen, v -> cfg.regen = v));
        numbers.add(Row.number("Regen delay (ticks)", "20", () -> (float) cfg.regenDelay, v -> cfg.regenDelay = Math.max(0, Math.round(v))));
    }

    private int contentHeight() {
        int toggleRows = (toggles.size() + 1) / 2;
        return toggleRows * ROW + 8 + numbers.size() * ROW;
    }

    @Override
    protected void init() {
        layout();
    }

    private void layout() {
        clearWidgets();
        int panelX = this.width / 2 - PANEL_W / 2;
        int leftX = this.width / 2 - TOGGLE_W - COL_GAP / 2;
        int rightX = this.width / 2 + COL_GAP / 2;
        int y0 = 32 - scroll;

        for (int i = 0; i < toggles.size(); i++) {
            int col = i % 2;
            int row = i / 2;
            int y = y0 + row * ROW;
            if (y < 28 || y > this.height - 40) {
                continue;
            }
            Row item = toggles.get(i);
            int x = col == 0 ? leftX : rightX;
            addRenderableWidget(Button.builder(
                    Component.literal(item.name + ": " + (item.read.get() ? "ON" : "OFF")),
                    button -> {
                        item.writeBool.accept(!item.read.get());
                        MovesConfig.save();
                        layout();
                    }).bounds(x, y, TOGGLE_W, 20).build());
        }

        int numbersY0 = y0 + ((toggles.size() + 1) / 2) * ROW + 8;
        for (int i = 0; i < numbers.size(); i++) {
            int y = numbersY0 + i * ROW;
            if (y < 28 || y > this.height - 40) {
                continue;
            }
            Row item = numbers.get(i);
            EditBox box = new EditBox(this.font, panelX + PANEL_W - FIELD_W, y, FIELD_W, 18, Component.literal(item.name));
            box.setMaxLength(12);
            box.setValue(item.draft);
            box.setResponder(text -> item.draft = text);
            addRenderableWidget(box);
        }

        addRenderableWidget(Button.builder(Component.literal("Done"), button -> onClose())
                .bounds(this.width / 2 - 50, this.height - 28, 100, 20).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, 12, 0xFFFFFF);
        int panelX = this.width / 2 - PANEL_W / 2;
        int y0 = 32 - scroll;
        int numbersY0 = y0 + ((toggles.size() + 1) / 2) * ROW + 8;
        for (int i = 0; i < numbers.size(); i++) {
            Row item = numbers.get(i);
            int y = numbersY0 + i * ROW;
            if (y < 28 || y > this.height - 40) {
                continue;
            }
            graphics.drawString(this.font, item.name + "  (default " + item.fallback + ")", panelX, y + 5, 0xE0E0E0);
        }
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        int max = Math.max(0, contentHeight() - (this.height - 80));
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
        for (Row row : numbers) {
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
            return new Row(name, fallback, true, null, null, write, trim(read.get()));
        }

        private static String trim(float value) {
            if (value == (int) value) {
                return Integer.toString((int) value);
            }
            return Float.toString(value);
        }
    }
}
