# MYOS — Ocean Wave Drawer

The design intent is **an ocean that becomes an app drawer**, not a conventional drawer placed over a wallpaper.

## Home

- Static ivory background: #FAF8F3.
- The original `app/src/main/assets/myos_ocean.jpg` is center-cropped and revealed in the lower 42% of the screen.
- The upper edge fades into ivory over approximately 48dp.
- The only hint is “Swipe up to open”, centered 20dp above the photo edge.
- No clock, widgets, panels, particles, or idle animation.

## Wave interaction

- A single custom launcher View owns rendering and touch interaction.
- Progress `p` ranges from 0 to 1 and is driven directly by vertical drag distance divided by 70% of view height.
- Waterline: `H - p * H * 1.08`.
- Shoreline amplitude: `28dp * sin(PI * p)`.
- The shoreline uses a reusable cubic Bézier Path with uneven hand-authored points.
- The original photo is revealed through the water shape with a restrained turquoise tint.
- Two white foam strokes are drawn along the same edge.

## Floating applications

- Four columns, 56dp icons, 11sp white labels.
- Alternate columns are offset vertically by 10dp.
- Soft, faint ellipse shadows suggest that icons float above the water.
- Icon entrance is derived from the waterline crossing each row, not an independent timer.
- App discovery and launch intents remain dynamic through PackageManager.
- App hit regions are preallocated and updated with the rendered icon positions.

## Motion and gestures

- Open settle: 520ms, PathInterpolator(0.22, 1, 0.36, 1).
- Close settle: 420ms, PathInterpolator(0.4, 0, 0.2, 1).
- Release settles open when progress exceeds 0.35 or upward fling velocity exceeds 800dp/s; otherwise it settles closed.
- Vertical drags scroll the app list. A downward drag closes only after the list is at its top.
- Android Back closes the drawer whenever progress is above zero.
- No bounce, blur, particles, or perpetual movement.

## Engineering constraints

- minSdk 26; no additional dependencies.
- Reuse drawing objects and avoid per-frame object creation in `onDraw`.
- Use a hardware layer during settle animations only.
- Preserve application ID and namespace `com.myos.home`.
- Preserve the permanent release signing configuration and repository secrets.
- Preserve the original ocean asset and dynamic app discovery.
- Do not merge this design branch into `main` until the release build and real-device interaction have been reviewed.
