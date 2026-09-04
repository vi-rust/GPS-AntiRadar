package ru.gpsantiradar.app;

import android.app.Presentation;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.util.Log;
import android.view.Surface;

import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class CarSurfaceController implements SurfaceCallback {
    private static final String TAG = "CarSurfaceController";
    private static final String SURFACE_ERROR =
            "Не удалось отобразить карту. Переподключите Android Auto.";

    interface SurfaceFactory {
        SurfaceResource create(CarSurfaceSpec spec, Surface surface);
    }

    interface SurfaceReleaser {
        void release(Surface surface);
    }

    interface FailureListener {
        void onSurfaceFailure(String message);
    }

    interface SurfaceResource {
        void onDrivingSnapshot(DrivingSnapshot snapshot);
        void zoomBy(float delta);
        void recenter();
        void setPanMode(boolean enabled);
        void onStableAreaChanged(Rect area);
        void onVisibleAreaChanged(Rect area);
        void onCarConfigurationChanged();
        void onScroll(float distanceX, float distanceY);
        void onFling(float velocityX, float velocityY);
        void onScale(float focusX, float focusY, float scaleFactor);
        void onClick(float x, float y);
        void release();
    }

    private final AppManager appManager;
    private final SurfaceFactory surfaceFactory;
    private final SurfaceReleaser surfaceReleaser;
    private final FailureListener failureListener;
    private final Set<Surface> releasedSurfaces =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<Surface, Long> surfaceTokens = new IdentityHashMap<>();
    private SurfaceResource surfaceResource;
    private SurfaceLease activeLease;
    private long nextSurfaceToken;
    private DrivingSnapshot latestSnapshot = DrivingSnapshot.idle();
    private Rect stableArea;
    private Rect visibleArea;
    private boolean panMode;
    private boolean destroyed;

    public CarSurfaceController(CarContext carContext,
            GpsAntiRadarApplication application) {
        this(carContext, application,
                new AndroidSurfaceFactory(carContext, application),
                Surface::release, message -> {});
    }

    public CarSurfaceController(CarContext carContext,
            GpsAntiRadarApplication application, FailureListener failureListener) {
        this(carContext, application,
                new AndroidSurfaceFactory(carContext, application),
                Surface::release, failureListener);
    }

    CarSurfaceController(CarContext carContext,
            GpsAntiRadarApplication application, SurfaceFactory surfaceFactory) {
        this(carContext, application, surfaceFactory,
                Surface::release, message -> {});
    }

    CarSurfaceController(CarContext carContext,
            GpsAntiRadarApplication application, SurfaceFactory surfaceFactory,
            SurfaceReleaser surfaceReleaser, FailureListener failureListener) {
        if (carContext == null) throw new IllegalArgumentException("carContext is required");
        if (surfaceFactory == null) {
            throw new IllegalArgumentException("surfaceFactory is required");
        }
        if (surfaceReleaser == null) {
            throw new IllegalArgumentException("surfaceReleaser is required");
        }
        if (failureListener == null) {
            throw new IllegalArgumentException("failureListener is required");
        }
        this.surfaceFactory = surfaceFactory;
        this.surfaceReleaser = surfaceReleaser;
        this.failureListener = failureListener;
        appManager = carContext.getCarService(AppManager.class);
        appManager.setSurfaceCallback(this);
    }

    @Override public synchronized void onSurfaceAvailable(
            SurfaceContainer surfaceContainer) {
        if (surfaceContainer == null) return;
        CarSurfaceSpec spec = CarSurfaceSpec.from(
                surfaceContainer.getWidth(),
                surfaceContainer.getHeight(),
                surfaceContainer.getDpi());
        Surface surface = surfaceContainer.getSurface();
        releaseSurface();
        SurfaceLease incoming = newSurfaceLease(surface, spec);
        if (destroyed || !spec.isUsable()) {
            releaseSurfaceObject(surface);
            return;
        }
        try {
            SurfaceResource created = surfaceFactory.create(spec, surface);
            if (created == null) {
                throw new IllegalStateException("Surface factory returned no resource");
            }
            surfaceResource = created;
            activeLease = incoming;
            created.onCarConfigurationChanged();
            if (stableArea != null) created.onStableAreaChanged(new Rect(stableArea));
            if (visibleArea != null) created.onVisibleAreaChanged(new Rect(visibleArea));
            created.setPanMode(panMode);
            created.onDrivingSnapshot(latestSnapshot);
        } catch (RuntimeException | LinkageError error) {
            if (activeLease == incoming) {
                releaseSurface();
            } else {
                releaseSurfaceObject(surface);
            }
            notifySurfaceFailure(error);
        }
    }

    @Override public synchronized void onSurfaceDestroyed(
            SurfaceContainer surfaceContainer) {
        if (surfaceContainer == null) return;
        CarSurfaceSpec destroyedSpec = CarSurfaceSpec.from(
                surfaceContainer.getWidth(),
                surfaceContainer.getHeight(),
                surfaceContainer.getDpi());
        Surface surface = surfaceContainer.getSurface();
        Long token = surface == null ? null : surfaceTokens.get(surface);
        if (activeLease != null
                && activeLease.matches(surface, destroyedSpec, token)) {
            releaseSurface();
        } else {
            releaseSurfaceObject(surface);
        }
    }

    @Override public synchronized void onStableAreaChanged(Rect area) {
        if (area == null || area.isEmpty()) return;
        stableArea = new Rect(area);
        if (surfaceResource != null) {
            surfaceResource.onStableAreaChanged(new Rect(stableArea));
        }
    }

    @Override public synchronized void onVisibleAreaChanged(Rect area) {
        if (area == null || area.isEmpty()) return;
        visibleArea = new Rect(area);
        if (surfaceResource != null) {
            surfaceResource.onVisibleAreaChanged(new Rect(visibleArea));
        }
    }

    @Override public synchronized void onScroll(float distanceX, float distanceY) {
        if (surfaceResource != null) {
            surfaceResource.onScroll(distanceX, distanceY);
        }
    }

    @Override public synchronized void onFling(float velocityX, float velocityY) {
        if (surfaceResource != null) {
            surfaceResource.onFling(velocityX, velocityY);
        }
    }

    @Override public synchronized void onScale(
            float focusX, float focusY, float scaleFactor) {
        if (surfaceResource != null) {
            surfaceResource.onScale(focusX, focusY, scaleFactor);
        }
    }

    @Override public synchronized void onClick(float x, float y) {
        if (surfaceResource != null) surfaceResource.onClick(x, y);
    }

    public synchronized void onDrivingSnapshot(DrivingSnapshot snapshot) {
        if (destroyed || snapshot == null) return;
        latestSnapshot = snapshot;
        if (surfaceResource != null) {
            surfaceResource.onDrivingSnapshot(snapshot);
        }
    }

    public synchronized void zoomBy(float delta) {
        if (!destroyed && surfaceResource != null) {
            surfaceResource.zoomBy(delta);
        }
    }

    public synchronized void recenter() {
        if (!destroyed && surfaceResource != null) {
            surfaceResource.recenter();
        }
    }

    public synchronized void setPanMode(boolean enabled) {
        if (destroyed) return;
        panMode = enabled;
        if (surfaceResource != null) {
            surfaceResource.setPanMode(enabled);
        }
    }

    public synchronized void onCarConfigurationChanged() {
        if (!destroyed && surfaceResource != null) {
            surfaceResource.onCarConfigurationChanged();
        }
    }

    public synchronized void refreshHudTransparency() {
        if (!destroyed && surfaceResource instanceof AndroidSurfaceResource) {
            ((AndroidSurfaceResource) surfaceResource).refreshHudTransparency();
        }
    }

    public synchronized void destroy() {
        if (destroyed) return;
        destroyed = true;
        appManager.setSurfaceCallback(null);
        releaseSurface();
    }

    private void releaseSurface() {
        SurfaceResource existing = surfaceResource;
        SurfaceLease lease = activeLease;
        surfaceResource = null;
        activeLease = null;
        try {
            if (existing != null) existing.release();
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "Failed to release car surface content", error);
        } finally {
            if (lease != null) releaseSurfaceObject(lease.surface);
        }
    }

    private SurfaceLease newSurfaceLease(Surface surface, CarSurfaceSpec spec) {
        long token = ++nextSurfaceToken;
        if (surface != null) surfaceTokens.put(surface, token);
        return new SurfaceLease(token, surface, spec);
    }

    private void releaseSurfaceObject(Surface surface) {
        if (surface == null || !releasedSurfaces.add(surface)) return;
        surfaceTokens.remove(surface);
        try {
            surfaceReleaser.release(surface);
        } catch (RuntimeException | LinkageError error) {
            Log.e(TAG, "Failed to release host surface", error);
        }
    }

    private void notifySurfaceFailure(Throwable error) {
        Log.e(TAG, "Unable to create car map surface", error);
        try {
            failureListener.onSurfaceFailure(SURFACE_ERROR);
        } catch (RuntimeException callbackError) {
            Log.e(TAG, "Unable to report car map surface failure", callbackError);
        }
    }

    private static final class SurfaceLease {
        final long token;
        final Surface surface;
        final CarSurfaceSpec spec;

        SurfaceLease(long token, Surface surface, CarSurfaceSpec spec) {
            this.token = token;
            this.surface = surface;
            this.spec = spec;
        }

        boolean matches(Surface candidate, CarSurfaceSpec candidateSpec,
                Long candidateToken) {
            boolean sameGeneration = surface == null
                    ? candidate == null
                    : candidateToken != null && candidateToken == token;
            return sameGeneration
                    && Objects.equals(surface, candidate)
                    && spec.equals(candidateSpec);
        }
    }

    private static final class AndroidSurfaceFactory implements SurfaceFactory {
        private final CarContext carContext;
        private final GpsAntiRadarApplication application;

        AndroidSurfaceFactory(CarContext carContext,
                GpsAntiRadarApplication application) {
            if (application == null) {
                throw new IllegalArgumentException("application is required");
            }
            this.carContext = carContext;
            this.application = application;
        }

        @Override public SurfaceResource create(
                CarSurfaceSpec spec, Surface surface) {
            return new AndroidSurfaceResource(
                    carContext, application, spec, surface);
        }
    }

    private static final class AndroidSurfaceResource implements SurfaceResource {
        private VirtualDisplay virtualDisplay;
        private Presentation presentation;
        private CarMapPresentation content;
        private boolean released;

        AndroidSurfaceResource(CarContext carContext,
                GpsAntiRadarApplication application,
                CarSurfaceSpec spec, Surface surface) {
            DisplayManager displayManager =
                    carContext.getSystemService(DisplayManager.class);
            if (displayManager == null) {
                throw new IllegalStateException("DisplayManager is unavailable");
            }
            try {
                virtualDisplay = displayManager.createVirtualDisplay(
                        "gps-antiradar-car-map",
                        spec.width, spec.height, spec.dpi, surface, 0);
                if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                    throw new IllegalStateException("Unable to create car map display");
                }
                presentation = new Presentation(
                        carContext, virtualDisplay.getDisplay());
                content = new CarMapPresentation(
                        presentation.getContext(), application, carContext);
                content.start();
                presentation.setContentView(content.rootView());
                presentation.show();
            } catch (RuntimeException | LinkageError error) {
                release();
                throw error;
            }
        }

        @Override public void onDrivingSnapshot(DrivingSnapshot snapshot) {
            if (!released) content.onDrivingSnapshot(snapshot);
        }

        @Override public void zoomBy(float delta) {
            if (!released) content.zoomBy(delta);
        }

        @Override public void recenter() {
            if (!released) content.recenter();
        }

        @Override public void setPanMode(boolean enabled) {
            if (!released) content.setPanMode(enabled);
        }

        @Override public void onStableAreaChanged(Rect area) {
            if (!released) content.onStableAreaChanged(area);
        }

        @Override public void onVisibleAreaChanged(Rect area) {
            if (!released) content.onVisibleAreaChanged(area);
        }

        @Override public void onCarConfigurationChanged() {
            if (!released) content.onCarConfigurationChanged();
        }

        @Override public void onScroll(float distanceX, float distanceY) {
            if (!released) content.onScroll(distanceX, distanceY);
        }

        @Override public void onFling(float velocityX, float velocityY) {
            if (!released) content.onFling(velocityX, velocityY);
        }

        @Override public void onScale(
                float focusX, float focusY, float scaleFactor) {
            if (!released) content.onScale(focusX, focusY, scaleFactor);
        }

        @Override public void onClick(float x, float y) {
            if (!released) content.onClick(x, y);
        }

        void refreshHudTransparency() {
            if (!released) content.refreshHudTransparency();
        }

        @Override public void release() {
            if (released) return;
            released = true;
            if (content != null) {
                try {
                    content.destroy();
                } catch (RuntimeException | LinkageError error) {
                    Log.e(TAG, "Failed to destroy car map content", error);
                }
                content = null;
            }
            if (presentation != null) {
                try {
                    presentation.dismiss();
                } catch (RuntimeException | LinkageError error) {
                    Log.e(TAG, "Failed to dismiss car presentation", error);
                }
                presentation = null;
            }
            if (virtualDisplay != null) {
                try {
                    virtualDisplay.release();
                } catch (RuntimeException | LinkageError error) {
                    Log.e(TAG, "Failed to release car virtual display", error);
                }
                virtualDisplay = null;
            }
        }
    }
}
