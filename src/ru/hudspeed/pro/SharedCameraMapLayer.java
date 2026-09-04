package ru.gpsantiradar.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

import com.yandex.mapkit.Animation;
import com.yandex.mapkit.geometry.LinearRing;
import com.yandex.mapkit.geometry.Point;
import com.yandex.mapkit.geometry.Polygon;
import com.yandex.mapkit.map.CameraListener;
import com.yandex.mapkit.map.CameraPosition;
import com.yandex.mapkit.map.CameraUpdateReason;
import com.yandex.mapkit.map.IconStyle;
import com.yandex.mapkit.map.MapObject;
import com.yandex.mapkit.map.MapObjectCollection;
import com.yandex.mapkit.map.MapObjectTapListener;
import com.yandex.mapkit.map.MapWindow;
import com.yandex.mapkit.map.PlacemarkMapObject;
import com.yandex.mapkit.map.PolygonMapObject;
import com.yandex.mapkit.map.RotationType;
import com.yandex.mapkit.map.VisibleRegion;
import com.yandex.runtime.image.ImageProvider;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Camera, coverage and current-location objects owned by one MapWindow. */
public final class SharedCameraMapLayer {
    public interface Host {
        void postToUi(Runnable action);
        void onCameraTapped(CameraPoint camera, Point position);
    }

    private static final int OBSERVATION_MARKER = -1;
    private static final int MAX_VISIBLE_MARKERS = 5000;
    private static final float COVERAGE_MIN_ZOOM = 13f;
    private static final long FOLLOW_PAUSE_MS = 7000L;
    private static final int GREEN = Color.rgb(0, 166, 82);

    private Context context;
    private MapWindow mapWindow;
    private Host host;
    private com.yandex.mapkit.map.Map map;
    private MapObjectCollection cameraMarkerCollection;
    private MapObjectCollection cameraCoverageCollection;
    private MapObjectCollection locationCollection;
    private PlacemarkMapObject locationPlacemark;
    private boolean cameraCoverageVisible;
    private boolean mapCenteredOnGps;
    private long followPausedUntil;
    private double lastLatitude = Double.NaN;
    private double lastLongitude = Double.NaN;
    private volatile int initialLoadGeneration;
    private volatile int cameraLoadGeneration;
    private volatile boolean destroyed;

    private final Map<Integer, ImageProvider> markerIcons = new HashMap<>();
    private final Map<Integer, Integer> markerResources = new HashMap<>();
    private final Map<Integer, ImageProvider> clusterIcons = new HashMap<>();
    private final Map<Long, CameraPoint> renderedCameras = new HashMap<>();
    private final Map<Long, List<PolygonMapObject>> renderedCameraCoverage = new HashMap<>();
    private final Map<String, PlacemarkMapObject> renderedMarkerObjects = new HashMap<>();
    private final Map<String, MapMarkerLayout.Entity> renderedMarkerEntities = new HashMap<>();

    private final MapObjectTapListener placemarkTapListener = new MapObjectTapListener() {
        @Override public boolean onMapObjectTap(MapObject mapObject, Point point) {
            Object data = mapObject.getUserData();
            Host activeHost = host;
            if (!destroyed && activeHost != null
                    && data instanceof CameraPoint && mapObject instanceof PlacemarkMapObject) {
                activeHost.onCameraTapped((CameraPoint) data, point);
                return true;
            }
            return false;
        }
    };
    private final WeakReference<MapObjectTapListener> placemarkTapListenerReference =
            new WeakReference<>(placemarkTapListener);

    private final CameraListener cameraListener = new CameraListener() {
        @Override public void onCameraPositionChanged(com.yandex.mapkit.map.Map changedMap,
                                                       CameraPosition cameraPosition,
                                                       CameraUpdateReason reason,
                                                       boolean finished) {
            if (reason == CameraUpdateReason.GESTURES) pauseFollowing();
            if (finished) refreshVisible();
        }
    };
    private final WeakReference<CameraListener> cameraListenerReference =
            new WeakReference<>(cameraListener);

    public SharedCameraMapLayer(Context context, MapWindow mapWindow, Host host) {
        if (context == null) throw new IllegalArgumentException("context is required");
        if (mapWindow == null) throw new IllegalArgumentException("mapWindow is required");
        if (host == null) throw new IllegalArgumentException("host is required");
        this.context = context;
        this.mapWindow = mapWindow;
        this.host = host;
        map = mapWindow.getMap();
        cameraCoverageCollection = map.getMapObjects().addCollection();
        cameraMarkerCollection = map.getMapObjects().addCollection();
        locationCollection = map.getMapObjects().addCollection();
        map.addCameraListener(cameraListenerReference);
    }

    public void loadInitial(final boolean moveToData) {
        if (destroyed) return;
        final Context queryContext = context;
        if (queryContext == null) return;
        final int generation = ++initialLoadGeneration;
        new Thread(new Runnable() {
            @Override public void run() {
                final double[] bounds;
                try (CameraDatabase db = new CameraDatabase(queryContext)) {
                    bounds = db.bounds();
                }
                if (generation != initialLoadGeneration || destroyed) return;
                Host activeHost = host;
                if (activeHost == null) return;
                activeHost.postToUi(new Runnable() {
                    @Override public void run() {
                        if (generation != initialLoadGeneration || destroyed) return;
                        com.yandex.mapkit.map.Map activeMap = map;
                        if (activeMap == null) return;
                        if (moveToData && bounds != null) {
                            double latitude = (bounds[0] + bounds[1]) / 2.0;
                            double longitude = (bounds[2] + bounds[3]) / 2.0;
                            activeMap.move(new CameraPosition(
                                    new Point(latitude, longitude), 7.5f, 0f, 0f));
                        }
                        refreshVisible();
                    }
                });
            }
        }, "map-camera-load").start();
    }

    public void refreshVisible() {
        com.yandex.mapkit.map.Map activeMap = map;
        final Context queryContext = context;
        if (destroyed || activeMap == null || queryContext == null) return;
        final VisibleRegion region;
        try {
            region = activeMap.getVisibleRegion();
        } catch (RuntimeException error) {
            return;
        }
        double south = Math.min(Math.min(region.getTopLeft().getLatitude(),
                        region.getTopRight().getLatitude()),
                Math.min(region.getBottomLeft().getLatitude(),
                        region.getBottomRight().getLatitude()));
        double north = Math.max(Math.max(region.getTopLeft().getLatitude(),
                        region.getTopRight().getLatitude()),
                Math.max(region.getBottomLeft().getLatitude(),
                        region.getBottomRight().getLatitude()));
        double west = Math.min(Math.min(region.getTopLeft().getLongitude(),
                        region.getBottomLeft().getLongitude()),
                Math.min(region.getTopRight().getLongitude(),
                        region.getBottomRight().getLongitude()));
        double east = Math.max(Math.max(region.getTopLeft().getLongitude(),
                        region.getBottomLeft().getLongitude()),
                Math.max(region.getTopRight().getLongitude(),
                        region.getBottomRight().getLongitude()));

        double latPadding = Math.max(0.02, (north - south) * 0.20);
        double lonPadding = Math.max(0.02, (east - west) * 0.20);
        final double querySouth = Math.max(-90.0, south - latPadding);
        final double queryNorth = Math.min(90.0, north + latPadding);
        final double queryWest = Math.max(-180.0, west - lonPadding);
        final double queryEast = Math.min(180.0, east + lonPadding);
        final int generation = ++cameraLoadGeneration;

        new Thread(new Runnable() {
            @Override public void run() {
                final List<CameraPoint> points;
                try (CameraDatabase db = new CameraDatabase(queryContext)) {
                    points = db.withinBounds(querySouth, queryNorth,
                            queryWest, queryEast, MAX_VISIBLE_MARKERS);
                }
                if (generation != cameraLoadGeneration || destroyed) return;
                Host activeHost = host;
                if (activeHost == null) return;
                activeHost.postToUi(new Runnable() {
                    @Override public void run() {
                        if (generation == cameraLoadGeneration && !destroyed) {
                            renderCameraMarkers(points);
                        }
                    }
                });
            }
        }, "visible-camera-load").start();
    }

    public void updateCurrentLocation(double latitude, double longitude, float speedKmh) {
        if (destroyed) return;
        lastLatitude = latitude;
        lastLongitude = longitude;
        updateLocationMarker();
        centerOnLocationFromGps(speedKmh);
    }

    public void moveToCurrentLocation() {
        com.yandex.mapkit.map.Map activeMap = map;
        if (destroyed || activeMap == null
                || Double.isNaN(lastLatitude) || Double.isNaN(lastLongitude)) return;
        resumeFollowing();
        activeMap.move(new CameraPosition(
                        new Point(lastLatitude, lastLongitude), 15f, 0f, 0f),
                new Animation(Animation.Type.SMOOTH, 0.55f));
        mapCenteredOnGps = true;
    }

    public void zoomBy(float delta) {
        com.yandex.mapkit.map.Map activeMap = map;
        if (destroyed || activeMap == null) return;
        pauseFollowing();
        CameraPosition current = activeMap.getCameraPosition();
        float zoom = Math.max(2f, Math.min(21f, current.getZoom() + delta));
        activeMap.move(new CameraPosition(current.getTarget(), zoom,
                        current.getAzimuth(), current.getTilt()),
                new Animation(Animation.Type.SMOOTH, 0.35f));
    }

    public void pauseFollowing() {
        if (!destroyed) followPausedUntil = SystemClock.elapsedRealtime() + FOLLOW_PAUSE_MS;
    }

    public void resumeFollowing() {
        if (!destroyed) followPausedUntil = 0L;
    }

    public void destroy() {
        if (destroyed) return;
        destroyed = true;
        initialLoadGeneration++;
        cameraLoadGeneration++;
        com.yandex.mapkit.map.Map activeMap = map;
        if (activeMap != null) {
            try {
                activeMap.removeCameraListener(cameraListenerReference);
                removeOwnedCollection(cameraMarkerCollection);
                removeOwnedCollection(cameraCoverageCollection);
                removeOwnedCollection(locationCollection);
            } catch (RuntimeException ignored) {
                // The MapWindow may already have been released by its host.
            }
        }
        renderedMarkerObjects.clear();
        renderedMarkerEntities.clear();
        renderedCameraCoverage.clear();
        renderedCameras.clear();
        markerIcons.clear();
        markerResources.clear();
        clusterIcons.clear();
        locationPlacemark = null;
        cameraMarkerCollection = null;
        cameraCoverageCollection = null;
        locationCollection = null;
        map = null;
        mapWindow = null;
        host = null;
        context = null;
    }

    private void removeOwnedCollection(MapObjectCollection collection) {
        if (collection != null && collection.isValid() && collection.getParent() != null) {
            collection.getParent().remove(collection);
        }
    }

    private void renderCameraMarkers(List<CameraPoint> points) {
        com.yandex.mapkit.map.Map activeMap = map;
        if (activeMap == null) return;
        List<CameraPoint> validPoints = MapMarkerLayout.validCameras(points);
        CameraMarkerDiff.Result coverageDiff =
                CameraMarkerDiff.between(renderedCameras, validPoints);
        List<MapMarkerLayout.Entity> entities =
                MapMarkerLayout.create(validPoints, activeMap.getCameraPosition().getZoom());
        MapMarkerEntityDiff.Result markerDiff =
                MapMarkerEntityDiff.between(renderedMarkerEntities, entities);
        boolean showCoverage =
                activeMap.getCameraPosition().getZoom() >= COVERAGE_MIN_ZOOM;
        boolean coverageModeChanged = showCoverage != cameraCoverageVisible;
        boolean markerChanges = !markerDiff.removeKeys.isEmpty()
                || !markerDiff.update.isEmpty() || !markerDiff.add.isEmpty();
        if (!markerChanges && !coverageDiff.hasMarkerChanges() && !coverageModeChanged) return;

        for (String key : markerDiff.removeKeys) {
            PlacemarkMapObject marker = renderedMarkerObjects.remove(key);
            if (marker != null) cameraMarkerCollection.remove(marker);
        }
        for (MapMarkerLayout.Entity entity : markerDiff.update) {
            PlacemarkMapObject marker = renderedMarkerObjects.get(entity.key);
            MapMarkerLayout.Entity previous = renderedMarkerEntities.get(entity.key);
            if (marker != null && previous != null) {
                updateMarkerEntity(marker, previous, entity);
            }
        }
        for (MapMarkerLayout.Entity entity : markerDiff.add) {
            renderedMarkerObjects.put(entity.key, addMarkerEntity(entity));
        }
        renderedMarkerEntities.clear();
        renderedMarkerEntities.putAll(markerDiff.desired);

        for (Long id : coverageDiff.removeIds) removeCameraCoverage(id);
        for (CameraPoint camera : coverageDiff.addOrReplace) {
            if (showCoverage && !coverageModeChanged) {
                renderedCameraCoverage.put(camera.id, addCameraCoverage(camera));
            }
        }
        renderedCameras.clear();
        renderedCameras.putAll(coverageDiff.desired);

        if (coverageModeChanged) {
            cameraCoverageCollection.clear();
            renderedCameraCoverage.clear();
            if (showCoverage) {
                for (CameraPoint camera : renderedCameras.values()) {
                    renderedCameraCoverage.put(camera.id, addCameraCoverage(camera));
                }
            }
            cameraCoverageVisible = showCoverage;
        }
    }

    private PlacemarkMapObject addMarkerEntity(MapMarkerLayout.Entity entity) {
        Point point = new Point(entity.latitude, entity.longitude);
        if (entity.cluster) {
            return cameraMarkerCollection.addPlacemark(point,
                    clusterIcon(entity.memberIds.size()), clusterMarkerStyle());
        }
        CameraPoint camera = entity.camera;
        PlacemarkMapObject marker = cameraMarkerCollection.addPlacemark(point,
                iconForCamera(camera), individualMarkerStyle());
        if (camera.isCameraOrControl()) marker.setDirection(shootingBearing(camera));
        marker.setUserData(camera);
        marker.addTapListener(placemarkTapListenerReference);
        return marker;
    }

    private void updateMarkerEntity(PlacemarkMapObject marker,
                                    MapMarkerLayout.Entity previous,
                                    MapMarkerLayout.Entity current) {
        if (Double.compare(previous.latitude, current.latitude) != 0
                || Double.compare(previous.longitude, current.longitude) != 0) {
            marker.setGeometry(new Point(current.latitude, current.longitude));
        }
        if (current.cluster) {
            if (previous.memberIds.size() != current.memberIds.size()) {
                marker.setIcon(clusterIcon(current.memberIds.size()));
            }
            return;
        }

        CameraPoint oldCamera = previous.camera;
        CameraPoint newCamera = current.camera;
        if (oldCamera.type != newCamera.type) marker.setIcon(iconForCamera(newCamera));
        float oldDirection = oldCamera.isCameraOrControl() ? shootingBearing(oldCamera) : 0f;
        float newDirection = newCamera.isCameraOrControl() ? shootingBearing(newCamera) : 0f;
        if (Float.compare(oldDirection, newDirection) != 0) {
            marker.setDirection(newDirection);
        }
        marker.setUserData(newCamera);
    }

    private IconStyle individualMarkerStyle() {
        return new IconStyle()
                .setAnchor(new android.graphics.PointF(0.5f, 0.5f))
                .setRotationType(RotationType.ROTATE)
                .setFlat(true)
                .setScale(1.0f)
                .setZIndex(10f);
    }

    private IconStyle clusterMarkerStyle() {
        return new IconStyle()
                .setAnchor(new android.graphics.PointF(0.5f, 0.5f))
                .setRotationType(RotationType.NO_ROTATION)
                .setFlat(false)
                .setScale(1.0f)
                .setZIndex(20f);
    }

    private ImageProvider clusterIcon(int count) {
        ImageProvider cached = clusterIcons.get(count);
        if (cached != null) return cached;
        ImageProvider result = createClusterIcon(count);
        clusterIcons.put(count, result);
        return result;
    }

    private void removeCameraCoverage(long id) {
        List<PolygonMapObject> coverage = renderedCameraCoverage.remove(id);
        if (coverage == null) return;
        for (PolygonMapObject polygon : coverage) cameraCoverageCollection.remove(polygon);
    }

    private List<PolygonMapObject> addCameraCoverage(CameraPoint camera) {
        List<PolygonMapObject> result = new ArrayList<>();
        if (!camera.isCameraOrControl()) return result;
        int baseColor = markerColor(camera.isObservation()
                ? OBSERVATION_MARKER : camera.type);
        int fill = Color.argb(52, Color.red(baseColor), Color.green(baseColor),
                Color.blue(baseColor));
        int stroke = Color.argb(145, Color.red(baseColor), Color.green(baseColor),
                Color.blue(baseColor));
        Point origin = new Point(camera.latitude, camera.longitude);
        if (camera.dirType == 0) {
            addCoverageCircle(origin, camera.distanceMeters, fill, stroke, result);
            return result;
        }
        float halfAngle = Math.max(1f, camera.angleDegrees / 2f);
        addCoverageSector(origin, primaryCoverageBearing(camera), camera.distanceMeters,
                halfAngle, fill, stroke, result);
        if (camera.hasReverseZone()) {
            int reverseFill = Color.argb(30, Color.red(baseColor), Color.green(baseColor),
                    Color.blue(baseColor));
            addCoverageSector(origin, camera.direction, camera.reverseDistanceMeters,
                    halfAngle, reverseFill, stroke, result);
        }
        return result;
    }

    private void addCoverageCircle(Point origin, double radiusMeters, int fill, int stroke,
                                   List<PolygonMapObject> result) {
        if (radiusMeters <= 0) return;
        List<Point> boundary = new ArrayList<>();
        for (int bearing = 0; bearing <= 360; bearing += 10) {
            boundary.add(destination(origin, bearing, radiusMeters));
        }
        Polygon polygon = new Polygon(new LinearRing(boundary), Collections.emptyList());
        PolygonMapObject circle = cameraCoverageCollection.addPolygon(polygon);
        circle.setFillColor(fill);
        circle.setStrokeColor(stroke);
        circle.setStrokeWidth(1.2f);
        circle.setGeodesic(true);
        circle.setZIndex(2f);
        result.add(circle);
    }

    private void addCoverageSector(Point origin, float bearing, double rangeMeters,
                                   float halfAngle, int fill, int stroke,
                                   List<PolygonMapObject> result) {
        if (rangeMeters <= 0) return;
        List<Point> boundary = new ArrayList<>();
        boundary.add(origin);
        float step = Math.max(1.5f, halfAngle / 5f);
        for (float offset = -halfAngle; offset <= halfAngle; offset += step) {
            boundary.add(destination(origin, bearing + offset, rangeMeters));
        }
        boundary.add(destination(origin, bearing + halfAngle, rangeMeters));
        boundary.add(origin);
        Polygon polygon = new Polygon(new LinearRing(boundary), Collections.emptyList());
        PolygonMapObject sector = cameraCoverageCollection.addPolygon(polygon);
        sector.setFillColor(fill);
        sector.setStrokeColor(stroke);
        sector.setStrokeWidth(1.2f);
        sector.setGeodesic(true);
        sector.setZIndex(2f);
        result.add(sector);
    }

    private Point destination(Point start, double bearingDegrees, double distanceMeters) {
        double radius = 6371000.0;
        double angularDistance = distanceMeters / radius;
        double bearing = Math.toRadians(bearingDegrees);
        double latitude = Math.toRadians(start.getLatitude());
        double longitude = Math.toRadians(start.getLongitude());
        double destinationLatitude = Math.asin(Math.sin(latitude) * Math.cos(angularDistance)
                + Math.cos(latitude) * Math.sin(angularDistance) * Math.cos(bearing));
        double destinationLongitude = longitude + Math.atan2(
                Math.sin(bearing) * Math.sin(angularDistance) * Math.cos(latitude),
                Math.cos(angularDistance) - Math.sin(latitude) * Math.sin(destinationLatitude));
        return new Point(Math.toDegrees(destinationLatitude), Math.toDegrees(destinationLongitude));
    }

    private float shootingBearing(CameraPoint camera) {
        float result = camera.direction;
        if (camera.dirType == 1 || camera.dirType == 2 || camera.dirType == 4) {
            result += 180f;
        }
        result %= 360f;
        return result < 0f ? result + 360f : result;
    }

    private float primaryCoverageBearing(CameraPoint camera) {
        float result = (camera.direction + 180f) % 360f;
        return result < 0f ? result + 360f : result;
    }

    private ImageProvider iconForCamera(CameraPoint camera) {
        int resourceId = cameraIconResource(camera.type);
        ImageProvider cached = markerIcons.get(resourceId);
        if (cached != null) return cached;
        ImageProvider result = ImageProvider.fromBitmap(createCameraBitmap(resourceId));
        markerIcons.put(resourceId, result);
        return result;
    }

    private int cameraIconResource(int type) {
        Integer cached = markerResources.get(type);
        if (cached != null) return cached;
        int resourceId = context.getResources().getIdentifier(
                "cam_type_" + type, "drawable", context.getPackageName());
        if (resourceId == 0) resourceId = R.drawable.cam_type_0;
        markerResources.put(type, resourceId);
        return resourceId;
    }

    private Bitmap createCameraBitmap(int resourceId) {
        int size = dp(42);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Drawable drawable = context.getDrawable(resourceId);
        if (drawable == null) drawable = context.getDrawable(R.drawable.cam_type_0);
        if (drawable != null) {
            drawable.setBounds(0, 0, size, size);
            drawable.draw(canvas);
        }
        return bitmap;
    }

    private int markerColor(int type) {
        switch (type) {
            case OBSERVATION_MARKER: return Color.rgb(70, 125, 165);
            case 16: return Color.rgb(115, 115, 115);
            case 3: case 10: case 18: case 103: return Color.rgb(220, 55, 48);
            case 5: case 104: case 105: case 108: return Color.rgb(195, 65, 155);
            case 41: case 42: case 43: return Color.rgb(236, 160, 20);
            case 107: return Color.rgb(35, 115, 220);
            case 17: case 171: case 172: return Color.rgb(145, 75, 190);
            default: return Color.rgb(238, 103, 28);
        }
    }

    private ImageProvider createClusterIcon(int count) {
        int size = dp(42);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.argb(245, 255, 255, 255));
        canvas.drawCircle(size / 2f, size / 2f, size * 0.48f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(3));
        paint.setColor(GREEN);
        canvas.drawCircle(size / 2f, size / 2f, size * 0.42f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(35, 35, 35));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.DEFAULT_BOLD);
        paint.setTextSize(dp(count > 999 ? 10 : 13));
        Paint.FontMetrics fm = paint.getFontMetrics();
        float baseline = size / 2f - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(Integer.toString(count), size / 2f, baseline, paint);
        return ImageProvider.fromBitmap(bitmap);
    }

    private void updateLocationMarker() {
        if (Double.isNaN(lastLatitude) || Double.isNaN(lastLongitude)
                || locationCollection == null) return;
        Point point = new Point(lastLatitude, lastLongitude);
        if (locationPlacemark == null || !locationPlacemark.isValid()) {
            locationPlacemark = locationCollection.addPlacemark();
            locationPlacemark.setIcon(ImageProvider.fromBitmap(createLocationBitmap()));
            locationPlacemark.setZIndex(100f);
        }
        locationPlacemark.setGeometry(point);
    }

    private Bitmap createLocationBitmap() {
        int size = dp(28);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        canvas.drawCircle(size / 2f, size / 2f, size * 0.47f, paint);
        paint.setColor(Color.rgb(35, 130, 255));
        canvas.drawCircle(size / 2f, size / 2f, size * 0.34f, paint);
        return bitmap;
    }

    private void centerOnLocationFromGps(float speedKmh) {
        com.yandex.mapkit.map.Map activeMap = map;
        if (activeMap == null || Double.isNaN(lastLatitude) || Double.isNaN(lastLongitude)) return;
        if (SystemClock.elapsedRealtime() < followPausedUntil) return;
        if (mapCenteredOnGps && speedKmh <= 0f) return;
        CameraPosition current = activeMap.getCameraPosition();
        float zoom = mapCenteredOnGps ? current.getZoom() : 15f;
        activeMap.move(new CameraPosition(new Point(lastLatitude, lastLongitude), zoom,
                        current.getAzimuth(), current.getTilt()),
                new Animation(Animation.Type.SMOOTH, 0.45f));
        mapCenteredOnGps = true;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private float dp(float value) {
        return value * context.getResources().getDisplayMetrics().density;
    }
}
