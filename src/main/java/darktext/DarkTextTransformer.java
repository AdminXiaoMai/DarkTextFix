package darktext;

import java.util.HashMap;
import java.util.ListIterator;
import java.util.Map;
import java.util.function.Consumer;

import net.minecraft.launchwrapper.IClassTransformer;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * ASM Transformer — patches text color handling for dark theme compatibility.
 * Uses a registry-based dispatch for 21 target classes across 5 pattern types:
 *
 * Pattern 1: ContainerFlag(drawScreen) — 6 targets
 * Pattern 2: BiblioCraft suppress (skipFixColor) — 4 targets
 * Pattern 3: Multi-method flag — 2 targets
 * Pattern 4: ContainerFlag + extra — 1 target
 * Pattern 5: Custom patching — 8 targets
 */
public class DarkTextTransformer implements IClassTransformer {

    // ==========================================================
    // Target class name constants
    // ==========================================================

    private static final String FONT_RENDERER = "net.minecraft.client.gui.FontRenderer";
    private static final String GUI_CONTAINER = "net.minecraft.client.gui.inventory.GuiContainer";
    private static final String MODULAR_GUI = "com.gtnewhorizons.modularui.common.internal.wrapper.ModularGui";
    private static final String CLIENT_SCREEN_HANDLER = "com.cleanroommc.modularui.screen.ClientScreenHandler";
    private static final String BATCHING_FONT_RENDERER = "com.gtnewhorizons.angelica.client.font.BatchingFontRenderer";
    private static final String RENDER_ITEM = "net.minecraft.client.renderer.entity.RenderItem";
    private static final String BQ_GUI_SCREEN_CANVAS = "betterquesting.api2.client.gui.GuiScreenCanvas";
    private static final String DE_GUI_FLOW_GATE = "com.brandon3055.draconicevolution.client.gui.GUIFlowGate";
    private static final String DE_GUI_TELEPORTER = "com.brandon3055.draconicevolution.client.gui.GUITeleporter";
    private static final String SC2_GUI_NEI_KILLER = "vswe.stevescarts.Interfaces.GuiNEIKiller";
    private static final String BC_GUI_ATLAS = "jds.bibliocraft.gui.GuiAtlas";
    private static final String BC_GUI_FANCY_SIGN = "jds.bibliocraft.gui.GuiFancySign";
    private static final String BC_GUI_SLOTTED_BOOK = "jds.bibliocraft.gui.GuiSlottedBook";
    private static final String BC_GUI_FANCY_WORKBENCH = "jds.bibliocraft.gui.GuiFancyWorkbench";
    private static final String MALISIS_GUI = "net.malisis.core.client.gui.MalisisGui";
    private static final String MALISIS_FONT = "net.malisis.core.renderer.font.MalisisFont";

    // ASM owner constants
    private static final String AE2_MANAGER = "darktext/AE2ColorManager";

    // ==========================================================
    // Registry
    // ==========================================================

    private static class TransformEntry {

        final String label;
        final int writerFlags;
        final Consumer<ClassNode> patcher;

        TransformEntry(String label, int writerFlags, Consumer<ClassNode> patcher) {
            this.label = label;
            this.writerFlags = writerFlags;
            this.patcher = patcher;
        }

        TransformEntry(String label, Consumer<ClassNode> patcher) {
            this(label, ClassWriter.COMPUTE_MAXS, patcher);
        }
    }

    private final Map<String, TransformEntry> registry = new HashMap<>(32);

    public DarkTextTransformer() {
        // === Pattern 1: Container flag on drawScreen (6 targets) ===
        registerContainerFlagDrawScreen(BQ_GUI_SCREEN_CANVAS, "BQ GuiScreenCanvas");
        registerContainerFlagDrawScreen(DE_GUI_FLOW_GATE, "DE GUIFlowGate");
        registerContainerFlagDrawScreen(DE_GUI_TELEPORTER, "DE GUITeleporter");
        registerContainerFlagDrawScreen(SC2_GUI_NEI_KILLER, "SC2 GuiNEIKiller");
        registerContainerFlagDrawScreen(MALISIS_GUI, "MalisisGui");
        registerContainerFlagDrawScreen("vswe.stevesfactory.interfaces.GuiAntiNEI", "SFM GuiAntiNEI");
        registerContainerFlagDrawScreen("com.glodblock.github.client.gui.GuiLevelTerminal", "AE2FC GuiLevelTerminal");

        // === Pattern 2: BiblioCraft suppression (4 targets) ===
        registerBiblioCraftSuppressed(BC_GUI_ATLAS, "BC GuiAtlas");
        registerBiblioCraftSuppressed(BC_GUI_FANCY_SIGN, "BC GuiFancySign");
        registerBiblioCraftSuppressed(BC_GUI_SLOTTED_BOOK, "BC GuiSlottedBook");
        registerBiblioCraftSuppressed(BC_GUI_FANCY_WORKBENCH, "BC GuiFancyWorkbench");

        // === Pattern 3: Flag on specific methods (2 targets) ===
        register(CLIENT_SCREEN_HANDLER, "ClientScreenHandler", cn -> {
            for (MethodNode m : cn.methods) {
                if (m.name.equals("drawContainer") || m.name.equals("drawScreenInternal")) {
                    patchMethodWithContainerFlag(m);
                }
            }
        });
        register(RENDER_ITEM, "RenderItem", cn -> {
            for (MethodNode m : cn.methods) {
                if (m.name.equals("renderItemOverlayIntoGUI") || m.name.equals("func_94148_a")) {
                    patchMethodWithFlag(m, "inItemOverlay");
                }
            }
        });

        // === Pattern 4: Container flag + extra (1 target) ===
        register(MODULAR_GUI, "ModularGui", cn -> {
            for (MethodNode m : cn.methods) {
                if (isDrawScreen(m)) patchMethodWithContainerFlag(m);
                if (m.name.equals("drawText") && m.desc.equals("(Ljava/lang/String;FFFIZ)V")) {
                    patchColorParam(m, 5);
                }
            }
        });

        // === Pattern 5: Custom targets (8 targets) ===
        register(FONT_RENDERER, "FontRenderer", this::patchFontRenderer);
        register(GUI_CONTAINER, "GuiContainer", this::patchGuiContainer);
        register(BATCHING_FONT_RENDERER, "BatchingFontRenderer (Angelica)", this::patchBatchingFontRenderer);
        register("appeng.client.gui.widgets.GuiCraftingCPUTable", "GuiCraftingCPUTable", this::patchAE2DrawFGContainer);
        register("appeng.client.gui.implementations.GuiCraftingCPU", "GuiCraftingCPU", this::patchAE2DrawFGContainer);
        registerWithFrames("appeng.core.localization.GuiColors", "GuiColors", this::patchGuiColorsClass);
        register("vswe.stevesfactory.interfaces.GuiBase", "SFM GuiBase", this::patchGuiBase);
        register(MALISIS_FONT, "MalisisFont", this::patchMalisisFont);
    }

    // ==========================================================
    // Transform dispatch
    // ==========================================================

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        var entry = registry.get(transformedName);
        if (entry == null) return basicClass;
        return transformClass(entry.label, basicClass, entry.writerFlags, entry.patcher);
    }

    // ==========================================================
    // Generic transform boilerplate
    // ==========================================================

    /**
     * Common transform pattern: read class -> apply patcher -> write class.
     * Handles logging and exception recovery for all transform methods.
     */
    private byte[] transformClass(String label, byte[] basicClass, int writerFlags, Consumer<ClassNode> patcher) {
        System.out.println("[DarkTextFix] Transforming " + label + "...");
        try {
            ClassNode classNode = new ClassNode();
            new ClassReader(basicClass).accept(classNode, 0);

            patcher.accept(classNode);

            ClassWriter cw = new ClassWriter(writerFlags);
            classNode.accept(cw);
            System.out.println("[DarkTextFix] " + label + " transformed.");
            return cw.toByteArray();
        } catch (Exception e) {
            System.err.println("[DarkTextFix] ERROR transforming " + label + ": " + e.getMessage());
            return basicClass;
        }
    }

    // ==========================================================
    // Class-level patchers (Consumer<ClassNode>)
    // ==========================================================

    private void patchFontRenderer(ClassNode cn) {
        for (MethodNode method : cn.methods) {
            patchDrawString(method);

            // Patch formatting code color handling in renderStringAtPos
            if ((method.name.equals("renderStringAtPos") || method.name.equals("func_78255_a"))
                && method.desc.equals("(Ljava/lang/String;Z)V")) {
                patchFormattingCodeColors(method, "field_78285_g", "colorCode");
            }
        }
    }

    private void patchGuiContainer(ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if ((method.name.equals("drawScreen") || method.name.equals("func_73863_a"))
                && method.desc.equals("(IIF)V")) {
                patchDrawScreen(method);
            }
        }
    }

    private void patchBatchingFontRenderer(ClassNode cn) {
        boolean patchedEntry = false;
        boolean patchedFormatting = false;
        for (MethodNode method : cn.methods) {
            if (method.name.equals("drawString") && method.desc.equals("(FFIZZLjava/lang/CharSequence;II)F")) {
                InsnList fix = new InsnList();
                fix.add(new VarInsnNode(Opcodes.ILOAD, 3));
                fix.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "darktext/DarkTextHelper", "fixColor", "(I)I", false));
                fix.add(new VarInsnNode(Opcodes.ISTORE, 3));
                method.instructions.insert(fix);
                patchedEntry = true;

                patchFormattingCodeColors(method, "colorCode");
                patchedFormatting = true;
            }
        }
        if (!patchedEntry) {
            System.err.println("[DarkTextFix]   WARNING: drawString not found in BatchingFontRenderer");
        }
        if (!patchedFormatting) {
            System.err.println("[DarkTextFix]   WARNING: formatting code patch not applied to BatchingFontRenderer");
        }
    }

    private void patchAE2DrawFGContainer(ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if (method.name.equals("drawFG") && (method.desc.equals("(IIIIII)V") || method.desc.equals("(IIII)V"))) {
                patchDrawFG(method);
            }
        }
    }

    private void patchGuiColorsClass(ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if (method.name.equals("getColor") && method.desc.equals("()I")) {
                patchGetColor(method);
            }
        }
    }

    private void patchGuiBase(ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if (method.name.equals("drawString") && method.desc.equals("(Ljava/lang/String;IIFI)V")) {
                patchColorParam(method, 5);
            }
            if (method.name.equals("drawSplitString") && method.desc.equals("(Ljava/lang/String;IIIFI)V")) {
                patchColorParam(method, 6);
            }
        }
    }

    /**
     * Suppresses fixColor() for BiblioCraft GuiContainer subclasses by setting
     * skipFixColor=true during rendering methods. Two strategies based on class structure:
     *
     * 1. If the class overrides drawScreen(): patch drawScreen to set skipFixColor=true
     * for the entire method scope.
     * 2. If the class does NOT override drawScreen(): patch BG/FG layer and mouseClicked
     * individually, since these are called within GuiContainer's inContainerGui=true scope.
     */
    private void patchBiblioCraftSuppressed(ClassNode cn) {
        boolean hasDrawScreen = false;
        for (MethodNode method : cn.methods) {
            if (isDrawScreen(method)) {
                patchMethodWithFlag(method, "skipFixColor");
                hasDrawScreen = true;
            }
        }
        // No drawScreen override — patch the individual rendering methods instead
        if (!hasDrawScreen) {
            for (MethodNode method : cn.methods) {
                boolean isBgLayer = (method.name.equals("drawGuiContainerBackgroundLayer")
                    || method.name.equals("func_146976_a")) && method.desc.equals("(FII)V");
                boolean isFgLayer = (method.name.equals("drawGuiContainerForegroundLayer")
                    || method.name.equals("func_146979_b")) && method.desc.equals("(II)V");
                boolean isMouseClicked = (method.name.equals("mouseClicked") || method.name.equals("func_73864_a"))
                    && method.desc.equals("(III)V");
                if (isBgLayer || isFgLayer || isMouseClicked) {
                    patchMethodWithFlag(method, "skipFixColor");
                }
            }
        }
    }

    private void patchMalisisFont(ClassNode cn) {
        for (MethodNode method : cn.methods) {
            if ((method.name.equals("drawChar") || method.name.equals("drawLineChar")) && method.desc.equals(
                "(Lnet/malisis/core/renderer/font/CharData;FFLnet/malisis/core/renderer/font/FontRenderOptions;)V")) {
                patchTessellatorColor(method);
            }
        }
    }

    // ==========================================================
    // Method-level patchers
    // ==========================================================

    /**
     * Patches FontRenderer methods that have a color parameter.
     * Matches by descriptor to handle all obfuscated name variants.
     */
    private void patchDrawString(MethodNode method) {
        String desc = method.desc;
        int colorSlot;

        if (desc.equals("(Ljava/lang/String;III)I") || desc.equals("(Ljava/lang/String;IIIZ)I")
            || desc.equals("(Ljava/lang/String;FFIZ)I")) {
            colorSlot = 4;
        } else {
            return;
        }

        InsnList inject = new InsnList();
        inject.add(new VarInsnNode(Opcodes.ILOAD, colorSlot));
        inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "darktext/DarkTextHelper", "fixColor", "(I)I", false));
        inject.add(new VarInsnNode(Opcodes.ISTORE, colorSlot));
        method.instructions.insert(inject);

        // For shadow-enabled variants, disable shadow only when fixColor() actually changed the color
        if (desc.endsWith("IZ)I")) {
            LabelNode skipShadow = new LabelNode();
            InsnList shadowFix = new InsnList();
            shadowFix
                .add(new FieldInsnNode(Opcodes.GETSTATIC, "darktext/DarkTextHelper", "lastColorWasCorrected", "Z"));
            shadowFix.add(new JumpInsnNode(Opcodes.IFEQ, skipShadow));
            shadowFix.add(new InsnNode(Opcodes.ICONST_0));
            shadowFix.add(new VarInsnNode(Opcodes.ISTORE, 5));
            shadowFix.add(skipShadow);
            method.instructions.insert(inject.getLast(), shadowFix);
        }
    }

    /**
     * Wraps both drawGuiContainerBackgroundLayer() and drawGuiContainerForegroundLayer()
     * calls with the inContainerGui flag in GuiContainer.drawScreen().
     */
    private void patchDrawScreen(MethodNode method) {
        ListIterator<AbstractInsnNode> iter = method.instructions.iterator();
        while (iter.hasNext()) {
            AbstractInsnNode node = iter.next();
            if (node.getOpcode() != Opcodes.INVOKEVIRTUAL || !(node instanceof MethodInsnNode)) continue;

            MethodInsnNode call = (MethodInsnNode) node;

            boolean isBgLayer = (call.name.equals("drawGuiContainerBackgroundLayer")
                || call.name.equals("func_146976_a")) && call.desc.equals("(FII)V");
            boolean isFgLayer = (call.name.equals("drawGuiContainerForegroundLayer")
                || call.name.equals("func_146979_b")) && call.desc.equals("(II)V");

            if (!isBgLayer && !isFgLayer) continue;

            InsnList setFlag = new InsnList();
            setFlag.add(new InsnNode(Opcodes.ICONST_1));
            setFlag.add(new FieldInsnNode(Opcodes.PUTSTATIC, "darktext/DarkTextHelper", "inContainerGui", "Z"));
            method.instructions.insertBefore(node, setFlag);

            InsnList resetFlag = new InsnList();
            resetFlag.add(new InsnNode(Opcodes.ICONST_0));
            resetFlag.add(new FieldInsnNode(Opcodes.PUTSTATIC, "darktext/DarkTextHelper", "inContainerGui", "Z"));
            method.instructions.insert(node, resetFlag);
        }
    }

    /**
     * Patches AE2 drawFG: redirects GL11.glColor4f to AE2ColorManager.dynamicColor4f,
     * replaces GuiColors.getColor() with AE2ColorManager config-based getters.
     */
    private void patchDrawFG(MethodNode method) {
        InsnList instructions = method.instructions;

        // Insert enterCpuTableDrawFG() at method start
        instructions.insertBefore(
            instructions.getFirst(),
            new MethodInsnNode(Opcodes.INVOKESTATIC, AE2_MANAGER, "enterCpuTableDrawFG", "()V", false));

        // Replace GL11.glColor4f -> AE2ColorManager.dynamicColor4f
        ListIterator<AbstractInsnNode> iterator = instructions.iterator();
        while (iterator.hasNext()) {
            AbstractInsnNode insn = iterator.next();
            if (insn.getOpcode() == Opcodes.INVOKESTATIC) {
                MethodInsnNode call = (MethodInsnNode) insn;
                if (call.owner.equals("org/lwjgl/opengl/GL11") && call.name.equals("glColor4f")
                    && call.desc.equals("(FFFF)V")) {
                    call.owner = AE2_MANAGER;
                    call.name = "dynamicColor4f";
                }
            }
        }

        // Replace GuiColors.getColor() -> AE2ColorManager.getCrafting*Color()
        iterator = instructions.iterator();
        while (iterator.hasNext()) {
            AbstractInsnNode insn = iterator.next();
            if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;

            MethodInsnNode call = (MethodInsnNode) insn;
            if (!call.owner.equals("appeng/core/localization/GuiColors") || !call.name.equals("getColor")
                || !call.desc.equals("()I")) continue;

            AbstractInsnNode prev = insn.getPrevious();
            if (prev == null || prev.getOpcode() != Opcodes.GETSTATIC) continue;

            String enumName = ((FieldInsnNode) prev).name;
            String replacement = null;
            if ("CraftingCPUActive".equals(enumName)) replacement = "getCraftingCPUActiveColor";
            else if ("CraftingCPUInactive".equals(enumName)) replacement = "getCraftingCPUInactiveColor";
            else if ("CraftingCPUScheduled".equals(enumName)) replacement = "getCraftingCPUScheduledColor";
            else if ("CraftingCPUAmount".equals(enumName)) replacement = "getCraftingCPUCraftingColor";

            if (replacement != null) {
                instructions.remove(prev);
                instructions
                    .set(insn, new MethodInsnNode(Opcodes.INVOKESTATIC, AE2_MANAGER, replacement, "()I", false));
            }
        }

        // Insert exitCpuTableDrawFG() before every RETURN
        iterator = instructions.iterator();
        while (iterator.hasNext()) {
            AbstractInsnNode insn = iterator.next();
            if (insn.getOpcode() == Opcodes.RETURN) {
                instructions.insertBefore(
                    insn,
                    new MethodInsnNode(Opcodes.INVOKESTATIC, AE2_MANAGER, "exitCpuTableDrawFG", "()V", false));
            }
        }
    }

    private void patchGetColor(MethodNode method) {
        InsnList insns = new InsnList();
        String[] enums = { "CraftingCPUActive", "CraftingCPUInactive", "CraftingCPUScheduled", "CraftingCPUAmount" };

        for (String enumName : enums) {
            LabelNode nextCheck = new LabelNode();
            insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
            insns.add(
                new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    "appeng/core/localization/GuiColors",
                    enumName,
                    "Lappeng/core/localization/GuiColors;"));
            insns.add(new JumpInsnNode(Opcodes.IF_ACMPNE, nextCheck));
            insns.add(new LdcInsnNode(enumName));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    AE2_MANAGER,
                    "getConfigColor",
                    "(Ljava/lang/String;)I",
                    false));
            insns.add(new InsnNode(Opcodes.IRETURN));
            insns.add(nextCheck);
        }

        method.instructions.insert(insns);
    }

    // ==========================================================
    // Shared utilities
    // ==========================================================

    /** Checks if a method is drawScreen / func_73863_a with (IIF)V descriptor */
    private static boolean isDrawScreen(MethodNode m) {
        return (m.name.equals("drawScreen") || m.name.equals("func_73863_a")) && m.desc.equals("(IIF)V");
    }

    /**
     * Generic flag patcher: sets a boolean field on DarkTextHelper to true at method entry,
     * false before every RETURN.
     */
    private void patchMethodWithFlag(MethodNode method, String fieldName) {
        InsnList enter = new InsnList();
        enter.add(new InsnNode(Opcodes.ICONST_1));
        enter.add(new FieldInsnNode(Opcodes.PUTSTATIC, "darktext/DarkTextHelper", fieldName, "Z"));
        method.instructions.insert(enter);

        ListIterator<AbstractInsnNode> iterator = method.instructions.iterator();
        while (iterator.hasNext()) {
            AbstractInsnNode insn = iterator.next();
            if (insn.getOpcode() == Opcodes.RETURN) {
                InsnList exit = new InsnList();
                exit.add(new InsnNode(Opcodes.ICONST_0));
                exit.add(new FieldInsnNode(Opcodes.PUTSTATIC, "darktext/DarkTextHelper", fieldName, "Z"));
                method.instructions.insertBefore(insn, exit);
            }
        }
    }

    /** Sets inContainerGui=true at method entry, false before every RETURN */
    private void patchMethodWithContainerFlag(MethodNode method) {
        patchMethodWithFlag(method, "inContainerGui");
    }

    /** Injects fixColor() on a color parameter at the given slot index */
    private void patchColorParam(MethodNode method, int colorSlot) {
        InsnList inject = new InsnList();
        inject.add(new VarInsnNode(Opcodes.ILOAD, colorSlot));
        inject.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "darktext/DarkTextHelper", "fixColor", "(I)I", false));
        inject.add(new VarInsnNode(Opcodes.ISTORE, colorSlot));
        method.instructions.insert(inject);
    }

    /** Injects fixColor() before Tessellator.setColorOpaque_I() calls */
    private void patchTessellatorColor(MethodNode method) {
        ListIterator<AbstractInsnNode> iter = method.instructions.iterator();
        int patchCount = 0;
        while (iter.hasNext()) {
            AbstractInsnNode insn = iter.next();
            if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) continue;
            MethodInsnNode call = (MethodInsnNode) insn;
            if (call.owner.equals("net/minecraft/client/renderer/Tessellator")
                && (call.name.equals("func_78378_d") || call.name.equals("setColorOpaque_I"))
                && call.desc.equals("(I)V")) {
                method.instructions.insertBefore(
                    insn,
                    new MethodInsnNode(Opcodes.INVOKESTATIC, "darktext/DarkTextHelper", "fixColor", "(I)I", false));
                patchCount++;
            }
        }
        System.out.println("[DarkTextFix]   Patched " + patchCount + " Tessellator.setColorOpaque_I call(s)");
    }

    /**
     * Patches formatting code color handling: injects fixColor() after every
     * colorCode[index] array read (IALOAD) within the target method.
     */
    private void patchFormattingCodeColors(MethodNode method, String... colorCodeFieldNames) {
        ListIterator<AbstractInsnNode> iter = method.instructions.iterator();
        int patchCount = 0;
        while (iter.hasNext()) {
            AbstractInsnNode node = iter.next();
            if (!(node instanceof FieldInsnNode)) continue;
            FieldInsnNode field = (FieldInsnNode) node;
            if (field.getOpcode() != Opcodes.GETFIELD || !field.desc.equals("[I")) continue;

            boolean nameMatch = false;
            for (String name : colorCodeFieldNames) {
                if (field.name.equals(name)) {
                    nameMatch = true;
                    break;
                }
            }
            if (!nameMatch) continue;

            // Found GETFIELD colorCode — scan forward for the next IALOAD
            AbstractInsnNode cursor = field.getNext();
            int limit = 5;
            while (cursor != null && cursor.getOpcode() != Opcodes.IALOAD && limit-- > 0) {
                cursor = cursor.getNext();
            }
            if (cursor == null || cursor.getOpcode() != Opcodes.IALOAD) continue;

            // Insert fixColor(int) after IALOAD
            method.instructions.insert(
                cursor,
                new MethodInsnNode(Opcodes.INVOKESTATIC, "darktext/DarkTextHelper", "fixColor", "(I)I", false));
            patchCount++;
        }
        System.out.println("[DarkTextFix]   Patched " + patchCount + " formatting code colorCode access(es)");
    }

    // ==========================================================
    // Registration helpers
    // ==========================================================

    private void register(String className, String label, Consumer<ClassNode> patcher) {
        registry.put(className, new TransformEntry(label, patcher));
    }

    private void registerWithFrames(String className, String label, Consumer<ClassNode> patcher) {
        registry.put(className, new TransformEntry(label, ClassWriter.COMPUTE_FRAMES, patcher));
    }

    private void registerContainerFlagDrawScreen(String className, String label) {
        register(className, label, cn -> {
            for (MethodNode m : cn.methods) {
                if (isDrawScreen(m)) patchMethodWithContainerFlag(m);
            }
        });
    }

    private void registerBiblioCraftSuppressed(String className, String label) {
        register(className, label, this::patchBiblioCraftSuppressed);
    }
}
