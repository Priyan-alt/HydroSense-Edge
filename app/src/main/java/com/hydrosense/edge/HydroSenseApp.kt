package com.hydrosense.edge

import android.app.Application
import com.hydrosense.edge.data.ble.BleManager
import com.hydrosense.edge.data.db.AppDatabase
import com.hydrosense.edge.data.repository.HydrationRepository

class HydroSenseApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var bleManager: BleManager
        private set

    lateinit var repository: HydrationRepository
        private set

    override fun onCreate() {
        super.onCreate()
        database = AppDatabase.getInstance(this)
        bleManager = BleManager(this)
        repository = HydrationRepository(
            bleManager = bleManager,
            hydrationDao = database.hydrationDao()
        )
    }
}
