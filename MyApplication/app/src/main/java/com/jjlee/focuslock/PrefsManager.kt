package com.jjlee.focuslock

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * SharedPreferences 관리자 - 동적 태그 구조
 *
 * 수정 내역:
 * 6. removeTag() → commit() 사용으로 동기 처리 (세션 잔존 방지)
 * 7. 백업/복구 기능 추가, JSON 파싱 오류 시 백업에서 복구
 */
object PrefsManager {

    private const val PREF_NAME = "focus_lock_prefs"
    private const val PREF_BACKUP_NAME = "focus_lock_prefs_backup"  // 7번: 백업용
    private const val KEY_PIN = "pin"
    private const val KEY_TAG_LIST = "tag_list"
    private const val KEY_TAG_PREFIX = "tag_"
    private const val KEY_SESSION_PREFIX = "session_"
    private const val KEY_ACTIVE_PREFIX = "active_"

    const val SESSION_DURATION_MS = 30 * 60 * 1000L

    data class TagInfo(
        val uid: String,
        val name: String,
        val blockedApps: Set<String>,
        val isActive: Boolean
    )

    // ─── 태그 목록 관리 ───────────────────────────────────────────

    fun getAllTagUids(context: Context): List<String> {
        val json = prefs(context).getString(KEY_TAG_LIST, "[]") ?: "[]"
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            // 7번: 파싱 실패 시 백업에서 복구 시도
            recoverFromBackup(context)
            emptyList()
        }
    }

    fun getAllTags(context: Context): List<TagInfo> {
        return getAllTagUids(context).mapNotNull { getTag(context, it) }
    }

    fun getTag(context: Context, uid: String): TagInfo? {
        val json = prefs(context).getString("$KEY_TAG_PREFIX$uid", null) ?: return null
        return try {
            val obj = JSONObject(json)
            val appsArr = obj.getJSONArray("blocked_apps")
            val apps = (0 until appsArr.length()).map { appsArr.getString(it) }.toSet()
            TagInfo(
                uid = uid,
                name = obj.getString("name"),
                blockedApps = apps,
                isActive = prefs(context).getBoolean("$KEY_ACTIVE_PREFIX$uid", true)
            )
        } catch (e: Exception) {
            // 7번: 개별 태그 파싱 실패 시 null 반환 (전체 날아가지 않음)
            null
        }
    }

    fun hasTag(context: Context, uid: String): Boolean {
        return prefs(context).contains("$KEY_TAG_PREFIX$uid")
    }

    fun saveTag(context: Context, uid: String, name: String, blockedApps: Set<String>) {
        val obj = JSONObject().apply {
            put("name", name)
            put("blocked_apps", JSONArray(blockedApps.toList()))
        }
        val editor = prefs(context).edit()
        editor.putString("$KEY_TAG_PREFIX$uid", obj.toString())

        val uids = getAllTagUids(context).toMutableList()
        if (uid !in uids) {
            uids.add(uid)
            editor.putString(KEY_TAG_LIST, JSONArray(uids).toString())
        }
        if (!prefs(context).contains("$KEY_ACTIVE_PREFIX$uid")) {
            editor.putBoolean("$KEY_ACTIVE_PREFIX$uid", true)
        }
        editor.apply()

        // 7번: 저장 후 백업
        backupData(context)
    }

    /**
     * 6번: commit()으로 동기 처리 → 세션 잔존 방지
     * apply()는 비동기라 태그 삭제 직후 세션이 남아있을 수 있음
     */
    fun removeTag(context: Context, uid: String) {
        val tag = getTag(context, uid)
        val uids = getAllTagUids(context).toMutableList()
        uids.remove(uid)

        val editor = prefs(context).edit()
        editor.putString(KEY_TAG_LIST, JSONArray(uids).toString())
        editor.remove("$KEY_TAG_PREFIX$uid")
        editor.remove("$KEY_ACTIVE_PREFIX$uid")

        // 6번: 세션도 동기적으로 즉시 삭제
        tag?.blockedApps?.forEach { pkg ->
            editor.remove("$KEY_SESSION_PREFIX${uid}_$pkg")
        }
        editor.commit()  // 6번: apply() → commit() (동기 처리)

        // 7번: 삭제 후 백업
        backupData(context)
    }

    fun updateBlockedApps(context: Context, uid: String, apps: Set<String>) {
        val tag = getTag(context, uid) ?: return
        saveTag(context, uid, tag.name, apps)
    }

    fun renameTag(context: Context, uid: String, newName: String) {
        val tag = getTag(context, uid) ?: return
        saveTag(context, uid, newName, tag.blockedApps)
    }

    // ─── 활성화 상태 ──────────────────────────────────────────────

    fun isTagActive(context: Context, uid: String): Boolean {
        return prefs(context).getBoolean("$KEY_ACTIVE_PREFIX$uid", true)
    }

    fun setTagActive(context: Context, uid: String, active: Boolean) {
        prefs(context).edit().putBoolean("$KEY_ACTIVE_PREFIX$uid", active).commit()  // 6번: commit
        if (!active) clearTagSessions(context, uid)
    }

    // ─── 차단 앱 조회 ─────────────────────────────────────────────

    fun getActiveTagsForApp(context: Context, packageName: String): List<String> {
        return getAllTags(context)
            .filter { it.isActive && packageName in it.blockedApps }
            .map { it.uid }
    }

    fun getAllBlockedApps(context: Context): Set<String> {
        return getAllTags(context)
            .filter { it.isActive }
            .flatMap { it.blockedApps }
            .toSet()
    }

    // ─── 세션 관리 ────────────────────────────────────────────────

    fun isSessionActive(context: Context, uid: String, packageName: String): Boolean {
        val expiry = prefs(context).getLong("$KEY_SESSION_PREFIX${uid}_$packageName", 0L)
        return System.currentTimeMillis() < expiry
    }

    fun hasAnySessionForApp(context: Context, packageName: String): Boolean {
        return getActiveTagsForApp(context, packageName).any { uid ->
            isSessionActive(context, uid, packageName)
        }
    }

    fun startSession(context: Context, uid: String, packageName: String) {
        val expiry = System.currentTimeMillis() + SESSION_DURATION_MS
        prefs(context).edit()
            .putLong("$KEY_SESSION_PREFIX${uid}_$packageName", expiry)
            .apply()
    }

    fun clearTagSessions(context: Context, uid: String) {
        val tag = getTag(context, uid) ?: return
        val editor = prefs(context).edit()
        tag.blockedApps.forEach { pkg ->
            editor.remove("$KEY_SESSION_PREFIX${uid}_$pkg")
        }
        editor.commit()  // 6번: commit으로 즉시 반영
    }

    fun clearAllSessions(context: Context) {
        getAllTagUids(context).forEach { clearTagSessions(context, it) }
    }

    // ─── PIN ──────────────────────────────────────────────────────

    fun getPin(context: Context): String? =
        prefs(context).getString(KEY_PIN, null)

    fun setPin(context: Context, pin: String) =
        prefs(context).edit().putString(KEY_PIN, pin).apply()

    fun hasPin(context: Context) = getPin(context) != null

    fun verifyPin(context: Context, input: String) = getPin(context) == input

    // ─── 7번: 백업/복구 ───────────────────────────────────────────

    /**
     * 현재 데이터를 백업 SharedPreferences에 저장
     * saveTag, removeTag 호출 후 자동 실행
     */
    private fun backupData(context: Context) {
        try {
            val src = prefs(context).all
            val backup = context.getSharedPreferences(PREF_BACKUP_NAME, Context.MODE_PRIVATE).edit()
            src.forEach { (key, value) ->
                when (value) {
                    is String -> backup.putString(key, value)
                    is Boolean -> backup.putBoolean(key, value)
                    is Long -> backup.putLong(key, value)
                    is Int -> backup.putInt(key, value)
                    else -> {}
                }
            }
            backup.apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 메인 데이터 손상 시 백업에서 복구
     */
    private fun recoverFromBackup(context: Context) {
        try {
            val backup = context.getSharedPreferences(PREF_BACKUP_NAME, Context.MODE_PRIVATE).all
            if (backup.isEmpty()) return

            val editor = prefs(context).edit()
            backup.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    else -> {}
                }
            }
            editor.commit()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ─── 내부 헬퍼 ────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}