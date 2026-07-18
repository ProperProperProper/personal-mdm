package com.pahtrik.warden

import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

/**
 * Dead-end handler for http/https links. Shows the URL as inert plain text —
 * nothing is fetched, nothing opens. Forced as the link handler by
 * persistent-preferred-activity policy when Link Guard is on.
 */
class LinkGuardActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.data?.toString() ?: "(no URL)"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(64, 64, 64, 64)
        }
        root.addView(TextView(this).apply {
            text = "Link blocked by Personal MDM"
            textSize = 20f
        })
        root.addView(TextView(this).apply {
            text = url
            textSize = 14f
            setPadding(0, 32, 0, 0)
            setTextIsSelectable(true) // copyable, never openable
        })
        root.addView(TextView(this).apply {
            text = "Nothing was fetched. To visit intentionally, type the address in Chrome yourself."
            textSize = 12f
            setPadding(0, 32, 0, 0)
        })
        setContentView(root)
    }
}
