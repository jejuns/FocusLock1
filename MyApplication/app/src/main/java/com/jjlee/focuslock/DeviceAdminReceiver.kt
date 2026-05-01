package com.jjlee.focuslock

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.UserManager

/**
 * 기기 관리자 수신기
 *
 * Device Admin: 기기 관리자 앱으로 등록 (설정에서 수동 활성화)
 * Device Owner: ADB로 설정 시 추가 제한 적용 가능
 *   - 공장 초기화 차단
 *   - 안전 모드 부팅 차단
 *   - ADB 디버깅 차단
 *   - 앱 삭제 차단
 */
class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        applyOwnerRestrictions(context)
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "FocusLock을 비활성화하면 앱 차단이 해제됩니다. 정말 비활성화하시겠습니까?"
    }

    companion object {

        fun getComponentName(context: Context): ComponentName =
            ComponentName(context, DeviceAdminReceiver::class.java)

        /**
         * Device Owner일 때만 적용되는 강력한 제한
         * ADB 명령어로 Device Owner 설정 필요:
         * adb shell dpm set-device-owner com.jjlee.focuslock/.DeviceAdminReceiver
         */
        fun applyOwnerRestrictions(context: Context) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = getComponentName(context)

            if (!dpm.isDeviceOwnerApp(context.packageName)) return

            try {
                // 공장 초기화 차단
                dpm.addUserRestriction(admin, UserManager.DISALLOW_FACTORY_RESET)

                // 안전 모드 부팅 차단
                dpm.addUserRestriction(admin, UserManager.DISALLOW_SAFE_BOOT)

                // 이 앱 삭제 차단
                dpm.setUninstallBlocked(admin, context.packageName, true)

                // ADB 디버깅 차단 (선택사항 - 너무 강력할 수 있음)
                // dpm.addUserRestriction(admin, UserManager.DISALLOW_DEBUGGING_FEATURES)

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isDeviceOwnerApp(context.packageName)
        }

        fun isDeviceAdmin(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isAdminActive(getComponentName(context))
        }
    }
}
