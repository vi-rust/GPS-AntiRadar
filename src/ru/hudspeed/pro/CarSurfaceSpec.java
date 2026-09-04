package ru.gpsantiradar.app;

import java.util.Objects;

public final class CarSurfaceSpec {
    public final int width;
    public final int height;
    public final int dpi;

    private CarSurfaceSpec(int width, int height, int dpi) {
        this.width = width;
        this.height = height;
        this.dpi = dpi;
    }

    public static CarSurfaceSpec from(int width, int height, int dpi) {
        return new CarSurfaceSpec(width, height, dpi);
    }

    public boolean isUsable() {
        return width > 0 && height > 0 && dpi > 0;
    }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof CarSurfaceSpec)) return false;
        CarSurfaceSpec that = (CarSurfaceSpec) other;
        return width == that.width && height == that.height && dpi == that.dpi;
    }

    @Override public int hashCode() {
        return Objects.hash(width, height, dpi);
    }

    @Override public String toString() {
        return width + "x" + height + "@" + dpi;
    }
}
