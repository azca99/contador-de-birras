package com.example.contadordebirras.domain

interface BeerSyncScheduler {
    fun requestSync(expectedUid: String)
    fun schedulePeriodicSync(expectedUid: String)
}
