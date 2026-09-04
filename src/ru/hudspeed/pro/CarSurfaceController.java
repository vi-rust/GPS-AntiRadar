package ru.gpsantiradar.app;

import android.app.Presentation;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Surface;

import androidx.car.app.AppManager;
import androidx.car.app.CarContext;
import androidx.car.app.SurfaceCallback;
import androidx.car.app.SurfaceContainer;

public final class CarSurfaceController implements SurfaceCallback {
    interface SurfaceFactory {
        SurfaceResource create(CarSurfaceSpec spec, Surface surface);
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
    private SurfaceResource surfaceResource;
    private Surface activeSurface;
    private CarSurfaceSpec activeSpec;
    private DrivingSnapshot latestSnapshot = DrivingSnapshot.idle();
    private Rect stableArea;
    private Rect visibleArea;
    private boolean panMode;
    private boolean destroyed;

    public CarSurfaceController(CarContext carContext,
            GpsAntiRadarApplication application) {
        this(carContext, application,
                new AndroidSurfaceFactory(carContext, application));
    }

    CarSurfaceController(CarContext carContext,
            GpsAntiRadarApplication application, SurfaceFactory surfaceFactory) {
        if (carContext == null) throw new IllegalArgumentException("carContext is required");
        if (surfaceFactory == null) {
            throw new IllegalArgumentException("surfaceFactory is required");
        }
        this.surfaceFactory = surfaceFactory;
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
        if (destroyed || !spec.isUsable()) return;
        SurfaceResource created = surfaceFactory.create(spec, surface);
        if (created == null) return;
        surfaceResource = created;
        activeSurface = surface;
        activeSpec = spec;
        created.onCarConfigurationChanged();
        if (stableArea != null) created.onStableAreaChanged(new Rect(stableArea));
        if (visibleArea != null) created.onVisibleAreaChanged(new Rect(visibleArea));
        created.setPanMode(panMode);
        created.onDrivingSnapshot(latestSnapshot);
    }

    @Override public synchronized void onSurfaceDestroyed(
            SurfaceContainer surfaceContainer) {
        if (surfaceContainer == null) return;
        CarSurfaceSpec destroyedSpec = CarSurfaceSpec.from(
                surfaceContainer.getWidth(),
                surfaceContainer.getHeight(),
                surfaceContainer.getDpi());
        if (surfaceContainer.getSurface() == activeSurface
                && destroyedSpec.equals(activeSpec)) {
            releaseSurface();
        }
    }

    @Override public synchronized void onStableAreaChanged(Rect area) {
        stableArea = area == null ? null : new Rect(area);
        if (surfaceResource != null && stableArea != null) {
            surfaceResource.onStableAreaChanged(new Rect(stableArea));
        }
    }

    @Override public synchronized void onVisibleAreaChanged(Rect area) {
        visibleArea = area == null ? null : new Rect(area);
        if (surfaceResource != null && visibleArea != null) {
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
        surfaceResource = null;
        activeSurface = null;
        activeSpec = null;
        if (existing != null) existing.release();
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
            virtualDisplay = displayManager.createVirtualDisplay(
                    "gps-antiradar-car-map",
                    spec.width, spec.height, spec.dpi, surface, 0);
            if (virtualDisplay == null || virtualDisplay.getDisplay() == null) {
                if (virtualDisplay != null) virtualDisplay.release();
                virtualDisplay = null;
                throw new IllegalStateException("Unable to create car map display");
            }
            presentation = new Presentation(carContext, virtualDisplay.getDisplay());
            try {
                content = new CarMapPresentation(
                        presentation.getContext(), application, carContext);
                presentation.setContentView(content.rootView());
                presentation.show();
            } catch (RuntimeException | Error error) {
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
                content.destroy();
                content = null;
            }
            if (presentation != null) {
                presentation.dismiss();
                presentation = null;
            }
            if (virtualDisplay != null) {
                virtualDisplay.release();
                virtualDisplay = null;
            }
        }
    }
}
