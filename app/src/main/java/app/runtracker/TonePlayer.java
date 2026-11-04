package app.runtracker;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.Looper;

import app.runtracker.core.SplitTracker;

/** Plays two rising tones for a faster split, two falling tones for a slower one. */
final class TonePlayer {
    private static final int SAMPLE_RATE = 44100;
    private static final double LOW_HZ = 660;
    private static final double HIGH_HZ = 990;
    private static final int TONE_MS = 220;
    private static final int SILENCE_MS = 30;
    private static final int FADE_MS = 10;

    private static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();

    private final AudioManager audio;
    private final Handler handler = new Handler(Looper.getMainLooper());

    TonePlayer(Context context) {
        audio = context.getSystemService(AudioManager.class);
    }

    void play(SplitTracker.Cue cue) {
        boolean faster = cue == SplitTracker.Cue.FASTER;
        short[] pcm = twoTones(faster ? LOW_HZ : HIGH_HZ, faster ? HIGH_HZ : LOW_HZ);

        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(ATTRIBUTES)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.length * 2)
                .build();
        track.write(pcm, 0, pcm.length);

        // Music dips briefly so the tones can be heard.
        AudioFocusRequest focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(ATTRIBUTES)
                .build();
        audio.requestAudioFocus(focus);
        track.play();
        handler.postDelayed(() -> {
            track.release();
            audio.abandonAudioFocusRequest(focus);
        }, pcm.length * 1000L / SAMPLE_RATE + 100);
    }

    private static short[] twoTones(double firstHz, double secondHz) {
        int toneSamples = SAMPLE_RATE * TONE_MS / 1000;
        int silenceSamples = SAMPLE_RATE * SILENCE_MS / 1000;
        short[] pcm = new short[toneSamples * 2 + silenceSamples];
        writeTone(pcm, 0, toneSamples, firstHz);
        writeTone(pcm, toneSamples + silenceSamples, toneSamples, secondHz);
        return pcm;
    }

    private static void writeTone(short[] pcm, int offset, int samples, double hz) {
        int fade = SAMPLE_RATE * FADE_MS / 1000;
        for (int i = 0; i < samples; i++) {
            double envelope = Math.min(1.0, Math.min(i, samples - 1 - i) / (double) fade);
            double value = Math.sin(2 * Math.PI * hz * i / SAMPLE_RATE);
            pcm[offset + i] = (short) (value * envelope * Short.MAX_VALUE * 0.9);
        }
    }
}
