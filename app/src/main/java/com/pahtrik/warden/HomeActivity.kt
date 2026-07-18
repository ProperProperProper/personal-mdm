package com.pahtrik.warden

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * Kiosk home screen: a full-screen grid of exactly the whitelisted apps.
 * Enforced as default HOME via addPersistentPreferredActivity when the
 * kiosk toggle is on. Back does nothing (it's the launcher).
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var grid: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        grid = RecyclerView(this)
        grid.layoutManager = GridLayoutManager(this, 3)
        grid.setPadding(24, 96, 24, 96)
        setContentView(grid)
    }

    override fun onResume() {
        super.onResume()
        val pm = packageManager
        val rows = Warden.whitelist(this)
            .filter { it != packageName }
            .mapNotNull { pkg ->
                pm.getLaunchIntentForPackage(pkg)?.let { intent ->
                    val ai = pm.getApplicationInfo(pkg, 0)
                    Entry(pm.getApplicationLabel(ai).toString(), pm.getApplicationIcon(ai), intent)
                }
            }
            .sortedBy { it.label.lowercase() }
            .toMutableList()
        // Settings entry into the MDM itself (biometric-gated there)
        rows.add(Entry("Personal MDM", packageManager.getApplicationIcon(packageName),
            Intent(this, MainActivity::class.java)))
        grid.adapter = HomeAdapter(rows)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { /* launcher: back is a no-op */ }

    data class Entry(val label: String, val icon: Drawable, val intent: Intent)

    inner class HomeAdapter(private val rows: List<Entry>) :
        RecyclerView.Adapter<HomeAdapter.VH>() {

        inner class VH(val root: LinearLayout, val icon: ImageView, val label: TextView) :
            RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val ctx = parent.context
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(16, 32, 16, 32)
                layoutParams = RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT,
                    RecyclerView.LayoutParams.WRAP_CONTENT
                )
            }
            val icon = ImageView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(160, 160)
            }
            val label = TextView(ctx).apply {
                gravity = Gravity.CENTER
                setPadding(0, 12, 0, 0)
            }
            col.addView(icon); col.addView(label)
            return VH(col, icon, label)
        }

        override fun getItemCount() = rows.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = rows[pos]
            h.icon.setImageDrawable(e.icon)
            h.label.text = e.label
            h.root.setOnClickListener { startActivity(e.intent) }
        }
    }
}
