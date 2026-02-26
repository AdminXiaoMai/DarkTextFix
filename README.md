# DarkTextFix

A Minecraft 1.7.10 coremod that fixes dark/unreadable text in container GUIs when using dark resource packs.

## The Problem

Minecraft hardcodes the container GUI title text color to `0x404040` (dark gray). This is designed for the vanilla light-colored GUI backgrounds, but becomes nearly invisible when using dark-themed resource packs.

![Before/After comparison — dark text becomes readable on dark backgrounds](https://img.shields.io/badge/status-working-brightgreen)

## How It Works

DarkTextFix uses ASM bytecode manipulation to:

1. **Patch `FontRenderer.drawString()`** — intercepts the color parameter and applies a lightness correction
2. **Patch `GuiContainer.drawScreen()`** — sets a context flag *only* during `drawGuiContainerForegroundLayer()`, so item durability bars, stack counts, and tooltips remain untouched

### Color Correction Modes

| Mode | Description |
|---|---|
| **Invert** (default) | Inverts HSL lightness — dark colors become light. Simple and effective. |
| **Contrast** | Uses the WCAG accessibility formula to calculate the minimum lightness needed for readability against a configurable background color. More natural results. |

Both modes preserve the original hue and saturation — only lightness is adjusted.

## Installation

1. Download `DarkTextFix-1.0.jar` from [Releases](../../releases)
2. Place it in your `.minecraft/mods/` folder
3. Launch the game — a config file will be created at `config/darktextfix.cfg`

### Requirements

- Minecraft **1.7.10**
- Forge

## Configuration

The config file `config/darktextfix.cfg` is auto-generated on first run. All changes require a game restart.

```properties
# ---- General ----
B:enabled=true
B:debugMode=false

# ---- Mode ----
# "invert" or "contrast"
S:mode=invert

# ---- Invert Mode ----
D:darkThreshold=0.45      # Colors darker than this get lightened
D:minLightness=0.65        # Minimum lightness after correction
D:maxLightness=0.93        # Maximum lightness after correction
I:brightnessSkip=140       # Skip colors with any RGB > this value

# ---- Contrast Mode ----
S:backgroundHex=444444     # Your dark pack's GUI background color
D:minContrast=4.5          # WCAG contrast ratio (4.5=AA, 7.0=AAA)
```

## Building from Source

### Prerequisites

- JDK 8+ (compiles with `--release 8`)
- Forge 1.7.10 universal JAR
- ASM 9.x libraries (included in `lib/`)

### Compile

```bash
javac -cp "lib/asm-9.7.1.jar;lib/asm-tree-9.7.1.jar;path/to/forge-universal.jar" \
  -d build/classes --release 8 \
  src/main/java/darktext/*.java

jar cfm DarkTextFix-1.0.jar src/main/resources/META-INF/MANIFEST.MF \
  -C build/classes .
```

## Technical Details

### Performance

The `fixColor()` method is called on every `FontRenderer.drawString()` invocation. To minimize overhead:

- **Fast exit**: colors with any RGB component above `brightnessSkip` are returned immediately
- **Context flag**: only processes colors during container GUI rendering (not world text, chat, etc.)
- **Pre-computed tables**: sRGB linearization uses a 256-entry lookup table
- **Zero allocations**: all math is done with primitives

### Scope

The color fix is applied **only** to text rendered inside `GuiContainer.drawGuiContainerForegroundLayer()`. This means:

| Text Type | Fixed? | Why |
|---|---|---|
| Container titles / labels | ✅ Yes | Rendered inside foreground layer |
| Item stack counts | ❌ No | Rendered outside foreground layer |
| Item durability text | ❌ No | Rendered outside foreground layer |
| Tooltips | ❌ No | Rendered after foreground layer |
| Chat / HUD | ❌ No | Not a container GUI |

## License

MIT License — see [LICENSE](LICENSE) for details.
