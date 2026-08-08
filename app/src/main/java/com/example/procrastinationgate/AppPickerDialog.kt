package com.example.procrastinationgate

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.icu.text.AlphabeticIndex
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.Collator
import java.util.Locale

object AppPickerDialog {
    private val letters = ('A'..'Z').map(Char::toString) + "#"
    private val chineseIndex = AlphabeticIndex<String>(Locale.CHINA)
        .addLabels(Locale.ENGLISH)
        .buildImmutableIndex()

    fun show(
        activity: Activity,
        title: String,
        entries: List<AppEntry>,
        onSelected: (AppEntry) -> Unit
    ) {
        val sorted = entries.sortedWith(
            compareBy<AppEntry> { sectionRank(sectionOf(it.label)) }
                .thenComparator { a, b ->
                    Collator.getInstance(Locale.CHINA).compare(a.label, b.label)
                }
        )
        var filtered = sorted

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 16), dp(activity, 8), dp(activity, 8), dp(activity, 8))
            setBackgroundColor(UiTheme.canvas)
        }
        val search = EditText(activity).apply {
            hint = "搜索 App 名称"
            setSingleLine(true)
            textSize = 16f
            setTextColor(UiTheme.ink)
            setHintTextColor(UiTheme.muted)
            background = UiTheme.shape(activity, UiTheme.surface, 14, UiTheme.line)
            setPadding(dp(activity, 14), 0, dp(activity, 14), 0)
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0)
            compoundDrawablePadding = dp(activity, 8)
        }
        root.addView(search, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(activity, 52)
        ))

        val body = FrameLayout(activity)
        val list = ListView(activity).apply {
            dividerHeight = 0
            setPadding(0, 0, dp(activity, 28), 0)
            clipToPadding = false
        }
        val adapter = AppListAdapter(activity, filtered)
        list.adapter = adapter
        body.addView(list, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        val index = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 3), 0, dp(activity, 3), 0)
        }
        letters.forEach { letter ->
            index.addView(TextView(activity).apply {
                text = letter
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(UiTheme.inkSoft)
                setOnClickListener {
                    val position = filtered.indexOfFirst { sectionOf(it.label) == letter }
                    if (position >= 0) list.setSelection(position)
                }
            }, LinearLayout.LayoutParams(dp(activity, 24), 0, 1f))
        }
        body.addView(index, FrameLayout.LayoutParams(
            dp(activity, 30),
            FrameLayout.LayoutParams.MATCH_PARENT,
            Gravity.END
        ))
        root.addView(body, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            0,
            1f
        ))

        val dialog = AlertDialog.Builder(activity)
            .setTitle(title)
            .setView(root)
            .setNegativeButton("取消", null)
            .create()

        list.setOnItemClickListener { _, _, position, _ ->
            onSelected(filtered[position])
            dialog.dismiss()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val keyword = s?.toString()?.trim().orEmpty()
                filtered = if (keyword.isEmpty()) sorted else sorted.filter {
                    it.label.contains(keyword, ignoreCase = true) ||
                        it.packageName.contains(keyword, ignoreCase = true)
                }
                adapter.replace(filtered)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        dialog.setOnShowListener {
            dialog.window?.setLayout(
                (activity.resources.displayMetrics.widthPixels * 0.94f).toInt(),
                (activity.resources.displayMetrics.heightPixels * 0.86f).toInt()
            )
        }
        dialog.show()
    }

    private class AppListAdapter(
        activity: Activity,
        items: List<AppEntry>
    ) : ArrayAdapter<AppEntry>(activity, android.R.layout.simple_list_item_1, items.toMutableList()) {
        private val data = items.toMutableList()

        fun replace(items: List<AppEntry>) {
            data.clear()
            data.addAll(items)
            clear()
            addAll(items)
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = (convertView as? TextView) ?: TextView(context).apply {
                setPadding(dp(context as Activity, 14), dp(context as Activity, 12), 8, dp(context as Activity, 12))
                textSize = 16f
                setTextColor(UiTheme.ink)
            }
            val item = getItem(position)!!
            val currentSection = sectionOf(item.label)
            val showSection = position == 0 || sectionOf(getItem(position - 1)!!.label) != currentSection
            view.text = if (showSection) "$currentSection\n${item.label}" else item.label
            view.setLineSpacing(0f, 1.12f)
            return view
        }
    }

    private fun sectionOf(label: String): String {
        val normalized = label.trim()
        val char = normalized.firstOrNull() ?: return "#"
        if (char.uppercaseChar() in 'A'..'Z') return char.uppercaseChar().toString()
        val bucketIndex = chineseIndex.getBucketIndex(normalized)
        val bucketLabel = runCatching {
            chineseIndex.getBucket(bucketIndex).label.uppercase(Locale.ENGLISH)
        }.getOrNull()
        return bucketLabel?.takeIf { it.length == 1 && it[0] in 'A'..'Z' } ?: "#"
    }

    private fun sectionRank(section: String): Int =
        if (section == "#") 26 else section.first() - 'A'

    private fun dp(activity: Activity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
