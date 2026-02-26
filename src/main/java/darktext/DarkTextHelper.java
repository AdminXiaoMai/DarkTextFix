package darktext;

/**
 * Runtime color correction for dark theme compatibility.
 * Two modes: HSL inversion or WCAG contrast-based adjustment.
 * 
 * PERFORMANCE CRITICAL: called on every FontRenderer.drawString().
 * All hot paths avoid object allocation and use pre-computed tables.
 */
public class DarkTextHelper {

    /**
     * Context flag set by ASM-patched GuiContainer around
     * drawGuiContainerForegroundLayer()
     */
    public static boolean inContainerGui = false;

    // Pre-computed sRGB linearization table (avoids Math.pow in hot path)
    private static final float[] LINEAR_TABLE = new float[256];

    // Pre-computed values from config (set once at load, read many times)
    private static float bgLuminance;
    private static float targetLuminance;

    private static boolean initialized = false;

    static {
        // Build sRGB -> linear lookup table
        for (int i = 0; i < 256; i++) {
            float c = i / 255f;
            LINEAR_TABLE[i] = (c <= 0.03928f) ? c / 12.92f : (float) Math.pow((c + 0.055) / 1.055, 2.4);
        }
    }

    /** Called once to pre-compute config-derived values */
    private static void init() {
        initialized = true;
        DarkTextConfig.load();
        bgLuminance = luminance(DarkTextConfig.bgR, DarkTextConfig.bgG, DarkTextConfig.bgB);
        // Pre-compute: what luminance does the text need to beat the target contrast?
        targetLuminance = DarkTextConfig.minContrast * (bgLuminance + 0.05f) - 0.05f;
    }

    // ==========================================================
    // Entry point
    // ==========================================================

    public static int fixColor(int color) {
        if (!initialized)
            init();
        if (!DarkTextConfig.enabled || !inContainerGui)
            return color;

        return DarkTextConfig.mode.equals("contrast")
                ? contrastFix(color)
                : invertFix(color);
    }

    // ==========================================================
    // MODE: INVERT
    // ==========================================================

    private static int invertFix(int color) {
        int rgb = color & 0x00FFFFFF;
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;

        // Fast exit: any bright component means color is fine
        if ((r | g | b) > DarkTextConfig.brightnessSkip)
            return color;

        // RGB -> HSL (inline for speed)
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float cmax = rf > gf ? (rf > bf ? rf : bf) : (gf > bf ? gf : bf);
        float cmin = rf < gf ? (rf < bf ? rf : bf) : (gf < bf ? gf : bf);
        float l = (cmax + cmin) * 0.5f;

        if (l >= DarkTextConfig.darkThreshold)
            return color;

        float delta = cmax - cmin;
        float h = 0f, s = 0f;
        if (delta > 0f) {
            s = delta / (1f - abs(2f * l - 1f));
            if (cmax == rf)
                h = ((gf - bf) / delta) % 6f;
            else if (cmax == gf)
                h = ((bf - rf) / delta) + 2f;
            else
                h = ((rf - gf) / delta) + 4f;
            h /= 6f;
            if (h < 0f)
                h += 1f;
        }

        float newL = 1f - l;
        float maxL = DarkTextConfig.maxLightness, minL = DarkTextConfig.minLightness;
        if (newL > maxL)
            newL = maxL;
        if (newL < minL)
            newL = minL;

        return result(color, hslToRgb(h, s, newL));
    }

    // ==========================================================
    // MODE: CONTRAST (WCAG)
    // ==========================================================

    private static int contrastFix(int color) {
        int rgb = color & 0x00FFFFFF;
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;

        // Already meets contrast? Skip.
        if (luminance(r, g, b) >= targetLuminance)
            return color;

        // RGB -> HSL
        float rf = r / 255f, gf = g / 255f, bf = b / 255f;
        float cmax = rf > gf ? (rf > bf ? rf : bf) : (gf > bf ? gf : bf);
        float cmin = rf < gf ? (rf < bf ? rf : bf) : (gf < bf ? gf : bf);
        float delta = cmax - cmin;
        float l = (cmax + cmin) * 0.5f;
        float h = 0f, s = 0f;

        if (delta > 0f) {
            s = delta / (1f - abs(2f * l - 1f));
            if (cmax == rf)
                h = ((gf - bf) / delta) % 6f;
            else if (cmax == gf)
                h = ((bf - rf) / delta) + 2f;
            else
                h = ((rf - gf) / delta) + 4f;
            h /= 6f;
            if (h < 0f)
                h += 1f;
        }

        // Binary search for minimum lightness meeting target luminance
        float lo = l, hi = 1f, best = 1f;
        for (int i = 0; i < 12; i++) {
            float mid = (lo + hi) * 0.5f;
            int test = hslToRgb(h, s, mid);
            if (luminance((test >> 16) & 0xFF, (test >> 8) & 0xFF, test & 0xFF) >= targetLuminance) {
                best = mid;
                hi = mid;
            } else {
                lo = mid;
            }
        }

        return result(color, hslToRgb(h, s, best));
    }

    // ==========================================================
    // Shared utilities (all inlined-friendly, no allocations)
    // ==========================================================

    /** WCAG relative luminance using pre-computed lookup table */
    private static float luminance(int r, int g, int b) {
        return 0.2126f * LINEAR_TABLE[r] + 0.7152f * LINEAR_TABLE[g] + 0.0722f * LINEAR_TABLE[b];
    }

    /** HSL to packed RGB int */
    private static int hslToRgb(float h, float s, float l) {
        float c = (1f - abs(2f * l - 1f)) * s;
        float x = c * (1f - abs((h * 6f) % 2f - 1f));
        float m = l - c * 0.5f;
        float rn, gn, bn;
        float h6 = h * 6f;

        if (h6 < 1f) {
            rn = c;
            gn = x;
            bn = 0;
        } else if (h6 < 2f) {
            rn = x;
            gn = c;
            bn = 0;
        } else if (h6 < 3f) {
            rn = 0;
            gn = c;
            bn = x;
        } else if (h6 < 4f) {
            rn = 0;
            gn = x;
            bn = c;
        } else if (h6 < 5f) {
            rn = x;
            gn = 0;
            bn = c;
        } else {
            rn = c;
            gn = 0;
            bn = x;
        }

        return (clamp255(rn + m) << 16) | (clamp255(gn + m) << 8) | clamp255(bn + m);
    }

    /** Float [0,1] to clamped int [0,255] */
    private static int clamp255(float f) {
        int v = (int) (f * 255f + 0.5f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }

    /** Preserve alpha, apply new RGB, optional debug log */
    private static int result(int origColor, int newRgb) {
        if (DarkTextConfig.debugMode) {
            System.out.printf("[DarkTextFix] #%06X -> #%06X%n", origColor & 0xFFFFFF, newRgb);
        }
        int alpha = origColor & 0xFF000000;
        return alpha == 0 ? newRgb : alpha | newRgb;
    }

    /** Branchless abs for float */
    private static float abs(float f) {
        return f < 0f ? -f : f;
    }
}
