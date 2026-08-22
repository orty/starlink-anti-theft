package dev.starlinkguard.alarm

import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns

/**
 * Resolving which sound the alarm should play.
 *
 * The guiding rule is that a chosen sound must never be able to make the alarm silent. A custom
 * sound can disappear at any time — the file is deleted, an SD card is pulled, a persisted URI
 * permission is revoked — and the failure would otherwise only be discovered during an actual
 * theft. So the user's choice is the *first* candidate, not the only one.
 */
object AlarmSound {

    /** An empty or unparseable stored value means "use the system default". */
    fun parse(value: String?): Uri? =
        value?.takeIf { it.isNotBlank() }?.let { runCatching { Uri.parse(it) }.getOrNull() }

    /**
     * Sounds to try, best first. The default alarm can itself be unset on some devices, hence
     * the ringtone and notification fallbacks behind it.
     */
    fun candidates(custom: Uri?): List<Uri> = listOfNotNull(
        custom,
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
        RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
    ).distinct()

    /** Whether the sound can actually be opened right now. Does I/O — keep it off the main thread. */
    fun isReadable(context: Context, uri: Uri): Boolean = runCatching {
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    }.getOrDefault(false)

    /** A human-readable name for a sound. Does I/O — keep it off the main thread. */
    fun label(context: Context, uri: Uri): String {
        val ringtoneTitle = runCatching {
            RingtoneManager.getRingtone(context, uri)?.getTitle(context)
        }.getOrNull()
        if (!ringtoneTitle.isNullOrBlank()) return ringtoneTitle

        val displayName = runCatching {
            context.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
        }.getOrNull()
        if (!displayName.isNullOrBlank()) return displayName

        return uri.lastPathSegment ?: uri.toString()
    }

    /** The system ringtone picker, limited to alarm sounds. */
    fun ringtonePickerIntent(current: Uri?): Intent =
        Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
            putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, "Alarm sound")
            // No "Silent" entry on purpose: a theft alarm that plays nothing is a broken one.
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
            putExtra(
                RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
            )
            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, current)
        }

    /** MIME types for the "pick an audio file" document picker. */
    val AUDIO_MIME_TYPES = arrayOf("audio/*")
}
