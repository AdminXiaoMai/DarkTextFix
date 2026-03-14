package darktext;

import java.lang.reflect.Field;

import org.lwjgl.opengl.GL11;

/**
 * AE2 integration manager for CPU slot colors and GuiColors enum patching.
 *
 * Handles:
 * - CPU table slot color mapping (dynamicColor4f, mapCpuSlotColor)
 * - GuiColors enum field patching via reflection
 * - Config-based color getters called by ASM-injected code
 *
 * NOT on the hot path: only active during AE2 GUI rendering.
 */
public class AE2ColorManager {

    /** Context flag set by ASM around AE2 drawFG methods */
    private static boolean inCpuTableDrawFG = false;

    /** Scratch buffer for CPU slot color mapping (render-thread only, zero allocation) */
    private static final float[] CPU_COLOR_BUF = new float[4];

    private static boolean guiColorsPatched = false;

    // ==========================================================
    // CPU table draw state (called by ASM)
    // ==========================================================

    public static void enterCpuTableDrawFG() {
        inCpuTableDrawFG = true;
    }

    public static void exitCpuTableDrawFG() {
        inCpuTableDrawFG = false;
    }

    // ==========================================================
    // Dynamic color (called by ASM — replaces GL11.glColor4f)
    // ==========================================================

    /**
     * If rendering inside CPU table drawFG, maps the original AE2 color to
     * configured values; otherwise passes through to GL11.glColor4f directly.
     */
    public static void dynamicColor4f(float r, float g, float b, float a) {
        if (inCpuTableDrawFG) {
            mapCpuSlotColor(r, g, b, a);
            GL11.glColor4f(CPU_COLOR_BUF[0], CPU_COLOR_BUF[1], CPU_COLOR_BUF[2], CPU_COLOR_BUF[3]);
        } else {
            GL11.glColor4f(r, g, b, a);
        }
    }

    /**
     * Maps original AE2 CPU slot colors to configured values.
     * Uses quantized integer key matching: RGB quantized to 8-bit and packed
     * into a 24-bit key for zero-allocation switch dispatch.
     * All known AE2 color patterns have alpha=1.0; non-standard alpha is passed through.
     */
    private static void mapCpuSlotColor(float r, float g, float b, float a) {
        // All known AE2 CPU color patterns have alpha=1.0
        if ((int) (a * 255f + 0.5f) != 255) {
            setCpuBuf(r, g, b, a);
            return;
        }

        int key = ((int) (r * 255f + 0.5f) << 16) | ((int) (g * 255f + 0.5f) << 8) | (int) (b * 255f + 0.5f);

        switch (key) {
            case 0xFF4040: // (1.0, 0.25, 0.25) selected + filtered
                setCpuBuf(DarkTextConfig.cpuSelectedFiltered);
                return;
            case 0xFFFF40: // (1.0, 1.0, 0.25) selected + mergeable
                setCpuBuf(DarkTextConfig.cpuSelectedMergeable);
                return;
            case 0x00D5FF: // (0.0, 0.8352, 1.0) selected + normal
                setCpuBuf(DarkTextConfig.cpuSelectedNormal);
                return;
            case 0xA6E6FF: // (0.65, 0.9, 1.0) hovered
                setCpuBuf(DarkTextConfig.cpuHovered);
                return;
            case 0xE6A6A6: // (0.9, 0.65, 0.65) unselected + filtered
                setCpuBuf(DarkTextConfig.cpuUnselectedFiltered);
                return;
            case 0xFFFFB3: // (1.0, 1.0, 0.7) unselected + mergeable
                setCpuBuf(DarkTextConfig.cpuUnselectedMergeable);
                return;
            case 0xFFFFFF: // (1.0, 1.0, 1.0) default
                setCpuBuf(DarkTextConfig.cpuDefault);
                return;
            default:
                setCpuBuf(r, g, b, a);
        }
    }

    /** Write CpuColor RGBA to the static scratch buffer */
    private static void setCpuBuf(DarkTextConfig.CpuColor c) {
        CPU_COLOR_BUF[0] = c.r;
        CPU_COLOR_BUF[1] = c.g;
        CPU_COLOR_BUF[2] = c.b;
        CPU_COLOR_BUF[3] = c.a;
    }

    /** Write raw RGBA to the static scratch buffer */
    private static void setCpuBuf(float r, float g, float b, float a) {
        CPU_COLOR_BUF[0] = r;
        CPU_COLOR_BUF[1] = g;
        CPU_COLOR_BUF[2] = b;
        CPU_COLOR_BUF[3] = a;
    }

    // ==========================================================
    // GuiCraftingCPU color getters (called by ASM)
    // ==========================================================

    public static int getCraftingCPUActiveColor() {
        ensureReady();
        return DarkTextConfig.craftingCPUActive;
    }

    public static int getCraftingCPUInactiveColor() {
        ensureReady();
        return DarkTextConfig.craftingCPUInactive;
    }

    public static int getCraftingCPUScheduledColor() {
        ensureReady();
        return DarkTextConfig.craftingCPUScheduled;
    }

    public static int getCraftingCPUCraftingColor() {
        ensureReady();
        return DarkTextConfig.craftingCPUCrafting;
    }

    /**
     * Returns configured color for a GuiColors enum name.
     * Called by ASM-injected code in GuiColors.getColor().
     */
    public static int getConfigColor(String enumName) {
        switch (enumName) {
            case "CraftingCPUActive":
                return DarkTextConfig.craftingCPUActive;
            case "CraftingCPUInactive":
                return DarkTextConfig.craftingCPUInactive;
            case "CraftingCPUScheduled":
                return DarkTextConfig.craftingCPUScheduled;
            case "CraftingCPUAmount":
                return DarkTextConfig.craftingCPUCrafting;
            default:
                return 0;
        }
    }

    // ==========================================================
    // Initialization
    // ==========================================================

    /** Called by DarkTextHelper.init() to apply GuiColors patches */
    static void init() {
        patchGuiColors();
    }

    /** Common init guard for getCraftingCPU*Color() methods */
    private static void ensureReady() {
        DarkTextHelper.ensureInitialized();
        ensureGuiColorsPatched();
    }

    private static void ensureGuiColorsPatched() {
        if (guiColorsPatched) return;
        try {
            Class.forName("appeng.core.localization.GuiColors", true, AE2ColorManager.class.getClassLoader());
            patchGuiColors();
        } catch (ClassNotFoundException e) {
            // AE2 not installed
        }
    }

    @SuppressWarnings("unchecked")
    private static void patchGuiColors() {
        if (guiColorsPatched) return;
        try {
            Class<?> guiColorsClass = Class.forName("appeng.core.localization.GuiColors");

            Object active = Enum.valueOf((Class<Enum>) guiColorsClass, "CraftingCPUActive");
            Object inactive = Enum.valueOf((Class<Enum>) guiColorsClass, "CraftingCPUInactive");
            Object scheduled = Enum.valueOf((Class<Enum>) guiColorsClass, "CraftingCPUScheduled");
            Object crafting = Enum.valueOf((Class<Enum>) guiColorsClass, "CraftingCPUAmount");

            Field colorField = guiColorsClass.getDeclaredField("color");
            colorField.setAccessible(true);

            colorField.set(active, DarkTextConfig.craftingCPUActive);
            colorField.set(inactive, DarkTextConfig.craftingCPUInactive);
            colorField.set(scheduled, DarkTextConfig.craftingCPUScheduled);
            colorField.set(crafting, DarkTextConfig.craftingCPUCrafting);

            int newActive = colorField.getInt(active);
            int newInactive = colorField.getInt(inactive);
            int newScheduled = colorField.getInt(scheduled);
            int newCrafting = colorField.getInt(crafting);
            System.out.println("[DarkTextFix] Patched GuiColors successfully:");
            System.out.println("  active   = 0x" + Integer.toHexString(newActive));
            System.out.println("  inactive = 0x" + Integer.toHexString(newInactive));
            System.out.println("  scheduled= 0x" + Integer.toHexString(newScheduled));
            System.out.println("  crafting = 0x" + Integer.toHexString(newCrafting));

            guiColorsPatched = true;
        } catch (Exception e) {
            System.err.println("[DarkTextFix] Failed to patch GuiColors: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
