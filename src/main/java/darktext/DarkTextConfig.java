package darktext;

import java.io.*;

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

    private static boolean loaded = false;

    public static synchronized void load() {
        if (loaded)
            return;
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
                if (line.isEmpty() || line.startsWith("#"))
                    continue;

                int eq = line.indexOf('=');
                if (eq < 0)
                    continue;

                String rawKey = line.substring(0, eq).trim();
                String value = line.substring(eq + 1).trim();

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
                    }
                } catch (NumberFormatException e) {
                    System.err.println("[DarkTextFix] Invalid value for " + key + ": " + value);
                }
            }
        } catch (IOException e) {
            System.err.println("[DarkTextFix] Error reading config: " + e.getMessage());
        }
    }

    private static void writeConfig(File file) {
        try {
            file.getParentFile().mkdirs();
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
                w.println("# Print color changes to log for debugging [default: false]");
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
            }
        } catch (IOException e) {
            System.err.println("[DarkTextFix] Error writing config: " + e.getMessage());
        }
    }

    private static void printConfig() {
        System.out.println("[DarkTextFix] Config: enabled=" + enabled + " mode=" + mode
                + (mode.equals("invert")
                        ? " threshold=" + darkThreshold + " lightness=[" + minLightness + "-" + maxLightness + "]"
                        : " background=#" + backgroundHex + " minContrast=" + minContrast)
                + " debug=" + debugMode);
    }

    private static float clampFloat(float val, float min, float max) {
        return Math.max(min, Math.min(max, val));
    }

    private static int clampInt(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }
}
