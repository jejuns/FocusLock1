package com.jjlee.focuslock

import android.app.AlertDialog
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.NestedScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * FocusLock — Material 3 redesigned MainActivity.
 *
 * 구조 원칙:
 *  • XML 레이아웃을 쓰지 않고 코드로 View를 빌드 (기존 방식 유지)
 *  • 색상 하드코딩 0(zero) — 모두 themes.xml의 M3 attr을 MaterialColors로 읽음
 *  • 카드는 MaterialCardView (16dp radius), 버튼은 MaterialButton (Filled / Tonal / Outlined / Text)
 *  • Section, StatusPill, StatusRow 등 재사용 컴포넌트로 분해
 */
class MainActivity : AppCompatActivity() {

    private var nfcAdapter: NfcAdapter? = null
    private var nfcPendingIntent: PendingIntent? = null

    private enum class NfcMode { NONE, ADD_TAG, AUTH_EDIT, AUTH_DELETE, SHUTDOWN }

    private var currentNfcMode = NfcMode.NONE
    private var pendingUid: String? = null

    // NFC 라이브 배너 — 보일 때만 화면 상단에 노출
    private var nfcBanner: View? = null
    private var nfcBannerText: TextView? = null

    // ─── 라이프사이클 ─────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        nfcPendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        buildAndSetContentView()
    }

    override fun onResume() { super.onResume(); enableNfcForegroundDispatch() }
    override fun onPause()  { super.onPause();  disableNfcForegroundDispatch() }

    private fun refreshUI() { buildAndSetContentView() }

    private fun buildAndSetContentView() { setContentView(buildMainLayout()) }

    // ─── NFC ──────────────────────────────────────────────────────

    private fun enableNfcForegroundDispatch() {
        val adapter = nfcAdapter ?: return
        val pi = nfcPendingIntent ?: return
        try { adapter.enableForegroundDispatch(this, pi, null, null) }
        catch (e: Exception) { e.printStackTrace() }
    }

    private fun disableNfcForegroundDispatch() {
        try { nfcAdapter?.disableForegroundDispatch(this) }
        catch (e: Exception) { e.printStackTrace() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val action = intent.action
        if (action != NfcAdapter.ACTION_TAG_DISCOVERED &&
            action != NfcAdapter.ACTION_NDEF_DISCOVERED &&
            action != NfcAdapter.ACTION_TECH_DISCOVERED) return

        @Suppress("DEPRECATION")
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        val uid = tag?.id?.toHexString() ?: return

        when (currentNfcMode) {
            NfcMode.ADD_TAG -> {
                if (PrefsManager.hasTag(this, uid)) {
                    showSnack("이미 등록된 태그예요"); hideNfcBanner(); return
                }
                currentNfcMode = NfcMode.NONE; hideNfcBanner()
                showTagNameInputDialog(uid)
            }
            NfcMode.AUTH_EDIT -> {
                if (pendingUid == null || uid != pendingUid) {
                    showSnack("해당 태그가 아닙니다"); return
                }
                currentNfcMode = NfcMode.NONE; pendingUid = null; hideNfcBanner()
                showAppSelectionDialog(uid)
            }
            NfcMode.AUTH_DELETE -> {
                if (pendingUid == null || uid != pendingUid) {
                    showSnack("해당 태그가 아닙니다"); return
                }
                currentNfcMode = NfcMode.NONE; pendingUid = null; hideNfcBanner()
                val tagInfo = PrefsManager.getTag(this, uid)
                MaterialAlertDialogBuilder(this)
                    .setTitle("태그 삭제")
                    .setMessage("'${tagInfo?.name}' 태그를 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ ->
                        PrefsManager.removeTag(this, uid)
                        showSnack("태그 삭제됨"); refreshUI()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            NfcMode.SHUTDOWN -> {
                val tagInfo = PrefsManager.getTag(this, uid)
                if (tagInfo == null) { showSnack("등록되지 않은 태그예요"); return }
                currentNfcMode = NfcMode.NONE; pendingUid = null; hideNfcBanner()
                if (!tagInfo.isActive) { showSnack("${tagInfo.name} 이미 비활성화 상태"); return }
                PrefsManager.setTagActive(this, uid, false)
                showSnack("${tagInfo.name} 잠금 해제됨"); refreshUI()
            }
            NfcMode.NONE -> showSnack("현재 NFC 대기 중이 아니에요")
        }
    }

    private fun showSnack(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }

    // ─── 메인 레이아웃 ────────────────────────────────────────────

    private fun buildMainLayout(): View {
        val scroll = NestedScrollView(this).apply {
            setBackgroundColor(c(com.google.android.material.R.attr.colorSurface))
            isFillViewport = true
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(32))
        }

        // Top app bar (Large)
        root.addView(buildAppBar())

        // 본문 컨테이너 — 좌우 패딩 20dp
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), 0, dp(20), 0)
        }

        // NFC 라이브 배너 (기본 숨김)
        content.addView(buildNfcBanner().also { nfcBanner = it })

        // 보호 상태 hero
        content.addView(buildStatusHero())

        // 시스템 상태 카드
        content.addView(buildSystemStatusCard().withMarginTop(dp(16)))

        // 1단계 — PIN
        content.addView(buildSectionHeader("1", "관리자 PIN", "설정 진입을 보호합니다"))
        content.addView(buildPinCard())

        // 2단계 — 태그
        content.addView(buildSectionHeader("2", "NFC 태그", "장소별 차단 규칙을 관리합니다"))
        content.addView(buildTagListSection())

        // 3단계 — 잠금 해제
        content.addView(buildSectionHeader("3", "잠금 해제", "외출 후 차단을 풀 때 사용"))
        content.addView(buildShutdownCard())

        // 4단계 — 접근성
        content.addView(buildSectionHeader("4", "접근성 서비스", "앱 차단 핵심 기능"))
        content.addView(buildAccessibilityCard())

        root.addView(content)
        scroll.addView(root)
        return scroll
    }

    // ─── 앱바 ─────────────────────────────────────────────────────

    private fun buildAppBar(): View {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(20))
        }

        // Headline
        container.addView(TextView(this).apply {
            text = "FocusLock"
            setTextSizeSp(36f)
            setTextColor(c(com.google.android.material.R.attr.colorOnSurface))
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            letterSpacing = -0.02f
        })
        container.addView(TextView(this).apply {
            text = "NFC 태그로 집중을 지킵니다"
            setTextSizeSp(14f)
            setTextColor(c(com.google.android.material.R.attr.colorOnSurfaceVariant))
            (layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(4)
        })
        return container
    }

    // ─── NFC 라이브 배너 ──────────────────────────────────────────

    private fun buildNfcBanner(): View {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(c(com.google.android.material.R.attr.colorTertiaryContainer))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            gravity = Gravity.CENTER_VERTICAL
        }
        val text = TextView(this).apply {
            setTextSizeSp(14f)
            setTextColor(c(com.google.android.material.R.attr.colorOnTertiaryContainer))
            text = "태그를 갖다 대세요"
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        nfcBannerText = text
        row.addView(text)
        row.addView(textButton("취소") {
            currentNfcMode = NfcMode.NONE; pendingUid = null; hideNfcBanner()
        })
        card.addView(row)
        return card
    }

    private fun showNfcBanner(msg: String) {
        nfcBannerText?.text = msg
        nfcBanner?.visibility = View.VISIBLE
    }
    private fun hideNfcBanner() { nfcBanner?.visibility = View.GONE }

    // ─── Hero — 보호 상태 ─────────────────────────────────────────

    private fun buildStatusHero(): View {
        val tags = PrefsManager.getAllTags(this)
        val active = tags.count { it.isActive }
        val protecting = active > 0

        val bg = if (protecting) c(com.google.android.material.R.attr.colorPrimaryContainer)
        else c(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val fg = if (protecting) c(com.google.android.material.R.attr.colorOnPrimaryContainer)
        else c(com.google.android.material.R.attr.colorOnSurface)
        val fgMuted = if (protecting) c(com.google.android.material.R.attr.colorOnPrimaryContainer)
        else c(com.google.android.material.R.attr.colorOnSurfaceVariant)

        val card = MaterialCardView(this).apply {
            radius = dp(28).toFloat()
            cardElevation = 0f
            setCardBackgroundColor(bg)
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        // EYEBROW
        box.addView(TextView(this).apply {
            text = "현재 보호 상태"
            setTextSizeSp(12f)
            setTextColor(fgMuted)
            letterSpacing = 0.1f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        // HEADLINE
        box.addView(TextView(this).apply {
            text = if (protecting) "${active}개 태그 잠금 중" else "잠금 없음"
            setTextSizeSp(24f)
            setTextColor(fg)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                .also { it.topMargin = dp(8); layoutParams = it }
        })
        // SUB
        box.addView(TextView(this).apply {
            text = "전체 ${tags.size}개 등록 · NFC로 해제 가능"
            setTextSizeSp(13f)
            setTextColor(fgMuted)
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                .also { it.topMargin = dp(4); layoutParams = it }
        })
        card.addView(box)
        return card
    }

    // ─── 시스템 상태 카드 ─────────────────────────────────────────

    private fun buildSystemStatusCard(): View {
        val tags = PrefsManager.getAllTags(this)
        val accessOk = isAccessibilityServiceEnabled()
        val ownerOk = DeviceAdminReceiver.isDeviceOwner(this)
        val active = tags.count { it.isActive }

        val card = filledCard()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        box.addView(eyebrow("시스템 상태"))
        box.addView(statusRow(PrefsManager.hasPin(this), "관리자 PIN 설정됨", null))
        box.addView(statusRow(tags.isNotEmpty(), "등록된 태그",
            if (tags.isEmpty()) "0개" else "${tags.size}개 (활성 ${active}개)"))
        box.addView(statusRow(accessOk, "접근성 서비스",
            if (accessOk) "작동 중" else "꺼짐"))
        box.addView(statusRow(ownerOk, "Device Owner",
            if (ownerOk) "활성화됨" else "ADB로 활성화 권장", warnIfFalse = true))
        card.addView(box)
        return card
    }

    private fun statusRow(ok: Boolean, label: String, sub: String?, warnIfFalse: Boolean = false): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        // 16dp 도트 — primary tone for ok, tertiary for warn, error for fail
        val dotColor = when {
            ok -> c(com.google.android.material.R.attr.colorPrimary)
            warnIfFalse -> c(com.google.android.material.R.attr.colorTertiary)
            else -> c(com.google.android.material.R.attr.colorError)
        }
        val dot = View(this).apply {
            background = circle(dotColor)
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                rightMargin = dp(12)
            }
        }
        row.addView(dot)
        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(this).apply {
            this.text = label; setTextSizeSp(14f)
            setTextColor(c(com.google.android.material.R.attr.colorOnSurface))
        })
        if (sub != null) text.addView(TextView(this).apply {
            this.text = sub; setTextSizeSp(12f)
            setTextColor(c(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        row.addView(text)
        return row
    }

    // ─── 섹션 헤더 (번호 칩) ──────────────────────────────────────

    private fun buildSectionHeader(num: String, title: String, subtitle: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                .also { it.topMargin = dp(28); it.bottomMargin = dp(12); layoutParams = it }
        }
        // Number chip
        val chip = TextView(this).apply {
            text = num
            setTextSizeSp(13f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(c(com.google.android.material.R.attr.colorOnPrimaryContainer))
            background = roundedDrawable(c(com.google.android.material.R.attr.colorPrimaryContainer), dp(14))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(12) }
        }
        row.addView(chip)

        val text = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        text.addView(TextView(this).apply {
            this.text = title; setTextSizeSp(16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(c(com.google.android.material.R.attr.colorOnSurface))
        })
        text.addView(TextView(this).apply {
            this.text = subtitle; setTextSizeSp(12f)
            setTextColor(c(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        row.addView(text)
        return row
    }

    // ─── PIN 카드 ─────────────────────────────────────────────────

    private fun buildPinCard(): View {
        val card = filledCard()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val hasPin = PrefsManager.hasPin(this)
        box.addView(TextView(this).apply {
            text = if (hasPin) "PIN이 설정되어 있습니다" else "앱 설정 진입 보호용 PIN을 설정하세요"
            setTextSizeSp(14f)
            setTextColor(c(com.google.android.material.R.attr.colorOnSurface))
        })
        box.addView(filledButton(if (hasPin) "PIN 변경" else "PIN 설정") {
            showPinSetupDialog()
        }.also { btn ->
            btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            btn.minimumHeight = dp(52)
        }.withMarginTop(dp(16)).fullWidth())
        card.addView(box)
        return card
    }

    // ─── 태그 목록 ────────────────────────────────────────────────

    private fun buildTagListSection(): View {
        val wrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tags = PrefsManager.getAllTags(this)

        if (tags.isEmpty()) {
            val empty = filledCard()
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(24), dp(20), dp(24))
            }
            box.addView(TextView(this).apply {
                text = "등록된 태그가 없어요"
                setTextSizeSp(14f)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                setTextColor(c(com.google.android.material.R.attr.colorOnSurface))
            })
            box.addView(TextView(this).apply {
                text = "아래 버튼으로 첫 태그를 추가하세요"
                setTextSizeSp(12f)
                setTextColor(c(com.google.android.material.R.attr.colorOnSurfaceVariant))
                (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                    .also { it.topMargin = dp(4); layoutParams = it }
            })
            empty.addView(box)
            wrap.addView(empty)
        } else {
            tags.forEach { wrap.addView(buildTagCard(it)) }
        }

        wrap.addView(filledButton("태그 추가") {
            requirePin {
                currentNfcMode = NfcMode.ADD_TAG
                showNfcBanner("등록할 NFC 태그를 갖다 대세요")
            }
        }.withMarginTop(dp(12)).fullWidth())

        return wrap
    }

    private fun buildTagCard(tag: PrefsManager.TagInfo): View {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = c(com.google.android.material.R.attr.colorOutlineVariant)
            setCardBackgroundColor(c(com.google.android.material.R.attr.colorSurfaceContainerLow))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        // Header row: leading dot + name + status pill
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val leading = View(this).apply {
            val color = if (tag.isActive) c(com.google.android.material.R.attr.colorPrimary)
            else c(com.google.android.material.R.attr.colorOutline)
            background = circle(color)
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(12) }
        }
        header.addView(leading)
        header.addView(TextView(this).apply {
            text = tag.name
            setTextSizeSp(16f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(c(com.google.android.material.R.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        header.addView(buildStatusPill(tag.isActive))
        box.addView(header)

        // Apps
        val appNames = tag.blockedApps.joinToString(", ") { pkg ->
            try {
                val info = packageManager.getApplicationInfo(pkg, 0)
                packageManager.getApplicationLabel(info).toString()
            } catch (e: Exception) { pkg }
        }
        box.addView(TextView(this).apply {
            text = if (tag.blockedApps.isEmpty()) "차단 앱 없음"
            else "차단 ${tag.blockedApps.size}개 · $appNames"
            setTextSizeSp(12f)
            setTextColor(c(com.google.android.material.R.attr.colorOnSurfaceVariant))
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                .also { it.topMargin = dp(8); it.leftMargin = dp(22); layoutParams = it }
        })

        // Action row
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
                .also { it.topMargin = dp(12); layoutParams = it }
        }
        actions.addView(filledButton("앱 수정") {
            currentNfcMode = NfcMode.AUTH_EDIT; pendingUid = tag.uid
            showNfcBanner("'${tag.name}' 태그를 갖다 대세요")
        }.also { btn ->
            btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            btn.minimumHeight = dp(44)
        }.withMarginEnd(dp(8)))
        actions.addView(outlinedButton(if (tag.isActive) "비활성화" else "활성화") {
            if (tag.isActive) {
                currentNfcMode = NfcMode.SHUTDOWN; pendingUid = tag.uid
                showNfcBanner("'${tag.name}' 태그를 갖다 대세요")
            } else {
                PrefsManager.setTagActive(this, tag.uid, true)
                showSnack("${tag.name} 활성화됨"); refreshUI()
            }
        }.also { btn ->
            btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            btn.minimumHeight = dp(44)
        }.withMarginEnd(dp(8)))
        actions.addView(outlinedButton("삭제") {
            currentNfcMode = NfcMode.AUTH_DELETE; pendingUid = tag.uid
            showNfcBanner("삭제할 '${tag.name}' 태그를 갖다 대세요")
        }.also { btn ->
            btn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            btn.minimumHeight = dp(44)
            btn.setTextColor(c(com.google.android.material.R.attr.colorError))
        })
        box.addView(actions)

        card.addView(box)
        return card
    }

    private fun buildStatusPill(active: Boolean): View {
        val bg = if (active) c(com.google.android.material.R.attr.colorPrimaryContainer)
        else c(com.google.android.material.R.attr.colorSurfaceContainerHigh)
        val fg = if (active) c(com.google.android.material.R.attr.colorOnPrimaryContainer)
        else c(com.google.android.material.R.attr.colorOnSurfaceVariant)
        return TextView(this).apply {
            text = if (active) "ON" else "OFF"
            setTextSizeSp(11f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(fg)
            background = roundedDrawable(bg, dp(100))
            setPadding(dp(10), dp(4), dp(10), dp(4))
            letterSpacing = 0.08f
        }
    }

    // ─── 셧다운 카드 ──────────────────────────────────────────────

    private fun buildShutdownCard(): View {
        val card = filledCard()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        box.addView(TextView(this).apply {
            text = "해제할 태그를 갖다 대면 해당 태그의 차단이 풀립니다."
            setTextSizeSp(13f)
            setTextColor(c(com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        box.addView(filledButton("태그로 잠금 해제") {
            currentNfcMode = NfcMode.SHUTDOWN; pendingUid = null
            showNfcBanner("해제할 태그를 갖다 대세요")
        }.withMarginTop(dp(16)).fullWidth())
        card.addView(box)
        return card
    }

    // ─── 접근성 카드 ──────────────────────────────────────────────

    private fun buildAccessibilityCard(): View {
        val card = filledCard()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        val isEnabled = isAccessibilityServiceEnabled()
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        row.addView(TextView(this).apply {
            text = if (isEnabled) "활성화됨 — 차단이 작동 중입니다"
            else "FocusLock → 켜기로 활성화하세요"
            setTextSizeSp(14f)
            setTextColor(c(com.google.android.material.R.attr.colorOnSurface))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        })
        row.addView(buildStatusPill(isEnabled))
        box.addView(row)
        if (!isEnabled) {
            box.addView(filledButton("접근성 설정 열기") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }.withMarginTop(dp(16)).fullWidth())
        }
        card.addView(box)
        return card
    }

    // ─── Device Owner 카드 ────────────────────────────────────────

    // ─── 다이얼로그 ───────────────────────────────────────────────

    private fun showTagNameInputDialog(uid: String) {
        val (wrap, input) = textInput("태그 이름 (예: 공부방, 카페)")
        MaterialAlertDialogBuilder(this)
            .setTitle("새 태그 이름")
            .setView(wrap)
            .setPositiveButton("다음") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) { showSnack("이름을 입력하세요"); return@setPositiveButton }
                PrefsManager.saveTag(this, uid, name, emptySet())
                showAppSelectionDialog(uid)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showAppSelectionDialog(uid: String) {
        val tag = PrefsManager.getTag(this, uid) ?: return
        val installed = getInstalledUserApps()
        val blocked = tag.blockedApps.toMutableSet()
        val appNames = installed.map { it.first }.toTypedArray()
        val pkgs = installed.map { it.second }
        val checked = BooleanArray(installed.size) { i -> pkgs[i] in blocked }

        MaterialAlertDialogBuilder(this)
            .setTitle("'${tag.name}' 차단 앱 선택")
            .setMultiChoiceItems(appNames, checked) { _, which, isChecked ->
                if (isChecked) blocked.add(pkgs[which]) else blocked.remove(pkgs[which])
            }
            .setPositiveButton("저장") { _, _ ->
                PrefsManager.updateBlockedApps(this, uid, blocked)
                showSnack("저장됐어요"); refreshUI()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun getInstalledUserApps(): List<Pair<String, String>> {
        val pm = packageManager
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter {
                try { pm.getLaunchIntentForPackage(it.packageName) != null }
                catch (e: Exception) { false }
            }
            .filter { it.packageName != packageName }
            .map { pm.getApplicationLabel(it).toString() to it.packageName }
            .sortedBy { it.first }
    }

    private fun requirePin(onSuccess: () -> Unit) {
        if (!PrefsManager.hasPin(this)) { onSuccess(); return }
        val (wrap, input) = textInput("PIN 입력", numeric = true)
        MaterialAlertDialogBuilder(this)
            .setTitle("PIN 확인")
            .setView(wrap)
            .setPositiveButton("확인") { _, _ ->
                if (PrefsManager.verifyPin(this, input.text.toString())) onSuccess()
                else showSnack("PIN이 틀렸어요")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun showPinSetupDialog() {
        val (wrap, input) = textInput("숫자 4~8자리", numeric = true)
        MaterialAlertDialogBuilder(this)
            .setTitle("PIN 설정")
            .setView(wrap)
            .setPositiveButton("저장") { _, _ ->
                val pin = input.text.toString()
                if (pin.length in 4..8) {
                    PrefsManager.setPin(this, pin)
                    showSnack("PIN 설정 완료"); refreshUI()
                } else showSnack("4~8자리 숫자를 입력하세요")
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val enabledServices = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val serviceName = ComponentName(
            this, AppBlockerAccessibilityService::class.java
        ).flattenToString()
        return enabledServices.contains(serviceName)
    }
    // ─── 컴포넌트 팩토리 ──────────────────────────────────────────

    private fun filledCard(): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(16).toFloat()
        cardElevation = 0f
        setCardBackgroundColor(c(com.google.android.material.R.attr.colorSurfaceContainer))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun filledButton(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonStyle).apply {
            text = label
            cornerRadius = dp(20)
            setOnClickListener { onClick() }
        }

    private fun tonalButton(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null,
            com.google.android.material.R.style.Widget_Material3_Button_TonalButton).apply {
            text = label
            cornerRadius = dp(20)
            setOnClickListener { onClick() }
            setBackgroundColor(MaterialColors.getColor(this.context, com.google.android.material.R.attr.colorSecondaryContainer, Color.LTGRAY))
            setTextColor(MaterialColors.getColor(this.context, com.google.android.material.R.attr.colorOnSecondaryContainer, Color.BLACK))
        }

    private fun outlinedButton(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            cornerRadius = dp(20)
            setOnClickListener { onClick() }
        }

    private fun textButton(label: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(this, null,
            com.google.android.material.R.attr.borderlessButtonStyle).apply {
            text = label
            setTextColor(c(com.google.android.material.R.attr.colorPrimary))
            setOnClickListener { onClick() }
        }

    private fun textInput(hint: String, numeric: Boolean = false): Pair<View, TextInputEditText> {
        val til = TextInputLayout(this,
            null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            this.hint = hint
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
        }
        val edit = TextInputEditText(til.context).apply {
            if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        til.addView(edit)
        val wrap = FrameLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(til)
        }
        return wrap to edit
    }
    private fun eyebrow(label: String): TextView = TextView(this).apply {
        text = label
        setTextSizeSp(11f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(c(com.google.android.material.R.attr.colorOnSurfaceVariant))
        letterSpacing = 0.12f
        (layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            .also { it.bottomMargin = dp(12); layoutParams = it }
    }

    // ─── 색/도형 헬퍼 ─────────────────────────────────────────────

    private fun c(attr: Int): Int = MaterialColors.getColor(this, attr, Color.MAGENTA)

    private fun roundedDrawable(fill: Int, radiusPx: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radiusPx.toFloat()
            setColor(fill)
        }

    private fun circle(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    // ─── 단위 변환 / 확장 ─────────────────────────────────────────

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(),
            resources.displayMetrics).toInt()

    private fun TextView.setTextSizeSp(v: Float) =
        setTextSize(TypedValue.COMPLEX_UNIT_SP, v)

    private fun <T : View> T.withMarginTop(top: Int): T = apply {
        val lp = (layoutParams as? ViewGroup.MarginLayoutParams)
            ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        if (lp is ViewGroup.MarginLayoutParams) lp.topMargin = top
        layoutParams = lp
    }

    private fun <T : View> T.withMarginEnd(end: Int): T = apply {
        val lp = (layoutParams as? ViewGroup.MarginLayoutParams)
            ?: LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT)
        if (lp is ViewGroup.MarginLayoutParams) lp.rightMargin = end
        layoutParams = lp
    }

    private fun <T : View> T.fullWidth(): T = apply {
        val lp = (layoutParams as? ViewGroup.MarginLayoutParams)
            ?: ViewGroup.MarginLayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        layoutParams = lp
    }
}