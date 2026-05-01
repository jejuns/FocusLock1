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

    // 세션 만료 감지용 주기적 폴링 (30초마다)
    private val sessionCheckRunnable = object : Runnable {
        override fun run() {
            checkForegroundApp()
            handler.postDelayed(this, SESSION_CHECK_INTERVAL_MS)
        }
    }

    // 1번: 최근 앱 목록에서 차단 앱 제거용 주기적 실행
    private val recentsCleanRunnable = object : Runnable {
        override fun run() {
            if (isBlockOverlayActive) {
                removeBlockedAppFromRecents()
                handler.postDelayed(this, RECENTS_CLEAN_INTERVAL_MS)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        lastBlockedPackage = ""
        overlayLaunched = false

        // 3번: 분할화면/팝업뷰 감지를 위해 TYPE_WINDOWS_CHANGED 포함
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
        handler.removeCallbacks(recentsCleanRunnable)
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

            if (!isBlockOverlayActive) removeFromRecents()
            launchBlockOverlay(topPackage, blockingTagUids)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return

        if (packageName in IGNORED_PACKAGES) return
        if (packageName == this.packageName) return

        // 3번: 분할화면/팝업뷰도 감지 - windows 이벤트에서 모든 창 확인
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

        if (!isBlockOverlayActive) removeFromRecents()
        launchBlockOverlay(packageName, blockingTagUids)
    }

    /**
     * 3번: 분할화면/팝업뷰 감지
     * TYPE_WINDOWS_CHANGED 이벤트 발생 시 현재 보이는 모든 창을 확인
     */
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

                removeFromRecents()
                launchBlockOverlay(pkgName, blockingTagUids)
                break
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun removeFromRecents() {
        handler.post {
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    /**
     * 1번: 오버레이 활성화 중 최근 앱 목록에서 차단 앱 지속 제거
     * OneUI가 excludeFromRecents를 무시하는 경우 대응
     */
    private fun removeBlockedAppFromRecents() {
        if (lastBlockedPackage.isEmpty()) return
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val tasks = am.getRunningTasks(10)
            // 최근 앱 목록에 차단 앱이 있으면 홈으로 밀어내기
            val hasBlockedInRecents = tasks.any {
                it.topActivity?.packageName == lastBlockedPackage
            }
            if (hasBlockedInRecents) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 1번: BlockOverlayActivity에서 호출
     * 오버레이 활성화 시 최근 앱 정리 주기적 실행 시작
     */
    fun onOverlayShown() {
        handler.post(recentsCleanRunnable)
    }

    /**
     * 오버레이 종료 시 플래그 초기화
     */
    fun onOverlayDismissed() {
        overlayLaunched = false
        lastBlockedPackage = ""
        handler.removeCallbacks(recentsCleanRunnable)
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
        private const val RECENTS_CLEAN_INTERVAL_MS = 2_000L  // 1번: 2초마다 최근 앱 정리

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