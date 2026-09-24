package com.example.animatedsplash

import androidx.annotation.DrawableRes

object ProjectIconCatalog {
    @DrawableRes
    fun resource(iconKey: String): Int = when (iconKey) {
        "chip" -> R.drawable.ic_arduino_chip
        "robot" -> R.drawable.ic_robot
        "sensor" -> R.drawable.ic_sensor
        "light" -> R.drawable.ic_light
        "rocket" -> R.drawable.ic_project_rocket
        "terminal" -> R.drawable.ic_project_terminal
        "bolt" -> R.drawable.ic_project_bolt
        "wifi" -> R.drawable.ic_project_wifi
        "motor" -> R.drawable.ic_project_motor
        "thermo" -> R.drawable.ic_project_thermo
        "display" -> R.drawable.ic_project_display
        "music" -> R.drawable.ic_project_music
        "camera" -> R.drawable.ic_project_camera
        "bluetooth" -> R.drawable.ic_project_bluetooth
        "cloud" -> R.drawable.ic_project_cloud
        "lock" -> R.drawable.ic_project_lock
        "clock" -> R.drawable.ic_project_clock
        "leaf" -> R.drawable.ic_project_leaf
        "satellite" -> R.drawable.ic_project_satellite
        else -> R.drawable.ic_spark
    }
}
