package yblk.moves.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import yblk.moves.Constants;
import yblk.moves.parkour.ParkourTuning;
import yblk.moves.platform.Services;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * {@code config/moves.json}. The file is re-read while the world is running,
 * and {@code /moves <key> <value>} writes the same file.
 */
public final class MovesConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String README = ""
            + "Changes apply immediately. Use the Config button in the Mods menu, /moves <key> <value>, or edit this file.\n"
            + "The bar is 100. A per-tick cost is spent 20 times a second. false turns that feature off.\n"
            + "\n"
            + "Features (default):\n"
            + "stamina (true) — the bar and every cost.\n"
            + "ledgeGrab (true) — grab a ledge.\n"
            + "climb (true) — chains, fences, bars, ladders.\n"
            + "ceilingHang (true) — ceiling.\n"
            + "pullUp (true) — mantle onto the ledge.\n"
            + "dodge (true) — dodge.\n"
            + "slide (true) — slide.\n"
            + "wallRun (false) — wall run.\n"
            + "crawl (true) — crawl.\n"
            + "chargedJump (true) — charged jump.\n"
            + "toolClimb (true) — wall climb with two pickaxes or hoes.\n"
            + "zipline (true) — grab a rope or chain.\n"
            + "\n"
            + "Costs, default in parentheses:\n"
            + "sprintDrain (0.12) — sprint, per tick, including while jumping.\n"
            + "jumpCost (6) — a normal jump, once.\n"
            + "enhancedJumpCost (22) — a charged jump, once.\n"
            + "grabCost (10) — grab a ledge, chain, or ceiling.\n"
            + "pullUpCost (14) — pull up.\n"
            + "shimmyDrain (0.08) — moving along a ledge, per tick.\n"
            + "hangDrain (0.1) — moving on a ceiling, per tick. Standing still costs 15%.\n"
            + "climbDrain (0.18) — climbing a chain or fence, per tick. Standing still costs 12%.\n"
            + "dodgeCost (20) — dodge.\n"
            + "slideCost (18) — starting a slide.\n"
            + "slideDrain (0.7) — sliding, per tick, while it lasts.\n"
            + "wallRunDrain (0.35) — wall run, per tick.\n"
            + "ziplineDrain (0.16) — zipline with empty hands, per tick.\n"
            + "ziplineToolDrain (0.06) — zipline with a pickaxe or sword, per tick.\n"
            + "toolClimbCost (3.5) — one pickaxe strike on a wall.\n"
            + "regen (0.4) — recovery while standing, per tick. Half of that while moving.\n"
            + "regenDelay (20) — ticks to wait after spending before the bar refills.";

    private static MovesConfig INSTANCE = new MovesConfig();
    private static long stamp = -1L;
    private static int pollTick;

    public String _readme = README;

    public boolean stamina = true;
    public boolean ledgeGrab = true;
    public boolean climb = true;
    public boolean ceilingHang = true;
    public boolean pullUp = true;
    public boolean dodge = true;
    public boolean slide = true;
    public boolean wallRun = false;
    public boolean crawl = true;
    public boolean chargedJump = true;
    public boolean toolClimb = true;
    public boolean zipline = true;

    public float sprintDrain = ParkourTuning.SPRINT_DRAIN;
    public float jumpCost = ParkourTuning.JUMP_COST;
    public float enhancedJumpCost = ParkourTuning.ENHANCED_JUMP_COST;
    public float grabCost = ParkourTuning.GRAB_COST;
    public float pullUpCost = ParkourTuning.PULLUP_COST;
    public float shimmyDrain = ParkourTuning.SHIMMY_DRAIN;
    public float hangDrain = ParkourTuning.HANG_DRAIN;
    public float climbDrain = ParkourTuning.CLIMB_DRAIN;
    public float dodgeCost = ParkourTuning.DODGE_COST;
    public float slideCost = ParkourTuning.SLIDE_COST;
    public float slideDrain = 0.7F;
    public float wallRunDrain = ParkourTuning.WALL_RUN_DRAIN;
    public float ziplineDrain = 0.16F;
    public float ziplineToolDrain = 0.06F;
    public float toolClimbCost = 3.5F;
    public float regen = ParkourTuning.REGEN;
    public int regenDelay = ParkourTuning.REGEN_DELAY_TICKS;

    private MovesConfig() {
    }

    public static MovesConfig get() {
        return INSTANCE;
    }

    public static void load() {
        read(true);
    }

    /** Picks up edits to config/moves.json while the world is open. */
    public static void poll() {
        if ((pollTick++ % 20) != 0) {
            return;
        }
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try {
            long time = Files.getLastModifiedTime(path).toMillis();
            if (time != stamp) {
                read(false);
            }
        } catch (IOException ignored) {
        }
    }

    public static void reload() {
        read(false);
    }

    private static void read(boolean rewrite) {
        Path path = configPath();
        if (!Files.isRegularFile(path)) {
            INSTANCE = new MovesConfig();
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject obj = JsonParser.parseReader(reader).getAsJsonObject();
            INSTANCE = overlay(obj);
            if (rewrite) {
                save();
            } else {
                stamp = Files.getLastModifiedTime(path).toMillis();
            }
        } catch (Exception e) {
            Constants.LOG.error("Failed to read Moves config, keeping the previous values", e);
            try {
                stamp = Files.getLastModifiedTime(path).toMillis();
            } catch (IOException ignored) {
            }
        }
    }

    public static void save() {
        Path path = configPath();
        INSTANCE._readme = README;
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
                GSON.toJson(INSTANCE, writer);
            }
            stamp = Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            Constants.LOG.error("Failed to write Moves config", e);
        }
    }

    public boolean set(String key, String raw) {
        String name = key.toLowerCase(Locale.ROOT);
        try {
            switch (name) {
                case "stamina" -> stamina = parseBool(raw);
                case "ledgegrab" -> ledgeGrab = parseBool(raw);
                case "climb" -> climb = parseBool(raw);
                case "ceilinghang" -> ceilingHang = parseBool(raw);
                case "pullup" -> pullUp = parseBool(raw);
                case "dodge" -> dodge = parseBool(raw);
                case "slide" -> slide = parseBool(raw);
                case "wallrun" -> wallRun = parseBool(raw);
                case "crawl" -> crawl = parseBool(raw);
                case "chargedjump" -> chargedJump = parseBool(raw);
                case "toolclimb" -> toolClimb = parseBool(raw);
                case "zipline" -> zipline = parseBool(raw);
                case "sprintdrain" -> sprintDrain = parseFloat(raw);
                case "jumpcost" -> jumpCost = parseFloat(raw);
                case "enhancedjumpcost" -> enhancedJumpCost = parseFloat(raw);
                case "grabcost" -> grabCost = parseFloat(raw);
                case "pullupcost" -> pullUpCost = parseFloat(raw);
                case "shimmydrain" -> shimmyDrain = parseFloat(raw);
                case "hangdrain" -> hangDrain = parseFloat(raw);
                case "climbdrain" -> climbDrain = parseFloat(raw);
                case "dodgecost" -> dodgeCost = parseFloat(raw);
                case "slidecost" -> slideCost = parseFloat(raw);
                case "slidedrain" -> slideDrain = parseFloat(raw);
                case "wallrundrain" -> wallRunDrain = parseFloat(raw);
                case "ziplinedrain" -> ziplineDrain = parseFloat(raw);
                case "ziplinetooldrain" -> ziplineToolDrain = parseFloat(raw);
                case "toolclimbcost" -> toolClimbCost = parseFloat(raw);
                case "regen" -> regen = parseFloat(raw);
                case "regendelay" -> regenDelay = Math.max(0, Integer.parseInt(raw.trim()));
                default -> {
                    return false;
                }
            }
        } catch (RuntimeException e) {
            return false;
        }
        save();
        return true;
    }

    public String summary() {
        return README + "\n\nCurrent:\n"
                + line("stamina", stamina)
                + line("ledgeGrab", ledgeGrab)
                + line("climb", climb)
                + line("ceilingHang", ceilingHang)
                + line("pullUp", pullUp)
                + line("dodge", dodge)
                + line("slide", slide)
                + line("wallRun", wallRun)
                + line("crawl", crawl)
                + line("chargedJump", chargedJump)
                + line("toolClimb", toolClimb)
                + line("zipline", zipline)
                + num("sprintDrain", sprintDrain, ParkourTuning.SPRINT_DRAIN)
                + num("jumpCost", jumpCost, ParkourTuning.JUMP_COST)
                + num("enhancedJumpCost", enhancedJumpCost, ParkourTuning.ENHANCED_JUMP_COST)
                + num("grabCost", grabCost, ParkourTuning.GRAB_COST)
                + num("pullUpCost", pullUpCost, ParkourTuning.PULLUP_COST)
                + num("shimmyDrain", shimmyDrain, ParkourTuning.SHIMMY_DRAIN)
                + num("hangDrain", hangDrain, ParkourTuning.HANG_DRAIN)
                + num("climbDrain", climbDrain, ParkourTuning.CLIMB_DRAIN)
                + num("dodgeCost", dodgeCost, ParkourTuning.DODGE_COST)
                + num("slideCost", slideCost, ParkourTuning.SLIDE_COST)
                + num("slideDrain", slideDrain, 0.7F)
                + num("wallRunDrain", wallRunDrain, ParkourTuning.WALL_RUN_DRAIN)
                + num("ziplineDrain", ziplineDrain, 0.16F)
                + num("ziplineToolDrain", ziplineToolDrain, 0.06F)
                + num("toolClimbCost", toolClimbCost, 3.5F)
                + num("regen", regen, ParkourTuning.REGEN)
                + "regenDelay = " + regenDelay + " (default " + ParkourTuning.REGEN_DELAY_TICKS + ")";
    }

    private static String line(String key, boolean value) {
        return key + " = " + value + "\n";
    }

    private static String num(String key, float value, float fallback) {
        return key + " = " + trim(value) + " (default " + trim(fallback) + ")\n";
    }

    private static String trim(float value) {
        if (value == (int) value) {
            return Integer.toString((int) value);
        }
        return Float.toString(value);
    }

    private static MovesConfig overlay(JsonObject obj) {
        MovesConfig cfg = new MovesConfig();
        cfg.stamina = bool(obj, "stamina", cfg.stamina);
        cfg.ledgeGrab = bool(obj, "ledgeGrab", cfg.ledgeGrab);
        cfg.climb = bool(obj, "climb", cfg.climb);
        cfg.ceilingHang = bool(obj, "ceilingHang", cfg.ceilingHang);
        cfg.pullUp = bool(obj, "pullUp", cfg.pullUp);
        cfg.dodge = bool(obj, "dodge", cfg.dodge);
        cfg.slide = bool(obj, "slide", cfg.slide);
        cfg.wallRun = bool(obj, "wallRun", cfg.wallRun);
        cfg.crawl = bool(obj, "crawl", cfg.crawl);
        cfg.chargedJump = bool(obj, "chargedJump", cfg.chargedJump);
        cfg.toolClimb = bool(obj, "toolClimb", cfg.toolClimb);
        cfg.zipline = bool(obj, "zipline", cfg.zipline);
        cfg.sprintDrain = num(obj, "sprintDrain", cfg.sprintDrain);
        cfg.jumpCost = num(obj, "jumpCost", cfg.jumpCost);
        cfg.enhancedJumpCost = num(obj, "enhancedJumpCost", cfg.enhancedJumpCost);
        cfg.grabCost = num(obj, "grabCost", cfg.grabCost);
        cfg.pullUpCost = num(obj, "pullUpCost", cfg.pullUpCost);
        cfg.shimmyDrain = num(obj, "shimmyDrain", cfg.shimmyDrain);
        cfg.hangDrain = num(obj, "hangDrain", cfg.hangDrain);
        cfg.climbDrain = num(obj, "climbDrain", cfg.climbDrain);
        cfg.dodgeCost = num(obj, "dodgeCost", cfg.dodgeCost);
        cfg.slideCost = num(obj, "slideCost", cfg.slideCost);
        cfg.slideDrain = num(obj, "slideDrain", cfg.slideDrain);
        cfg.wallRunDrain = num(obj, "wallRunDrain", cfg.wallRunDrain);
        cfg.ziplineDrain = num(obj, "ziplineDrain", cfg.ziplineDrain);
        cfg.ziplineToolDrain = num(obj, "ziplineToolDrain", cfg.ziplineToolDrain);
        cfg.toolClimbCost = num(obj, "toolClimbCost", cfg.toolClimbCost);
        cfg.regen = num(obj, "regen", cfg.regen);
        cfg.regenDelay = obj.has("regenDelay") ? obj.get("regenDelay").getAsInt() : cfg.regenDelay;
        return cfg;
    }

    private static boolean bool(JsonObject obj, String key, boolean fallback) {
        return obj.has(key) ? obj.get(key).getAsBoolean() : fallback;
    }

    private static float num(JsonObject obj, String key, float fallback) {
        return obj.has(key) ? obj.get(key).getAsFloat() : fallback;
    }

    private static boolean parseBool(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (value.equals("true") || value.equals("on") || value.equals("1")) {
            return true;
        }
        if (value.equals("false") || value.equals("off") || value.equals("0")) {
            return false;
        }
        throw new IllegalArgumentException(raw);
    }

    private static float parseFloat(String raw) {
        return Float.parseFloat(raw.trim().replace(',', '.'));
    }

    private static Path configPath() {
        return Services.PLATFORM.getConfigDirectory().resolve("moves.json");
    }
}
