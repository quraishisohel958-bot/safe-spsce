package com.safespace.sandbox

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppsAdapter(
    private val items: List<Item>,
    private val onOpen: (Item) -> Unit,
    private val onFreeze: (Item) -> Unit,
    private val onUninstall: (Item) -> Unit
) : RecyclerView.Adapter<AppsAdapter.VH>() {

    data class Item(
        val pkg: String,
        val label: String,
        val version: String,
        val icon: Drawable?,
        val hidden: Boolean
    )

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.imgIcon)
        val name: TextView = v.findViewById(R.id.txtName)
        val pkg: TextView = v.findViewById(R.id.txtPkg)
        val status: TextView = v.findViewById(R.id.txtStatus)
        val open: Button = v.findViewById(R.id.btnOpen)
        val freeze: Button = v.findViewById(R.id.btnFreeze)
        val del: Button = v.findViewById(R.id.btnUninstall)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false))

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val item = items[pos]
        h.icon.setImageDrawable(item.icon)
        h.name.text = item.label
        h.pkg.text = item.pkg
        h.status.text = if (item.hidden) "❄️ FROZEN (chhupa hua)" else "v${item.version}"
        h.open.isEnabled = !item.hidden
        h.open.alpha = if (item.hidden) 0.4f else 1f
        h.freeze.text = if (item.hidden) "Unfreeze" else "Freeze"
        h.open.setOnClickListener { onOpen(item) }
        h.freeze.setOnClickListener { onFreeze(item) }
        h.del.setOnClickListener { onUninstall(item) }
    }
}
