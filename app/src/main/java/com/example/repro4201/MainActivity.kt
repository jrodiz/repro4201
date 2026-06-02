package com.example.repro4201

import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Repro for firebase-android-sdk#4201:
 * after the previous run ends with an ANR, FirebaseCrashlytics.didCrashOnPreviousExecution()
 * should return true on the next launch (API 30+).
 *
 * Steps:
 *  1. Launch the app — status shows "didCrashOnPreviousExecution = false" on a clean run.
 *  2. Tap "Trigger ANR" — the main thread blocks; wait ~5s for the system ANR dialog,
 *     then tap "Close app" so the OS records REASON_ANR in ApplicationExitInfo.
 *  3. Relaunch — status should now read "didCrashOnPreviousExecution = true".
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val status = findViewById<TextView>(R.id.status)
        val anrButton = findViewById<Button>(R.id.anrButton)

        // Read the predicate OFF the main thread. The ANR flag is set during Crashlytics
        // background init (finalizeSessions), so it is eventually-consistent: poll briefly.
        Thread {
            val crashlytics = FirebaseCrashlytics.getInstance()
            var result = false
            for (attempt in 1..10) {
                result = crashlytics.didCrashOnPreviousExecution()
                Log.i(TAG, "didCrashOnPreviousExecution=$result (attempt $attempt)")
                if (result) break
                Thread.sleep(500)
            }
            val finalResult = result
            runOnUiThread {
                status.text = "didCrashOnPreviousExecution = $finalResult"
            }
        }.start()

        anrButton.setOnClickListener {
            Log.w(TAG, "Blocking the main thread to force an ANR. Tap \"Close app\" on the dialog.")
            // Block well past the 5s ANR threshold so the system raises an ANR.
            while (true) {
                try {
                    Thread.sleep(10_000)
                } catch (ignored: InterruptedException) {
                    // keep blocking
                }
            }
        }
    }

    companion object {
        private const val TAG = "Repro4201"
    }
}
