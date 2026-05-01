package com.jjlee.focuslock

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

/**
 * 차단 오버레이 화면 - 동적 태그 구조
 *
 * 차단된 앱이 실행될 때 전체화면으로 표시
 * 해당 앱을 차단하는 태그 중 하나를 갖다 대야 해제
 * 여러 태그가 같은 앱을 차단할 경우 어느 태그든 인증 가능
 */
class BlockOverlayActivity : AppCompatActivity() {

    private var blockedPackage: String = ""
    private var blockingTagUids: List<String> = emptyList()
    private var nfcAdapter: NfcAdapter? = null

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

    private fun buildLayout(): android.view.View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setBackgroundColor(android.graphics.Color.parseColor("#1A1A2E"))
            setPadding(64, 64, 64, 64)
        }

        // 자물쇠 아이콘
        root.addView(TextView(this).apply {
            text = "🔒"
            textSize = 80f
            gravity = android.view.Gravity.CENTER
        })

        // 앱 이름
        val appName = getAppName(blockedPackage)
        root.addView(TextView(this).apply {
            text = "$appName\n차단됨"
            textSize = 26f
            setTextColor(android.graphics.Color.WHITE)
            gravity = android.view.Gravity.CENTER
            setPadding(0, 32, 0, 16)
        })

        // 어떤 태그로 해제 가능한지 표시
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
                setPadding(0, 0, 0, 32)
            })
        }

        root.addView(TextView(this).apply {
            text = "인증 성공 시 30분 사용 가능"
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#404060"))
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

    // ─── NFC 포그라운드 디스패치 ──────────────────────────────────

    override fun onResume() {
        super.onResume()
        AppBlockerAccessibilityService.instance?.isBlockOverlayActive = true
        AppBlockerAccessibilityService.instance?.onOverlayShown()  // 1번: 최근 앱 정리 시작
        enableNfcForegroundDispatch()
    }

    override fun onPause() {
        super.onPause()
        AppBlockerAccessibilityService.instance?.isBlockOverlayActive = false
        disableNfcForegroundDispatch()
    }

    override fun onDestroy() {
        super.onDestroy()
        AppBlockerAccessibilityService.instance?.onOverlayDismissed()  // 1번: 정리 중단
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

    // ─── NFC 태그 감지 ────────────────────────────────────────────

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val action = intent.action
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED) return

        @Suppress("DEPRECATION")
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        val uid = tag?.id?.toHexString() ?: return

        // 이 앱을 차단하는 태그 중 하나인지 확인
        if (uid in blockingTagUids) {
            onNfcAuthSuccess(uid)
        } else {
            onNfcAuthFail()
        }
    }

    private fun onNfcAuthSuccess(uid: String) {
        // 해당 태그로 이 앱의 세션 시작 (30분)
        PrefsManager.startSession(this, uid, blockedPackage)

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

    // ─── 탈출 방지 ────────────────────────────────────────────────

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