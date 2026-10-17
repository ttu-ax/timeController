package com.example.procrastinationgate

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.icu.text.AlphabeticIndex
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
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
            setPadding(dp(activity, 16), dp(activity, 16), dp(activity, 8), dp(activity, 12))
            setBackgroundColor(UiTheme.canvas)
        }

        // 搜索框
        val search = EditText(activity).apply {
            hint = "搜索应用名称或包名"
            setSingleLine(true)
            textSize = 15f
            setTextColor(UiTheme.ink)
            setHintTextColor(UiTheme.muted)
            background = UiTheme.shape(activity, UiTheme.surface, 14, UiTheme.line)
            setPadding(dp(activity, 14), 0, dp(activity, 14), 0)
            setCompoundDrawablesWithIntrinsicBounds(android.R.drawable.ic_menu_search, 0, 0, 0)
            compoundDrawablePadding = dp(activity, 10)
        }
        root.addView(search, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            dp(activity, 48)
        ).apply { bottomMargin = dp(activity, 10) })

        val body = FrameLayout(activity)
        val list = ListView(activity).apply {
            dividerHeight = 0
            setPadding(0, 0, dp(activity, 28), 0)
            clipToPadding = false
            selector = UiTheme.ripple(activity, Color.TRANSPARENT, UiTheme.accentSoft, 12)
        }
        val adapter = AppListAdapter(activity, filtered)
        list.adapter = adapter
        body.addView(list, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // 右侧字母快速索引
        val index = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(activity, 2), 0, dp(activity, 2), 0)
        }
        letters.forEach { letter ->
            index.addView(TextView(activity).apply {
                text = letter
                textSize = 10f
                gravity = Gravity.CENTER
                setTextColor(UiTheme.muted)
                typeface = Typeface.create("sans-serif", Typeface.BOLD)
                setOnClickListener {
                    val position = filtered.indexOfFirst { sectionOf(it.label) == letter }
                    if (position >= 0) list.setSelection(position)
                }
            }, LinearLayout.LayoutParams(dp(activity, 22), 0, 1f))
        }
        body.addView(index, FrameLayout.LayoutParams(
            dp(activity, 26),
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
            dialog.window?.apply {
                setBackgroundDrawable(UiTheme.shape(activity, UiTheme.canvas, 22))
                setLayout(
                    (activity.resources.displayMetrics.widthPixels * 0.92f).toInt(),
                    (activity.resources.displayMetrics.heightPixels * 0.85f).toInt()
                )
            }
        }
        dialog.show()
    }

    private class AppListAdapter(
        private val activity: Activity,
        items: List<AppEntry>
    ) : ArrayAdapter<AppEntry>(activity, 0, items.toMutableList()) {
        private val data = items.toMutableList()

        fun replace(items: List<AppEntry>) {
            data.clear()
            data.addAll(items)
            clear()
            addAll(items)
            notifyDataSetChanged()
        }

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val item = getItem(position)!!
            val currentSection = sectionOf(item.label)
            val showSection = position == 0 || sectionOf(getItem(position - 1)!!.label) != currentSection

            val container = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
            }

            if (showSection) {
                val sectionHeader = LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(activity, 4), dp(activity, 10), dp(activity, 8), dp(activity, 4))
                }
                val badge = TextView(activity).apply {
                    text = currentSection
                    textSize = 12f
                    setTextColor(Color.rgb(28, 122, 72))
                    typeface = Typeface.create("sans-serif", Typeface.BOLD)
                    setPadding(dp(activity, 8), dp(activity, 2), dp(activity, 8), dp(activity, 2))
                    background = UiTheme.shape(activity, UiTheme.accentSoft, UiTheme.RADIUS_PILL)
                }
                sectionHeader.addView(badge)
                sectionHeader.addView(View(activity).apply {
                    setBackgroundColor(UiTheme.line)
                }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(activity, 1)).apply {
                    marginStart = dp(activity, 8)
                })
                container.addView(sectionHeader)
            }

            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(activity, 4), dp(activity, 8), dp(activity, 8), dp(activity, 8))
            }

            // 真实 App 图标
            val appIcon = UiTheme.createAppIconView(activity, item.packageName, item.label, 38, 10)
            row.addView(appIcon, LinearLayout.LayoutParams(dp(activity, 38), dp(activity, 38)).apply {
                rightMargin = dp(activity, 12)
            })

            val textStack = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                val title = TextView(activity).apply {
                    text = item.label
                    textSize = 15f
                    setTextColor(UiTheme.ink)
                    typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
                    includeFontPadding = false
                }
                val subtitle = TextView(activity).apply {
                    text = item.packageName
                    textSize = 11f
                    setTextColor(UiTheme.muted)
                    setSingleLine(true)
                    includeFontPadding = false
                }
                addView(title)
                addView(subtitle, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(activity, 2) })
            }
            row.addView(textStack, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            container.addView(row)
            return container
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
