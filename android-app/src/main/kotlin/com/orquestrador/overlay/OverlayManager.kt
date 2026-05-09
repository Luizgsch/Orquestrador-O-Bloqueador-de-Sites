package com.orquestrador.overlay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.orquestrador.MainActivity

object OverlayManager {

    private var overlayView: View? = null

    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun show(context: Context) {
        if (overlayView != null) return
        val appCtx = context.applicationContext
        if (!canDrawOverlays(appCtx)) return

        Handler(Looper.getMainLooper()).post {
            val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val root = buildOverlayView(appCtx)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.OPAQUE,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
            }
            try {
                wm.addView(root, params)
                overlayView = root
            } catch (_: Exception) {}
        }
    }

    fun dismiss(context: Context) {
        val view = overlayView ?: return
        val appCtx = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            try {
                val wm = appCtx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                wm.removeView(view)
            } catch (_: Exception) {}
            overlayView = null
        }
    }

    private fun buildOverlayView(context: Context): View {
        val root = object : LinearLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) return true
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#F00D1117"))
            setPadding(80, 80, 80, 80)
        }

        TextView(context).apply {
            text = "⚠ PROTEÇÃO DESATIVADA"
            textSize = 22f
            setTextColor(Color.parseColor("#FF4444"))
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }.also { root.addView(it) }

        TextView(context).apply {
            text = "\nA VPN foi revogada pelo sistema.\nO Modo Implacável não está ativo.\n"
            textSize = 15f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
        }.also { root.addView(it) }

        Button(context).apply {
            text = "REATIVAR PROTEÇÃO"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1A6B3C"))
            setOnClickListener {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .setAction(MainActivity.ACTION_REACTIVATE_VPN)
                        .addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    Intent.FLAG_ACTIVITY_CLEAR_TOP,
                        ),
                )
            }
        }.also { root.addView(it) }

        return root
    }
}
