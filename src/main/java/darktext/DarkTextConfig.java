package darktext;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

/**
 * Configuration manager for DarkTextFix.
 * Reads/writes config/darktextfix.cfg with Forge-style formatting.
 * Auto-generates the config file with defaults on first run.
 */
public class DarkTextConfig {

    private static final String CONFIG_PATH = "config" + File.separator + "darktextfix.cfg";

    // === General ===
    public static boolean enabled = true;
    public static boolean debugMode = false;

    // === Mode ===
    /** "invert" = HSL lightness inversion, "contrast" = WCAG contrast-based */
    public static String mode = "invert";

    // === Invert mode settings ===
    public static float darkThreshold = 0.45f;
    public static float minLightness = 0.65f;
    public static float maxLightness = 0.93f;
    public static int brightnessSkip = 140;

    // === Contrast mode settings ===
    /** Background color hex (without #) for contrast calculation */
    public static String backgroundHex = "444444";
    /** Parsed background RGB values */
    public static int bgR = 0x44, bgG = 0x44, bgB = 0x44;
    /** Minimum WCAG contrast ratio (4.5 = AA, 7.0 = AAA) */
    public static float minContrast = 4.5f;

    // === AE2 GuiCraftingCPU Colors (ARGB hex) ===
    public static int craftingCPUActive = 0x5A808080;
    public static int craftingCPUInactive = 0x5ADCDCDC;
    public static int craftingCPUScheduled = 0xFFFFFF00;
    public static int craftingCPUCrafting = 0xFF00FF00;

    // === CPU Slot Colors (for AE2 GuiCraftingCPUTable) ===

    /** Mutable RGBA color holder for CPU slot color groups */
    public static class CpuColor {

        public float r, g, b, a;

        public CpuColor(float r, float g, float b, float a) {
            this.r = r;
            this.g = g;
            this.b = b;
            this.a = a;
        }
    }

    public static final CpuColor cpuSelectedFiltered = new CpuColor(0.8f, 0.0f, 0.0f, 0.8f);
    public static final CpuColor cpuSelectedMergeable = new CpuColor(1.0f, 1.0f, 0.0f, 0.8f);
    public static final CpuColor cpuSelectedNormal = new CpuColor(0.8f, 0.8f, 0.8f, 0.8f);
    public static final CpuColor cpuHovered = new CpuColor(0.5f, 0.5f, 0.5f, 0.5f);
    public static final CpuColor cpuUnselectedFiltered = new CpuColor(0.5f, 0.0f, 0.0f, 0.5f);
    public static final CpuColor cpuUnselectedMergeable = new CpuColor(1.0f, 1.0f, 0.0f, 0.5f);
    public static final CpuColor cpuDefault = new CpuColor(0.0f, 0.0f, 0.0f, 0.0f);

    /** CPU color group metadata: prefix, color instance, config comment */
    private static final String[][] CPU_METAS = { { "cpuSelectedFiltered", "Selected CPU but filtered out" },
        { "cpuSelectedMergeable", "Selected CPU and job mergeable" },
        { "cpuSelectedNormal", "Selected CPU normal (compatible, not mergeable)" },
        { "cpuHovered", "Hovered CPU (not selected)" }, { "cpuUnselectedFiltered", "Unselected CPU but filtered out" },
        { "cpuUnselectedMergeable", "Unselected CPU and job mergeable" },
        { "cpuDefault", "Default (unselected, not filtered, not mergeable)" }, };

    /** Lookup: CPU color prefix -> CpuColor instance (parallel to CPU_METAS) */
    private static final CpuColor[] CPU_COLORS = { cpuSelectedFiltered, cpuSelectedMergeable, cpuSelectedNormal,
        cpuHovered, cpuUnselectedFiltered, cpuUnselectedMergeable, cpuDefault, };

    private static boolean loaded = false;

    public static synchronized void load() {
        if (loaded) return;
        loaded = true;

        File configFile = new File(CONFIG_PATH);

        if (configFile.exists()) {
            readConfig(configFile);
            // Re-write to add any new options that didn't exist before
            writeConfig(configFile);
            System.out.println("[DarkTextFix] Config loaded from " + CONFIG_PATH);
        } else {
            writeConfig(configFile);
            System.out.println("[DarkTextFix] Default config created at " + CONFIG_PATH);
        }

        parseBackgroundColor();
        printConfig();
    }

    private static void parseBackgroundColor() {
        try {
            int bg = Integer.parseInt(backgroundHex, 16);
            bgR = (bg >> 16) & 0xFF;
            bgG = (bg >> 8) & 0xFF;
            bgB = bg & 0xFF;
        } catch (NumberFormatException e) {
            System.err.println("[DarkTextFix] Invalid backgroundHex: " + backgroundHex + ", using 444444");
            bgR = 0x44;
            bgG = 0x44;
            bgB = 0x44;
        }
    }

    private static void readConfig(File file) {
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                int eq = line.indexOf('=');
                if (eq < 0) continue;

                String rawKey = line.substring(0, eq)
                    .trim();
                String value = line.substring(eq + 1)
                    .trim();

                // Strip Forge-style type prefix (e.g. "B:" -> "")
                String key = rawKey;
                if (rawKey.length() > 2 && rawKey.charAt(1) == ':') {
                    key = rawKey.substring(2);
                }

                try {
                    switch (key) {
                        case "enabled":
                            enabled = Boolean.parseBoolean(value);
                            break;
                        case "debugMode":
                            debugMode = Boolean.parseBoolean(value);
                            break;
                        case "mode":
                            mode = value.toLowerCase();
                            break;
                        case "darkThreshold":
                            darkThreshold = clampFloat(Float.parseFloat(value), 0f, 1f);
                            break;
                        case "minLightness":
                            minLightness = clampFloat(Float.parseFloat(value), 0f, 1f);
                            break;
                        case "maxLightness":
                            maxLightness = clampFloat(Float.parseFloat(value), 0f, 1f);
                            break;
                        case "brightnessSkip":
                            brightnessSkip = clampInt(Integer.parseInt(value), 0, 255);
                            break;
                        case "backgroundHex":
                            backgroundHex = value.replaceAll("[^0-9a-fA-F]", "");
                            break;
                        case "minContrast":
                            minContrast = clampFloat(Float.parseFloat(value), 1f, 21f);
                            break;
                        case "craftingCPUActive":
                            craftingCPUActive = (int) Long.parseLong(value, 16);
                            break;
                        case "craftingCPUInactive":
                            craftingCPUInactive = (int) Long.parseLong(value, 16);
                            break;
                        case "craftingCPUScheduled":
                            craftingCPUScheduled = (int) Long.parseLong(value, 16);
                            break;
                        case "craftingCPUCrafting":
                            craftingCPUCrafting = (int) Long.parseLong(value, 16);
                            break;
                        default:
                            readCpuColorField(key, value);
                            break;
                    }
                } catch (NumberFormatException e) {
                    System.err.println("[DarkTextFix] Invalid value for " + key + ": " + value);
                }
            }
        } catch (IOException e) {
            System.err.println("[DarkTextFix] Error reading config: " + e.getMessage());
        }
    }

    /** Reads a CPU RGBA color field by matching key prefix against CPU_METAS. No reflection. */
    private static void readCpuColorField(String key, String value) {
        for (int i = 0; i < CPU_METAS.length; i++) {
            var prefix = CPU_METAS[i][0];
            if (key.length() == prefix.length() + 1 && key.startsWith(prefix)) {
                try {
                    float val = clampFloat(Float.parseFloat(value), 0f, 1f);
                    var color = CPU_COLORS[i];
                    switch (key.charAt(key.length() - 1)) {
                        case 'R':
                            color.r = val;
                            break;
                        case 'G':
                            color.g = val;
                            break;
                        case 'B':
                            color.b = val;
                            break;
                        case 'A':
                            color.a = val;
                            break;
                    }
                } catch (NumberFormatException e) {
                    System.err.println("[DarkTextFix] Invalid value for " + key + ": " + value);
                }
                return;
            }
        }
    }

    private static void writeConfig(File file) {
        try {
            file.getParentFile()
                .mkdirs();
            try (PrintWriter w = new PrintWriter(new FileWriter(file))) {
                w.println("# ==========================================");
                w.println("# DarkTextFix Configuration");
                w.println("# ==========================================");
                w.println("# Changes take effect on game restart.");
                w.println("# ==========================================");
                w.println();

                w.println("# ---- General ----");
                w.println();
                w.println("# Enable or disable all color corrections [default: true]");
                w.println("B:enabled=" + enabled);
                w.println();
                w.println("# Log unique color corrections to console (up to 256 pairs) [default: false]");
                w.println("B:debugMode=" + debugMode);
                w.println();

                w.println("# ---- Mode ----");
                w.println();
                w.println("# Color correction mode [default: invert]");
                w.println("#   invert   = Inverts HSL lightness (dark -> light).");
                w.println("#              Simple and effective. Good for most cases.");
                w.println("#   contrast = Uses WCAG accessibility formula to calculate");
                w.println("#              the minimum lightness needed for readability");
                w.println("#              against the background. More natural results.");
                w.println("S:mode=" + mode);
                w.println();

                w.println("# ---- Invert Mode Settings ----");
                w.println();
                w.println("# HSL lightness threshold (0.0-1.0).");
                w.println("# Colors darker than this are lightened. [default: 0.45]");
                w.println("D:darkThreshold=" + darkThreshold);
                w.println();
                w.println("# Min lightness after inversion. [default: 0.65]");
                w.println("D:minLightness=" + minLightness);
                w.println();
                w.println("# Max lightness after inversion. [default: 0.93]");
                w.println("D:maxLightness=" + maxLightness);
                w.println();
                w.println("# Fast-path: skip colors with any RGB > this value. [default: 140]");
                w.println("I:brightnessSkip=" + brightnessSkip);
                w.println();

                w.println("# ---- Contrast Mode Settings ----");
                w.println();
                w.println("# Background color hex for contrast calculation.");
                w.println("# Set this to match your dark resource pack's GUI background.");
                w.println("# Common values: 444444, 333333, 2C2C2C [default: 444444]");
                w.println("S:backgroundHex=" + backgroundHex);
                w.println();
                w.println("# Minimum WCAG contrast ratio. [default: 4.5]");
                w.println("#   4.5 = WCAG AA (readable)");
                w.println("#   7.0 = WCAG AAA (high legibility)");
                w.println("#   3.0 = relaxed (less adjustment, dimmer text)");
                w.println("D:minContrast=" + minContrast);

                w.println();
                w.println("# ---- AE2 CPU Slot Colors (for GuiCraftingCPUTable) ----");
                w.println("# Colors are RGBA floats (0.0-1.0).");
                w.println();

                for (int i = 0; i < CPU_METAS.length; i++) {
                    writeCpuColorGroup(w, CPU_METAS[i][0], CPU_COLORS[i], CPU_METAS[i][1]);
                }

                w.println("# ---- AE2 GuiCraftingCPU Colors (ARGB hex) ----");
                w.println("# Crafting CPU active background color");
                w.println("S:craftingCPUActive=" + String.format("%08X", craftingCPUActive));
                w.println("# Crafting CPU inactive background color");
                w.println("S:craftingCPUInactive=" + String.format("%08X", craftingCPUInactive));
                w.println("# Crafting CPU scheduled text color");
                w.println("S:craftingCPUScheduled=" + String.format("%08X", craftingCPUScheduled));
                w.println("# Crafting text color (active items being crafted)");
                w.println("S:craftingCPUCrafting=" + String.format("%08X", craftingCPUCrafting));
            }
        } catch (IOException e) {
            System.err.println("[DarkTextFix] Error writing config: " + e.getMessage());
        }
    }

    /** Writes a CPU RGBA color group to the config file. No reflection. */
    private static void writeCpuColorGroup(PrintWriter w, String prefix, CpuColor color, String comment) {
        w.println("# " + comment);
        w.println("D:" + prefix + "R=" + color.r);
        w.println("D:" + prefix + "G=" + color.g);
        w.println("D:" + prefix + "B=" + color.b);
        w.println("D:" + prefix + "A=" + color.a);
        w.println();
    }

    private static void printConfig() {
        System.out.println(
            "[DarkTextFix] Config: enabled=" + enabled
                + " mode="
                + mode
                + (mode.equals("invert")
                    ? " threshold=" + darkThreshold + " lightness=[" + minLightness + "-" + maxLightness + "]"
                    : " background=#" + backgroundHex + " minContrast=" + minContrast)
                + " debug="
                + debugMode);
        System.out.println(
            "[DarkTextFix] craftingCPUActive=0x" + Integer.toHexString(craftingCPUActive)
                + " craftingCPUInactive=0x"
                + Integer.toHexString(craftingCPUInactive)
                + " craftingCPUScheduled=0x"
                + Integer.toHexString(craftingCPUScheduled)
                + " craftingCPUCrafting=0x"
                + Integer.toHexString(craftingCPUCrafting));
    }

    private static float clampFloat(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    private static int clampInt(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
