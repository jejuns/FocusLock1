package com.jjlee.focuslock

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class AppBlockerAccessibilityService : AccessibilityService() {

    private var lastBlockedPackage = ""
    private var lastBlockedTime = 0L
    private var overlayLaunched = false
    private val handler = Handler(Looper.getMainLooper())
    var isBlockOverlayActive = false

    private val sessionCheckRunnable = object : Runnable {
        override fun run() {
            checkForegroundApp()
            handler.postDelayed(this, SESSION_CHECK_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lastBlockedPackage = ""
        overlayLaunched = false

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                    AccessibilityEvent.TYPE_WINDOWS_CHANGED
        }

        handler.postDelayed(sessionCheckRunnable, SESSION_CHECK_INTERVAL_MS)
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(sessionCheckRunnable)
        instance = null
    }

    private fun checkForegroundApp() {
        if (isBlockOverlayActive) return
        val allBlockedApps = PrefsManager.getAllBlockedApps(this)
        if (allBlockedApps.isEmpty()) return

        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val tasks = am.getRunningTasks(1)
            val topPackage = tasks.firstOrNull()?.topActivity?.packageName ?: return

            if (topPackage in IGNORED_PACKAGES) return
            if (topPackage == this.packageName) return
            if (topPackage !in allBlockedApps) return
            if (PrefsManager.hasAnySessionForApp(this, topPackage)) return

            val blockingTagUids = PrefsManager.getActiveTagsForApp(this, topPackage)
            if (blockingTagUids.isEmpty()) return

            val now = System.currentTimeMillis()
            if (topPackage == lastBlockedPackage && now - lastBlockedTime < 500) return

            lastBlockedPackage = topPackage
            lastBlockedTime = now

            launchBlockOverlay(topPackage, blockingTagUids)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        if (packageName in IGNORED_PACKAGES) return
        if (packageName == this.packageName) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            checkAllVisibleWindows()
            return
        }

        val allBlockedApps = PrefsManager.getAllBlockedApps(this)
        if (packageName !in allBlockedApps) return

        if (PrefsManager.hasAnySessionForApp(this, packageName)) {
            if (lastBlockedPackage == packageName) {
                lastBlockedPackage = ""
                overlayLaunched = false
            }
            return
        }

        val now = System.currentTimeMillis()
        if (packageName == lastBlockedPackage && now - lastBlockedTime < 500) return
        if (overlayLaunched && packageName == lastBlockedPackage) return

        lastBlockedPackage = packageName
        lastBlockedTime = now
        overlayLaunched = true

        val blockingTagUids = PrefsManager.getActiveTagsForApp(this, packageName)
        if (blockingTagUids.isEmpty()) return

        launchBlockOverlay(packageName, blockingTagUids)
    }

    private fun checkAllVisibleWindows() {
        if (isBlockOverlayActive) return
        val allBlockedApps = PrefsManager.getAllBlockedApps(this)
        if (allBlockedApps.isEmpty()) return

        try {
            val windows = windows ?: return
            for (window in windows) {
                val pkgName = window.root?.packageName?.toString() ?: continue
                if (pkgName in IGNORED_PACKAGES) continue
                if (pkgName == this.packageName) continue
                if (pkgName !in allBlockedApps) continue
                if (PrefsManager.hasAnySessionForApp(this, pkgName)) continue

                val now = System.currentTimeMillis()
                if (pkgName == lastBlockedPackage && now - lastBlockedTime < 500) continue
                if (overlayLaunched && pkgName == lastBlockedPackage) continue

                val blockingTagUids = PrefsManager.getActiveTagsForApp(this, pkgName)
                if (blockingTagUids.isEmpty()) continue

                lastBlockedPackage = pkgName
                lastBlockedTime = now
                overlayLaunched = true

                launchBlockOverlay(pkgName, blockingTagUids)
                break
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun onOverlayDismissed() {
        overlayLaunched = false
        lastBlockedPackage = ""
    }

    private fun launchBlockOverlay(packageName: String, blockingTagUids: List<String>) {
        val intent = Intent(this, BlockOverlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(BlockOverlayActivity.EXTRA_BLOCKED_PACKAGE, packageName)
            putStringArrayListExtra(
                BlockOverlayActivity.EXTRA_BLOCKING_TAG_UIDS,
                ArrayList(blockingTagUids)
            )
        }
        startActivity(intent)
    }

    override fun onInterrupt() {}

    companion object {
        var instance: AppBlockerAccessibilityService? = null
        private const val SESSION_CHECK_INTERVAL_MS = 30_000L

        private val IGNORED_PACKAGES = setOf(
            "com.android.systemui",
            "com.android.launcher",
            "com.sec.android.app.launcher",
            "com.google.android.inputmethod.latin",
            "com.samsung.android.inputmethod",
            "android",
            "com.android.settings",
            "com.jjlee.focuslock"
        )
    }
}