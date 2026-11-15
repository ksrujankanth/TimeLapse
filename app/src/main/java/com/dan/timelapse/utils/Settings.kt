package com.dan.timelapse.utils

import android.app.Activity
import android.content.Context
import android.os.Environment
import java.io.File
import kotlin.reflect.KMutableProperty
import kotlin.reflect.KVisibility
import kotlin.reflect.full.createType
import kotlin.reflect.full.declaredMemberProperties

/**
Settings: all public var fields will be saved
 */
class Settings( private val activity: Activity) {

    companion object {
        val VIDEO_SAVE_FOLDER = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "TimeLapse")
        val PHOTO_SAVE_FOLDER = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "TimeLapse")

        val FPS_VALUES = arrayOf( 5, 10, 15, 20, 25, 30, 60, 120, 240 )

        const val EXT_VIDEO = "mp4"
        const val EXT_PHOTO = "jpg"
        
        const val RES_720P = 0
        const val RES_1080P = 1
        const val RES_4K = 2

        const val UI_MODE_SIMPLE = 0
        const val UI_MODE_EXPERT = 1

        fun getClosestFpsIndex(fps: Int): Int {
            for (index in FPS_VALUES.lastIndex downTo  0) {
                if (FPS_VALUES[index] <= fps) return index
            }
            return 0
        }
    }

    var h265: Boolean = HardwareUtils.isHEVCHardwareSupported(activity)
    var crop: Boolean = true
    var videoResolution: Int = if (HardwareUtils.isLowEndDevice(activity)) RES_720P else RES_1080P
    var jpegQuality = 95
    var lastFolderUri: String = ""
    var uiMode: Int = UI_MODE_SIMPLE

    init {
        loadProperties()
    }


    private fun forEachSettingProperty( listener: (KMutableProperty<*>)->Unit ) {
        for( member in this::class.declaredMemberProperties ) {
            if (member.visibility == KVisibility.PUBLIC && member is KMutableProperty<*>) {
                listener.invoke(member)
            }
        }
    }

    private fun loadProperties() {
        val preferences = activity.getPreferences(Context.MODE_PRIVATE)

        forEachSettingProperty { property ->
            try {
                if (preferences.contains(property.name)) {
                    when (property.returnType) {
                        Boolean::class.createType() -> property.setter.call(this, preferences.getBoolean(property.name, property.getter.call(this) as Boolean))
                        Int::class.createType() -> property.setter.call(this, preferences.getInt(property.name, property.getter.call(this) as Int))
                        Long::class.createType() -> property.setter.call(this, preferences.getLong(property.name, property.getter.call(this) as Long))
                        Float::class.createType() -> property.setter.call(this, preferences.getFloat(property.name, property.getter.call(this) as Float))
                        String::class.createType() -> property.setter.call(this, preferences.getString(property.name, property.getter.call(this) as? String ?: ""))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun saveProperties() {
        val preferences = activity.getPreferences(Context.MODE_PRIVATE)
        val editor = preferences.edit()

        forEachSettingProperty { property ->
            when( property.returnType ) {
                Boolean::class.createType() -> editor.putBoolean( property.name, property.getter.call(this) as Boolean )
                Int::class.createType() -> editor.putInt( property.name, property.getter.call(this) as Int )
                Long::class.createType() -> editor.putLong( property.name, property.getter.call(this) as Long )
                Float::class.createType() -> editor.putFloat( property.name, property.getter.call(this) as Float )
                String::class.createType() -> editor.putString( property.name, property.getter.call(this) as? String ?: "" )
            }
        }

        editor.apply()
    }

    fun getString(key: String, defValue: String?): String? {
        val preferences = activity.getPreferences(Context.MODE_PRIVATE)
        return preferences.getString(key, defValue)
    }

    fun putString(key: String, value: String) {
        val preferences = activity.getPreferences(Context.MODE_PRIVATE)
        with(preferences.edit()) {
            putString(key, value)
            apply()
        }
    }
}