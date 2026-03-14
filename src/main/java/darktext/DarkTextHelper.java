package darktext;

import java.util.HashSet;
import java.util.Set;

/**
 * Runtime color correction entry point for dark theme compatibility.
 * Manages context flags and delegates to ColorCorrection for the actual algorithms.
 *
 * PERFORMANCE CRITICAL: fixColor() is called on every FontRenderer.drawString().
 * Guard chain uses static field reads (fast), delegates to static methods (inlinable).
 */
public class DarkTextHelper {

    /**
     * Context flag set by ASM-patched GuiContainer around
     * drawGuiContainerBackgroundLayer() and drawGuiContainerForegroundLayer()
     */
    public static boolean inContainerGui = false;

    /**
     * Suppression flag set by ASM-patched RenderItem around
     * renderItemOverlayIntoGUI() — prevents fixColor from altering
     * item charge/durability bar text drawn inside container GUIs.
     */
    public static boolean inItemOverlay = false;

    /**
     * Per-GUI suppression flag set by ASM patches on specific GuiContainer subclasses
     * whose text should NOT be color-corrected (e.g. BiblioCraft GuiAtlas, GuiFancySign).
     * When true, fixColor() returns the original color even if inContainerGui is true.
     */
    public static boolean skipFixColor = false;

    /** Set by fixColor(): true if the color was actually modified by color correction */
    public static boolean lastColorWasCorrected = false;

    private static boolean initialized = false;

    static {
        // Force early initialization to load config and apply patches
        init();
    }

    // ==========================================================
    // Entry point (hot path)
    // ==========================================================

    public static int fixColor(int color) {
        if (!initialized) init();
        if (!DarkTextConfig.enabled || !inContainerGui || inItemOverlay || skipFixColor) {
            lastColorWasCorrected = false;
            return color;
        }
        // Pure black (0x000000) is used for text outline/border effects, not readable labels
        if ((color & 0xFFFFFF) == 0) {
            lastColorWasCorrected = false;
            return color;
        }

        int fixed = ColorCorrection.fix(color);
        lastColorWasCorrected = (fixed != color);
        if (DarkTextConfig.debugMode && lastColorWasCorrected) logDebugColor(color, fixed);
        return fixed;
    }

    // ==========================================================
    // Debug logging (only active when debugMode=true)
    // ==========================================================

    private static Set<Long> debugLoggedPairs;
    private static final int DEBUG_MAX_UNIQUE = 256;

    /** Logs unique color corrections to console, up to DEBUG_MAX_UNIQUE pairs. */
    private static void logDebugColor(int original, int fixed) {
        if (debugLoggedPairs == null) debugLoggedPairs = new HashSet<>();
        if (debugLoggedPairs.size() >= DEBUG_MAX_UNIQUE) return;
        long key = ((long) (original & 0xFFFFFF) << 24) | (fixed & 0xFFFFFF);
        if (debugLoggedPairs.add(key)) {
            System.out.println(
                "[DarkTextFix] DEBUG: #" + String.format("%06X", original & 0xFFFFFF)
                    + " -> #"
                    + String.format("%06X", fixed & 0xFFFFFF));
        }
    }

    // ==========================================================
    // Initialization
    // ==========================================================

    /** Called once to load config and initialize subsystems */
    private static void init() {
        if (initialized) return;
        initialized = true;
        DarkTextConfig.load();
        ColorCorrection.configure(
            "contrast".equals(DarkTextConfig.mode),
            DarkTextConfig.bgR,
            DarkTextConfig.bgG,
            DarkTextConfig.bgB,
            DarkTextConfig.minContrast);
        AE2ColorManager.init();
    }

    /** Package-private init guard for AE2ColorManager.ensureReady() */
    static void ensureInitialized() {
        if (!initialized) init();
    }
}
