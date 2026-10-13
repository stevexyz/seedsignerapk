package com.seedsigner

import android.app.Activity
import android.os.Bundle
import android.view.WindowManager
import com.seedsigner.app.Controller

/**
 * The single Activity (spec §1.4: one Activity, View-swapped screens).
 * All app state lives in the Controller; process death wipes the seeds.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // screen always on while the app is foreground (upstream behavior:
        // the Pi keeps its display lit during flows)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(android.widget.FrameLayout(this))

        val controller = Controller.instance
        controller.attachActivity(this)
        controller.start()
    }

    /** Android back gesture == on-screen back arrow (upstream RET_CODE__BACK_BUTTON). */
    override fun onBackPressed() {
        Controller.instance.onBack()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        Controller.instance.onPermissionResult(granted)
    }
}
