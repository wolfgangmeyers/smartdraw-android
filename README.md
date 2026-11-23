# SmartDraw Android

Native Android reference-guided painting and sketch application built in Kotlin with OpenGL ES 3.0 hardware-accelerated shaders, pressure-sensitive stylus dynamics with palm rejection, Bezier curve stroke smoothing, dynamic reference color auto-picking, distance-monotonic Ratchet Mode painting, and Autodesk Sketchbook-inspired non-destructive document persistence and project gallery.

---

## Key Features

### 1. Pen-Only Drawing & Hardware Palm Rejection
- **Strict Input Tool Discrimination**: Strokes are emitted strictly when `MotionEvent.getToolType()` reports `TOOL_TYPE_STYLUS` or `TOOL_TYPE_ERASER` (Samsung S-Pen, Lenovo Precision Pen, USI styluses, Google Pixel Tablet Pen).
- **Zero Stray Marks from Palms**: Single-finger touches, resting palms, and wrist contacts report `TOOL_TYPE_FINGER` and are completely ignored during drawing.
- **Stylus Priority Locking**: Once a stylus touches down, an isolated pointer ID lock prevents simultaneous edge touches or resting knuckles from perturbing the active stroke or triggering canvas gestures.

### 2. Fluid Two-Finger Viewport Navigation
- **Simultaneous 2D Affine Gestures**: Fluid two-finger pan, pinch-to-zoom ($0.1\times$ to $10.0\times$ scale), and midpoint-pivoted canvas rotation centered on the dynamic gesture focal point.
- **Exact Forward & Inverse Affine Math**:
  $$\text{Forward: } \begin{bmatrix} x_s \\ y_s \end{bmatrix} = \begin{bmatrix} T_x \\ T_y \end{bmatrix} + R(\theta) \begin{bmatrix} S \cdot x_c \\ S \cdot y_c \end{bmatrix}$$
  $$\text{Inverse: } \begin{bmatrix} x_c \\ y_c \end{bmatrix} = \frac{1}{S} \begin{bmatrix} \cos(\theta) & \sin(\theta) \\ -\sin(\theta) & \cos(\theta) \end{bmatrix} \begin{bmatrix} x_s - T_x \\ y_s - T_y \end{bmatrix}$$
- **Double-Tap Viewport Reset**: Double-tapping the glass with two fingers (or tapping the target icon in the toolbar) snaps the canvas back to centered 1:1 aspect-fit with rotation reset to $0^\circ$.

### 3. Y-Axis Inversion Fix & Coordinate Space Alignment
- **Top-Left to OpenGL Alignment**: Bridges Android screen space and `Bitmap` coordinates (origin at top-left, $Y$ pointing downward) with OpenGL ES 3.0 FBO and texture coordinates (origin at bottom-left, $Y$ pointing upward).
- **Shader & Pipeline Corrections**:
  - `CircleBrushShader` & `SmudgeShader`: Vertex shader applies `clipSpace.y = -clipSpace.y;` mapping world canvas coordinates directly into upright FBO targets.
  - `SmudgeShader`: Inverts texture $V$ sampling coordinates: $v = 1.0 - (y / H)$.
  - `CompositingShader`: Canvas quad explicitly maps UV coordinates (top-left $y=0 \to v=1.0$, bottom-left $y=1 \to v=0.0$), rendering offscreen FBOs upright.
  - `GlesFbo`: Full bitmap exports (`readBitmap`) and bounded sub-rectangle readbacks (`readSubRect`) perform vertical scanline row-flipping.
- **Pending GL Lifecycle Queue**: Thread-safe staging mechanism (`pendingBaseBitmap`, `pendingReferenceBitmap`, etc.) queues textures on the Main Thread and drains them on `onSurfaceCreated()` inside the active EGL render context.

### 4. Streamlined Icon-Only Toolbar & Active Tool Switcher
- **Uncluttered 5-Icon Top Bar**:
  - `btnActiveTool`: Displays active tool icon (Pencil / Smudge) with active surface highlight.
  - `btnAutoColor`: Color sampling eyedropper with active/inactive visual highlight.
  - `btnResetView`: Snaps viewport to 1:1 aspect-fit centered.
  - `btnGallery`: Opens the multi-painting gallery modal.
  - `btnMoreOptions`: Overflow ellipsis (`...`) opening secondary settings.
- **Tool Settings Bottom Sheet (`ToolSettingsBottomSheet`)**:
  - Direct selection cards for **Pencil** and **Smudge** tools.
  - Interactive live **Brush Preview Ring** (`BrushPreviewView`) rendering antialiased stamps dynamically updating with radius and opacity changes.
  - Brush Size Slider: $2\text{ px}$ to $100\text{ px}$ with real-time numeric readout.
  - Brush Opacity Slider: $1\%$ to $100\%$ with real-time percentage readout.

### 5. Nested Source Image & Overflow Options Sheet (`MoreOptionsBottomSheet`)
- **Nested Source Image Action**: Tapping "Load Source Image" (or "Change Source Image") launches the native Android storage photo picker (`ActivityResultContracts.GetContent("image/*")`).
- **Source Opacity & Visibility**: Continuous opacity slider ($0\text{–}100\%$) and toggle button ("Hide Source Image" / "Show Source Image").
- **Ratchet Mode Switch**: Dedicated `SwitchCompat` toggle enabling distance-monotonic guided painting.
- **Canvas Clear**: Destructive "Clear Canvas" action with alert dialog confirmation.
- **Shortcuts**: Direct shortcuts for "Reset Canvas View", "Open Gallery", "+ New Painting", and "Run Test Harness".

### 6. Source Image Aspect Ratio Preservation & Resolution Multiplier Modal
- **Aspect Ratio Parity**: Dynamically configures canvas dimensions $(W_{\text{canvas}}, H_{\text{canvas}})$ to match imported image aspect ratios, eliminating letterboxing, borders, or stretching.
- **Interactive Import Modal**:
  - Bounds pre-flight via `inJustDecodeBounds = true`.
  - Resolution multiplier slider ($1\times$ to $8\times$) with real-time megapixel calculation.
  - **GPU Ceiling Guard**: Checks against the $8192\text{ px}$ GPU texture limit, disabling confirmation and raising a warning if exceeded.

### 7. Persistence & 5-Second Inactivity Auto-Save Engine
- **Autodesk Sketchbook-Inspired Workflow**: Completely eliminates manual save dialogs and guarantees zero data loss across app termination, process death, and device reboots.
- **5-Second Debounce Timer**: Resets on every stroke completion (`ACTION_UP`), tool switch, or parameter adjustment. Auto-save fires only after $5.0\text{ seconds}$ of user idle time, ensuring 60–120 FPS drawing without disk I/O interference.
- **Synchronous Lifecycle Flush**: Immediate blocking auto-save flush in `onPause()` and `onStop()`.
- **Atomic File Swapping**: Writes to `.tmp` files with `FileOutputStream.fd.sync()` before atomic rename, preventing truncated or corrupt files.
- **Session Restoration on Launch**: Restores active project FBO pixels, reference image, opacity, tool mode, brush radius, and brush opacity on cold start.

### 8. Multi-Painting Gallery (`GalleryDialogFragment`)
- **Responsive Grid**: Adaptive presentation (2 columns on portrait phones, 3 columns on landscape tablets).
- **Tile Previews**: High-quality aspect-fit thumbnail previews (`thumbnail.png` $\le 384\text{ px}$) against a subtle checkered canvas.
- **Document Metadata**: Project title, resolution, and relative timestamp caption (e.g., `2400 × 3200 • Updated 5m ago`).
- **Seamless Document Switching**: Flushes active project changes to disk, re-points `active_project.json`, reallocates FBOs, and loads target canvas pixels.
- **Safe Deletion & Fallback**: Purges project folder; if deleting the active document, seamlessly transitions editor to the next most recent project or creates a blank canvas.

### 9. Fast 13-Point Disc Stencil Covered-Pixel Averaging (<0.05ms)
- **Sub-Millisecond Averaging**: Replaced the previous $O(W \times H \times N)$ bounding box CPU loop that caused $50\text{–}200\text{ ms}$ UI stutter on stroke completion.
- **Isotropic Disc Stencil Pattern**:
  - Center point: $(0, 0)$
  - Inner ring at $0.3R$: $(0, \pm 0.3R)$, $(\pm 0.3R, 0)$
  - Outer cardinal ring at $0.6R$: $(0, \pm 0.6R)$, $(\pm 0.6R, 0)$
  - Diagonal ring at $0.4243R$ ($0.6R / \sqrt{2}$): $(\pm 0.4243R, \pm 0.4243R)$
- **Bounded Path Striding**: Strides across up to 60 evenly spaced stamp positions along the Bezier curve ($\le 780$ point lookups total), executing in **$< 0.05\text{ ms}$**.

### 10. Ratchet Mode (Distance-Monotonic Guided Painting)
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
| **Pencil / Smudge** | Active Tool | Tapping opens the **Tool Settings Bottom Sheet** to switch tools or adjust size & opacity. |
| **Eyedropper** | Auto Color | Toggles dynamic reference color sampling along stroke paths. Highlights when active. |
| **Target Frame** | Reset View | Instantly resets canvas viewport to 1:1 aspect-fit centered with $0^\circ$ rotation. |
| **Grid / Image** | Gallery | Opens the **Paintings Gallery** to switch, create, or delete painting projects. |
| **Ellipsis (`...`)** | More Options | Opens the **Overflow Settings Sheet** for source image loading, opacity, ratchet mode, and canvas clearing. |

### Gesture Interaction Model
- **Stylus Tip Pressed (`TOOL_TYPE_STYLUS` / `TOOL_TYPE_ERASER`)**: Emits Bezier-smoothed antialiased paint or smudge stamps.
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
  "activeTool": "PENCIL",
  "brushSize": 24.0,
  "brushOpacity": 0.85
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
