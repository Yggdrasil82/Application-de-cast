package fr.cast.audio

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager

/** Réglages et souvenirs de l'application (préférences Android). */
class Prefs(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("audiocast", Context.MODE_PRIVATE)

    /** Couper le son du téléphone pendant la diffusion. */
    var muteLocal: Boolean
        get() = prefs.getBoolean(KEY_MUTE_LOCAL, true)
        set(value) = prefs.edit().putBoolean(KEY_MUTE_LOCAL, value).apply()

    /** Vrai si c'est l'application qui a coupé le son (pour le rétablir, même après un plantage). */
    var mutedByApp: Boolean
        get() = prefs.getBoolean(KEY_MUTED_BY_APP, false)
        set(value) = prefs.edit().putBoolean(KEY_MUTED_BY_APP, value).apply()

    /** Dernière enceinte utilisée : identifiant interne et nom affiché. */
    var lastSpeakerId: String?
        get() = prefs.getString(KEY_LAST_ID, null)
        set(value) = prefs.edit().putString(KEY_LAST_ID, value).apply()

    var lastSpeakerName: String?
        get() = prefs.getString(KEY_LAST_NAME, null)
        set(value) = prefs.edit().putString(KEY_LAST_NAME, value).apply()

    /** Volume AirPlay (0 à 100), retenu d'une session à l'autre. */
    var airplayVolume: Int
        get() = prefs.getInt(KEY_AIRPLAY_VOLUME, RaopSession.DEFAULT_VOLUME)
        set(value) = prefs.edit().putInt(KEY_AIRPLAY_VOLUME, value).apply()

    fun airplayPassword(deviceId: String): String? = prefs.getString(KEY_PASSWORD + deviceId, null)

    fun setAirplayPassword(deviceId: String, password: String?) {
        prefs.edit().apply {
            if (password == null) remove(KEY_PASSWORD + deviceId) else putString(KEY_PASSWORD + deviceId, password)
        }.apply()
    }

    companion object {
        private const val KEY_MUTE_LOCAL = "mute_local"
        private const val KEY_MUTED_BY_APP = "muted_by_app"
        private const val KEY_LAST_ID = "last_speaker_id"
        private const val KEY_LAST_NAME = "last_speaker_name"
        private const val KEY_AIRPLAY_VOLUME = "airplay_volume"
        private const val KEY_PASSWORD = "airplay_password:"
    }
}

/** Coupe ou rétablit le son « médias » du téléphone. */
object LocalMute {
    fun apply(context: Context, mute: Boolean) {
        val prefs = Prefs(context)
        val audio = context.getSystemService(AudioManager::class.java)
        if (mute && !prefs.mutedByApp) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, 0)
            prefs.mutedByApp = true
            StreamState.log("Son du téléphone coupé pendant la diffusion")
        } else if (!mute && prefs.mutedByApp) {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, 0)
            prefs.mutedByApp = false
            StreamState.log("Son du téléphone rétabli")
        }
    }
}
