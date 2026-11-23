# SmartDraw Android

Native Android reference-guided painting and sketch application built in Kotlin with OpenGL ES 3.0 hardware-accelerated shaders, pressure-sensitive stylus dynamics with palm rejection, Bezier curve stroke smoothing, dynamic reference color auto-picking, distance-monotonic Ratchet Mode painting, and Autodesk Sketchbook-inspired non-destructive document persistence and project gallery.

---

## Key Features

### 1. Hard Eraser Tool & Hardware S-Pen Eraser Support
- **Destination-Alpha Subtraction Blending**: Real-time antialiased pixel erasure using dedicated OpenGL ES blend modes:
  $$\text{glBlendFuncSeparate}(\text{GL\_ZERO}, \text{GL\_ONE\_MINUS\_SRC\_ALPHA}, \text{GL\_ZERO}, \text{GL\_ONE\_MINUS\_SRC\_ALPHA})$$
  Smoothly carves away pixel opacity down to complete transparency (`0x00000000`), allowing underlying layers and the canvas background quad to show through without dark haloes or artifacts.
- **Hardware S-Pen & Stylus Detection**: Automatic hardware detection of stylus tail/tip erasers (`TOOL_TYPE_ERASER`) and stylus barrel buttons (`BUTTON_STYLUS_PRIMARY` / `BUTTON_STYLUS_SECONDARY`). Touching the canvas with the eraser end or holding the side button instantly activates eraser mode using independent eraser radius and opacity without requiring manual UI tool switching. Releasing the stylus immediately restores the previously active drawing tool.
- **Full Undo/Redo Integration**: Erase strokes record minimal bounding square sub-rectangle diffs on `baseFbo`, capturing pre-erase opaque pixels and post-erase transparent pixels. Undoing an erase operation perfectly restores the erased color.

### 2. Tool Specialization & Sub-Pixel Inking Precision
- **Specialized Tool Dynamics**:
  - **Paintbrush (`ic_brush`)**: Stylus pressure dynamically modulates radius ($R = R_{\text{base}} \cdot P$) with constant base opacity ($\alpha = \alpha_{\text{base}}$).
  - **Pen (`ic_pen`)**: Zero pressure sensitivity on both radius and opacity ($R = R_{\text{base}}$, $\alpha = \alpha_{\text{base}}$), maintaining uniform, crisp line width down to **$0.5\text{ px}$** for technical inking and fine cross-hatching.
  - **Eraser (`ic_eraser`)**: Stylus pressure modulates eraser radius with constant opacity, subtracting destination alpha on the active layer.
  - **Smudge (`ic_smudge`)**: Directional 9-tap kernel blur shader dragging underlying colors across the canvas, modulating both radius and dragging strength with pressure.
- **Sub-Pixel Brush Sizing**: Granular slider spanning $0.5\text{ px}$ to $100\text{ px}$ in fine $0.5\text{ px}$ steps with live numeric readout.
- **Independent Per-Tool Memory**: Each tool remembers its own radius and opacity settings (`toolRadii` and `toolOpacities`). Switching tools seamlessly restores that tool's last-used size and opacity across sliders, text readouts, and the live preview ring.
- **Premultiplied Alpha Blending**: Layers and in-flight stroke previews blend cleanly using premultiplied alpha math, eliminating dark fringing and saturation clipping.

### 3. Multi-Layer GLES Management Stack
- **Multi-Layer Drawing Stack (`CanvasLayer.kt`)**: User artwork is structured as an ordered stack of discrete canvas layers ($L_0, L_1, \dots, L_{n-1}$), each backed by an independent offscreen FBO texture target (`GL_RGBA8`).
- **Layer Manager Sheet (`LayerManagerBottomSheet`)**:
  - **Add Layer**: Creates a new transparent `CanvasLayer` immediately above the currently selected layer.
  - **Duplicate Layer**: Clones active layer pixels and properties via fast GPU `glBlitFramebuffer`.
  - **Merge Down**: Blends active layer onto the layer directly below using `compositingShader.renderLayerQuad` and releases the merged layer.
  - **Reorder (Move Up / Down)**: Promotes or demotes the active layer in the rendering hierarchy.
  - **Per-Layer Opacity**: Continuous $0–100\%$ slider dynamically modulating layer alpha in the compositing pass.
  - **Layer Visibility**: One-tap eye toggle to hide or show layers without destroying drawing data.
  - **Delete Confirmation Dialog**: Tapping the trash icon prompts an explicit confirmation dialog ("Are you sure you want to delete this layer? This action cannot be undone.") with "Delete" and "Cancel" buttons, preventing accidental data loss. Disabled when only one layer remains.

### 4. Manual Color Selection & Canvas Eyedropper
- **Dedicated Color Bottom Sheet (`ColorSettingsBottomSheet`)**: Centralized color configuration modal opened via `btnColorSettings` on the top toolbar.
- **Auto vs. Manual Color Toggle**: Side-by-side segmented controls switching between dynamic reference image color sampling and manual palette editing.
- **Dual RGB & HSL Sliders**:
  - RGB sliders: Red ($0–255$), Green ($0–255$), Blue ($0–255$) with color-coded track styling.
  - HSL sliders: Hue ($0^\circ–360^\circ$), Saturation ($0–100\%$), Lightness ($0–100\%$).
  - **Bidirectional Synchronization**: Adjusting RGB sliders automatically recalculates HSL values via `ColorSelectionUtil.colorToHsl`, and vice versa.
- **Live Swatch & Hex Readout**: Circular color swatch with crisp white border and formatted `#RRGGBB` hex code.
- **Top Toolbar Eyedropper Button (`btnEyedropper`)**: One-tap toolbar button activating canvas eyedropper mode. Touching or dragging across the canvas samples pixel colors directly from `baseFbo` in real time; releasing commits the sampled color, switches to manual mode, and deactivates eyedropper mode.
- **Toolbar Color Indicator**: `btnColorSettings` renders a live circular swatch in manual mode or the dynamic sampling icon in auto color mode.

### 5. Floating Canvas Undo & Redo (Minimal Bounding Square Diffs)
- **Ergonomic Floating Controls**: Circular floating action buttons positioned directly over the canvas in the lower corners (`btnUndo` at bottom-left, `btnRedo` at bottom-right) for rapid thumb access while keeping the top toolbar completely decluttered.
- **Minimal Bounding Square Sub-Rect Diffs (`SubRectDiff`)**:
  - Instead of duplicating multi-megapixel FBO textures, the engine captures only the minimal square bounding box enclosing each stroke:
    $$\text{side} = \max(\text{maxX} - \text{minX} + 1, \text{maxY} - \text{minY} + 1)$$
  - Records `beforePixels` and `afterPixels` over the bounded sub-rectangle, consuming only a few kilobytes per stroke.
  - Undoing/redoing uploads only the affected $(W \times H)$ tile back into the layer texture using `glTexSubImage2D`, maintaining 60–120 FPS without garbage collection pauses.
  - Supports up to 30 sequential undo operations across Paintbrush, Pen, Eraser, and Smudge strokes.

### 6. Decluttered Top Toolbar & More Options Overflow Sheet
- **Streamlined 6-Item Top Toolbar**:
  - `btnActiveTool`: Displays active tool icon (`ic_brush`, `ic_pen`, `ic_eraser`, `ic_smudge`) with active outline. Opens `ToolSettingsBottomSheet`.
  - `btnColorSettings`: Displays live color swatch or auto-color icon. Opens `ColorSettingsBottomSheet`.
  - `btnEyedropper`: One-tap canvas color sampler toggle.
  - `btnLayers`: Opens `LayerManagerBottomSheet`.
  - Flexible spacer.
  - `btnGallery`: Opens `GalleryDialogFragment`.
  - `btnMoreOptions`: Overflow ellipsis (`...`) opening `MoreOptionsBottomSheet`.
- **More Options Overflow Sheet (`MoreOptionsBottomSheet`)**:
  - **Reset Canvas View**: Snaps canvas scale to 1:1 aspect-fit, rotation to $0^\circ$, and centers the viewport.
  - **Load / Change Source Image**: Launches system photo picker.
  - **Source Opacity & Visibility**: Continuous opacity slider ($0–100\%$) and toggle button.
  - **Ratchet Mode Switch**: Dedicated toggle enabling distance-monotonic guided painting.
  - **Clear Canvas**: Destructive action protected by an alert confirmation dialog.
  - **Document Shortcuts**: "+ New Painting", "Open Gallery", and "Run Test Harness".

### 7. Pen-Only Drawing & Hardware Palm Rejection
- **Strict Input Tool Discrimination**: Strokes are emitted strictly when `MotionEvent.getToolType()` reports `TOOL_TYPE_STYLUS` or `TOOL_TYPE_ERASER` (Samsung S-Pen, Lenovo Precision Pen, USI styluses, Google Pixel Tablet Pen).
- **Zero Stray Marks from Palms**: Single-finger touches, resting palms, and wrist contacts report `TOOL_TYPE_FINGER` and are completely ignored during drawing.
- **Stylus Priority Locking**: Once a stylus touches down, an isolated pointer ID lock prevents simultaneous edge touches or resting knuckles from perturbing the active stroke or triggering canvas gestures.

### 8. Fluid Two-Finger Viewport Navigation
- **Simultaneous 2D Affine Gestures**: Fluid two-finger pan, pinch-to-zoom ($0.1\times$ to $10.0\times$ scale), and midpoint-pivoted canvas rotation centered on the dynamic gesture focal point.
- **Exact Forward & Inverse Affine Math**:
  $$\text{Forward: } \begin{bmatrix} x_s \\ y_s \end{bmatrix} = \begin{bmatrix} T_x \\ T_y \end{bmatrix} + R(\theta) \begin{bmatrix} S \cdot x_c \\ S \cdot y_c \end{bmatrix}$$
  $$\text{Inverse: } \begin{bmatrix} x_c \\ y_c \end{bmatrix} = \frac{1}{S} \begin{bmatrix} \cos(\theta) & \sin(\theta) \\ -\sin(\theta) & \cos(\theta) \end{bmatrix} \begin{bmatrix} x_s - T_x \\ y_s - T_y \end{bmatrix}$$
- **Double-Tap Viewport Reset**: Double-tapping the glass with two fingers (or tapping "Reset Canvas View" in More Options) snaps the canvas back to centered 1:1 aspect-fit with rotation reset to $0^\circ$.

### 9. Coordinate Space Alignment & Y-Axis Inversion Fix
- **Top-Left to OpenGL Alignment**: Bridges Android screen space and `Bitmap` coordinates (origin at top-left, $Y$ pointing downward) with OpenGL ES 3.0 FBO and texture coordinates (origin at bottom-left, $Y$ pointing upward).
- **Shader & Pipeline Corrections**:
  - `CircleBrushShader` & `SmudgeShader`: Vertex shader applies `clipSpace.y = -clipSpace.y;` mapping world canvas coordinates directly into upright FBO targets.
  - `SmudgeShader`: Inverts texture $V$ sampling coordinates: $v = 1.0 - (y / H)$.
  - `CompositingShader`: Canvas quad explicitly maps UV coordinates (top-left $y=0 \to v=1.0$, bottom-left $y=1 \to v=0.0$), rendering offscreen FBOs upright.
  - `GlesFbo`: Full bitmap exports (`readBitmap`) and bounded sub-rectangle readbacks (`readSubRect`) perform vertical scanline row-flipping.
- **Pending GL Lifecycle Queue**: Thread-safe staging mechanism (`pendingBaseBitmap`, `pendingReferenceBitmap`, etc.) queues textures on the Main Thread and drains them on `onSurfaceCreated()` inside the active EGL render context.

### 10. Source Image Aspect Ratio Preservation & Resolution Multiplier Modal
- **Aspect Ratio Parity**: Dynamically configures canvas dimensions $(W_{\text{canvas}}, H_{\text{canvas}})$ to match imported image aspect ratios, eliminating letterboxing, borders, or stretching.
- **Interactive Import Modal**:
  - Bounds pre-flight via `inJustDecodeBounds = true`.
  - Resolution multiplier slider ($1\times$ to $8\times$) with real-time megapixel calculation.
  - **GPU Ceiling Guard**: Checks against the $8192\text{ px}$ GPU texture limit, disabling confirmation and raising a warning if exceeded.

### 11. Persistence & 5-Second Inactivity Auto-Save Engine
- **Autodesk Sketchbook-Inspired Workflow**: Completely eliminates manual save dialogs and guarantees zero data loss across app termination, process death, and device reboots.
- **5-Second Debounce Timer**: Resets on every stroke completion (`ACTION_UP`), tool switch, or parameter adjustment. Auto-save fires only after $5.0\text{ seconds}$ of user idle time, ensuring 60–120 FPS drawing without disk I/O interference.
- **Synchronous Lifecycle Flush**: Immediate blocking auto-save flush in `onPause()` and `onStop()`.
- **Atomic File Swapping**: Writes to `.tmp` files with `FileOutputStream.fd.sync()` before atomic rename, preventing truncated or corrupt files.
- **Session Restoration on Launch**: Restores active project FBO pixels, reference image, opacity, tool mode, brush radius, and brush opacity on cold start.

### 12. Multi-Painting Gallery (`GalleryDialogFragment`)
- **Responsive Grid**: Adaptive presentation (2 columns on portrait phones, 3 columns on landscape tablets).
- **Tile Previews**: High-quality aspect-fit thumbnail previews (`thumbnail.png` $\le 384\text{ px}$) against a subtle checkered canvas.
- **Document Metadata**: Project title, resolution, and relative timestamp caption (e.g., `2400 × 3200 • Updated 5m ago`).
- **Seamless Document Switching**: Flushes active project changes to disk, re-points `active_project.json`, reallocates FBOs, and loads target canvas pixels.
- **Safe Deletion & Fallback**: Purges project folder; if deleting the active document, seamlessly transitions editor to the next most recent project or creates a blank canvas.

### 13. Fast 13-Point Disc Stencil Covered-Pixel Averaging (<0.05ms)
- **Sub-Millisecond Averaging**: Replaced the previous $O(W \times H \times N)$ bounding box CPU loop that caused $50\text{–}200\text{ ms}$ UI stutter on stroke completion.
- **Isotropic Disc Stencil Pattern**:
  - Center point: $(0, 0)$
  - Inner ring at $0.3R$: $(0, \pm 0.3R)$, $(\pm 0.3R, 0)$
  - Outer cardinal ring at $0.6R$: $(0, \pm 0.6R)$, $(\pm 0.6R, 0)$
  - Diagonal ring at $0.4243R$ ($0.6R / \sqrt{2}$): $(\pm 0.4243R, \pm 0.4243R)$
- **Bounded Path Striding**: Strides across up to 60 evenly spaced stamp positions along the Bezier curve ($\le 780$ point lookups total), executing in **$< 0.05\text{ ms}$**.

### 14. Ratchet Mode (Distance-Monotonic Guided Painting)
- **The Ratchet Invariant**: Guarantees that every stroke committed to the canvas monotonically decreases or preserves error relative to the reference image:
  $$\text{Loss}(P', I_{\text{ref}}) \le \text{Loss}(P, I_{\text{ref}})$$
- **Non-Destructive Live Accumulation**: Live strokes accumulate in `activeStrokeFbo` while `baseFbo` remains pristine.
- **CPU Sub-Rect Simulation**: On `ACTION_UP`, `RatchetEvaluator` reads the stroke's bounding box sub-rectangle from `baseFbo`, simulates disc stamps in software, and evaluates L1 Manhattan color distance against reference pixels.
- **Zero-Cost Rollback**: Retrograde strokes that increase divergence are discarded simply by clearing `activeStrokeFbo`, leaving the canvas untouched.

---

## User Interface & Gesture Guide

### Top Toolbar Controls
| Icon Button | Name | Action / Behavior |
| :--- | :--- | :--- |
| **Tool (Brush/Pen/Eraser/Smudge)** | Active Tool | Displays active tool icon (`ic_brush`, `ic_pen`, `ic_eraser`, `ic_smudge`). Tapping opens **Tool Settings Bottom Sheet**. |
| **Color Swatch / Auto** | Color Settings | Displays live color swatch or auto-sampling icon. Tapping opens **Color Settings Bottom Sheet**. |
| **Eyedropper** | Canvas Eyedropper | One-tap canvas sampler to pick colors directly from canvas pixels in real time. |
| **Layers** | Layer Manager | Opens **Layer Management Bottom Sheet** to add, duplicate, reorder, merge, or delete layers. |
| **Grid / Image** | Gallery | Opens **Paintings Gallery** modal to switch, create, or delete painting projects. |
| **Ellipsis (`...`)** | More Options | Opens **Overflow Settings Sheet** for Reset Canvas View, source image controls, ratchet mode, and canvas clearing. |

### Floating Canvas Controls
| Control | Placement | Action / Behavior |
| :--- | :--- | :--- |
| **`btnUndo`** | Bottom-Left Floating Action Button | Reverts previous stroke using minimal bounding square sub-rectangle diffs. Dimmed when stack is empty. |
| **`btnRedo`** | Bottom-Right Floating Action Button | Reapplies next undone stroke using minimal bounding square sub-rectangle diffs. Dimmed when stack is empty. |

### Gesture Interaction Model
- **Stylus Tip Pressed (`TOOL_TYPE_STYLUS` / `TOOL_TYPE_ERASER`)**: Emits Bezier-smoothed antialiased paint, inking, smudge, or eraser stamps.
- **Single Finger Touch (`TOOL_TYPE_FINGER`)**: Completely ignored (no paint, no panning). Prevents accidental palm drags.
- **Two Fingers Drag / Pinch / Twist**: Fluid simultaneous pan, pinch-to-zoom, and midpoint-pivoted rotation.
- **Two Fingers Double-Tap**: Snaps viewport back to centered aspect-fit ($S = 1.0, \theta = 0^\circ$).

---

## Project Directory & Storage Hierarchy

Each painting document is stored inside its own directory in internal sandbox storage (`context.filesDir/projects/<uuid>/`):

```
filesDir/
└── projects/
    ├── active_project.json                     # Atomic pointer to the currently open project ID
    ├── 8f4e2c0a-6b31-4c12-9e8a-123456789abc/   # Project Directory (<UUIDv4>)
    │   ├── painting.png                        # Lossless 32-bit RGBA8888 canvas base layer
    │   ├── source.png                          # Optional imported source / reference image
    │   ├── thumbnail.png                       # Aspect-fit downscaled preview (≤ 384x384)
    │   └── metadata.json                       # Dimensions, timestamps, tool, and layer settings
    └── ...
```

### `metadata.json` Schema
```json
{
  "id": "8f4e2c0a-6b31-4c12-9e8a-123456789abc",
  "title": "Portrait Study",
  "canvasWidth": 2400,
  "canvasHeight": 3200,
  "createdAt": 1700643600000,
  "updatedAt": 1700645400000,
  "referenceOpacity": 0.35,
  "isReferenceVisible": true,
  "useReferenceColors": true,
  "isRatchetEnabled": false,
  "activeTool": "PAINTBRUSH",
  "brushSize": 20.0,
  "brushOpacity": 1.0,
  "brushSizeBrush": 20.0,
  "brushSizePen": 1.0,
  "brushSizeEraser": 20.0,
  "brushSizeSmudge": 30.0,
  "brushOpacityBrush": 1.0,
  "brushOpacityPen": 1.0,
  "brushOpacityEraser": 1.0,
  "brushOpacitySmudge": 0.5,
  "manualBrushColor": -16777216,
  "layers": [
    {
      "id": "c1f2a3b4-5678-9abc-def0-1234567890ab",
      "name": "Background",
      "opacity": 1.0,
      "isVisible": true,
      "order": 0
    },
    {
      "id": "d2e3f4a5-6789-0bcd-ef01-234567890abc",
      "name": "Line Art",
      "opacity": 1.0,
      "isVisible": true,
      "order": 1
    }
  ],
  "activeLayerId": "d2e3f4a5-6789-0bcd-ef01-234567890abc"
}
```

---

## Technical Specifications & Architecture

- **Platform**: Android SDK 34 (minSdk 26, targetSdk 34)
- **Language**: Kotlin 1.9+
- **Graphics API**: OpenGL ES 3.0 via EGL (Offscreen FBOs + `GLSurfaceView`)
- **Shaders**:
  - `CircleBrushShader.kt`: Antialiased circular brush stamp shader with dynamic pressure flow.
  - `SmudgeShader.kt`: Directional 9-tap kernel blur shader dragging underlying colors.
  - `CompositingShader.kt`: Fullscreen quad shader combining background, drawing layer, active stroke preview, and reference overlay with Viewport MVP transformation.
- **Input Pipeline**: `TouchInputHandler.kt` with tool type filtering, pressure curve mapping, and `StrokeSmoother.kt` cubic Bezier spline fitting.
- **Auto-Save Engine**: `AutoSaveManager.kt` with 5-second inactivity debouncer and lifecycle-aware synchronous flushing.
- **Document Management**: `ProjectManager.kt` with atomic file I/O and automatic fallback routines.

For complete mathematical derivations, shader listings, and architectural details, refer to [SPECIFICATION.md](SPECIFICATION.md).

---

## Building & Testing

### Prerequisites
- Android Studio Iguana / Jellyfish or Android SDK Tools 34+
- JDK 17 (recommended for Gradle 8.2+)

### Command-Line Build & Unit Tests
```bash
# Run unit test suite
./gradlew testDebugUnitTest

# Assemble debug APK
./gradlew assembleDebug
```

### Running the Bezier Test Harness
To run the automated 30–200 stroke Bezier test harness on an Android device or emulator:
- Launch the app with `--ez run_harness true`, or
- Open the overflow menu (`...`) and tap **Run Test Harness**.

Rendered test paintings are exported to `test-output/`:
- `test_bezier_curves_showcase.png`: 50 multi-curvature Bezier paths with dynamic pressure ramps.
- `test_stylus_pressure_dynamics.png`: Pressure response curves and taper verification.
- `test_smudge_shader_blend.png`: Multi-directional smudge blending across adjacent color strokes.
- `test_steady_improvement_painting.png`: 200-stroke automated iterative convergence loop.

---

## License

[UNLICENSE](UNLICENSE) (Public Domain)
