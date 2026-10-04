package com.mag.docswipe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentRulesTest {
    @Test
    fun supportedFormatsAreAcceptedCaseInsensitively() {
        assertTrue(DocumentRules.isSupportedFile("report.PDF", 1))
        assertTrue(DocumentRules.isSupportedFile("report.docx", 42))
        assertTrue(DocumentRules.isSupportedFile("table.XLSX", 42))
        assertTrue(DocumentRules.isSupportedFile("slides.pptx", 42))
        assertTrue(DocumentRules.isSupportedFile("notes.txt", 42))
        assertTrue(DocumentRules.isSupportedFile("data.csv", 42))
    }

    @Test
    fun zeroByteAndUnknownFilesAreRejected() {
        assertFalse(DocumentRules.isSupportedFile("empty.pdf", 0))
        assertFalse(DocumentRules.isSupportedFile("archive.zip", 100))
        assertFalse(DocumentRules.isSupportedFile("README", 100))
    }

    @Test
    fun systemAndHiddenDirectoriesAreExcludedAsExpected() {
        assertTrue(DocumentRules.isExcludedSystemDirectory("Android"))
        assertTrue(DocumentRules.isExcludedSystemDirectory("DATA"))
        assertTrue(DocumentRules.isExcludedSystemDirectory("obb"))
        assertFalse(DocumentRules.isExcludedSystemDirectory("Documents"))
        assertTrue(DocumentRules.isHiddenDirectory(".private"))
        assertFalse(DocumentRules.isHiddenDirectory("Downloads"))
    }
}
