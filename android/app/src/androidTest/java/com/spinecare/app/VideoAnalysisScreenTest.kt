package com.spinecare.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opens demo videos in the app's own "Analyze a video" screen (VideoAnalysisActivity,
 *  from the video guides work) exactly as a user would after picking a file, and
 *  screenshots the screen while it detects and overlays, and once it has finished.
 *  Looked up by class name so this file also builds on branches without that screen
 *  (the test is then skipped). Clips: instrumentation arg inAppClips=id1,id2,... */
@RunWith(AndroidJUnit4::class)
class VideoAnalysisScreenTest {
    @Test
    fun analyzeDemoVideosInTheApp() {
        val inst = InstrumentationRegistry.getInstrumentation()
        val app = inst.targetContext
        val screen = "com.spinecare.app.VideoAnalysisActivity"
        assumeTrue("$screen not in this build", runCatching { Class.forName(screen) }.isSuccess)
        val sourcesJson = runCatching { inst.context.assets.open("videos/sources.json").bufferedReader().use { it.readText() } }.getOrNull()
        assumeTrue("no demo videos bundled", sourcesJson != null)
        val videos = JSONObject(sourcesJson!!).getJSONArray("videos")
        val wanted = InstrumentationRegistry.getArguments().getString("inAppClips")?.split(',')?.map { it.trim() }?.toSet()
        val timeoutMs = (InstrumentationRegistry.getArguments().getString("inAppTimeoutS")?.toLongOrNull() ?: 240L) * 1000

        val outRoot = File(app.filesDir, "in_app").apply { deleteRecursively(); mkdirs() }
        val results = JSONArray()
        for (i in 0 until videos.length()) {
            val v = videos.getJSONObject(i)
            val id = v.getString("id")
            if (wanted != null && id !in wanted) continue
            val clip = File(app.cacheDir, v.getString("file"))
            inst.context.assets.open("videos/" + v.getString("file")).use { inp -> clip.outputStream().use { inp.copyTo(it) } }
            val out = File(outRoot, id).apply { mkdirs() }
            val intent = Intent().setClassName(app, screen).setData(Uri.fromFile(clip))
                .putExtra("exercise_id", v.getString("exercise_id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            val t0 = SystemClock.elapsedRealtime()
            var shots = 0
            var finished = false
            ActivityScenario.launch<Activity>(intent).use { scenario ->
                while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
                    SystemClock.sleep(4000)
                    var done = false
                    scenario.onActivity { a -> done = findSeekBar(a.window.decorView)?.isEnabled == true }
                    inst.uiAutomation.takeScreenshot()?.let { bmp ->
                        File(out, "screen_%02d%s.png".format(shots++, if (done) "_done" else "")).outputStream()
                            .use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    if (done) { finished = true; break }
                }
            }
            val r = JSONObject().put("id", id).put("exercise_id", v.getString("exercise_id"))
                .put("finished", finished).put("seconds", (SystemClock.elapsedRealtime() - t0) / 1000.0).put("screenshots", shots)
            Log.i(TAG, r.toString())
            results.put(r)
            clip.delete()
        }
        File(outRoot, "summary.json").writeText(JSONObject().put("clips", results).toString(1))
        assumeTrue("no requested clip found", results.length() > 0)
        for (i in 0 until results.length()) {
            val r = results.getJSONObject(i)
            assertTrue("${r.getString("id")}: analysis screen did not finish in time", r.getBoolean("finished"))
        }
    }

    private fun findSeekBar(v: View): SeekBar? {
        if (v is SeekBar) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findSeekBar(v.getChildAt(i))?.let { return it }
        return null
    }

    companion object { private const val TAG = "VideoAnalysisScreenTest" }
}
