package ru.gpsantiradar.app

import android.annotation.SuppressLint
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

class GpsCarAppService : CarAppService() {
    @SuppressLint("PrivateResource")
    override fun createHostValidator(): HostValidator = HostValidator.Builder(this)
        .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample).build()
    override fun onCreateSession(): Session = GpsCarSession()
}
