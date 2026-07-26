package jp.voicevox.android

import android.content.Context
import android.content.SharedPreferences

/**
 * モデル利用規約への同意状態を管理するゲート。
 *
 * モデルのダウンロード・ロードは、対象モデルに対して [accept] 済みでない限り
 * [VoicevoxException.LicenseNotAccepted] で拒否される。
 * 同意状態は (modelId, termsVersion) の組で永続化されるため、
 * 利用規約のバージョンが上がると再同意が必要になる。
 */
class LicenseGate internal constructor(
    private val prefs: SharedPreferences,
    private val termsVersion: String,
) {
    internal constructor(context: Context, termsVersion: String) : this(
        context.getSharedPreferences("jp.voicevox.vv-mobile.licenses", Context.MODE_PRIVATE),
        termsVersion,
    )

    private fun storageValue(modelId: String) = "$modelId@$termsVersion"

    /**
     * モデルの利用規約に同意する。
     * アプリ側は必ず利用者に規約(termsURL)を提示した上で呼ぶこと。
     */
    fun accept(modelId: String) {
        val set = (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
        set.add(storageValue(modelId))
        prefs.edit().putStringSet(KEY, set).apply()
    }

    /** 同意を取り消す。 */
    fun revoke(modelId: String) {
        val set = (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).toMutableSet()
        set.remove(storageValue(modelId))
        prefs.edit().putStringSet(KEY, set).apply()
    }

    /** 現在の規約バージョンで同意済みか。 */
    fun isAccepted(modelId: String): Boolean =
        (prefs.getStringSet(KEY, emptySet()) ?: emptySet()).contains(storageValue(modelId))

    /** 未同意なら [VoicevoxException.LicenseNotAccepted] を投げる。 */
    internal fun require(modelId: String) {
        if (!isAccepted(modelId)) throw VoicevoxException.LicenseNotAccepted(modelId)
    }

    private companion object {
        const val KEY = "acceptedLicenses"
    }
}
