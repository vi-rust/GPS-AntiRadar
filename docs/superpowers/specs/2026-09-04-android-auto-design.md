# GPS-AntiRadar Android Auto Design

## Status

Approved in chat on 2026-09-04. This design targets a manually installed build and is not intended for Google Play review.

## Goals

1. Hide the phone navigation and status bars while the main activity is in the foreground.
2. Make GPS-AntiRadar discoverable and launchable from Android Auto.
3. Reproduce the phone's primary driving view on the car display: Yandex map, database object icons and clusters, current position, speed HUD, nearest object, distance, limit, and alert state.
4. Expose all existing menu functions on the car display using Android Auto-compatible screens and controls.
5. Reuse the current tracking, warning, database, and preference behavior without duplicate announcements or divergent settings.
6. Release and install version 4.9.5 (version code 40), then verify the phone and Android Auto experiences.

## Non-goals

- Google Play approval or compliance review.
- Turn-by-turn route calculation or destination guidance.
- Android Automotive OS packaging.
- Pixel-identical Android Auto system chrome. The host owns its navigation rail and overlays.
- Mirroring the phone activity. Android Auto receives a dedicated car app service and surface.

## Platform Decisions

- Use stable Android for Cars App Library 1.7.0: `androidx.car.app:app:1.7.0` and `androidx.car.app:app-projected:1.7.0`.
- Declare the templated Android Auto capability and a `CarAppService` in the navigation category.
- Use `NavigationTemplate` without active turn-by-turn navigation. It provides a full-screen map surface and host-managed action strips.
- Declare `androidx.car.app.ACCESS_SURFACE` and `androidx.car.app.NAVIGATION_TEMPLATES`.
- Accept all Android Auto hosts because this is a sideload-only build. This is an explicit distribution trade-off and must be isolated in `GpsCarAppService.createHostValidator()`.
- Use `VirtualDisplay` and `Presentation` to render Android Views onto the host-provided surface. This is the documented Android Auto mechanism and allows Yandex `MapView` plus the speed HUD to share one rendered hierarchy.

## Phone Immersive Mode

`MainActivity` owns a small idempotent `applyImmersiveMode()` method.

- API 30 and newer: hide `WindowInsets.Type.systemBars()` through `WindowInsetsController`, set `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, and keep edge-to-edge layout enabled.
- API 26 through 29: use fullscreen, hide-navigation, layout-stable, layout-hide-navigation, layout-fullscreen, and immersive-sticky system UI flags.
- Reapply the mode from `onCreate`, `onResume`, and `onWindowFocusChanged(true)` so bars do not remain visible after dialogs, permission screens, or task switching.
- Overlay padding remains stable and accounts for display cutouts, not the visibility of transient system bars. Showing bars by edge swipe must not shift the map or HUD.
- Android Auto's own navigation rail is not hidden; it belongs to the host.

## Car Components

### GpsCarAppService

- Exported `CarAppService` discovered by Android Auto.
- Returns an allow-all host validator for sideload operation.
- Creates one `GpsCarSession` per host session.

### GpsCarSession

- Owns the car screen stack and car-scoped lifecycle resources.
- Checks location permission and the MapKit key when created.
- Shows a setup message when the phone-side prerequisite is missing.
- Starts or reuses `TrackingService`; it never creates a second tracking engine.
- Releases receivers and surface resources when destroyed.

### CarMapScreen

- Returns a `NavigationTemplate` with an app action strip containing Menu.
- Adds map actions for zoom in, zoom out, and recenter.
- Registers a `SurfaceCallback` with `AppManager`.
- Invalidates only host template state; frequent speed and marker updates are rendered inside the presentation and do not rebuild the screen stack.

### CarMapPresentation

- Creates a `VirtualDisplay` for each available Android Auto surface and displays a `Presentation` on it.
- Contains a Yandex `MapView` and a programmatic HUD equivalent to the phone layout.
- Positions the HUD and critical controls within `onStableAreaChanged`; uses `onVisibleAreaChanged` for temporary host occlusion.
- Supports light and dark host modes without changing the database icon meanings.
- Dismisses the presentation and releases the virtual display exactly once when a surface is replaced or destroyed.

### SharedCameraMapLayer

Extract the reusable camera layer behavior from `MainActivity` behind a class that accepts a Yandex `MapWindow`, Android context, and camera database.

- Resolves the existing `cam_type_*` resources and cluster icons.
- Queries only the displayed geographic bounds plus the existing 20% buffer.
- Applies `CameraMarkerDiff` and `MapMarkerEntityDiff` incrementally so retained markers are not recreated.
- Draws the current-position marker and camera coverage objects.
- Supports zoom, recentering, follow pause, and resumption.
- Keeps separate layer instances for phone and car; the two views share behavior and resources, not mutable Yandex map objects.

## Driving State

Introduce an immutable `DrivingSnapshot` containing:

- speed;
- current location;
- nearest object name and identifier when available;
- distance;
- speed limit;
- activation distance;
- alert state and algorithm summary.

`TrackingService` remains the only owner of GPS scans, camera selection, state transitions, sound playback, and beep repetition. It publishes one package-scoped update intent. Both `MainActivity` and `GpsCarSession` parse that intent into `DrivingSnapshot` and update their own views. Opening both screens therefore cannot duplicate a warning.

The car HUD uses the same wording and color rules as the phone HUD. Transparency comes from the shared preference and updates immediately.

## Android Auto Menu

The Menu action opens `CarMenuScreen`, backed by the same `SharedPreferences` keys as the phone menu.

- **Update database:** starts the shared updater and reports start/current/success/error through `CarToast`.
- **Alert distance:** a value screen with decrement and increment actions, 300 to 2000 metres in 100-metre steps.
- **Beep overspeed threshold:** a value screen with decrement and increment actions, 0 to 20 km/h in 1 km/h steps.
- **HUD transparency:** a value screen with decrement and increment actions, 0% to 80% in 5% steps.
- **MapKit key:** opens a host text-input screen when input is allowed. If the host blocks input while driving, it instructs the user to change the key on the phone. The stored key and map restart behavior are shared with `MainActivity`.
- **About:** shows app version, object count, last successful database download, current-version notes, and all known `ReleaseHistory` entries.
- **Exit:** stops `TrackingService`, closes the car screen stack, and finishes the car app session.

Android Auto has no seek-bar template, so car settings use step controls while preserving the exact phone ranges and stored values.

## Database Update Ownership

Move RadarBase networking/import from `MainActivity` into an application-scoped `RadarBaseUpdater`.

- `GpsAntiRadarApplication` starts one automatic update when the process is created, independent of whether the first entry point is the phone activity or Android Auto.
- Manual phone and car requests call the same updater.
- The existing process-wide single-flight guard prevents overlapping downloads/imports.
- The updater exposes start, unchanged, success, and error states to registered phone/car presenters.
- HTTP 304 does not change the last-successful timestamp.
- A successful HTTP 200 download and completed import stores the timestamp before emitting success.
- Phone presenters use `Toast`; car presenters use `CarToast`.
- Database replacement emits the existing package-scoped update so both map layers refresh incrementally.

## Lifecycle And Failure Handling

- Repeated `onSurfaceAvailable` first releases the previous presentation and virtual display, then creates replacements using the new width, height, and DPI.
- Surface destruction is idempotent and unregisters Yandex callbacks before releasing display resources.
- Car session recreation does not restart tracking or database import.
- A missing MapKit key, failed MapKit initialization, or failed surface creation produces a car message screen. Tracking and audio continue when location permission is available.
- A missing location permission directs the user to the phone; Android Auto does not attempt to bypass the system permission flow.
- Database and marker queries remain off the main thread. Results carry generation identifiers so stale viewport results cannot replace newer ones.
- Closing Android Auto does not stop tracking. Only the explicit Exit action, phone exit action, or removal through Recent Apps stops it.

## Manifest And Resources

- Add the Android Auto template descriptor under `res/xml/automotive_app_desc.xml`.
- Add the Car App API level metadata required by library 1.7.0.
- Declare `GpsCarAppService`, surface/template permissions, and navigation category.
- Reuse existing camera and command icons; add monochrome car action icons only where Android Auto requires host tinting.
- Keep phone orientation landscape. The car presentation derives all dimensions from the supplied surface and stable area.

## Testing

Development follows test-first red/green cycles.

### Automated

- Pure JVM tests for `DrivingSnapshot`, setting bounds/steps, menu state, and surface lifecycle state transitions.
- Existing parser, alert algorithm, tracker, viewport diff, and release-history tests remain green.
- Source/manifest contract tests verify Android Auto metadata, service category, surface permissions, stable Car App Library version, immersive-mode reapplication, and version 4.9.5 (40).
- Car App Library test helpers verify root template creation, menu navigation, actions, and shared setting changes where supported by the local toolchain.
- `git diff --check`, release compilation, lint vital, and APK signing validation must pass.

### Device And DHU

- Install the release APK on the connected phone without clearing its data.
- Verify the phone navigation/status bars are hidden after cold launch, after closing a dialog, and after returning from Recent Apps; an edge swipe temporarily reveals them.
- Enable Android Auto developer mode and unknown sources on the phone.
- Run Android Auto Desktop Head Unit and verify app discovery, cold launch, map tiles, existing camera icons/clusters, HUD updates, zoom, recenter, all menu actions, About, database status, and Exit.
- Disconnect/reconnect DHU and rotate or resize its surface to exercise surface replacement.
- Check the application PID log for fatal exceptions after phone and DHU scenarios.

## Release

- Add a non-empty known-release entry for 4.9.5 describing immersive mode and Android Auto support.
- Set `versionName` to `4.9.5` and `versionCode` to `40`.
- Build `outputs/GPS-AntiRadar.apk`, report its SHA-256 and size, install it on the connected phone, and confirm installed package metadata.

## Accepted Limitations

- This sideload build may stop appearing if a future Android Auto release removes developer-mode unknown-source support.
- The host can cover parts of the surface with its own UI and can restrict text entry while driving.
- The Android Auto experience is visually equivalent to the phone's driving view but cannot remove or restyle Android Auto system chrome.
- The app declares a navigation category to obtain a map surface but intentionally does not provide route guidance; this is unsuitable for Google Play review.
