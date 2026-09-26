package io.github.akarimarisa.tumblrdownloader.utils

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.StringRes
import io.github.akarimarisa.tumblrdownloader.R

/** Shows a toast using the language selected in the app settings. */
object LocalizedToast {

    fun show(context: Context, text: CharSequence, duration: Int) {
        val localizedContext = LocaleHelper.contextForAppLocale(context)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ resolves the standard toast attribution using the
            // platform per-app locale synchronized by LocaleHelper.
            Toast.makeText(localizedContext, text, duration).show()
            return
        }

        // Android 12 and earlier resolve standard-toast attribution using the
        // system locale. Include the localized app name in a custom toast so
        // the complete visible message follows the app language.
        val textView = TextView(localizedContext).apply {
            this.text = localizedContext.getString(
                R.string.toast_message_with_app_name,
                localizedContext.getString(R.string.app_name),
                text
            )
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            background = GradientDrawable().apply {
                setColor(Color.rgb(48, 48, 48))
                cornerRadius = dp(24).toFloat()
            }
        }
        Toast(localizedContext).apply {
            this.duration = duration
            view = textView
            setGravity(Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL, 0, dp(64))
        }.show()
    }

    fun show(context: Context, @StringRes resId: Int, duration: Int) {
        val localizedContext = LocaleHelper.contextForAppLocale(context)
        show(localizedContext, localizedContext.getString(resId), duration)
    }

    private fun dp(value: Int): Int =
        (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
