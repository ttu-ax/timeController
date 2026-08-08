package com.example.procrastinationgate

data class AppEntry(val label: String, val packageName: String) {
    override fun toString(): String = label
}
