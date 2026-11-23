# SmartDraw Android Specification

Complete technical and behavioral architecture specification for the native Android (Kotlin) implementation of SmartDraw.

---

## 1. Executive Summary & Purpose

SmartDraw Android is a high-performance native Android painting application designed for assisted and reference-guided painting. It faithfully reproduces the WebGL shader rendering model of the original SmartDraw web application while adding first-class mobile capabilities:

- **Hardware-Accelerated GLES 3.0 Pipeline**: Offscreen Framebuffer Objects (FBOs) and custom GLSL fragment shaders reproducing circle brush stamping, directional color smudging, reference image overlay blending, and composite output.
- **Stylus & Touch Pressure Dynamics**: Real-time continuous sampling of `MotionEvent.getPressure()`, historical batch events, and customizable pressure response curves (linear, sigmoid, power) mapped to brush radius and opacity.
- **Stylus / Pen-Only Drawing & Palm Rejection**: Explicit discrimination of hardware input tools (`TOOL_TYPE_STYLUS` / `TOOL_TYPE_ERASER` vs `TOOL_TYPE_FINGER`). Paint strokes are strictly emitted by active/passive styluses; single finger touches and resting palms are rejected without emitting paint.
- **Two-Finger Fluid Viewport Navigation**: Smooth 2D affine viewport transformation (pan and pinch-to-zoom centered on gesture focal point) driven strictly by two-finger gestures, accompanied by instant viewport reset controls.
- **Coordinate Transformation Pipeline**: Exact mathematical mapping bridging Screen Space $(x_s, y_s)$, Canvas Space $(x_c, y_c)$, and Reference Image Space $(x_r, y_r)$, with MVP matrix integration in the GLES 3.0 compositing pass.
- **Bezier Curve Stroke Smoothing**: Multi-point quadratic and cubic Bezier curve fitting with arc-length parameterization to guarantee uniform stamp spacing independent of touch sampling frequency or drawing speed.
- **Dynamic Reference Color Auto-Picker**: Coordinate-translated continuous sampling of the reference image along the stroke path, automatically adjusting the brush color in real time.
- **Ratchet Mode (Distance-Monotonic Guided Painting)**: Non-destructive interactive painting guard that guarantees every committed human or automated stroke monotonically reduces or preserves distance to the reference image, rolling back retrograde strokes.
- **Autodesk Sketchbook-Inspired Persistence & Project Gallery**: Robust multi-painting management with discrete project folders, lossless canvas preservation, automatic downscaled gallery thumbnails, active project restoration on launch, and a zero-data-loss 5-second inactivity debounced auto-save engine.
- **Steady Improvement Algorithmic Painting Loop**: Automated candidate stroke evaluation against the reference image using mean squared error (MSE) difference metrics and acceptance criteria.
- **Pragmatic Bezier Test Harness**: Codegen test runner exercising 30–200 varied Bezier strokes (S-curves, loops, arcs, pressure ramps) with headless and on-device rendering verification.

---

## 2. Architecture & Technology Stack

- **Platform**: Android SDK 34 (minSdk 26+, targetSdk 34)
- **Language**: Kotlin 1.9+
- **Graphics API**: OpenGL ES 3.0 via EGL (offscreen Pbuffer / FBO rendering) + Android `SurfaceView` / `TextureView` for UI presentation
- **Threading Model**: 
  - Main UI Thread: Touch event capture, gestures, Compose/View state
  - Render Thread: Dedicated EGL context, GL draw calls, texture swapping
  - Background Worker: Kotlin Coroutines (`Dispatchers.Default`) for the steady improvement loop
- **License**: UNLICENSE (Public Domain)

---

## 3. Graphics & Shader Pipeline (OpenGL ES 3.0)

### 3.1 Layer Architecture, Dynamic Dimensions & Framebuffers

SmartDraw uses a multi-layer composition pipeline operating on dynamic canvas dimensions $(W_{\text{canvas}} \times H_{\text{canvas}})$:

1. **Background Layer**: Generates or uploads a solid background color (`solidBackgroundColor`, default opaque white) rendered via `compositingShader.renderSolidQuad`.
2. **Multi-Layer Drawing Stack (`CanvasLayer.kt`)**: User artwork is structured as an ordered stack of discrete canvas layers ($L_0, L_1, \dots, L_{n-1}$), each backed by an independent offscreen FBO texture target (`GL_RGBA8`).
   - Each `CanvasLayer` maintains an immutable `id` (UUID), user-facing `name`, `opacity` $\in [0.0, 1.0]$, and boolean `isVisible`.
   - The active drawing layer (`getActiveLayer()`) receives all incoming brush stamps, smudge operations, and finalized Bezier strokes.
   - Layers are rendered bottom-to-top ($i = 0$ to $n-1$) using `compositingShader.renderLayerQuad`, with premultiplied alpha blending:
     $$\text{dst} = \text{src} \cdot \alpha_{\text{src}} + \text{dst} \cdot (1 - \alpha_{\text{src}})$$
   - Active in-flight strokes (`activeStrokeFbo`) are dynamically composited on top of the currently active layer during brush drag events before being permanently stamped into that layer's FBO upon `ACTION_UP`.
3. **Reference Image Layer**: GPU texture (`GL_TEXTURE_2D`) containing the target painting image, with uniform opacity (`u_referenceOpacity`) and toggleable visibility (`u_renderReference`), rendered as the top-most overlay using `compositingShader.renderLayerQuad`.
4. **Compositing Pass**: Multi-pass quad shader combining background solid quad, all visible canvas layers in bottom-to-top order, in-progress active stroke quad, and reference overlay layer. During viewport navigation (pan and zoom), the composite is rendered transformed by the Viewport Model-View-Projection (MVP) matrix directly into the screen framebuffer (`GL_FRAMEBUFFER 0`).
5. **Dynamic Aspect Ratio & Resolution Scaling**:
   - The canvas dimensions $(W_{\text{canvas}}, H_{\text{canvas}})$ dynamically adapt to the aspect ratio of imported source images:
     $$W_{\text{canvas}} = W_{\text{src}} \times \text{scale}, \quad H_{\text{canvas}} = H_{\text{src}} \times \text{scale}$$
   - When an image is imported, the user selects a resolution scale from $1\times$ to $8\times$.
   - All layer FBOs, `scratchFbo`, `compositeFbo`, and `activeStrokeFbo` are cleanly reallocated to $(W_{\text{canvas}}, H_{\text{canvas}})$ via `SmartDrawRenderer.resizeCanvas()`.
   - The reference layer texture maps $1:1$ directly onto the canvas space without letterboxing distortion because both share the exact same aspect ratio.
   - The compositing quad scales by $(W_{\text{canvas}}, H_{\text{canvas}})$ in its model matrix, ensuring rectangular canvases render with their true geometric proportions letterboxed against the screen viewport.

### 3.2 Circle Brush Shader (`CircleBrushShader.kt`)

Renders antialiased circular brush stamps with pressure-dependent radius and flow:

#### Vertex Shader (GLSL ES 3.0)
```glsl
#version 300 es
layout(location = 0) in vec2 a_position; // Quad [-1, 1]
layout(location = 1) in vec2 a_texCoord; // [0, 1]

uniform vec2 u_resolution;
uniform vec2 u_brushCenter;
uniform float u_brushRadius;

out vec2 v_pixelCoord;
out vec2 v_brushCenter;
out float v_brushRadius;

void main() {
    vec2 worldPos = u_brushCenter + (a_position * u_brushRadius);
    vec2 clipSpace = (worldPos / u_resolution) * 2.0 - 1.0;
    clipSpace.y = -clipSpace.y; // Match screen coordinates
    gl_Position = vec4(clipSpace, 0.0, 1.0);
    
    v_pixelCoord = worldPos;
    v_brushCenter = u_brushCenter;
    v_brushRadius = u_brushRadius;
}
```

#### Fragment Shader (GLSL ES 3.0)
```glsl
#version 300 es
precision highp float;

in vec2 v_pixelCoord;
in vec2 v_brushCenter;
in float v_brushRadius;

uniform vec4 u_brushColor;
uniform float u_brushOpacity;
uniform float u_pressure;
uniform float u_antiAlias;

out vec4 fragColor;

void main() {
    float dist = length(v_pixelCoord - v_brushCenter);
    float effectiveRadius = v_brushRadius * clamp(u_pressure, 0.1, 1.0);
    
    float alpha = 1.0 - smoothstep(effectiveRadius - u_antiAlias, effectiveRadius + u_antiAlias, dist);
    if (alpha <= 0.001) {
        discard;
    }
    
    float finalAlpha = alpha * u_brushColor.a * u_brushOpacity * u_pressure;
    fragColor = vec4(u_brushColor.rgb, finalAlpha);
}
```

### 3.3 Smudge Shader (`SmudgeShader.kt`)

Recreates physical paint dragging by sampling colors along the motion vector and blending:

```glsl
#version 300 es
precision highp float;

in vec2 v_pixelCoord;
in vec2 v_brushCenter;
in float v_brushRadius;

uniform sampler2D u_canvasTexture;
uniform vec2 u_resolution;
uniform vec2 u_smudgeDelta;   // Direction and magnitude of motion
uniform float u_smudgeStrength; // [0.0, 1.0]

out vec4 fragColor;

void main() {
    float dist = length(v_pixelCoord - v_brushCenter);
    if (dist > v_brushRadius) {
        discard;
    }
    
    vec2 currentUv = v_pixelCoord / u_resolution;
    vec2 sampleUv = (v_pixelCoord - u_smudgeDelta * u_smudgeStrength) / u_resolution;
    
    vec4 currentColor = texture(u_canvasTexture, currentUv);
    vec4 draggedColor = texture(u_canvasTexture, sampleUv);
    
    float falloff = 1.0 - smoothstep(0.0, v_brushRadius, dist);
    float blendFactor = falloff * u_smudgeStrength;
    
    fragColor = mix(currentColor, draggedColor, blendFactor);
}
```

### 3.4 Y-Axis Inversion Fix, Coordinate Space Alignment & GL Lifecycle Queue

#### 3.4.1 Coordinate Space Inversion Challenge
Android Screen Space and native `Bitmap` buffers place the origin $(0, 0)$ at the **top-left corner**, with the $X$-axis pointing rightward and the $Y$-axis pointing downward $(0 \to H)$. Conversely, standard OpenGL ES window coordinates, Framebuffer Objects (FBOs), and texture coordinates place the origin $(0, 0)$ at the **bottom-left corner**, with the $Y$-axis pointing upward. 

Without explicit mathematical inversion across each stage of the graphics pipeline, brush stamps, smudge dragging, composite output, and sub-rectangle pixel readbacks would appear vertically mirrored or inverted relative to touch input.

#### 3.4.2 Architectural Alignment Pipeline
SmartDraw enforces exact 1:1 visual parity across Android Screen Space, offscreen Canvas FBO Space, and texture memory through six coordinated alignment mechanisms:

1. **Vertex Shader Clip Space Inversion (`CircleBrushShader.kt`, `SmudgeShader.kt`)**:
   In the brush stamping vertex shaders, world canvas coordinates are converted to normalized device coordinates (NDC) $[-1, 1] \times [-1, 1]$:
   $$\text{clipSpace} = \left(\frac{\text{worldPos}}{\text{resolution}}\right) \times 2.0 - 1.0$$
   $$\text{clipSpace}.y = -\text{clipSpace}.y$$
   By negating `clipSpace.y`, the shader maps top-left canvas coordinates $(y = 0)$ to GLES NDC top $(y = +1.0)$ and bottom canvas coordinates $(y = H)$ to GLES NDC bottom $(y = -1.0)$, stamping upright into the FBO.

2. **Smudge Texture V Inversion (`SmudgeShader.kt`)**:
   Because the FBO texture's vertical coordinate $V$ is oriented bottom-to-top while canvas space is top-to-bottom, the smudge vertex shader calculates the canvas sampling UV coordinate as:
   $$v_{\text{canvasTexCoord}} = \begin{bmatrix} \frac{\text{worldPos}.x}{u_{\text{resolution}}.x} \\ 1.0 - \left(\frac{\text{worldPos}.y}{u_{\text{resolution}}.y}\right) \end{bmatrix}$$
   This ensures color dragging samples the true spatial neighborhood around the brush tip without vertical inversion.

3. **Compositing Quad UV Mapping (`CompositingShader.kt`)**:
   During the final composite pass to `GL_FRAMEBUFFER 0`, the canvas quad defines vertices spanning $[0, 1] \times [0, 1]$ positioned by the Viewport Model-View-Projection (MVP) matrix:
   - Top-Left: $\text{pos}(0.0, 0.0) \to \text{uv}(0.0, 1.0)$
   - Top-Right: $\text{pos}(1.0, 0.0) \to \text{uv}(1.0, 1.0)$
   - Bottom-Left: $\text{pos}(0.0, 1.0) \to \text{uv}(0.0, 0.0)$
   - Bottom-Right: $\text{pos}(1.0, 1.0) \to \text{uv}(1.0, 0.0)$
   Mapping vertex $Y = 0$ (top of quad) to texture $V = 1.0$ (top of OpenGL texture) and vertex $Y = 1$ (bottom of quad) to texture $V = 0.0$ (bottom of OpenGL texture) renders the drawing FBO perfectly upright on screen.

4. **Orthographic Screen Projection Matrix**:
   In `SmartDrawRenderer.composite()`, the orthographic projection matrix maps screen pixel coordinates $[0, W_{\text{surface}}] \times [H_{\text{surface}}, 0]$ to NDC:
   $$\text{Matrix.orthoM}(M_{\text{proj}}, 0, 0.0f, W_{\text{surface}}, H_{\text{surface}}, 0.0f, -1.0f, 1.0f)$$
   Screen coordinate $(y = 0)$ projects to NDC $+1.0$ and $(y = H_{\text{surface}})$ projects to NDC $-1.0$, matching Android touch coordinate conventions.

5. **Full Bitmap Export Row Inversion (`GlesFbo.readBitmap`)**:
   `glReadPixels(0, 0, width, height, ...)` reads scanlines starting from row 0 at the bottom of the FBO. To construct an upright Android `Bitmap` where row 0 is the top, scanlines are inverted:
   $$y_{\text{bitmap}} = H - 1 - y_{\text{gl}}$$

6. **Sub-Rectangle Sub-Pixel Readback Inversion (`GlesFbo.readSubRect`)**:
   For Ratchet Mode bounding box evaluations, given top-left canvas coordinates $(x, y)$ and dimensions $(w, h)$:
   $$glX = \text{clamp}(x, 0, W - 1), \quad glY = \text{clamp}(H - (y + h), 0, H - 1)$$
   `glReadPixels` reads the sub-rectangle starting at $glY$. The row loop flips the scanlines into the output ARGB integer array so that row 0 corresponds to top-left $(x, y)$, matching CPU software simulation.

#### 3.4.3 Pending GL Lifecycle Queue (`SmartDrawView.kt`)
Android lifecycle events and `Activity.onCreate()` frequently load bitmaps, reference images, opacity, and visibility before the underlying `GLSurfaceView` EGL context is created on the render thread (`rendererInstance == null`).

To prevent race conditions, dropped textures, or null pointer exceptions:
- Staging fields (`pendingBaseBitmap`, `pendingReferenceBitmap`, `pendingReferenceOpacity`, `pendingReferenceVisible`) queue requests on the Main Thread.
- When `onSurfaceCreated(gl, config)` fires inside the active EGL render context, `SmartDrawView` instantiates `SmartDrawRenderer` and drains the pending queue, uploading textures to the GPU and recycling temporary bitmap references.
- Guarantees seamless, zero-glitch project restoration upon cold start or process resurrection.

---

## 4. Input Architecture, Stylus Drawing & Viewport Navigation

### 4.1 Stylus / Pen-Only Drawing Requirement

To deliver professional-grade palm rejection and an ergonomic drawing experience on stylus-equipped devices (Samsung Galaxy Tab S-Pen, Lenovo Precision Pen, USI styluses, Google Pixel Tablet Pen), SmartDraw enforces a strict separation between drawing tools and touch gestures:

1. **Stroke Emission Eligibility**:
   - Paint strokes, smudge strokes, and Bezier point sequences strictly require `MotionEvent.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_STYLUS` or `MotionEvent.TOOL_TYPE_ERASER`.
   - Finger touches (`TOOL_TYPE_FINGER`), mouse clicks (`TOOL_TYPE_MOUSE`), and unknown pointer types (`TOOL_TYPE_UNKNOWN`) must **NEVER** draw, smudge, or emit paint stamps onto the canvas FBO.
2. **Hardware Palm Rejection**:
   - Single-finger touches, resting palms, or wrist contacts report `TOOL_TYPE_FINGER` or `ACTION_POINTER_DOWN` with finger tools. All such touches are completely rejected and ignored during drawing.
   - When a stylus is active on screen (`ACTION_DOWN` or `ACTION_MOVE` with `TOOL_TYPE_STYLUS`), any simultaneous finger touches are discarded, preventing unintended canvas navigation or stray artifact generation.
   - Eraser hardware button / tail eraser: Styluses reporting `TOOL_TYPE_ERASER` or `BUTTON_STYLUS_PRIMARY` automatically map to canvas erasing / clearing behaviors without finger gesture interference.

### 4.2 Two-Finger Viewport Navigation (Pan, Pinch-to-Zoom & Midpoint-Pivoted Rotation)

Finger touches are reserved exclusively for viewport manipulation:

1. **Gesture Disambiguation**:
   - **0 fingers / Stylus only**: Drawing / smudging active (if stylus pressed). Viewport remains stationary.
   - **1 finger touch**: Rejected / ignored (no drawing, no panning). This prevents accidental one-finger drags when resting fingers or palms touch the glass before or after a stroke.
   - **2 fingers touch**: Activates Viewport Navigation mode (combined pan, pinch-to-zoom, and midpoint-pivoted rotation).
   - **3+ fingers touch**: Ignored or reserved for system multi-touch gestures (e.g., three-finger tap for undo/redo).
2. **Viewport State Model**:
   The canvas viewport is governed by a 2D affine transformation:
   - Translation offsets: $(T_x, T_y)$ in screen pixels.
   - Uniform scale: $S \in [S_{\min}, S_{\max}]$ where $S_{\min} = 0.1$ ($10\%$ canvas size) and $S_{\max} = 10.0$ ($1000\%$ zoom). Default scale $S_0 = 1.0$.
   - Rotation angle: $\theta \in (-180^\circ, 180^\circ]$ (in degrees). Default rotation $\theta_0 = 0^\circ$.
3. **Midpoint-Pivoted Affine Gesture Math**:
   - Given two finger contact points $P_{1} = (x_1, y_1)$ and $P_2 = (x_2, y_2)$ at time $t$:
     $$\text{Midpoint / Focal Point } F = \left(\frac{x_1 + x_2}{2}, \frac{y_1 + y_2}{2}\right)$$
     $$\text{Finger Distance } D = \|P_2 - P_1\| = \sqrt{(x_2 - x_1)^2 + (y_2 - y_1)^2}$$
     $$\text{Finger Angle } \alpha = \text{atan2}(P_2.y - P_1.y, P_2.x - P_1.x)$$
   - Between consecutive gesture frames $(t-1 \to t)$:
     - Scale factor $s = \frac{D_t}{D_{t-1}}$, with target scale clamped to $[S_{\min}, S_{\max}]$:
       $$S_{\text{new}} = \text{clamp}(S \times s, S_{\min}, S_{\max}), \quad s_{\text{actual}} = \frac{S_{\text{new}}}{S}$$
     - Angular delta $\Delta\alpha = \alpha_t - \alpha_{t-1}$ normalized to $(-\pi, \pi]$:
       $$\Delta\theta = \text{degrees}(\Delta\alpha)$$
       $$\theta_{\text{new}} = \text{normalize}(\theta + \Delta\theta) \in (-180^\circ, 180^\circ]$$
     - Focal-anchored affine update:
       $$T_{\text{new}} = F_{\text{curr}} + R(\Delta\theta) \cdot (s_{\text{actual}} \cdot (T_{\text{prev}} - F_{\text{prev}}))$$
       where $R(\Delta\theta)$ is the 2D rotation matrix:
       $$R(\Delta\theta) = \begin{bmatrix} \cos(\Delta\theta) & -\sin(\Delta\theta) \\ \sin(\Delta\theta) & \cos(\Delta\theta) \end{bmatrix}$$
4. **Viewport Reset & Clear Mechanisms**:
   - **Double-Tap with Two Fingers**: Instantly snaps the viewport back to default centered fit with $S = 1.0$, $\theta = 0^\circ$, and re-centered translation:
     $$S = 1.0, \quad \theta = 0^\circ, \quad T_x = \frac{W_{\text{screen}} - W_{\text{canvas}}}{2}, \quad T_y = \frac{H_{\text{screen}} - H_{\text{canvas}}}{2}$$
   - **Toolbar Reset Button**: A dedicated reset button in the UI overlay executes the same viewport re-centering, scale normalization to $1.0f$, and rotation reset to $0^\circ$.
   - **Toolbar Clear Button**: Clears the canvas FBOs (`baseFbo` and `scratchFbo`) to opaque white (`[1f, 1f, 1f, 1f]`).

### 4.3 Coordinate Space & Transformation Pipeline

SmartDraw operates across three distinct coordinate spaces:

1. **Screen Space $(x_s, y_s)$**: Raw pixel coordinates received from Android `MotionEvent` in the `SmartDrawView` bounds $[0, W_{\text{screen}}] \times [0, H_{\text{screen}}]$.
2. **Canvas Space $(x_c, y_c)$**: Intrinsic texture coordinates of the drawing FBO $[0, W_{\text{canvas}}] \times [0, H_{\text{canvas}}]$ (e.g., $1024 \times 1024$).
3. **Reference Image Space $(x_r, y_r)$**: Normalized or pixel coordinates of the loaded reference image $[0, W_{\text{ref}}] \times [0, H_{\text{ref}}]$.

#### 4.3.1 Forward & Inverse Viewport Transforms

The forward transform maps Canvas Space to Screen Space:
$$\begin{bmatrix} x_s \\ y_s \end{bmatrix} = \begin{bmatrix} T_x \\ T_y \end{bmatrix} + R(\theta) \begin{bmatrix} S \cdot x_c \\ S \cdot y_c \end{bmatrix}$$

The exact inverse affine transform maps Screen Space to Canvas Space:
$$\begin{bmatrix} x_c \\ y_c \end{bmatrix} = \frac{1}{S} R(-\theta) \begin{bmatrix} x_s - T_x \\ y_s - T_y \end{bmatrix}$$

Expanding the inverse transform:
$$\begin{bmatrix} x_c \\ y_c \end{bmatrix} = \frac{1}{S} \begin{bmatrix} \cos(\theta) & \sin(\theta) \\ -\sin(\theta) & \cos(\theta) \end{bmatrix} \begin{bmatrix} x_s - T_x \\ y_s - T_y \end{bmatrix}$$

#### 4.3.2 Stylus Event Mapping

When stylus `MotionEvent` samples arrive at $(x_s, y_s)$ (including both current and batched historical coordinates):
1. Coordinates are inverted to canvas space via the exact inverse affine transform:
   $$x_c = \frac{(x_s - T_x) \cos(\theta) + (y_s - T_y) \sin(\theta)}{S}$$
   $$y_c = \frac{-(x_s - T_x) \sin(\theta) + (y_s - T_y) \cos(\theta)}{S}$$
2. The resulting $(x_c, y_c)$ points are passed into the Bezier spline smoother (`StrokeSmoother.kt`).
3. Stroke bounding boxes, stamp step sizes, and minimum/maximum extent evaluations are computed directly in Canvas Space so brush geometry remains invariant to zoom level and rotation angle.
4. Brush radius scale behavior: Brush radius $R$ is defined in Canvas Space. At higher zoom levels, the stroke displays proportionally larger on screen, but writes the identical pixel footprint into the offscreen FBO.

#### 4.3.3 Reference Color Sampling Mapping

When `useReferenceColors` is enabled, the auto-picker (`ReferenceColorSampler.kt`) samples color at $(x_c, y_c)$:
1. Canvas aspect ratio scaling and letterbox offsets:
   $$\text{scale}_{\text{ref}} = \min\left(\frac{W_{\text{canvas}}}{W_{\text{ref}}}, \frac{H_{\text{canvas}}}{H_{\text{ref}}}\right)$$
   $$\text{offset}_x = \frac{W_{\text{canvas}} - (W_{\text{ref}} \times \text{scale}_{\text{ref}})}{2}, \quad \text{offset}_y = \frac{H_{\text{canvas}} - (H_{\text{ref}} \times \text{scale}_{\text{ref}})}{2}$$
2. Reference pixel coordinates:
   $$x_r = \frac{x_c - \text{offset}_x}{\text{scale}_{\text{ref}}}, \quad y_r = \frac{y_c - \text{offset}_y}{\text{scale}_{\text{ref}}}$$
3. Because sampling operates in Canvas Space, zooming, rotating, or panning the viewport never shifts or misaligns the sampled reference colors relative to the canvas content.

#### 4.3.3.1 Manual Color Mode & Canvas Eyedropper

When Auto Color (`useReferenceColors`) is disabled, the brush uses `manualBrushColor`:
1. **Manual Color Application**:
   - The brush color is fixed to `SmartDrawView.brushColor` (`currentProject.manualBrushColor`).
   - Stamps drawn during active strokes are rendered with the manual color and modulated by dynamic stylus pressure and stroke opacity.
2. **Canvas Eyedropper Sampling**:
   - When eyedropper mode is activated (`isEyedropperActive = true`), touch and stylus gestures are diverted from painting strokes to canvas color sampling.
   - Coordinate inversion: Touch screen coordinates $(x_s, y_s)$ are inverted to Canvas Space $(x_c, y_c)$ via `screenToCanvas()`.
   - GPU Pixel Read: Executed on the GL render thread via `sampleCanvasColor()`. Binds `baseFbo`, queries $1 \times 1$ pixel at clamped integer coordinates $(x_c, y_c)$ via `GLES30.glReadPixels(x, glY, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, buffer)`, and packs into an ARGB `Int`.
   - Real-Time Feedback: On `ACTION_DOWN` and `ACTION_MOVE`, the sampled color updates immediately in real time. On `ACTION_UP`, the sampled color is committed to `manualBrushColor`, Auto Color is disabled, eyedropper mode deactivates (`isEyedropperActive = false`), and feedback is displayed in the status bar and Toast.
   - Persistence: The selected color is committed to `PaintingProject.manualBrushColor` and saved via the auto-save engine.

#### 4.3.4 Viewport MVP Matrix in GLES Compositing Pass

The Drawing FBO texture remains unscaled, unrotated, and unpanned in texture memory ($W_{\text{canvas}} \times H_{\text{canvas}}$). During the final composite pass to the display `SurfaceView` (`GL_FRAMEBUFFER 0`):
- An orthographic projection matrix $M_{\text{proj}}$ projects screen pixel space $[0, W_{\text{screen}}] \times [H_{\text{screen}}, 0]$ to NDC $[-1, 1] \times [-1, 1]$:
  $$\text{orthoM}(M_{\text{proj}}, 0, 0, W_{\text{screen}}, H_{\text{screen}}, 0, -1, 1)$$
- A model matrix $M_{\text{model}}$ translates by $(T_x, T_y)$, rotates by $\theta$ around $Z$-axis, and scales by $(S \times W_{\text{canvas}}, S \times H_{\text{canvas}})$:
  $$\text{translateM}(M_{\text{model}}, 0, T_x, T_y, 0)$$
  $$\text{rotateM}(M_{\text{model}}, 0, \theta, 0, 0, 1)$$
  $$\text{scaleM}(M_{\text{model}}, 0, S \cdot W_{\text{canvas}}, S \cdot H_{\text{canvas}}, 1)$$
- Combined MVP Matrix:
  $$M_{\text{mvp}} = M_{\text{proj}} \times M_{\text{model}}$$
- In `CompositingShader.kt`, the quad UV mapping maps top-left $(x=0, y=0)$ to $(u=0, v=1.0)$ and bottom-left $(x=0, y=1)$ to $(u=0, v=0.0)$, matching OpenGL texture coordinates and ensuring the canvas, reference layer, stylus drawing, and AutoColor sampling match 1:1 visually on the screen.
- The quad vertex shader applies $gl\_Position = M_{\text{mvp}} \times vec4(a\_position, 0.0, 1.0)$, transforming the canvas quad on screen smoothly at 60–120 FPS without altering FBO pixel data.

### 4.4 Edge Cases & State Invariants

| Edge Case | Behavioral Specification & Resolution |
| :--- | :--- |
| **Stylus draws while two fingers touch** | **Stylus Priority**: If a stylus is actively in contact with the display (`ACTION_DOWN` / `ACTION_MOVE`), two-finger gesture recognition is suppressed. The stylus stroke continues without viewport jitter. If a two-finger pinch/pan is already in progress and the stylus contacts the screen, the two-finger gesture is terminated (`CANCEL`), and stylus stroke processing begins immediately from the contact point. |
| **Pen Hover Events (`ACTION_HOVER_MOVE`)** | Stylus hover events update the cursor/brush preview ring on the screen overlay in Screen Space. Hover events do **not** trigger drawing, do not allocate Bezier points, and do not disrupt two-finger gestures. |
| **Stylus Button Depressed** | If the stylus primary button is held during stroke inception, the stroke can be routed to an alternate tool (e.g., Smudge or Eraser mode) while maintaining the stylus-only invariant. |
| **Orientation / Screen Resize** | On configuration change or window resize $(W_{\text{old}}, H_{\text{old}}) \to (W_{\text{new}}, H_{\text{new}})$, viewport translation is adjusted to preserve the canvas center point relative to the viewport: $T_x' = T_x + \frac{W_{\text{new}} - W_{\text{old}}}{2}$, $T_y' = T_y + \frac{H_{\text{new}} - H_{\text{old}}}{2}$. The scale factor $S$ is clamped to $[S_{\min}, S_{\max}]$. FBO contents are preserved without re-allocation. |
| **Multi-touch Palm Latency** | When an edge palm touch registers before the stylus tip lands, the palm's `TOOL_TYPE_FINGER` ensures no stroke starts. When the stylus tip touches down, the stylus pointer ID is isolated, and all existing finger pointer IDs are discarded. |
| **Out-of-Bounds Stroke Drawing** | When drawing while zoomed in such that $(x_c, y_c)$ falls outside $[0, W_{\text{canvas}}] \times [0, H_{\text{canvas}}]$, Bezier curves and stamps continue to be evaluated; stamps falling outside the FBO viewport are naturally clipped by OpenGL scissor / viewport bounds without memory or coordinate corruption. |

---

## 5. Input & Pressure Dynamics

### 5.1 Native MotionEvent Handling (`TouchInputHandler.kt`)

Supports fingers, active styluses (S-Pen, Apple Pencil over bridge, USI styluses), and passive styluses:

1. **Pressure Extraction**:
   - `event.getPressure(pointerIndex)` reports normalized pressure `[0.0, 1.0]`.
   - Historical points: `event.getHistoricalPressure(pointerIndex, pos)` prevents loss of fast pressure transients between VSYNC pulses.
2. **Tool Type Discrimination**:
   - `TOOL_TYPE_STYLUS` / `TOOL_TYPE_ERASER` vs `TOOL_TYPE_FINGER`.
   - Rejection of accidental palm touches when stylus is detected.
3. **Pressure Response Curves**:
   - **Linear**: `r = baseRadius * pressure`
   - **Power (Gamma)**: `r = baseRadius * pow(pressure, gamma)` (gamma = 1.5–2.0 for finer light-touch control)
   - **Sigmoid**: Smooth S-curve dampening micro-tremors at near-zero and near-maximum pressure.

---

## 6. Bezier Curve Stroke Smoothing (`BezierCurve.kt`, `StrokeSmoother.kt`)

Raw touch coordinates arrive as discrete polygons. SmartDraw applies real-time cubic Bezier spline fitting:

1. **Control Point Fitting**:
   - For consecutive touch samples $P_0, P_1, P_2, P_3$, midpoints are calculated as spline knots.
   - Tangents are computed to guarantee $C^1$ continuity (no sharp kinks).
2. **Arc-Length Parameterization**:
   - The curve length $L$ is approximated via Gaussian quadrature or subdivison.
   - Number of stamps $N = \lceil L / \text{stepSize} \rceil$ where $\text{stepSize} = \max(1.0, \text{radius} \times 0.25)$.
   - Equal arc-length parameter $s \in [0, 1]$ prevents stamps from clumping during slow drawing or spreading apart during rapid strokes.
3. **Pressure Interpolation**:
   - Pressure values are linearly interpolated along $s$: $p(s) = (1-s)p_{\text{start}} + s \cdot p_{\text{end}}$.

---

## 7. Reference Color Auto-Picker & Covered-Pixel Averaging (`ReferenceColorSampler.kt`)

Enables reference-guided painting where the brush automatically samples and harmonizes colors from an underlying photograph or artwork:

### 7.1 Single-Point Color Sampling & Normalization
1. **Coordinate Mapping**:
   - Maps canvas coordinates $(x_c, y_c)$ to reference image coordinates $(x_r, y_r)$ considering aspect ratio scaling and letterboxing:
     $$\text{scale} = \min\left(\frac{W_{\text{canvas}}}{W_{\text{ref}}}, \frac{H_{\text{canvas}}}{H_{\text{ref}}}\right)$$
     $$\text{offsetX} = \frac{W_{\text{canvas}} - (W_{\text{ref}} \times \text{scale})}{2}, \quad \text{offsetY} = \frac{H_{\text{canvas}} - (H_{\text{ref}} \times \text{scale})}{2}$$
     $$x_r = \text{round}\left(\frac{x_c - \text{offsetX}}{\text{scale}}\right), \quad y_r = \text{round}\left(\frac{y_c - \text{offsetY}}{\text{scale}}\right)$$
   - Out-of-bounds samples safely clamp or return `defaultColor`.
2. **Channel Normalization**:
   - `sampleColorNormalized(x, y)` extracts ARGB integer components into float array $[R, G, B, A] \in [0.0, 1.0]^4$ for direct consumption by OpenGL ES shader uniforms (`u_brushColor`).

### 7.2 Dynamic Stroke Modulation Along Path
- In live drawing mode, `sampleColor()` is evaluated at stroke initiation to supply the initial preview tint for `activeStrokeFbo`.
- In autonomous loop execution (`SteadyImprovementLoop.kt`), stroke paths are evaluated continuously along the Bezier curve, allowing multi-color gradient sweeps across reference color transitions.

### 7.3 Fast 13-Point Disc Stencil Covered-Pixel Averaging (<0.05ms)

#### 7.3.1 Motivation & Bottleneck Elimination
In previous implementations, stroke termination attempted to calculate the average covered reference color using a full bounding-box CPU double loop over all pixels in $[x_{\min}, x_{\max}] \times [y_{\min}, y_{\max}]$, checking distance to every Bezier stamp point. For wide strokes or long sweeping curves (e.g., $100\text{ px}$ radius over $1500\text{ px}$ curve length), this resulted in evaluating tens of thousands of pixels on the main/render thread, causing severe $50–200\text{ ms}$ UI frame freezes upon lifting the stylus (`ACTION_UP`).

#### 7.3.2 13-Point Disc Stencil Architecture
SmartDraw replaces the brute-force pixel scanning loop with an isotropic, constant-time 13-point disc stencil evaluated at regular intervals along the Bezier stroke path:

1. **Disc Stencil Geometry**:
   At each evaluation point $(x, y)$ with effective brush radius $R$, 13 representative points are sampled across concentric rings:
   - **Center Anchor (1 point)**: $(0.0, 0.0)$
   - **Inner Ring at $0.3R$ (4 cardinal points)**: $(0.0, \pm 0.3R)$, $(\pm 0.3R, 0.0)$
   - **Outer Ring at $0.6R$ (4 cardinal points)**: $(0.0, \pm 0.6R)$, $(\pm 0.6R, 0.0)$
   - **Diagonal Ring at $0.6R / \sqrt{2} \approx 0.4243R$ (4 diagonal points)**: $(\pm 0.4243R, \pm 0.4243R)$

   ```
                      (0, +0.6r)
                          •
         (-0.42r, +0.42r)   (0, +0.3r)   (+0.42r, +0.42r)
               •          •          •
   (-0.6r, 0) •   (-0.3r, 0)• (0,0)• (+0.3r, 0)• (+0.6r, 0)
               •          •          •
         (-0.42r, -0.42r)   (0, -0.3r)   (+0.42r, -0.42r)
                          •
                      (0, -0.6r)
   ```

2. **Bounded Path Striding**:
   - The Bezier curve points are downsampled to a maximum of $K = 60$ evenly spaced positions:
     $$\text{stride} = \max\left(1, \left\lfloor\frac{N_{\text{points}}}{60}\right\rfloor\right)$$
   - Total sample operations per stroke: $N_{\text{samples}} \le 60 \times 13 = 780$ point lookups.
   - Fixed computational bound: Regardless of canvas resolution or stroke length, execution time is strictly bounded and completes in **$< 0.05\text{ ms}$**, eliminating all UI stutter while yielding mathematically representative color coverage.

3. **Accumulation & Clamping**:
   $$\text{total}_R = \sum_{i=1}^M R_i, \quad \text{total}_G = \sum_{i=1}^M G_i, \quad \text{total}_B = \sum_{i=1}^M B_i$$
   $$R_{\text{avg}} = \text{clamp}\left(\left\lfloor \frac{\text{total}_R}{M} \right\rfloor, 0, 255\right)$$
   $$G_{\text{avg}} = \text{clamp}\left(\left\lfloor \frac{\text{total}_G}{M} \right\rfloor, 0, 255\right)$$
   $$B_{\text{avg}} = \text{clamp}\left(\left\lfloor \frac{\text{total}_B}{M} \right\rfloor, 0, 255\right)$$
   $$\text{finalColor} = \text{Color.rgb}(R_{\text{avg}}, G_{\text{avg}}, B_{\text{avg}})$$

---

## 8. Steady Improvement Algorithmic Painting Loop (`SteadyImprovementLoop.kt`)

Autonomous painting engine that iterates toward the reference image:

1. **Candidate Generation**:
   - Randomly selects starting coordinates $(x, y)$ in regions with high difference error.
   - Generates candidate Bezier curves following local image gradient tensors (edge-aligned strokes).
   - Samples stroke color via `ReferenceColorSampler`.
2. **Difference Evaluation (MSE)**:
   - Evaluates Mean Squared Error (MSE) between canvas and reference in the stroke's bounding box:
     $$E = \frac{1}{|A|} \sum_{(x,y) \in A} \| C(x,y) - R(x,y) \|^2$$
3. **Acceptance Criterion**:
   - If candidate stroke reduces error ($\Delta E < 0$), the stroke is committed to the drawing FBO.
   - Otherwise, the stroke is discarded, and parameters (radius, length, opacity) are perturbed.
4. **Coarse-to-Fine Progression**:
   - Begins with large brush radii (blocking in forms) and automatically decreases radius as MSE drops, adding fine detail.

---

## 9. Pragmatic Bezier Test Harness (`BezierTestHarness.kt`)

Built for automated verification and regression testing:

- **Codegen Stroke Patterns**:
  - Parametric Archimedean spirals, S-curves, figure-eights, loops, and straight lines.
  - Dynamically modulated pressure ramps from 0.1 to 1.0.
  - Batch execution of 30 to 200 strokes per test painting.
- **Test Suites**:
  - `test_bezier_curves_showcase`: 50 multi-curvature Bezier paths.
  - `test_stylus_pressure_dynamics`: Pressure taper, light-to-heavy ramps, flow test.
  - `test_smudge_shader_blend`: Multi-directional smudge blending across colors.
  - `test_steady_improvement_painting`: 200-stroke automated iterative convergence.
- **Export**: Direct offscreen rendering to 1024×1024 PNG files in `test-output/`.

---

## 10. Source Image Import & Dynamic Canvas Resolution Scaling

### 10.1 Aspect Ratio Preservation Architecture

Previous iterations fixed the offscreen drawing FBO to a square ($1024 \times 1024$), causing non-square imported images to distort or letterbox with unused canvas boundaries. SmartDraw's dynamic canvas architecture eliminates this constraint:

1. **Aspect Ratio Parity**:
   - The canvas aspect ratio is defined directly by the source image:
     $$\frac{W_{\text{canvas}}}{H_{\text{canvas}}} = \frac{W_{\text{src}}}{H_{\text{src}}}$$
   - Because canvas space and reference space share the identical aspect ratio $(W : H)$, the reference image maps $1:1$ across the entire canvas surface without distortion, stretch, or black letterbox borders.
   - In `SmartDrawRenderer.composite()`, the model matrix applies:
     $$\text{scaleM}(M_{\text{model}}, 0, S \cdot W_{\text{canvas}}, S \cdot H_{\text{canvas}}, 1.0f)$$
     This preserves true rectangular proportions on screen across any orientation or display size.

### 10.2 Import Modal Dialog & Resolution Multiplier ($1\times$ to $8\times$)

When a user selects an image from the device gallery or file provider (via "Load Source Image" or "Change Source Image"):

1. **Pre-flight Bounds Inspection**:
   - Image bounds $(W_{\text{src}}, H_{\text{src}})$ are extracted efficiently using `BitmapFactory.Options.inJustDecodeBounds = true` without allocating full pixel buffers in memory.
2. **Interactive Resolution Selection**:
   - An import modal displays:
     - Original source dimensions and megapixel count: e.g., `Original: 1200 × 1600 (1.9 MP)`.
     - An integer slider spanning $1\times$ to $8\times$ resolution multiplier.
     - Real-time resulting canvas dimensions and megapixel calculation: e.g., at $2\times$, `Canvas Resolution: 2400 × 3200 (7.7 MP)`.
     - GPU texture ceiling guard: checks against max texture size ($8192\text{ px}$). If $W_{\text{canvas}} > 8192$ or $H_{\text{canvas}} > 8192$, displays a warning and disables the confirm button to prevent GPU memory faults or device OOM.
3. **Execution on Confirmation**:
   - Upon clicking "Import & Create Canvas":
     1. Allocates and resizes GLES FBOs to $(W_{\text{canvas}}, H_{\text{canvas}})$, clearing the canvas to opaque white.
     2. Sets the reference image layer texture and enables auto-color mode (`useReferenceColors = true`).
     3. Calls `resetView()`, which calculates the aspect-fit scale factor:
        $$S_{\text{fit}} = \min\left(1.0, \min\left(\frac{W_{\text{surface}}}{W_{\text{canvas}}}, \frac{H_{\text{surface}}}{H_{\text{canvas}}}\right)\right)$$
        and centers the newly proportioned canvas inside the display viewport.

---

## 11. Persistence, Auto-Save & Project Gallery Architecture

Inspired by the workflow and reliability model of Autodesk Sketchbook, SmartDraw Android implements a non-destructive, zero-data-loss document lifecycle and multi-painting project gallery. Users never explicitly manage file save dialogs or risk losing work due to app termination, process death, or device reboots.

### 11.1 Painting Project Model & Storage Hierarchy

Each painting document is isolated within its own dedicated directory in internal sandbox storage (`context.filesDir/projects/<uuid>/`). Internal storage guarantees private isolation, zero runtime storage permission requirements across all Android API levels, and automatic cleanup on app uninstallation.

```
filesDir/
├── projects/
│   ├── active_project.json                     # Atomic pointer to the currently active project ID
│   ├── 8f4e2c0a-6b31-4c12-9e8a-123456789abc/   # Project Directory (<UUIDv4>)
│   │   ├── painting.png                        # Lossless RGBA8888 canvas base layer export
│   │   ├── source.png                          # Optional source / reference image
│   │   ├── thumbnail.png                       # Aspect-ratio preserving downscaled preview (≤ 384x384)
│   │   └── metadata.json                       # Project configuration, dimensions, timestamps, tool state
│   ├── 3d1a7f82-90bc-41de-8422-fedcba987654/
│   │   ├── painting.png
│   │   ├── thumbnail.png
│   │   └── metadata.json
│   └── ...
```

#### 11.1.1 Artifact Specification

1. **`painting.png`**:
   - Direct lossless PNG compression (`Bitmap.CompressFormat.PNG`, quality = 100) of the offscreen canvas base layer FBO (`baseFbo`).
   - Bit depth: 32-bit RGBA (8 bits per channel), preserving all brush strokes, smudge blending, and transparency/opacity levels at native canvas resolution $(W_{\text{canvas}} \times H_{\text{canvas}})$.
2. **`source.png`** *(Optional)*:
   - Untransformed reference/source image bitmap imported by the user. If no reference image has been loaded for the project, this file is omitted from the folder.
   - Stored in lossless PNG or compressed format directly matching the imported source image, used to populate `ReferenceLayer` and `ReferenceColorSampler` upon project load.
3. **`thumbnail.png`**:
   - Downscaled, aspect-ratio preserving composite representation used for visual rendering in gallery grid tiles.
   - Sizing invariant: Scaled such that $\max(W_{\text{thumb}}, H_{\text{thumb}}) \le 384\text{ px}$:
     $$s_{\text{thumb}} = \frac{384.0}{\max(W_{\text{canvas}}, H_{\text{canvas}})}$$
     $$W_{\text{thumb}} = \max(1, \text{round}(W_{\text{canvas}} \times s_{\text{thumb}})), \quad H_{\text{thumb}} = \max(1, \text{round}(H_{\text{canvas}} \times s_{\text{thumb}}))$$
   - Generated with high-quality bilinear filtering (`Bitmap.createScaledBitmap(..., filter = true)`) from the composite or base canvas bitmap and saved as PNG or high-quality WebP/JPEG.
4. **`metadata.json`**:
   - JSON serialized project manifest detailing geometric, chronological, and UI/tool configuration states.
   - Strict schema definition:
     ```json
     {
       "id": "8f4e2c0a-6b31-4c12-9e8a-123456789abc",
       "title": "Untitled Painting",
       "canvasWidth": 2400,
       "canvasHeight": 3200,
       "createdAt": 1700643600000,
       "updatedAt": 1700645400000,
       "referenceOpacity": 0.5,
       "isReferenceVisible": true,
       "useReferenceColors": true,
       "isRatchetEnabled": false,
       "activeTool": "PAINTBRUSH",
       "brushSize": 24.0,
       "brushOpacity": 0.85,
       "manualBrushColor": -16777216
     }
     ```
   - Field semantics:
     - `id` (`String`): Canonical UUIDv4 identifier matching the project directory name.
     - `title` (`String`): Human-readable display name (e.g., "Untitled Painting", "Portrait Study").
     - `canvasWidth` / `canvasHeight` (`Int`): Intrinsic resolution $(W_{\text{canvas}}, H_{\text{canvas}})$ in pixels.
     - `createdAt` / `updatedAt` (`Long`): Epoch milliseconds indicating creation and most recent save timestamps.
     - `referenceOpacity` (`Float`): Reference overlay layer opacity $\in [0.0, 1.0]$.
     - `isReferenceVisible` (`Boolean`): Visibility toggle state for the reference layer.
     - `useReferenceColors` (`Boolean`): AutoColor auto-picker active state.
     - `isRatchetEnabled` (`Boolean`): Ratchet mode distance-monotonic painting guard state.
     - `activeTool` (`String`): Identifier for the active tool (`PAINTBRUSH`, `PEN`, `SMUDGE`). Legacy "PENCIL" serializations gracefully map to `PAINTBRUSH`.
     - `brushSize` (`Float`): Canvas-space radius / diameter for brush stamping.
     - `brushOpacity` (`Float`): Stroke opacity factor $\in [0.0, 1.0]$.
     - `manualBrushColor` (`Int`): ARGB 32-bit integer color value for manual brush strokes when Auto Color is disabled; defaults to `#FF000000` (opaque black). Backwards compatible with existing project JSONs missing this field.
5. **`active_project.json`**:
   - Located in the root `projects/` directory, acting as an atomic pointer to the current document:
     ```json
     {
       "activeProjectId": "8f4e2c0a-6b31-4c12-9e8a-123456789abc"
     }
     ```

#### 11.1.2 Atomic File I/O & Crash Resilience

To prevent corrupt or partial project states caused by sudden power loss or process kill mid-write, all disk write operations adhere to atomic file swapping (`AtomicFile` or write-to-temp-then-rename pattern):
1. Payload is written completely to `<filename>.tmp` in the target project folder.
2. File descriptors are explicitly flushed to non-volatile disk (`FileOutputStream.fd.sync()`).
3. Atomic rename replaces the target file (`renameTo()`), guaranteeing that readers never encounter a half-written bitmap or truncated JSON payload.

---

### 11.2 5-Second Inactivity Auto-Save Engine

SmartDraw employs an event-driven, debounced auto-save architecture designed to eliminate frame drops and memory bandwidth contention while the user is actively painting.

```
[Stylus ACTION_UP] ──┐
[Tool Change]       ──┼──> Reset 5-Second Debounce Timer ──(5s Inactivity)──> Trigger Auto-Save
[Opacity / Size]    ──┤                                                        │
[Source Import]     ──┘                                                        ▼
                                                                     [Background Coroutine]
[Lifecycle: onPause/onStop] ──────────────────(Immediate Dispatch)──> ├─ exportBaseBitmap() (GL Thread)
                                                                       ├─ Compress painting.png
                                                                       ├─ Generate thumbnail.png
                                                                       └─ Write metadata.json (Atomic)
```

#### 11.2.1 Debounce Trigger Sources & Reset Mechanism

A high-resolution countdown timer (interval $\Delta t_{\text{idle}} = 5000\text{ ms}$) is scheduled on the main thread / coroutine dispatcher. The countdown resets to $5.0\text{ seconds}$ on any of the following user actions:
- **Stylus Stroke Completion**: Fired on `MotionEvent.ACTION_UP` or `ACTION_CANCEL` after committing Bezier stamps to `baseFbo`. Intermediary `ACTION_MOVE` events do not trigger disk I/O.
- **Tool Mode Modification**: Switching between Paintbrush, Pen, and Smudge.
- **Brush Parameter Adjustment**: Adjusting brush size or brush opacity sliders.
- **Reference Layer State Changes**: Adjusting reference opacity, toggling visibility, or enabling/disabling AutoColor.
- **Source Image Import / Mutation**: Importing a new reference image or altering canvas dimensions.

#### 11.2.2 Asynchronous Snapshot Execution Pipeline

When $5\text{ seconds}$ of user inactivity elapse without further interaction:
1. **Dirty Flag Verification**: A volatile `isDirty` boolean flag is checked. If no canvas strokes or parameter mutations occurred since the last snapshot, the engine skips disk operations entirely.
2. **GL Thread Pixel Extraction (`exportBaseBitmap`)**:
   - The engine requests a pixel readback from the Render Thread.
   - Inside the active EGL context, `SmartDrawRenderer.exportBaseBitmap()` binds `baseFbo` (`GL_FRAMEBUFFER`), executes `glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixelBuffer)`, and constructs a native Android `Bitmap` with vertical row flipping to convert OpenGL bottom-up coordinates to Android top-down bitmap coordinates.
3. **Background Coroutine Dispatch (`Dispatchers.IO`)**:
   - Pixel compression and file I/O are strictly offloaded from both the Main UI Thread and Render Thread to a dedicated I/O coroutine worker pool.
   - **Step A**: Compresses the base bitmap to `painting.png.tmp` via `Bitmap.CompressFormat.PNG` and atomically renames to `painting.png`.
   - **Step B**: Creates a filtered downscaled bitmap adhering to the $\le 384\text{ px}$ thumbnail bounding box, compresses to `thumbnail.png.tmp`, and atomically renames to `thumbnail.png`.
   - **Step C**: Updates `metadata.json` with current timestamps (`updatedAt = System.currentTimeMillis()`), resolution, reference layer parameters, and tool settings via atomic write.
   - **Step D**: Recycles temporary bitmap allocations and clears `isDirty = false`.

#### 11.2.3 Immediate Lifecycle Auto-Save (`onPause`, `onStop`)

Android activities can be paused or terminated abruptly by the system under memory pressure:
- In `Activity.onPause()` and `Activity.onStop()`, any running 5-second debounce countdown is immediately canceled.
- If `isDirty == true`, an immediate synchronous or blocking coroutine (`runBlocking(Dispatchers.IO)`) snapshot is executed before releasing EGL/OpenGL contexts or relinquishing foreground priority.
- This guarantees zero stroke loss even if the user switches apps or powers off the display mid-session.

---

### 11.3 Session Restoration & Launch Workflow

When SmartDraw Android launches (cold start, process recreation after death, or task switch):

```
                        App Launch / Cold Start
                                   │
                                   ▼
                      Read active_project.json
                                   │
                    ┌──────────────┴──────────────┐
                    │ Found Valid Project ID      │ Missing / Empty
                    ▼                             ▼
         Load projects/<id>/metadata.json   Create Default Project
                    │                       (1024x1024, Blank Canvas)
                    ▼                             │
         Check files & dimensions                 ▼
                    │                       Save to projects/<uuid>/
                    ▼                             │
    Reallocate FBOs (W_canvas, H_canvas)          ▼
                    │                       Write active_project.json
                    ▼                             │
    Restore baseFbo from painting.png             │
                    │                             │
    Load source.png (if exists) ─────────┐        │
                    │                    │        │
    Restore Tool, Opacity, AutoColor <───┘        │
                    │                             │
                    ▼                             ▼
              Initialize Viewport Camera (Fit, Centered, 1.0x, 0°)
                                   │
                                   ▼
                             Ready to Paint
```

#### 11.3.1 Step-by-Step Restoration Protocol

1. **Active Project Resolution**:
   - Reads `filesDir/projects/active_project.json`.
   - If the file exists and references an existing directory `<uuid>/` containing valid `metadata.json` and `painting.png`:
     - Deserializes `metadata.json` into a `ProjectMetadata` entity.
   - If `active_project.json` is missing, corrupt, or references a deleted project:
     - Scans `filesDir/projects/` for the project directory with the most recent `updatedAt` timestamp.
     - If no valid project exists on disk, invokes the **New Project Initialization** routine (creates `<uuid>/`, clears a default $1024 \times 1024$ white canvas, writes baseline metadata, and updates `active_project.json`).
2. **GPU Texture & FBO Reallocation**:
   - Extracts canvas resolution $(W_{\text{canvas}}, H_{\text{canvas}})$ from metadata.
   - Calls `SmartDrawRenderer.resizeCanvas(width, height)`, cleanly reallocating `baseFbo`, `scratchFbo`, `compositeFbo`, and `activeStrokeFbo` with matching dimensions and depth/stencil buffers.
3. **Canvas Base Layer Restoration**:
   - Decodes `painting.png` using `BitmapFactory.decodeFile()` with `inPreferredConfig = Bitmap.Config.ARGB_8888`.
   - Binds `baseFbo` texture and uploads pixel data via `GLUtils.texImage2D()`.
   - Also synchronizes `scratchFbo` to preserve smudge blending history.
4. **Reference Layer & Sampling Restoration**:
   - Inspects project folder for `source.png`.
   - If present:
     - Decodes `source.png` into a reference bitmap.
     - Calls `ReferenceLayer.setBitmap(bitmap)`, uploading the reference texture to the GPU and configuring pixel sampling arrays in `ReferenceColorSampler`.
     - Restores `referenceOpacity` and `isReferenceVisible`.
     - Restores `useReferenceColors` auto-picker state.
   - If absent:
     - Disables reference rendering and AutoColor mode.
5. **Tool & Brush Parameter Restoration**:
   - Restores `activeTool` (`PAINTBRUSH`, `PEN`, `SMUDGE`), updating the top toolbar icon and bottom sheet selection cards. Legacy "PENCIL" entries fallback safely to `PAINTBRUSH`.
   - Restores `brushSize` and `brushOpacity` sliders and preview indicators.
6. **Viewport Camera Normalization**:
   - To ensure consistent orientation upon document entry, the camera resets to canonical aspect-fit:
     $$\theta = 0^\circ$$
     $$S_{\text{fit}} = \min\left(1.0, \min\left(\frac{W_{\text{surface}}}{W_{\text{canvas}}}, \frac{H_{\text{surface}}}{H_{\text{canvas}}}\right)\right)$$
     $$T_x = \frac{W_{\text{surface}} - (S_{\text{fit}} \cdot W_{\text{canvas}})}{2}, \quad T_y = \frac{H_{\text{surface}} - (S_{\text{fit}} \cdot H_{\text{canvas}})}{2}$$
   - The drawing canvas opens centered, upright, and perfectly framed on the screen.

---

### 11.4 Gallery View & Multi-Painting Workflow

The Gallery interface provides a visual, responsive multi-painting management hub accessible directly from the top toolbar overflow menu ("Gallery" / "My Paintings").

#### 11.4.1 UI Layout & Presentation Model

- **Modal Presentation**: Presented as a full-screen overlay or bottom sheet with an app bar containing:
  - Title: "Paintings Gallery"
  - Action button: "+ New Painting"
  - Close button: Dismisses gallery, returning to the active painting.
- **Adaptive Grid Structure**:
  - Two-column grid on portrait phones; three- to four-column grid on landscape tablets.
  - Each gallery tile displays:
    - **Visual Card**: Rounded card container with elevated surface background.
    - **Thumbnail Preview (`ImageView`)**: Aspect-fit rendering of `thumbnail.png` against a subtle checkered background.
    - **Active Badge**: A visual accent indicator (e.g., colored border or "Current" pill) designating the currently opened project.
    - **Title Label**: Project title from `metadata.json` (editable inline or via context menu).
    - **Metadata Caption**: Resolution and relative timestamp: e.g., `2400 × 3200 • Updated 5m ago`.
    - **Overflow Actions (Three Dots)**: "Open", "Duplicate", "Export PNG", and "Delete".

#### 11.4.2 Project Switching Sequence

When the user taps a different painting tile in the gallery:
1. **Immediate Pre-Switch Save**:
   - The engine flushes any pending debounced auto-save for the currently loaded project immediately.
   - `exportBaseBitmap()` runs, and `painting.png`, `thumbnail.png`, and `metadata.json` are written to disk.
2. **Active Project Re-pointing**:
   - Updates `active_project.json` with the selected project's UUID.
3. **Editor Pipeline Re-initialization**:
   - Executes the Session Restoration sequence (§11.3) for the newly selected project.
   - Clears existing undo/redo buffers and smudge scratch buffers.
   - Reallocates FBOs to the target project's resolution.
   - Loads base pixels, reference image, tool settings, and resets the camera viewport to aspect-fit.
4. **Gallery Dismissal**:
   - The gallery modal closes, seamlessly presenting the newly selected painting in the editor.

#### 11.4.3 New Painting Creation Sequence

When the user taps "+ New Painting" (or selects "New Canvas" from overflow options):
1. **Current Project Flush**:
   - Immediately commits and saves the active project.
2. **Project Workspace Allocation**:
   - Generates a new UUIDv4: `val newId = UUID.randomUUID().toString()`.
   - Creates directory `filesDir/projects/<newId>/`.
3. **Default Dimension Initialization**:
   - If created from scratch: defaults to device screen resolution or standard $1024 \times 1024$ / $2048 \times 2048$ canvas.
   - If created via Source Image Import (§10): dynamically adopts aspect ratio and scaled dimensions $(W_{\text{canvas}}, H_{\text{canvas}})$.
4. **Canvas Reset & Baseline Persistence**:
   - Clears `baseFbo` and `scratchFbo` to opaque white (`[1.0, 1.0, 1.0, 1.0]`).
   - Writes initial `metadata.json` with default paintbrush settings, timestamp, and dimensions.
   - Generates initial blank `painting.png` and `thumbnail.png`.
   - Sets `active_project.json` pointing to `<newId>`.
5. **Editor Transition**:
   - Resets viewport camera to $(S = 1.0, \theta = 0^\circ, \text{centered})$.
   - Closes gallery and presents the fresh blank canvas to the user.

#### 11.4.4 Project Deletion & Fallback Invariants

When a user deletes a painting from the gallery:
1. **Directory Purge**:
   - Recursively deletes the project folder `filesDir/projects/<targetId>/` and all contained artifacts (`painting.png`, `source.png`, `thumbnail.png`, `metadata.json`).
2. **Active Painting Deletion Handling**:
   - If the deleted project is **not** the currently active project, the active project and editor state remain unaffected.
   - If the deleted project **is** the currently active project:
     - Scans `filesDir/projects/` for all remaining valid projects, sorting descending by `updatedAt`.
     - **Branch A (Remaining Projects Exist)**: Selects the most recently updated remaining project, writes its ID to `active_project.json`, and loads it into the editor via §11.3.
     - **Branch B (No Remaining Projects)**: If the gallery becomes completely empty, automatically invokes the New Painting Creation sequence (§11.4.3), ensuring the editor always holds a valid, writable canvas state and never encounters null pointer exceptions.

---

### 11.5 Edge Cases & State Invariants Matrix

| Scenario / Edge Case | Behavioral Specification & Defensive Strategy |
| :--- | :--- |
| **Rapid Stylus Stroking** | While drawing continuous strokes, each `ACTION_UP` pushes the 5-second timer forward. Disk I/O is never triggered during active drawing runs, ensuring smooth 60–120 FPS render performance. |
| **Sudden Process Death / Battery Depletion** | Immediate lifecycle save in `onPause()` / `onStop()` combined with atomic file writes (`AtomicFile` / temp file swap) guarantees that the document on disk is never corrupt and retains all strokes drawn up to the last 5-second idle window or background transition. |
| **Low Disk Space (Storage Exhaustion)** | Before writing `painting.png`, the system verifies available bytes via `StatFs(context.filesDir.path)`. If free space falls below $10\text{ MB}$, a non-intrusive warning notification is raised, and thumbnail downsampling is prioritized over multi-version scratch copies. |
| **Corrupted `metadata.json`** | If JSON parsing fails on project load, the loader falls back to reading bitmap dimensions directly from `painting.png` via `BitmapFactory.Options.inJustDecodeBounds = true`, recreates default metadata, and restores canvas pixels safely. |
| **Large Canvas Memory Footprint** | For high-resolution canvases (e.g., $4096 \times 4096$), bitmap encoding is executed on `Dispatchers.IO` using a stream buffer (`BufferedOutputStream`), and large bitmaps are recycled immediately after encoding to prevent Dalvik/ART GC thrashing. |
| **External File Alterations** | Project directories reside strictly inside internal sandbox storage (`filesDir`), isolating them from external media scanners, third-party file managers, and accidental user file deletions. |

---

## 12. Ratchet Mode (Distance-Monotonic / Improvement-Only Painting)

Ratchet Mode is an interactive painting constraint and guided assistance engine that guarantees every stroke committed to the canvas monotonically preserves or decreases the distance (difference) between the painting and the underlying reference image. Retrograde strokes—strokes that introduce color divergence, cover correct details with errors, or overshoot reference accuracy—are automatically discarded without mutating the base canvas.

### 12.1 Mathematical Objective & Invariant

#### 12.1.1 The Ratchet Invariant
Let $P$ represent the current committed painting state on the canvas, $I_{\text{ref}}$ represent the target reference image, and $S$ denote an incoming paint stroke defined by its geometry, color, and opacity stamps. Let $P'$ denote the candidate canvas state produced by blending stroke $S$ over $P$:
$$P' = P \oplus S$$

Let $\Omega_S \subset [0, W_{\text{canvas}}] \times [0, H_{\text{canvas}}]$ represent the canvas spatial bounding region affected by stroke $S$. The Ratchet Invariant stipulates that candidate state $P'$ is accepted and committed to the canvas base layer if and only if:
$$\text{Distance}(P', I_{\text{ref}}) \le \text{Distance}(P, I_{\text{ref}})$$
evaluated over the affected bounding region $\Omega_S$. If:
$$\text{Distance}(P', I_{\text{ref}}) > \text{Distance}(P, I_{\text{ref}})$$
the stroke is discarded / rolled back with zero mutation to $P$.

#### 12.1.2 Monotonic Convergence Property
By induction over successive accepted strokes $S_1, S_2, \dots, S_n$ generating canvas states $P_1, P_2, \dots, P_n$:
$$\text{Distance}(P_k, I_{\text{ref}}) \le \text{Distance}(P_{k-1}, I_{\text{ref}}) \quad \forall k \ge 1$$
Under Ratchet Mode, the canvas distance function is strictly non-increasing over all committed regions. The user is physically prevented from making the painting diverge from the reference image, allowing fearless, rapid, and expressive exploratory brushwork.

---

### 12.2 Similarity & Distance Metrics

The difference metric $\text{Distance}(P, I_{\text{ref}})$ over bounding domain $\Omega$ can be evaluated using Mean Absolute Error (MAE / L1 norm) or Mean Squared Error (MSE / L2 norm) across the RGB color channels:

#### 12.2.1 Mean Absolute Error (MAE / L1 Metric)
$$\text{Loss}(P, I_{\text{ref}}) = \frac{1}{3 \cdot |\Omega|} \sum_{(x, y) \in \Omega} (|P_R(x, y) - I_{R}(x, y)| + |P_G(x,y) - I_{G}(x,y)| + |P_B(x,y) - I_{B}(x,y)|)$$
where:
- $|\Omega| = W_\Omega \times H_\Omega$ is the total pixel count within the bounding region.
- $P_c(x, y) \in [0.0, 1.0]$ and $I_c(x, y) \in [0.0, 1.0]$ denote the color intensities for channel $c \in \{R, G, B\}$.

#### 12.2.2 Mean Squared Error (MSE / L2 Metric)
$$\text{Loss}_{\text{MSE}}(P, I_{\text{ref}}) = \frac{1}{3 \cdot |\Omega|} \sum_{(x, y) \in \Omega} \left( (P_R(x, y) - I_R(x, y))^2 + (P_G(x, y) - I_G(x, y))^2 + (P_B(x, y) - I_B(x, y))^2 \right)$$

#### 12.2.3 Acceptance Differential ($\Delta \text{Loss}$)
The change in loss induced by the candidate stroke is:
$$\Delta \text{Loss} = \text{Loss}(P', I_{\text{ref}}) - \text{Loss}(P, I_{\text{ref}})$$
- **Acceptance Condition**: $\Delta \text{Loss} \le 0$ (error decreases or remains unchanged).
- **Rejection Condition**: $\Delta \text{Loss} > 0$ (error increases).

---

### 12.3 Execution Pipeline & Non-Destructive Rollback

The execution architecture relies on offscreen GLES 3.0 Framebuffer Object (FBO) isolation to maintain real-time 60–120 FPS stroke responsiveness while deferring final base canvas mutation until post-stroke validation:

```
[Stylus ACTION_DOWN / ACTION_MOVE]
               │
               ▼
   Stamp into activeStrokeFbo ──(Zero mutation to baseFbo)
               │
   CompositingShader blends activeStrokeFbo + baseFbo on screen (Live Preview)
               │
[Stylus ACTION_UP]
               │
               ▼
   Calculate Bounding Box Ω_S (Clamped to Canvas)
               │
   Sample Ω_S: Candidate P' (activeStrokeFbo + baseFbo), Base P (baseFbo), Reference I_ref
               │
   Compute ΔLoss = Loss(P', I_ref) - Loss(P, I_ref)
               │
         ┌─────┴────────────────────────┐
         │ ΔLoss ≤ 0                    │ ΔLoss > 0
         ▼                              ▼
   endActiveStroke()              clearActiveStroke()
   - Commit stamps to baseFbo     - Discard activeStrokeFbo
   - Clear activeStrokeFbo        - baseFbo left 100% pristine
   - Reset debounce auto-save     - Haptic / Toast notification
```

#### 12.3.1 Bounding Region Delineation ($\Omega_S$)
During stroke tracking, a dynamic bounding box is maintained from all evaluated Bezier points and touch coordinates $(x_i, y_i)$ with associated brush radii $R_i$:
$$\Omega_S = [\min(x) - R_{\max}, \; \min(y) - R_{\max}, \; \max(x) + R_{\max}, \; \max(y) + R_{\max}]$$
clamped to canvas dimensions $[0, W_{\text{canvas}}] \times [0, H_{\text{canvas}}]$, where $R_{\max}$ accounts for maximum brush radius plus antialiasing padding along the Bezier curve / stroke path.

Restricting the loss evaluation domain to $\Omega_S$ prevents costly full-canvas pixel readbacks and guarantees instantaneous evaluation even on high-resolution canvases.

#### 12.3.2 Non-Destructive Live Accumulation in `activeStrokeFbo`
- In GLES 3.0, live strokes are accumulated in `activeStrokeFbo` without mutating `baseFbo`.
- `baseFbo` and `scratchFbo` remain untouched during active drawing.
- `CompositingShader` blends `activeStrokeFbo` over `baseFbo` and checkered background on the screen framebuffer (`GL_FRAMEBUFFER 0`), giving the user immediate, latency-free visual feedback of their stroke.

#### 12.3.3 Stroke Finalization on `ACTION_UP`
When the stylus lifts (`MotionEvent.ACTION_UP`):
1. **Pre-Commit Sampling**:
   - Before committing to `baseFbo`, sample the bounding box region $\Omega_S$ of `baseFbo` and reference image $I_{\text{ref}}$.
   - Evaluate preview/committed candidate against reference image.
2. **Evaluation & Execution**:
   - If candidate improves or matches reference distance ($\Delta \text{Loss} \le 0$), `endActiveStroke()` commits the stroke to `baseFbo`.
   - If candidate worsens similarity ($\Delta \text{Loss} > 0$), `clearActiveStroke()` discards the stroke with zero mutation to `baseFbo`.
3. **Cancellation Handling**:
   - On `ACTION_CANCEL`, `clearActiveStroke()` discards the active stroke immediately.

#### 12.3.4 Sub-Rectangle Software Simulation & Evaluator Implementation
To prevent expensive GPU synchronization stalls (`glFinish` or full texture copy), `RatchetEvaluator.kt` evaluates candidate loss entirely via CPU sub-rectangle simulation:
1. **Sub-Rect Readback (`baseFbo.readSubRect`)**:
   - Reads only the bounded rectangle $\Omega_S = [x_0, y_0, x_1, y_1]$ into an integer pixel buffer `basePixels` ($W_\Omega \times H_\Omega$).
2. **Software Disc Stamp Rasterizer (`simulateStrokeOnSubRect`)**:
   - Copies `basePixels` into `candidatePixels`.
   - Iterates through accumulated Bezier segments, sampling uniform stamp positions at `stepSize = max(0.5f, radius * spacingRatio)`.
   - Stamps circular disc brush footprints with antialiased edge falloff:
     $$\text{edgeDist} = r_{\text{eff}} - \text{dist}, \quad \text{antialias} = \text{clamp}(\text{edgeDist}, 0.0, 1.0)$$
     $$\alpha_{\text{stamp}} = \alpha_{\text{seg}} \times \text{antialias}$$
   - Blends candidate stroke color over `candidatePixels` using standard alpha compositing.
3. **L1 Manhattan Loss Comparison (`evaluateSimilarity`)**:
   - Compares total absolute color error across RGB channels over all pixels in $\Omega_S$:
     $$\text{lossBefore} = \sum_{(x, y) \in \Omega_S} (|P_R - I_R| + |P_G - I_G| + |P_B - I_B|)$$
     $$\text{lossAfter} = \sum_{(x, y) \in \Omega_S} (|P'_R - I_R| + |P'_G - I_G| + |P'_B - I_B|)$$
   - Returns `lossAfter <= lossBefore`. If true, the GPU renderer permanently commits stamps to `baseFbo`; if false, `activeStrokeFbo` is cleared, achieving zero-cost rollback.

---

### 12.4 UI Integration & State Persistence

#### 12.4.1 User Interface Controls
- **More Options Bottom Sheet**: "Ratchet Mode (Keep Improvements Only)" toggle in `MoreOptionsBottomSheet` alongside Source Image Opacity and Reference Layer Visibility controls.
- **Availability Guard**: Enabled when a valid reference image is loaded for the project. When no reference image exists, the toggle is disabled with an explanatory caption.

#### 12.4.2 Project Metadata & Schema Persistence
The Ratchet Mode toggle state is persisted per-project:
- Persisted in `metadata.json` (`isRatchetEnabled: Boolean`) and `PaintingProject`.
- In Kotlin domain entities:
  - `PaintingProject(..., val isRatchetEnabled: Boolean = false)`
  - Serialized and deserialized in `ProjectManager` and maintained across sessions, gallery switching, and app restarts.

#### 12.4.3 Visual & Haptic Feedback
- **Visual Feedback**: Subtle status message in the persistent status banner when ratchet mode accepts or rejects strokes.
- **Zero Data Loss Guarantee**: Rejected strokes leave `baseFbo` in its exact pre-stroke state without allocating undo history or creating disk writes.

---

## 13. User Interface & Interaction Architecture

SmartDraw Android implements an uncluttered, modern dark-theme user interface optimized for tablet and phone displays. Screen real estate is preserved for the canvas by utilizing icon-only controls and modal bottom sheets.

### 13.1 Streamlined Icon-Only Top Toolbar (`activity_main.xml`)

The top app bar (height $56\text{ dp}$, background `#1E1E1E`, elevation $4\text{ dp}$) houses six primary controls with visual state cues:

```
┌─────────────────────────────────────────────────────────────────────────────────────────────────────┐
│ [ Tool ] [ Color ] [ 💧 Eyedropper ] [ Reset View ] [ ⧉ Layers ]   <Spacer>   [ 🖼 Gallery ] [ … More ] │
└─────────────────────────────────────────────────────────────────────────────────────────────────────┘
```

1. **`btnActiveTool` (Active Tool Icon & Quick Settings Trigger)**:
   - Displays icon reflecting current tool mode: `ic_brush` for Paintbrush, `ic_pen` for Pen, or `ic_smudge` for Smudge.
   - Styled with `@drawable/bg_tool_button_active` (`#2A2A2A` surface with active outline).
   - Single tap opens the **Tool Settings Bottom Sheet** (`ToolSettingsBottomSheet`).
2. **`btnColorSettings` (Dedicated Color Settings Button)**:
   - Displays the active color state:
     - **Manual Color Mode**: Renders a live circular color swatch preview reflecting `drawView.brushColor` with a crisp white border.
     - **Auto Color Mode**: Renders the dynamic sampling icon (`ic_auto_color`) with highlighted active state styling.
   - Single tap opens the dedicated **Color Settings Bottom Sheet** (`ColorSettingsBottomSheet`).
3. **`btnEyedropper` (Top Toolbar Eyedropper Tool Button)**:
   - Displays the eyedropper icon (`ic_eyedropper`).
   - One-tap activation of canvas eyedropper mode (`drawView.isEyedropperActive = true`).
   - Highlights with `@drawable/bg_tool_button_active` when eyedropper mode is active.
   - Touching or dragging on the canvas samples pixel colors in real time. Releasing (`ACTION_UP`) immediately commits the sampled color, switches to manual color mode, and deactivates eyedropper mode. Tapping the toolbar button while active toggles eyedropper mode off.
4. **`btnResetView` (Viewport Reset to 1:1 Aspect-Fit Centered)**:
   - Displays the target frame icon (`ic_reset_view`).
   - Instantly snaps canvas scale to aspect-fit ($S = 1.0$), rotation to $\theta = 0^\circ$, and centers the canvas inside the viewport.
5. **`btnLayers` (Layer Management Button)**:
   - Displays the layers icon (`ic_layers`).
   - Single tap opens the **Layer Management Bottom Sheet** (`LayerManagerBottomSheet`).
6. **Flexible Spacer (`View weight=1`)**:
   - Generously separates core drawing tools on the left from document and session management on the right.
7. **`btnGallery` (Multi-Painting Gallery)**:
   - Displays the image grid icon (`ic_gallery`).
   - Flushes active auto-save and opens the **Paintings Gallery** modal (`GalleryDialogFragment`).
8. **`btnMoreOptions` (Overflow Ellipsis Settings Sheet)**:
   - Displays the ellipsis icon (`ic_more`).
   - Opens the **More Options Bottom Sheet** (`MoreOptionsBottomSheet`).

---

### 13.2 Tool Settings Bottom Sheet (`ToolSettingsBottomSheet.kt`)

Activated by tapping the active tool icon (`btnActiveTool`) in the toolbar, this bottom sheet provides tactile control strictly focused on tool selection, radius, and opacity with real-time visual feedback:

1. **Tool Switcher Cards**:
   - Interactive card containers with $8\text{ dp}$ rounded corners (`bg_tool_card_active` / `bg_tool_card_inactive`) for:
     1. **Paintbrush (`cardToolBrush`)**:
        - Dynamics: Stylus pressure modulates brush radius ($R_{\text{effective}} = R_{\text{base}} \cdot P$). Opacity is fixed to configured brush opacity ($\alpha_{\text{effective}} = \alpha_{\text{base}}$).
        - Icon: `ic_brush.xml`.
     2. **Pen (`cardToolPen`)**:
        - Dynamics: Zero pressure sensitivity. Radius is fixed to $R_{\text{base}}$ and opacity is fixed to $\alpha_{\text{base}}$, producing uniform, clean inking lines.
        - Icon: `ic_pen.xml`.
     3. **Smudge (`cardToolSmudge`)**:
        - Retains existing smudge convolution behavior across the canvas.
        - Icon: `ic_smudge.xml`.
   - Active card is highlighted with `@drawable/bg_tool_card_active` (`#1A3A60` background with `#2979FF` accent border).
   - Inactive cards use `@drawable/bg_tool_card_inactive` (`#212121` background with `#333333` border).
   - Switching tools automatically updates `drawView.toolMode`, updates the top toolbar icon dynamically, and triggers a debounced auto-save timer reset.
2. **Live Brush Preview Ring (`BrushPreviewView.kt`)**:
   - Custom Android `View` rendering an antialiased circular brush stamp on a checkered preview box.
   - Dynamically scales radius and adjusts alpha in real time as the user drags sliders, providing instantaneous tactile calibration.
   - Displays current brush color (or white when Auto Color is active).
3. **Brush Size Slider**:
   - Granular slider spanning $2\text{ px}$ to $100\text{ px}$ with integer readout (e.g., `24 px`).
4. **Brush Opacity Slider**:
   - Continuous slider spanning $1\%$ to $100\%$ with percentage readout (e.g., `85%`).

---

### 13.3 Dedicated Color Settings Bottom Sheet (`ColorSettingsBottomSheet.kt`)

Activated by tapping the dedicated color button (`btnColorSettings`) in the top toolbar, this bottom sheet centralizes color configuration:

1. **Header & Close Button**:
   - Prominent "Color" title alongside an explicit close icon button (`btnCloseColorSettings`) for rapid dismissal.
2. **Mode Selector Segmented Toggle**:
   - Side-by-side toggle buttons for **Auto Color** vs **Manual Color**.
   - Switches between automatic reference sampling and manual color palette controls.
3. **Auto Color Mode View**:
   - **Context & Guidance**: Explains that brush color is dynamically sampled from the reference image along each stroke.
   - **Reference Image Status Indicator**: Readout displaying whether a custom reference image (`Custom Image (W×H)`) or default radial gradient is loaded and ready.
   - **Quick Action**: "Switch to Manual Color Configuration" button seamlessly flips to manual color editing.
4. **Manual Color Mode View**:
   - **Live Swatch & Hex Readout**: Displays an antialiased circular color swatch preview with a crisp border alongside the formatted uppercase hex code (`#RRGGBB`).
   - **Quick-Action Eyedropper Button**: Dedicated button styled with `ic_eyedropper`. Tapping dismisses the bottom sheet, activates `drawView.isEyedropperActive = true`, and presents guidance feedback to sample any color directly from the canvas.
   - **Color Mode Switcher**: Segmented toggle buttons switching between **RGB** and **HSL** slider sets.
   - **RGB Sliders**:
     - Red ($0$ to $255$, styled in red `#FF5252`).
     - Green ($0$ to $255$, styled in green `#4CAF50`).
     - Blue ($0$ to $255$, styled in blue `#2979FF`).
   - **HSL Sliders**:
     - Hue ($0^\circ$ to $360^\circ$, styled in orange `#FF9800`).
     - Saturation ($0\%$ to $100\%$, styled in pink `#E91E63`).
     - Lightness ($0\%$ to $100\%$, styled in purple `#9C27B0`).
   - **Bidirectional Model Synchronization**: Adjusting RGB sliders automatically computes and updates HSL sliders via `ColorSelectionUtil.colorToHsl`, and adjusting HSL sliders computes and updates RGB sliders via `ColorSelectionUtil.hslToColor`.

---

### 13.4 Overflow Settings Bottom Sheet (`MoreOptionsBottomSheet.kt`)

Activated by tapping the ellipsis (`...`) button, this sheet consolidates secondary document and layer controls:

1. **Nested Source Image Action**:
   - Dynamic button: Displays "Load Source Image" if no custom reference image is present, or "Change Source Image" if an image is already active.
   - Clicking dismisses the sheet and launches the Android photo picker (`ActivityResultContracts.GetContent("image/*")`).
2. **Source Image Opacity Slider**:
   - Continuous slider spanning $0\%$ to $100\%$ with percentage readout.
   - Dragging slider automatically enables reference layer visibility if opacity $> 0\%$.
3. **Source Image Visibility Toggle**:
   - "Hide Source Image" / "Show Source Image" button toggling `referenceLayer.isVisible`.
4. **Ratchet Mode Toggle (`SwitchCompat`)**:
   - Switch control labeled "Ratchet Mode (Keep Improvements Only)" with explanatory subtitle.
   - Enforces distance-monotonic painting (§12).
5. **Clear Canvas Action**:
   - Raises a destructive confirmation alert dialog: "Are you sure you want to clear your painting? This cannot be undone."
   - Clears `baseFbo` and `scratchFbo` to opaque white upon confirmation.
6. **Reset Canvas View Action**:
   - Redundant shortcut executing `drawView.resetView()`.
7. **Run Test Harness Action**:
   - Triggers asynchronous execution of the 30–200 stroke Bezier test harness (§9).
8. **Document Shortcuts**:
   - Direct buttons to "Open Gallery" and "+ New Painting".

---

### 13.5 Source Image Import & Aspect-Fit Resolution Scaling Modal (`dialog_import_image.xml`)

When an image is picked from device storage or gallery:

1. **Header & Bounds Pre-flight**:
   - Displays original dimensions and megapixel count: e.g., `Original: 1200 × 1600 (1.9 MP)`.
2. **Resolution Multiplier Slider**:
   - Integer slider spanning $1\times$ to $8\times$. Default scale is automatically pre-selected ($2\times$ for images $\le 1024\text{ px}$, $1\times$ for larger images).
3. **Real-Time Target Dimensions & MP Calculation**:
   - Displays scaled resolution: e.g., at $3\times$, `Canvas Resolution: 3600 × 4800 (17.3 MP)`.
4. **GPU Texture Ceiling Guard**:
   - Enforces $8192\text{ px}$ GPU hardware limit:
     $$\max(W_{\text{target}}, H_{\text{target}}) \le 8192$$
   - If exceeded, displays a prominent warning in red (`textGpuLimitWarning`) and disables the "Import & Create Canvas" button.
5. **Confirmation Pipeline**:
   - Allocates FBOs to exact target dimensions, clears canvas to opaque white, sets reference image texture, and centers the camera.

---

### 13.6 Multi-Painting Gallery Dialog (`GalleryDialogFragment.kt` & `GalleryAdapter.kt`)

Provides visual, responsive document management:

1. **Fullscreen Presentation**:
   - Dark theme overlay (`Theme_Black_NoTitleBar_Fullscreen`) with app bar containing "+ New Painting" and close buttons.
2. **Adaptive Grid Layout**:
   - Automatically switches column span based on device orientation: 2 columns on portrait, 3 columns on landscape.
3. **Painting Tile Card**:
   - **Thumbnail Preview**: Aspect-fit rendering of downscaled `thumbnail.png` against checkered background.
   - **Active Badge**: Prominent colored border / badge indicating the currently open document.
   - **Title Label**: Project title from `metadata.json`.
   - **Metadata Caption**: Dimensions and relative timestamp (e.g., `2400 × 3200 • Updated 5m ago`).
   - **Delete Button**: Trash icon raising confirmation dialog.
4. **Safe Deletion & Active Document Fallback**:
   - Deleting the active document automatically transfers the editor to the most recently updated remaining project.
   - If the gallery becomes empty, automatically creates a new blank canvas project, guaranteeing zero null-state crashes.

---

### 13.7 Dedicated Status Banner & Progress Indicator

Positioned immediately beneath the top toolbar (background `#181818`, height $32\text{ dp}$):
- Displays real-time operational feedback: e.g., "Ready - Draw with stylus, pan/zoom with two fingers", "Tool: Paintbrush (size: 24 px)", "Brush color: #2979FF", "Auto color sampling enabled", "View reset to 1:1 centered".
- Integrated circular `ProgressBar` indicating background disk auto-save, image decoding, or test harness execution.

---

### 13.8 Complete UI Action & Event Flow Matrix

| UI Component | User Action | Event / Execution Target | State Transition |
| :--- | :--- | :--- | :--- |
| **`btnActiveTool`** | Click | `showToolSettingsModal()` | Opens `ToolSettingsBottomSheet` |
| **Tool Card (Brush/Pen/Smudge)** | Click | `onToolSelected(toolMode)` | Updates active tool, top toolbar icon, marks dirty |
| **Brush Size Slider** | Drag | `onBrushSizeChanged(radius)` | Updates `baseRadius`, brush preview ring, marks dirty |
| **Brush Opacity Slider** | Drag | `onBrushOpacityChanged(opacity)` | Updates `baseOpacity`, brush preview ring, marks dirty |
| **`btnColorSettings`** | Click | `showColorSettingsModal()` | Opens `ColorSettingsBottomSheet` |
| **Mode Selector (Auto/Manual)** | Click | `onAutoColorToggled(enabled)` | Toggles between Auto and Manual color modes, updates toolbar button, marks dirty |
| **RGB / HSL Sliders** | Drag | `onBrushColorChanged(color)` | Updates `drawView.brushColor`, live swatch, toolbar button swatch, marks dirty |
| **Eyedropper Button (Sheet)** | Click | `onEyedropperRequested()` | Dismisses sheet, sets `isEyedropperActive = true`, updates toolbar eyedropper highlight |
| **`btnEyedropper` (Toolbar)** | Click | Toggle `drawView.isEyedropperActive` | One-tap toggle of canvas eyedropper mode, highlights toolbar button |
| **Canvas Eyedropper** | Touch / Drag | `sampleCanvasColor(cx, cy)` | Samples `baseFbo` pixel, updates color live; commits & deactivates on release |
| **`btnResetView`** | Click | `drawView.resetView()` | Viewport scale $\to S_{\text{fit}}$, rotation $\to 0^\circ$, centered |
| **`btnGallery`** | Click | `openGallery()` | Flushes auto-save, opens `GalleryDialogFragment` |
| **`btnMoreOptions`** | Click | `showMoreOptionsModal()` | Opens `MoreOptionsBottomSheet` |
| **Load/Change Source Image** | Click | `pickImageLauncher.launch("image/*")` | Opens system photo picker |
| **Import Modal Slider** | Drag | Scale $1\times$ to $8\times$ | Recalculates MP, validates $\le 8192\text{ px}$ GPU ceiling |
| **Import Modal Confirm** | Click | `performImageImport(uri, w, h)` | Resizes canvas FBOs, sets reference, enables AutoColor |
| **Source Opacity Slider** | Drag | `onOpacityChanged(percent)` | Updates `referenceLayer.opacity`, marks dirty |
| **Toggle Source Visibility** | Click | `onToggleSourceVisibilityClicked()` | Toggles `referenceLayer.isVisible`, marks dirty |
| **Ratchet Mode Switch** | Toggle | `onRatchetModeToggled(enabled)` | Updates `drawView.isRatchetEnabled`, marks dirty |
| **Clear Painting Button** | Click | Confirmation Dialog $\to$ Clear | Clears FBOs to white, marks dirty |
| **Gallery Tile** | Click | `onProjectSelected(project)` | Flushes current save, restores target project |
| **Gallery Tile Delete** | Click | Confirmation Dialog $\to$ Purge | Deletes `<uuid>/`, falls back to next active project |
| **Gallery "+ New Painting"** | Click | `promptNewPainting()` | Flushes current save, creates new project, resets view |
| **`btnLayers`** | Click | `LayerManagerBottomSheet.newInstance().show()` | Opens `LayerManagerBottomSheet` |
| **Add Layer (+)** | Click | `drawView.addLayer()` | Appends new layer above active layer, sets active |
| **Duplicate Layer** | Click | `drawView.duplicateLayer(id)` | Clones active layer pixels via `blitFbo` |
| **Merge Down** | Click | `drawView.mergeDown(id)` | Alpha-blends active layer into lower layer, deletes active |
| **Move Up (▲)** | Click | `drawView.moveLayerUp(id)` | Moves active layer up in rendering stack |
| **Move Down (▼)** | Click | `drawView.moveLayerDown(id)` | Moves active layer down in rendering stack |
| **Delete Layer (Trash)** | Click | `drawView.deleteLayer(id)` | Deletes layer and releases FBO (if $>1$ layer remains) |
| **Layer Item Click** | Click | `drawView.selectLayer(id)` | Sets active drawing layer target |
| **Layer Eye Icon** | Click | `drawView.setLayerVisibility(id, visible)` | Toggles layer rendering in compositing pass |
| **Layer Opacity Slider**| Drag | `drawView.setLayerOpacity(id, opacity)` | Adjusts layer alpha $[0\%, 100\%]$ in compositing pass |

---

### 13.9 Layer Management Bottom Sheet (`LayerManagerBottomSheet.kt` & `modal_layer_manager.xml`)

Activated by tapping the layers icon (`btnLayers`) in the top toolbar, this bottom sheet provides full multi-layer creation, organization, and compositing control:

1. **Header & Layer Count**:
   - Title: "Layers".
   - Displays real-time count of layers (e.g., "3 layers").
2. **Action Toolbar**:
   - Modern $44\text{ dp}$ buttons with $8\text{ dp}$ rounded corners (`bg_tool_button_inactive`).
   - **Add Layer (`btnAddLayer`, `ic_add`)**: Creates a new transparent `CanvasLayer` immediately above the currently selected layer and sets it active.
   - **Duplicate (`btnDuplicateLayer`, `ic_copy`)**: Clones the active layer's texture and properties into a new layer using fast GPU `glBlitFramebuffer`.
   - **Merge Down (`btnMergeDown`, `ic_merge`)**: Blends the active layer onto the layer directly below it via `compositingShader.renderLayerQuad` and releases the merged layer. Disabled when the bottom-most layer is active.
   - **Move Up (`btnMoveUp`, `ic_arrow_up`)**: Promotes the active layer higher in the rendering stack (increasing z-index). Disabled when the top-most layer is active.
   - **Move Down (`btnMoveDown`, `ic_arrow_down`)**: Demotes the active layer lower in the rendering stack. Disabled when the bottom-most layer is active.
   - **Delete (`btnDeleteLayer`, `ic_delete`)**: Prompts with a confirmation dialog: "Are you sure you want to delete this layer? This action cannot be undone." with "Delete" (destructive) and "Cancel" buttons. Upon confirmation, removes the active layer and reclaims GPU FBO memory. Disabled when only one layer remains on the canvas.
3. **Layer List (`RecyclerView` with `item_layer.xml`)**:
   - Presented in natural visual order (top layer at the top of the list, bottom layer at the bottom).
   - **Active Selection**: Card highlights with `@drawable/bg_tool_card_active` and an active indicator badge; inactive cards render with `@drawable/bg_tool_card_inactive`. Tapping any card selects it as the active drawing layer.
   - **Layer Name**: Displays layer title (e.g., "Layer 1", "Layer 2", "Layer 1 Copy").
   - **Visibility Toggle**: Eye icon (`ic_visibility` when visible, `ic_visibility_off` with dimmed `#757575` tint when hidden). Toggling updates the compositing pass instantly without destroying drawing contents.
   - **Opacity Slider**: Integrated $0-100\%$ `SeekBar` with live percentage readout, modulating the layer quad's alpha in real time.

---

## 14. Undo / Redo with Minimal Bounding Square Sub-Rect Diffs

SmartDraw Android implements a multi-level, memory-efficient Undo / Redo system. Rather than duplicating full megapixel canvas textures (which would exhaust GPU and heap memory on large canvases such as $4096 \times 4096$), the engine captures only the minimal square bounding sub-rectangle affected by each individual stroke or smudge operation.

### 14.1 SubRectDiff Architecture

Each undoable operation is captured as a discrete `SubRectDiff`:

```kotlin
data class SubRectDiff(
    val layerId: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val beforePixels: IntArray,
    val afterPixels: IntArray
)
```

- **`layerId`**: Identifier of the target canvas layer where the drawing operation occurred.
- **`x`, `y`**: Canvas-space coordinates of the top-left corner of the affected bounding box.
- **`width`, `height`**: Dimensions of the affected sub-rectangle (in pixels).
- **`beforePixels`**: Row-major packed 32-bit ARGB pixels of size $\text{width} \times \text{height}$ capturing the layer's pixels immediately prior to the stroke.
- **`afterPixels`**: Row-major packed 32-bit ARGB pixels of size $\text{width} \times \text{height}$ capturing the layer's pixels immediately after the stroke is permanently committed.

### 14.2 Minimal Bounding Square Calculation

When a stylus stroke or smudge begins (`onStrokeStart`):
1. A pre-stroke capture of the target layer's pixels is initiated (`beginStrokeCapture()`).
2. Point cloud tracking records every coordinate $(x_i, y_i)$ and dynamic radius $R_i$.
3. When the stroke finishes (`onStrokeEnd`), the bounding box is computed over all stroke points and maximum radius:

```kotlin
val minX = points.minOf { it.x - maxRadius }.toInt().coerceIn(0, width - 1)
val maxX = points.maxOf { it.x + maxRadius }.toInt().coerceIn(0, width - 1)
val minY = points.minOf { it.y - maxRadius }.toInt().coerceIn(0, height - 1)
val maxY = points.maxOf { it.y + maxRadius }.toInt().coerceIn(0, height - 1)

// Expand to square:
val side = maxOf(maxX - minX + 1, maxY - minY + 1)
val boxW = minOf(side, width - minX)
val boxH = minOf(side, height - minY)
```

4. The engine reads `afterPixels` over $(minX, minY, boxW, boxH)$ on the target layer's FBO, extracts the matching sub-rectangle from `beforePixels`, and packages them into `SubRectDiff`.

### 14.3 Stack Invariants & Memory Bounds

- **Undo Stack (`undoStack`)**: Holds up to 30 sequential `SubRectDiff` operations (configurable via `maxUndoHistory = 30`). When the stack exceeds 30 items, the oldest diff is dropped from the bottom of the deque.
- **Redo Stack (`redoStack`)**: Stores diffs that have been undone. Any newly committed stroke automatically clears the `redoStack`.
- **Canvas Resizing & Layer Reset (`clearHistory()`)**: When loading a new project, clearing the canvas, or resizing dimensions, both stacks are purged.
- **Sub-Rect Texture Blit (`GlesFbo.loadSubRect`)**: Undoing or redoing uploads only the $(boxW \times boxH)$ pixels into the target layer texture using `glTexSubImage2D`, ensuring near-instantaneous restoration without allocating or reallocating FBO textures.

### 14.4 Toolbar Action Integration

- **Toolbar Buttons**:
  - `btnUndo` (icon: `ic_undo.xml`, description: "Undo")
  - `btnRedo` (icon: `ic_redo.xml`, description: "Redo")
- **Dynamic Visual State**:
  - When active (`canUndo` or `canRedo` is true): `alpha = 1.0f`, `isEnabled = true`, `isClickable = true`.
  - When inactive (`canUndo` or `canRedo` is false): `alpha = 0.35f`, `isEnabled = false`, `isClickable = false`.
- **Click Actions**:
  - `btnUndo.setOnClickListener { drawView.undo() }`
  - `btnRedo.setOnClickListener { drawView.redo() }`
- **Callback Hook**: `SmartDrawView.onHistoryStateChanged: ((canUndo: Boolean, canRedo: Boolean) -> Unit)?` notifies the UI immediately when stack availability changes.

---


