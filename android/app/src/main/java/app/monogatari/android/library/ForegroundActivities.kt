package app.monogatari.android.library

import android.app.Activity

/** One activity stopping must not pause work while another app window remains visible. */
object ForegroundActivities {
    private val visible = mutableSetOf<Activity>()
    @Synchronized fun started(activity: Activity) { visible += activity }
    @Synchronized fun stopped(activity: Activity): Boolean { visible -= activity; return visible.isEmpty() }
}
