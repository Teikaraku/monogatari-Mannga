package app.monogatari.android

/** Display branding only; package identifiers and portable formats stay compatible. */
object AppBrand {
    const val NAME = "物语"
    const val REPOSITORY_URL = "https://github.com/Teikaraku/monogatari-Mannga"

    // Atomic aliases protect URL schemes; Croatian catalogs use the inflected name Aidokua.
    private val legacyName = Regex(
        """(?<![A-Za-z0-9_/:.\\-])(?>Aidoku(?:[ -]?Android|a(?=[\s.,!?;:]|$))?)(?![A-Za-z0-9_/\\]|://|\.[A-Za-z0-9])""",
        RegexOption.IGNORE_CASE,
    )

    /** Rebrand descriptions and legacy backup titles without rewriting embedded URLs. */
    fun displayText(value: String): String = legacyName.replace(value) { NAME }
}
