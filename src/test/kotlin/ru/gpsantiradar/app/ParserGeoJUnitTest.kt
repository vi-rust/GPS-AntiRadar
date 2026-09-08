package ru.gpsantiradar.app

import org.junit.Test

class ParserGeoJUnitTest {
    @Test
    fun parserAndPureLogicSuite() {
        val radarBaseFile = System.getProperty("radarBaseFile")
        ParserGeoTest.main(
            if (radarBaseFile.isNullOrBlank()) emptyArray() else arrayOf(radarBaseFile),
        )
    }
}
