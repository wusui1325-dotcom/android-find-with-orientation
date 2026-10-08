package com.wusper.findorientation

import android.app.Application
import com.wusper.findorientation.nearby.FindRepository

class FindApp : Application() {
    lateinit var repository: FindRepository
        private set

    override fun onCreate() {
        super.onCreate()
        repository = FindRepository(this)
    }
}
