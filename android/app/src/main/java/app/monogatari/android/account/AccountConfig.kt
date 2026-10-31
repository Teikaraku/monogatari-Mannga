package app.monogatari.android.account

/** The 物语 identity center and this app's sync service. */
object AccountConfig {
    const val ISSUER = "https://auth.monogatari.top/oidc"
    const val ORIGIN = "https://auth.monogatari.top"
    /** The 物语漫画 Native application in Logto (customData.key manga-app). */
    const val CLIENT_ID = "gdjqw40f0zh1o9u4rjtwn"
    /** Access tokens for this audience open the manga sync service only. */
    const val RESOURCE = "https://manga.monogatari.top/sync"
    const val SYNC_BASE = "https://manga.monogatari.top"
    const val CALLBACK_SCHEME = "app.monogatari.android"
    const val REDIRECT_URI = "$CALLBACK_SCHEME://callback"
    val SCOPES = listOf("openid", "profile", "email", "offline_access")
}
