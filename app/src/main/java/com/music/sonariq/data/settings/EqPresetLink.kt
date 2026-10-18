package com.music.sonariq.data.settings

import android.content.Intent
import com.music.sonariq.data.SonariqLinks
import com.music.sonariq.playback.EqLayout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI
import java.util.Base64

/**
 * Sharing an EQ preset as a Sonariq link.
 *
 * Follows the [SonariqLinks] pattern: song links are `/s/<videoId>` and party
 * invites are `/party/<CODE>`; a preset link is `/eq/<payload>`, on the web
 * host and on the custom scheme alike:
 *
 * - `https://sonariq.giize.com/eq/<payload>` — opens in the app when
 *   installed (needs the `/eq/` path prefix in the manifest, like `/s/` and
 *   `/party/`)
 * - `sonariq://eq/<payload>` — the custom-scheme twin
 *
 * The payload is the preset's name and its seven gains, base64url-encoded so
 * the path stays one clean segment whatever the name contains. Decoding is
 * strict about shape and lenient about values: malformed payloads are refused
 * outright, while short or wild gain lists are padded and clamped the way
 * [decodeUserEqualizerPresets] already does for the preference string.
 *
 * Receiving is real end to end: [import] saves the preset through
 * [AppSettings.saveUserEqualizerPreset] — the same path the EQ screen's own
 * save uses, which also applies the curve — so an imported preset behaves
 * exactly like one made on the device.
 */
object EqPresetLink {

    private const val PATH = "eq"
    private const val EXTRA_CONSUMED = "sonariq.eqPresetConsumed"
    private const val SEPARATOR = "\u001F"

    private val _pending = MutableStateFlow<UserEqualizerPreset?>(null)

    /** A preset link that arrived and has not been acted on yet. */
    val pending: StateFlow<UserEqualizerPreset?> = _pending.asStateFlow()

    /** `https://sonariq.giize.com/eq/<payload>` — the shareable link. */
    fun shareUrl(preset: UserEqualizerPreset): String =
        "https://${SonariqLinks.WEB_HOST}/$PATH/${encode(preset)}"

    /** `sonariq://eq/<payload>` — the custom-scheme twin. */
    fun schemeUrl(preset: UserEqualizerPreset): String =
        "${SonariqLinks.SCHEME}://$PATH/${encode(preset)}"

    /** The Android share sheet payload for the preset. */
    fun shareIntent(preset: UserEqualizerPreset): Intent =
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, preset.name)
                putExtra(Intent.EXTRA_TEXT, shareUrl(preset))
            },
            null,
        )

    /**
     * Encodes the preset into the link payload: the sanitised name, a
     * separator, and the seven gains, base64url-encoded without padding.
     */
    fun encode(preset: UserEqualizerPreset): String {
        val name = sanitizePresetName(preset.name)
        val gains = List(EqLayout.MANUAL_COUNT) {
            preset.bandsDb.getOrElse(it) { 0f }
                .coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        }.joinToString(",")
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString("$name$SEPARATOR$gains".toByteArray(Charsets.UTF_8))
    }

    /**
     * Decodes a payload from [encode]. Null for anything that is not a
     * well-formed preset: bad base64, a missing name, or a gain list with no
     * parseable numbers.
     */
    fun decode(payload: String?): UserEqualizerPreset? {
        if (payload.isNullOrBlank()) return null
        val raw = runCatching {
            String(Base64.getUrlDecoder().decode(payload.trim()), Charsets.UTF_8)
        }.getOrNull() ?: return null
        val name = sanitizePresetName(raw.substringBefore(SEPARATOR))
        if (name.isEmpty()) return null
        val gains = raw.substringAfter(SEPARATOR, "")
            .split(",")
            .mapNotNull { it.trim().toFloatOrNull()?.takeIf(Float::isFinite) }
        if (gains.isEmpty()) return null
        val bands = List(EqLayout.MANUAL_COUNT) {
            gains.getOrElse(it) { 0f }.coerceIn(-EqLayout.MANUAL_RANGE_DB, EqLayout.MANUAL_RANGE_DB)
        }
        return UserEqualizerPreset(name, bands)
    }

    /**
     * Parses an incoming preset link in either shape. Accepts the official
     * web host and the custom scheme only; anything else is not a Sonariq
     * preset link and returns null.
     */
    fun parse(value: String?): UserEqualizerPreset? {
        val uri = runCatching { URI(value?.trim() ?: return null) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase() ?: return null
        val host = uri.host?.lowercase() ?: return null
        val path = uri.path.orEmpty().trim('/')
        val payload = when {
            scheme == "https" && host == SonariqLinks.WEB_HOST && path.startsWith("$PATH/") ->
                path.removePrefix("$PATH/").takeIf { it.isNotBlank() }
            scheme == SonariqLinks.SCHEME && host == PATH ->
                path.takeIf { it.isNotBlank() }
            else -> null
        } ?: return null
        return decode(payload)
    }

    /**
     * Reads a preset link from an incoming intent: `ACTION_VIEW` for a tapped
     * link, `ACTION_SEND` for one shared as text (the URL is extracted from
     * the shared text). Reports whether the intent carried a preset.
     */
    fun consume(intent: Intent?): Boolean {
        if (intent == null || intent.getBooleanExtra(EXTRA_CONSUMED, false)) return false
        val preset = when (intent.action) {
            Intent.ACTION_VIEW -> parse(intent.dataString)
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
                ?.let(URL_IN_TEXT::find)?.value
                ?.let(::parse)
            else -> null
        } ?: return false
        intent.putExtra(EXTRA_CONSUMED, true)
        _pending.value = preset
        return true
    }

    fun handled() {
        _pending.value = null
    }

    /**
     * Imports the preset: saved under its name through the same path the EQ
     * screen's save uses, which also applies its curve. An existing preset
     * with the same name is replaced, matching the save behaviour.
     */
    fun import(preset: UserEqualizerPreset): Boolean =
        AppSettings.saveUserEqualizerPreset(preset.name, preset.bandsDb)

    private val URL_IN_TEXT = Regex("""https?://\S+""")
}
