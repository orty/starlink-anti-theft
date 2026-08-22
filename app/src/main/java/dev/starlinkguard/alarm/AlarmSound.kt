package dev.starlinkguard.alarm

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import android.provider.OpenableColumns
import dev.starlinkguard.R

/**
 * Resolving which sound the alarm should play.
 *
 * The guiding rule is that a chosen sound must never be able to make the alarm silent. A custom
 * sound can disappear at any time — the file is deleted, an SD card is pulled, a persisted URI
 * permission is revoked — and the failure would otherwise only be discovered during an actual
 * theft. So the user's choice is the *first* candidate, not the only one.
 */
object AlarmSound {

    /**
     * Stored when the user picks "Default" in the ringtone picker.
     *
     * A sentinel rather than the resolved URI, so the alarm keeps following whatever the device
     * default is later changed to instead of pinning today's.
     */
    const val SYSTEM_DEFAULT = "system-default"

    /**
     * The siren shipped with the app, and the default when nothing has been chosen.
     *
     * A device's stock alarm tone is designed to wake someone gently. This one is not: it is a
     * harsh two-tone warble in the band the ear is most sensitive to, which is what a break-in
     * alarm should sound like. It is also the only sound guaranteed to exist on every device.
     */
    fun builtIn(context: Context): Uri = Uri.Builder()
        .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
        .authority(context.packageName)
        .appendPath(R.raw.alarm_siren.toString())
        .build()

    /** Turns the stored setting into the sound to try first. */
    fun chosen(context: Context, value: String?): Uri? = when {
        value.isNullOrBlank() -> builtIn(context)
        value == SYSTEM_DEFAULT -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        else -> runCatching { Uri.parse(value) }.getOrNull()
    }

    /**
     * Sounds to try, best first.
     *
     * The built-in siren sits directly behind the user's choice: it ships inside the APK, so
     * unlike anything from the media store it cannot be deleted, unmounted, or have its
     * permission revoked. The system tones behind it cover the case of a corrupt install.
     */
    fun candidates(context: Context, custom: Uri?): List<Uri> = listOfNotNull(
        custom,
        builtIn(context),
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
