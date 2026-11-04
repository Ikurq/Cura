package com.example.voicevox

import android.content.Context
import android.graphics.Bitmap
import androidx.core.content.edit
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder

object QrAlarmHelper {

    /**
     * 登録されている解除用QRコード文字列を取得します。
     * 未設定の場合はデフォルト値（CURA_DISMISS_ALARM）を返します。
     */
    fun getExpectedQrValue(context: Context): String {
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
        return prefs.getString(CuraConstants.KEY_ALARM_DISMISS_QR, CuraConstants.DEFAULT_ALARM_DISMISS_QR)
            ?.takeIf { it.isNotBlank() } ?: CuraConstants.DEFAULT_ALARM_DISMISS_QR
    }

    /**
     * 解除用QRコード文字列を保存します。
     */
    fun setExpectedQrValue(context: Context, value: String) {
        val prefs = context.getSharedPreferences(CuraConstants.PREFS_ALARM, Context.MODE_PRIVATE)
        prefs.edit {
            putString(CuraConstants.KEY_ALARM_DISMISS_QR, value.trim())
        }
    }

    /**
     * スキャンされた文字列が期待されるQRコードと一致するか判定します。
     */
    fun isQrMatch(scanned: String?, expected: String): Boolean {
        if (scanned.isNullOrBlank() || expected.isBlank()) {
            return false
        }
        return scanned.trim() == expected.trim()
    }

    /**
     * 任意の文字列からQRコードのBitmap画像を生成します。
     */
    fun generateQrBitmap(content: String, size: Int = 512): Bitmap? {
        if (content.isBlank()) return null
        return try {
            val barcodeEncoder = BarcodeEncoder()
            barcodeEncoder.encodeBitmap(content, BarcodeFormat.QR_CODE, size, size)
        } catch (e: Exception) {
            android.util.Log.e("QrAlarmHelper", "Failed to generate QR bitmap", e)
            null
        }
    }
}
