package com.dan.timelapse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings as AppSettings
import android.util.Log
import android.view.MenuItem
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.dan.timelapse.screens.AppFragment
import com.dan.timelapse.screens.BusyDialog
import com.dan.timelapse.screens.MainFragment
import com.dan.timelapse.utils.Settings
import org.opencv.android.OpenCVLoader
import java.io.PrintWriter
import java.io.StringWriter


class MainActivity : AppCompatActivity() {
    companion object {
        val REQUIRED_PERMISSIONS_BELOW_Q = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        // Android 10 (Q) still needs WRITE_EXTERNAL_STORAGE for legacy storage access
        val REQUIRED_PERMISSIONS_Q = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        )
        // Android 11 (R) and 12 (S)
        val REQUIRED_PERMISSIONS_R_TO_S = arrayOf(
            Manifest.permission.READ_EXTERNAL_STORAGE
        )
        val REQUIRED_PERMISSIONS_TIRAMISU_ONWARD = arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO
        )

        const val REQUEST_PERMISSIONS_CODE = 1

        fun getRequiredPermissions(): Array<String> {
            return when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> REQUIRED_PERMISSIONS_TIRAMISU_ONWARD
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> REQUIRED_PERMISSIONS_R_TO_S
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> REQUIRED_PERMISSIONS_Q
                else -> REQUIRED_PERMISSIONS_BELOW_Q
            }
        }
    }

    private val stack = mutableListOf<Pair<String, AppFragment>>()
    val settings: Settings by lazy { Settings(this) }

    private fun setupExceptionHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val stackTrace = sw.toString()
            Log.e("CRASH", stackTrace)

            // Use a safer way to show the toast
            try {
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this@MainActivity, "CRASH: ${throwable.localizedMessage ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                // Ignore if we can't show toast
            }
            
            Thread.sleep(2000)
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupExceptionHandler()
        BusyDialog.create(this)

        if (!checkAndRequestPermissions()) {
            onPermissionsAllowed()
        }
    }

    private fun checkAndRequestPermissions(): Boolean {
        val currentRequiredPermissions = getRequiredPermissions()
        val permissionsToRequest = mutableListOf<String>()
        for (permission in currentRequiredPermissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(permission)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), REQUEST_PERMISSIONS_CODE)
            return true
        }
        return false
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            var allPermissionsGranted = true
            if (grantResults.isNotEmpty()) {
                val currentRequiredPermissionsMap = getRequiredPermissions().associateWith { false }.toMutableMap()
                for (i in permissions.indices) {
                    if (currentRequiredPermissionsMap.containsKey(permissions[i])) {
                        if (grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                            currentRequiredPermissionsMap[permissions[i]] = true
                        } else {
                            Log.w("Permissions", "Permission denied: ${permissions[i]}")
                        }
                    }
                }
                allPermissionsGranted = currentRequiredPermissionsMap.values.all { it }
            } else {
                allPermissionsGranted = false
            }

            if (allPermissionsGranted) {
                onPermissionsAllowed()
            } else {
                showPermissionsDeniedDialog()
            }
        }
    }

    private fun showPermissionsDeniedDialog() {
        if (!isFinishing && !isDestroyed) {
            AlertDialog.Builder(this)
                .setTitle("Permissions Required")
                .setMessage("This app needs storage permissions to function correctly. Please grant them in app settings or restart the app to try again.")
                .setPositiveButton("App Settings") { _, _ ->
                    val intent = Intent(AppSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    val uri = Uri.fromParts("package", packageName, null)
                    intent.data = uri
                    startActivity(intent)
                    finishAppGracefully()
                }
                .setNegativeButton("Exit") { _, _ ->
                    fatalError("Required permissions were not granted. The app will now exit.", true)
                }
                .setCancelable(false)
                .show()
        }
    }


    private fun popView(homeButton: Boolean = false): Boolean {
        if (stack.size <= 1) return false

        val prevFragment = stack.removeLast().second
        val item = stack.last()

        prevFragment.onBack(homeButton)

        val transaction = supportFragmentManager.beginTransaction()
        transaction.show(item.second)
        transaction.remove(prevFragment)
        transaction.commitAllowingStateLoss()

        supportActionBar?.setDisplayHomeAsUpEnabled(stack.size > 1)
        supportActionBar?.title = item.first

        return true
    }

    fun pushView(title: String, fragment: AppFragment) {
        val prevFragment: AppFragment? = if (stack.isEmpty()) null else stack.last().second

        stack.add(Pair(title, fragment))

        val transaction = supportFragmentManager.beginTransaction()
        if (null != prevFragment) transaction.hide(prevFragment)
        transaction.add(R.id.app_fragment, fragment)
        transaction.commitAllowingStateLoss()

        supportActionBar?.setDisplayHomeAsUpEnabled(stack.size > 1)
        supportActionBar?.title = title
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (android.R.id.home == item.itemId) {
            popView(true)
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (!popView(false)) {
            super.onBackPressed()
        }
    }

    fun showToast(message: String) {
        Toast.makeText(applicationContext, message, Toast.LENGTH_LONG).show()
    }

    private fun finishAppGracefully() {
        finishAffinity()
    }

    private fun fatalError(msg: String, fromPermissionDenial: Boolean = false) {
        if (!isFinishing && !isDestroyed) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.app_name))
                .setMessage(msg)
                .setIcon(android.R.drawable.stat_notify_error)
                .setCancelable(false)
                .setPositiveButton("OK") { _, _ -> finishAppGracefully() }
                .show()
        } else if (fromPermissionDenial) {
            Log.e("FatalError", "Activity is finishing, cannot show dialog: $msg")
            finishAppGracefully()
        }
    }

    private fun onPermissionsAllowed() {
        Log.d("Permissions", "All required permissions granted.")
        try {
            if (!OpenCVLoader.initDebug()) {
                Log.e("OpenCV", "Internal OpenCV library not found. Using Manual load.")
                System.loadLibrary("opencv_java4")
            }
            System.loadLibrary("timelapse")
        } catch (e: UnsatisfiedLinkError) {
            fatalError("Failed to initialize core libraries: ${e.message}")
            return
        } catch (e: Exception) {
            fatalError("An unexpected error occurred during initialization: ${e.message}")
            return
        }

        if (isFinishing || isDestroyed) return

        setContentView(R.layout.activity_main)
        MainFragment.show(this)
    }
}
