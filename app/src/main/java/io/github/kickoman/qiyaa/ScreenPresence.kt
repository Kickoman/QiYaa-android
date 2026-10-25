package io.github.kickoman.qiyaa

import android.app.Activity
import android.app.Application
import android.os.Bundle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ScreenPresence(application: Application) {
    private val mutableOnScreen = MutableStateFlow(false)
    val onScreen: StateFlow<Boolean> = mutableOnScreen.asStateFlow()

    init {
        application.registerActivityLifecycleCallbacks(Counter(mutableOnScreen))
    }

    private class Counter(private val onScreen: MutableStateFlow<Boolean>) :
        Application.ActivityLifecycleCallbacks {
        private var started = 0

        override fun onActivityStarted(activity: Activity) {
            started++
            onScreen.value = true
        }

        override fun onActivityStopped(activity: Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (!activity.isChangingConfigurations) onScreen.value = started > 0
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

        override fun onActivityResumed(activity: Activity) = Unit

        override fun onActivityPaused(activity: Activity) = Unit

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

        override fun onActivityDestroyed(activity: Activity) = Unit
    }
}
