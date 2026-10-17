package com.music.sonariq.data

/**
 * Every link Sonariq makes, in one place.
 *
 * Song links (`/s/<videoId>`) and party links (`/party/<CODE>`) live on the
 * Sonariq website: with the app installed they open straight into the song or
 * the party (Android App Links), without it they land on a page that offers
 * the download. The custom `sonariq://` scheme is the same shapes for the
 * website's "Open in app" buttons.
 *
 * The host is a constant rather than scattered literals so a future domain
 * move is a one-line change.
 */
object SonariqLinks {

    /** The website host behind every Sonariq link. */
    const val WEB_HOST = "sonariq.giize.com"

    /** Custom scheme for in-app and website "open" buttons. */
    const val SCHEME = "sonariq"

    /** `https://sonariq.giize.com/s/<videoId>` — opens the song in the app. */
    fun songUrl(videoId: String): String = "https://$WEB_HOST/s/$videoId"

    /** `https://sonariq.giize.com/party/<CODE>` — joins the party in the app. */
    fun partyUrl(code: String): String = "https://$WEB_HOST/party/${code.uppercase()}"

    /** `sonariq://s/<videoId>` — same song, custom scheme. */
    fun songSchemeUrl(videoId: String): String = "$SCHEME://s/$videoId"
}
