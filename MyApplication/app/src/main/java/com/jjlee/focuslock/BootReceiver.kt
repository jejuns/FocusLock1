package com.jjlee.focuslock

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 부팅 완료 수신기
 * 폰이 재부팅되면 Device Owner 제한을 다시 적용
 * 접근성 서비스는 Android가 자동으로 재시작하므로 별도 처리 불필요
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.LOCKED_BOOT_COMPLETED" -> {
                // Device Owner 제한 재적용 (재부팅 후에도 유지)
                DeviceAdminReceiver.applyOwnerRestrictions(context)

                // 모든 임시 세션 초기화 (재부팅 후 세션 리셋)
                PrefsManager.clearAllSessions(context)
            }
        }
    }
}
