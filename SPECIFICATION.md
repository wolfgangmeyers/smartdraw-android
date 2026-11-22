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

1. **Background Layer**: Generates or uploads a checkered transparency pattern or solid background color.
2. **Reference Image Layer**: GPU texture (`GL_TEXTURE_2D`) containing the target painting image, with uniform opacity (`u_referenceOpacity`) and toggleable visibility (`u_renderReference`).
3. **Drawing Layer (Base Layer)**: Main user painting canvas stored in an FBO texture target (`GL_RGBA8`).
4. **Compositing Pass**: Fullscreen or transformed quad shader combining background, drawing layer, reference layer, and UI overlay with alpha blending. During viewport navigation (pan and zoom), the canvas quad is rendered transformed by the Viewport Model-View-Projection (MVP) matrix directly into the screen framebuffer (`GL_FRAMEBUFFER 0`).
5. **Dynamic Aspect Ratio & Resolution Scaling**:
   - The canvas dimensions $(W_{\text{canvas}}, H_{\text{canvas}})$ dynamically adapt to the aspect ratio of imported source images:
     $$W_{\text{canvas}} = W_{\text{src}} \times \text{scale}, \quad H_{\text{canvas}} = H_{\text{src}} \times \text{scale}$$
   - When an image is imported, the user selects a resolution scale from $1\times$ to $8\times$.
   - FBOs (`baseFbo`, `scratchFbo`, `compositeFbo`, `activeStrokeFbo`) are cleanly reallocated to $(W_{\text{canvas}}, H_{\text{canvas}})$ via `SmartDrawRenderer.resizeCanvas()`.
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

## 7. Reference Color Auto-Picker (`ReferenceColorSampler.kt`)

Enables guided painting where the brush automatically matches colors from an underlying photograph or artwork:

1. **Coordinate Mapping**:
   - Maps canvas coordinates $(x_c, y_c)$ to reference image coordinates $(x_r, y_r)$ considering aspect ratio scaling and letterboxing.
2. **Sampling Logic**:
   - Bilinear interpolation or $3 \times 3$ box kernel averaging over reference pixels to eliminate high-frequency camera noise.
3. **Dynamic Stroke Modulation**:
   - As the Bezier curve advances, the auto-picker updates `u_brushColor` at each stamp step, producing gradient-matched transitions that follow the contours of the reference image.

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
