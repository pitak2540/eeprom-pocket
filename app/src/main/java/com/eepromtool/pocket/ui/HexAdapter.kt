package com.eepromtool.pocket.ui

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.util.TypedValue
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HexAdapter(
    private val bytesPerRow: Int,
    private val onLongPress: (row: Int) -> Unit
) : RecyclerView.Adapter<HexAdapter.VH>() {

    var data: ByteArray = ByteArray(0)
        @SuppressLint("NotifyDataSetChanged")
        set(v) { field = v; notifyDataSetChanged() }

    class VH(val tv: TextView) : RecyclerView.ViewHolder(tv)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val tv = TextView(parent.context)
        tv.typeface = Typeface.MONOSPACE
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
        tv.setPadding(8, 3, 8, 3)
        tv.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        tv.isLongClickable = true
        return VH(tv)
    }

    override fun getItemCount() = (data.size + bytesPerRow - 1) / bytesPerRow

    override fun onBindViewHolder(holder: VH, position: Int) {
        val start = position * bytesPerRow
        val end = minOf(start + bytesPerRow, data.size)
        val sb = StringBuilder(bytesPerRow * 4 + 10)
        sb.append("%06X ".format(start))
        for (i in start until start + bytesPerRow) {
            if (i < end) sb.append(HEX[data[i].toInt() and 0xFF]).append(' ') else sb.append("   ")
        }
        sb.append(' ')
        for (i in start until end) {
            val c = data[i].toInt() and 0xFF
            sb.append(if (c in 0x20..0x7E) c.toChar() else '.')
        }
        holder.tv.text = sb
        holder.tv.setOnLongClickListener { onLongPress(holder.bindingAdapterPosition); true }
    }

    companion object {
        private val HEX = Array(256) { "%02X".format(it) }
    }
}
