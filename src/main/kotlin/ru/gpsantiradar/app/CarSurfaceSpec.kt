package ru.gpsantiradar.app

class CarSurfaceSpec private constructor(
    val width: Int,
    val height: Int,
    val dpi: Int
) {
    fun isUsable() = width > 0 && height > 0 && dpi > 0
    override fun equals(other: Any?) =
        this === other || other is CarSurfaceSpec && width == other.width && height == other.height && dpi == other.dpi
    override fun hashCode() = (31 * width + height) * 31 + dpi
    override fun toString() = "${width}x${height}@${dpi}"

    companion object {
        fun from(width: Int, height: Int, dpi: Int) = CarSurfaceSpec(width, height, dpi)
    }
}
