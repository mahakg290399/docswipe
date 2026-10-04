package com.mag.docswipe

import java.util.Locale

internal object DocumentRules {
    val supportedExtensions = setOf("pdf", "docx", "xlsx", "pptx", "txt", "csv", "epub", "cbz")

    fun isSupportedFile(name: String, size: Long): Boolean {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return size > 0 && extension in supportedExtensions
    }

    fun isExcludedSystemDirectory(name: String): Boolean =
        name.equals("Android", ignoreCase = true) ||
            name.equals("data", ignoreCase = true) ||
            name.equals("obb", ignoreCase = true)

    fun isHiddenDirectory(name: String): Boolean = name.startsWith('.')
}
