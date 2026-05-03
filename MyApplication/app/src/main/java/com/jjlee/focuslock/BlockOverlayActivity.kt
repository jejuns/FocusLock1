package com.jjlee.focuslock

import android.app.ActivityManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class BlockOverlayActivity : AppCompatActivity() {

    private var blockedPackage: String = ""
    private var blockingTagUids: List<String> = emptyList()
    private var nfcAdapter: NfcAdapter? = null
    private val handler = Handler(Looper.getMainLooper())
    private var timerText: TextView? = null
    private var remainingSeconds = 3
    private var nfcAuthenticated = false

    // 3초 카운트다운 타이머
    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (nfcAuthenticated) return
            remainingSeconds--
            timerText?.text = "$remainingSeconds"
            if (remainingSeconds <= 0) {
                onTimeout()
            } else {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        blockedPackage = intent.getStringExtra(EXTRA_BLOCKED_PACKAGE) ?: ""
        blockingTagUids = intent.getStringArrayListExtra(EXTRA_BLOCKING_TAG_UIDS) ?: emptyList()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        setContentView(buildLayout())
    }

    override fun onResume() {
        super.onResume()
        AppBlockerAccessibilityService.instance?.isBlockOverlayActive = true
        enableNfcForegroundDispatch()
        // 화면 뜨고 1초 후 카운트다운 시작
        handler.postDelayed(countdownRunnable, 1000)
    }

    override fun onPause() {
        super.onPause()
        AppBlockerAccessibilityService.instance?.isBlockOverlayActive = false
        disableNfcForegroundDispatch()
        handler.removeCallbacks(countdownRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(countdownRunnable)
        if (!nfcAuthenticated) {
            AppBlockerAccessibilityService.instance?.onOverlayDismissed()
        }
    }

    private fun onTimeout() {
        // 서비스 플래그 초기화 → 다음 진입 시 재차단 정상 작동
        AppBlockerAccessibilityService.instance?.onOverlayDismissed()

        // 차단 앱 프로세스 킬
        killBlockedApp()

        // 홈으로 이동 (최근 앱 목록에서 제거)
        AppBlockerAccessibilityService.instance?.performGlobalAction(
            android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
        )

        finish()
    }

    private fun killBlockedApp() {
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            am.killBackgroundProcesses(blockedPackage)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildLayout(): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#0A1628"))
            setPadding(64, 64, 64, 64)
        }

        root.addView(TextView(this).apply {
            text = "🔒"
            textSize = 80f
            gravity = android.view.Gravity.CENTER
        })

        val appName = getAppName(blockedPackage)
        root.addView(TextView(this).apply {
            text = "$appName\n차단됨"
            textSize = 26f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 16)
        })

        val tagNames = blockingTagUids.mapNotNull { uid ->
            PrefsManager.getTag(this, uid)?.name
        }.joinToString(" / ")

        root.addView(TextView(this).apply {
            text = "등록된 NFC 태그를\n갖다 대세요"
            textSize = 20f
            setTextColor(android.graphics.Color.parseColor("#A0A0C0"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 16)
        })

        if (tagNames.isNotEmpty()) {
            root.addView(TextView(this).apply {
                text = "인증 가능 태그: $tagNames"
                textSize = 14f
                setTextColor(android.graphics.Color.parseColor("#6060A0"))
                gravity = android.view.Gravity.CENTER
                setPadding(0, 0, 0, 24)
            })
        }

        // 카운트다운 타이머 표시
        val tv = TextView(this).apply {
            text = "$remainingSeconds"
            textSize = 48f
            setTextColor(android.graphics.Color.parseColor("#FF6B6B"))
            gravity = android.view.Gravity.CENTER
            setPadding(0, 0, 0, 8)
        }
        timerText = tv
        root.addView(tv)

        root.addView(TextView(this).apply {
            text = "초 안에 태그 인증 없으면 앱이 종료됩니다"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#606060"))
            gravity = android.view.Gravity.CENTER
        })

        return root
    }

    private fun getAppName(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (e: PackageManager.NameNotFoundException) { packageName }
    }

    private fun enableNfcForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        val intent = Intent(this, BlockOverlayActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        try { adapter.enableForegroundDispatch(this, pendingIntent, null, null) }
        catch (e: Exception) { e.printStackTrace() }
    }

    private fun disableNfcForegroundDispatch() {
        try { nfcAdapter?.disableForegroundDispatch(this) }
        catch (e: Exception) { e.printStackTrace() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val action = intent.action
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED) return

        @Suppress("DEPRECATION")
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        val uid = tag?.id?.toHexString() ?: return

        if (uid in blockingTagUids) {
            onNfcAuthSuccess(uid)
        } else {
            onNfcAuthFail()
        }
    }

    private fun onNfcAuthSuccess(uid: String) {
        nfcAuthenticated = true
        handler.removeCallbacks(countdownRunnable)

        PrefsManager.startSession(this, uid, blockedPackage)
        AppBlockerAccessibilityService.instance?.onOverlayDismissed()

        val tagName = PrefsManager.getTag(this, uid)?.name ?: "태그"
        Toast.makeText(this, "✅ $tagName 인증 성공! 30분 사용 가능", Toast.LENGTH_SHORT).show()

        Handler(Looper.getMainLooper()).postDelayed({
            val launchIntent = packageManager.getLaunchIntentForPackage(blockedPackage)
            if (launchIntent != null) startActivity(launchIntent)
            finish()
        }, 500)
    }

    private fun onNfcAuthFail() {
        Toast.makeText(this, "❌ 등록되지 않은 태그예요", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* 차단 */ }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_BACK -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    companion object {
        const val EXTRA_BLOCKED_PACKAGE = "blocked_package"
        const val EXTRA_BLOCKING_TAG_UIDS = "blocking_tag_uids"
    }
}

fun ByteArray.toHexString(): String = joinToString("") { "%02X".format(it) }