package com.unhappychoice.droidflyer.infrastructure.preference

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.xpath.XPathConstants
import javax.xml.xpath.XPathFactory

class BackupRulesTest {
    @Test
    fun excludesApiCredentialsFromLegacyBackups() {
        val rules = rulesReferencedBy("fullBackupContent")
        assertCredentialExclusion(rules, "/full-backup-content")
    }

    @Test
    fun excludesApiCredentialsFromCloudBackups() {
        val rules = rulesReferencedBy("dataExtractionRules")
        assertCredentialExclusion(rules, "/data-extraction-rules/cloud-backup")
    }

    @Test
    fun excludesApiCredentialsFromDeviceTransfers() {
        val rules = rulesReferencedBy("dataExtractionRules")
        assertCredentialExclusion(rules, "/data-extraction-rules/device-transfer")
    }

    private fun rulesReferencedBy(attribute: String): File {
        val manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File("src/main/AndroidManifest.xml"))
        val application = manifest.getElementsByTagName("application").item(0)
        val reference = application.attributes.getNamedItem("android:$attribute").nodeValue
        return File("src/main/res/${reference.removePrefix("@")}.xml")
    }

    private fun assertCredentialExclusion(rules: File, section: String) {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(rules)
        val exclusion = "$section/exclude[@domain='sharedpref' and @path='api_token.xml']"
        val xpath = XPathFactory.newInstance().newXPath()
        assertTrue(
            "Missing credential exclusion in $rules at $section",
            xpath.evaluate("boolean($exclusion)", document, XPathConstants.BOOLEAN) as Boolean
        )
    }
}
