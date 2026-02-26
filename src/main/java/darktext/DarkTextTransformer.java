package darktext;

import net.minecraft.launchwrapper.IClassTransformer;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.util.ListIterator;

/**
 * ASM Transformer that:
 * 1. Patches FontRenderer.drawString() to call DarkTextHelper.fixColor()
 * 2. Patches GuiContainer.drawScreen() to set the inContainerGui flag
 * ONLY around the call to drawGuiContainerForegroundLayer(),
 * avoiding item durability/stack count text.
 */
public class DarkTextTransformer implements IClassTransformer {

    private static final String FONT_RENDERER = "net.minecraft.client.gui.FontRenderer";
    private static final String GUI_CONTAINER = "net.minecraft.client.gui.inventory.GuiContainer";

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null)
            return null;

        if (transformedName.equals(FONT_RENDERER)) {
            return transformFontRenderer(basicClass);
        }

        if (transformedName.equals(GUI_CONTAINER)) {
            return transformGuiContainer(basicClass);
        }

        return basicClass;
    }

    // ===== FontRenderer transformation =====

    private byte[] transformFontRenderer(byte[] basicClass) {
        System.out.println("[DarkTextFix] Transforming FontRenderer...");

        try {
            ClassReader cr = new ClassReader(basicClass);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, 0);

            for (MethodNode method : classNode.methods) {
                if (method.name.equals("drawString") ||
                        method.name.equals("a") ||
                        method.name.equals("func_78276_b") ||
                        method.name.equals("func_85187_a")) {
                    patchDrawString(method);
                }
            }

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(cw);
            System.out.println("[DarkTextFix] FontRenderer transformed successfully!");
            return cw.toByteArray();
        } catch (Exception e) {
            System.err.println("[DarkTextFix] ERROR transforming FontRenderer: " + e.getMessage());
            e.printStackTrace();
            return basicClass;
        }
    }

    private void patchDrawString(MethodNode method) {
        String desc = method.desc;
        int colorParamIndex = -1;

        if (desc.equals("(Ljava/lang/String;III)I")) {
            colorParamIndex = 4;
        } else if (desc.equals("(Ljava/lang/String;FFIZ)I")) {
            colorParamIndex = 4;
        } else {
            return;
        }

        System.out.println("[DarkTextFix]   Patching: " + method.name + desc);

        InsnList inject = new InsnList();
        inject.add(new VarInsnNode(Opcodes.ILOAD, colorParamIndex));
        inject.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "darktext/DarkTextHelper",
                "fixColor",
                "(I)I",
                false));
        inject.add(new VarInsnNode(Opcodes.ISTORE, colorParamIndex));

        method.instructions.insert(inject);
    }

    // ===== GuiContainer transformation =====

    private byte[] transformGuiContainer(byte[] basicClass) {
        System.out.println("[DarkTextFix] Transforming GuiContainer...");

        try {
            ClassReader cr = new ClassReader(basicClass);
            ClassNode classNode = new ClassNode();
            cr.accept(classNode, 0);

            for (MethodNode method : classNode.methods) {
                // drawScreen(int mouseX, int mouseY, float partialTicks)
                if ((method.name.equals("drawScreen") || method.name.equals("func_73863_a"))
                        && method.desc.equals("(IIF)V")) {
                    patchDrawScreen(method);
                }
            }

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            classNode.accept(cw);
            System.out.println("[DarkTextFix] GuiContainer transformed successfully!");
            return cw.toByteArray();
        } catch (Exception e) {
            System.err.println("[DarkTextFix] ERROR transforming GuiContainer: " + e.getMessage());
            e.printStackTrace();
            return basicClass;
        }
    }

    /**
     * Patches drawScreen() to wrap ONLY the call to
     * drawGuiContainerForegroundLayer()
     * with the inContainerGui flag. This way:
     * - Title text (inside foreground layer) -> flag is TRUE -> colors get fixed
     * - Item durability/stack count text -> flag is FALSE -> colors are untouched
     * - Tooltips -> flag is FALSE -> colors are untouched
     */
    private void patchDrawScreen(MethodNode method) {
        System.out.println("[DarkTextFix]   Patching GuiContainer.drawScreen() - wrapping foreground layer call");

        boolean found = false;
        ListIterator<AbstractInsnNode> iter = method.instructions.iterator();

        while (iter.hasNext()) {
            AbstractInsnNode node = iter.next();

            // Look for: INVOKEVIRTUAL GuiContainer.drawGuiContainerForegroundLayer(II)V
            if (node.getOpcode() == Opcodes.INVOKEVIRTUAL && node instanceof MethodInsnNode) {
                MethodInsnNode methodCall = (MethodInsnNode) node;

                if ((methodCall.name.equals("drawGuiContainerForegroundLayer") ||
                        methodCall.name.equals("func_146979_b"))
                        && methodCall.desc.equals("(II)V")) {

                    System.out.println(
                            "[DarkTextFix]     Found call to " + methodCall.name + " - injecting flag wrapper");

                    // INSERT BEFORE the call: DarkTextHelper.inContainerGui = true;
                    InsnList setFlag = new InsnList();
                    setFlag.add(new InsnNode(Opcodes.ICONST_1));
                    setFlag.add(new FieldInsnNode(
                            Opcodes.PUTSTATIC,
                            "darktext/DarkTextHelper",
                            "inContainerGui",
                            "Z"));
                    // Insert before the ALOAD that pushes 'this' for the method call.
                    // We need to go back to find the start of the call sequence.
                    // The call sequence is: ALOAD 0, ILOAD x, ILOAD y, INVOKEVIRTUAL
                    // We insert the flag set BEFORE the ALOAD 0.
                    // But it's simpler to insert right before the INVOKEVIRTUAL;
                    // the flag only matters when drawString is called inside the method.
                    method.instructions.insertBefore(node, setFlag);

                    // INSERT AFTER the call: DarkTextHelper.inContainerGui = false;
                    InsnList resetFlag = new InsnList();
                    resetFlag.add(new InsnNode(Opcodes.ICONST_0));
                    resetFlag.add(new FieldInsnNode(
                            Opcodes.PUTSTATIC,
                            "darktext/DarkTextHelper",
                            "inContainerGui",
                            "Z"));
                    method.instructions.insert(node, resetFlag);

                    found = true;
                    break;
                }
            }
        }

        if (!found) {
            System.err.println(
                    "[DarkTextFix]   WARNING: Could not find drawGuiContainerForegroundLayer call in drawScreen!");
            System.out.println("[DarkTextFix]   Falling back: dumping all method calls for debugging...");
            iter = method.instructions.iterator();
            while (iter.hasNext()) {
                AbstractInsnNode node = iter.next();
                if (node instanceof MethodInsnNode) {
                    MethodInsnNode m = (MethodInsnNode) node;
                    System.out.println("[DarkTextFix]     CALL: " + m.owner + "." + m.name + m.desc);
                }
            }
        }
    }
}
