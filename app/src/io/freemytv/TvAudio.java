package io.freemytv;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import java.util.function.Consumer;

/**
 * "Sonido" in Settings, on Amlogic streamers (onn 2K / 4K): who decodes Dolby.
 *  - "Parlantes del TV": this box decodes it and always sends plain PCM, so the HDMI output never changes format. Left
 *    on Auto, a Netflix trailer in Dolby Digital Plus switches it from PCM to a Dolby bitstream as it starts, and the
 *    TV clicks or squeals each time (onn 2K, Google TV 14).
 *  - "Barra de sonido": the factory setting (Auto): Dolby and 5.1 go through to a soundbar or receiver.
 * The same three changes as the TV's own Sound > Digital Audio Format ("None" / "Auto"): Android's surround setting,
 * Amlogic's own (its Netflix service watches it) and the audio HAL's hdmi_format, which applies it at once.
 */
final class TvAudio {
    /** Amlogic's digital audio mode: 0 PCM ("None"), 1 Auto, 2 Manual, 3 Passthrough, 4 Always. */
    private static final String AML_MODE = "db_id_droidlogic_audio_digital_mode";
    private static final int PCM = 0, AUTO = 1;
    /** The HAL's hdmi_format for the same two (its log: "digital audio mode: PCM" / "AUTO"; 1 is an invalid enum). */
    private static final int HDMI_PCM = 0, HDMI_AUTO = 5;
    /** Android's surround setting (Settings.Global.ENCODED_SURROUND_OUTPUT): 0 Auto, 1 Never. */
    private static final int SURROUND_AUTO = 0, SURROUND_NEVER = 1;
    private static volatile boolean busy;

    private TvAudio() {}

    static boolean busy() { return busy; }

    /** Only Amlogic's TV settings keep this value: elsewhere the row shows unavailable. */
    static boolean available(Context c) {
        return Settings.Global.getString(c.getContentResolver(), AML_MODE) != null;
    }

    static boolean tvSpeakers(Context c) {
        return String.valueOf(PCM).equals(Settings.Global.getString(c.getContentResolver(), AML_MODE));
    }

    static String label(Context c) {
        return tvSpeakers(c) ? "Parlantes del TV" : "Barra de sonido";
    }

    static void toggle(Context c, Consumer<String> toast, Runnable done) {
        if (busy) return;
        busy = true;
        boolean pcm = !tvSpeakers(c);
        Context app = c.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String msg;
            try (Adb adb = Adb.connect(app, 10_000, null)) {
                adb.shell("settings put global encoded_surround_output " + (pcm ? SURROUND_NEVER : SURROUND_AUTO)
                        + "; settings put global " + AML_MODE + " " + (pcm ? PCM : AUTO));
                app.getSystemService(AudioManager.class).setParameters("hdmi_format=" + (pcm ? HDMI_PCM : HDMI_AUTO));
                AppLog.w("tv audio: " + (pcm ? "pcm" : "auto"));
                msg = pcm ? "Parlantes del TV: el aparato decodifica el Dolby, sin chasquidos"
                        : "Barra de sonido: Dolby y 5.1 pasan directo";
            } catch (Exception e) {
                msg = "No pude cambiar el sonido: " + e.getMessage();
            }
            String m = msg;
            main.post(() -> {
                busy = false;
                toast.accept(m);
                done.run();
            });
        }, "tv-audio").start();
    }
}
