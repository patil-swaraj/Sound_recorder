package com.github.axet.audiorecorder.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;

import com.github.axet.androidlibrary.sound.AudioTrack;
import com.github.axet.audiolibrary.app.RawSamples;
import com.github.axet.audiolibrary.app.Sound;
import com.github.axet.audiolibrary.encoders.Encoder;
import com.github.axet.audiolibrary.encoders.OnFlyEncoding;
import com.github.axet.audiorecorder.BuildConfig;
import com.github.axet.audiorecorder.R;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.json.JSONException;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

public class RecordingStorage {
    public static final int PINCH = 1;
    public static final int UPDATESAMPLES = 2;
    public static final int END = 3;
    public static final int ERROR = 4;
    public static final int MUTED = 5;
    public static final int UNMUTED = 6;
    public static final int PAUSED = 7;

    public Context context;
    public final ArrayList<Handler> handlers = new ArrayList<>();

    public Sound sound;
    public Storage storage;

    public Encoder e; // recording encoder (onfly or raw data)

    public AtomicBoolean interrupt = new AtomicBoolean(); // nio throws ClosedByInterruptException if thread interrupted
    public Thread thread;
    public final Object bufferSizeLock = new Object(); // lock for bufferSize
    public int bufferSize; // dynamic buffer size. big for backgound recording. small for realtime view updates.
    public int sampleRate; // variable from settings. how may samples per second.
    public int samplesUpdate; // pitch size in samples. how many samples count need to update view. 4410 for 100ms update.
    public int samplesUpdateStereo; // samplesUpdate * number of channels
    public Uri targetUri = null; // output target file 2016-01-01 01.01.01.wav
    public long samplesTime; // how many samples passed for current recording, stereo = samplesTime * 2
    public RawSamples.Info info;

    public AudioTrack.SamplesBuffer dbBuffer = null; // PinchView samples buffer

    public int pitchTime; // screen width

    public RecordingStorage(Context context, int format, int pitchTime, Uri targetUri) {
        this.context = context;
        this.pitchTime = pitchTime;
        this.targetUri = targetUri;
        storage = new Storage(context);
        sound = new Sound(context);
        sampleRate = Sound.getSampleRate(context);
        samplesUpdate = (int) (pitchTime * sampleRate / 1000f);
        samplesUpdateStereo = samplesUpdate * Sound.getChannels(context);
        info = new RawSamples.Info(format, sampleRate, Sound.getChannels(context));
    }

    public void startRecording(int source) {
        final SharedPreferences shared = android.preference.PreferenceManager.getDefaultSharedPreferences(context);

        sound.silent();

        int[] ss = new int[]{
                source,
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.DEFAULT
        };

        if (shared.getBoolean(AudioApplication.PREFERENCE_FLY, false)) {
            if (e == null) { // do not recreate encoder if on-fly mode enabled
                final OnFlyEncoding fly = new OnFlyEncoding(storage, targetUri, info);
                e = new Encoder() {
                    @Override
                    public void encode(AudioTrack.SamplesBuffer buf, int pos, int len) {
                        fly.encode(buf, pos, len);
                    }

                    @Override
                    public void close() {
                        fly.close();
                    }
                };
            }
        } else {
            final RawSamples rs = new RawSamples(storage.getTempRecording(), info);
            rs.open(samplesTime * rs.info.channels);
            e = new Encoder() {
                @Override
                public void encode(AudioTrack.SamplesBuffer buf, int pos, int len) {
                    rs.write(buf, pos, len);
                }

                @Override
                public void close() {
                    rs.close();
                }
            };
        }

        final AudioRecord recorder = sound.createAudioRecorder(info.format, sampleRate, ss, 0);

        final Thread old = thread;
        final AtomicBoolean oldb = interrupt;

        interrupt = new AtomicBoolean(false);

        thread = new Thread("RecordingThread") {
            @Override
            public void run() {
                if (old != null) {
                    oldb.set(true);
                    old.interrupt();
                    try {
                        old.join();
                    } catch (InterruptedException e) {
                        return;
                    }
                }

                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                PowerManager.WakeLock wlcpu = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, BuildConfig.APPLICATION_ID + ":recordinglock");
                wlcpu.acquire();

                android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);

                boolean silenceDetected = false;
                long silence = samplesTime; // last non silence frame

                long start = System.currentTimeMillis(); // recording start time
                long session = 0; // samples count from start of recording

                try {
                    long last = System.currentTimeMillis();
                    recorder.startRecording();

                    int samplesTimeCount = 0;
                    final int samplesTimeUpdate = 1000 * sampleRate / 1000; // how many samples we need to update 'samples'. time clock. every 1000ms.

                    AudioTrack.SamplesBuffer buffer = null;

                    boolean stableRefresh = false;

                    while (!interrupt.get()) {
                        synchronized (bufferSizeLock) {
                            if (buffer == null || buffer.capacity != bufferSize)
                                buffer = new AudioTrack.SamplesBuffer(info.format, bufferSize);
                        }

                        int readSize = -1;
                        switch (buffer.format) {
                            case AudioFormat.ENCODING_PCM_8BIT:
                                break;
                            case AudioFormat.ENCODING_PCM_16BIT:
                                readSize = recorder.read(buffer.shorts, 0, buffer.shorts.length);
                            case Sound.ENCODING_PCM_24BIT_PACKED:
                                break;
                            case Sound.ENCODING_PCM_32BIT:
                                break;
                            case AudioFormat.ENCODING_PCM_FLOAT:
                                if (Build.VERSION.SDK_INT >= 23)
                                    readSize = recorder.read(buffer.floats, 0, buffer.floats.length, AudioRecord.READ_BLOCKING);
                                break;
                            default:
                                throw new RuntimeException("Unknown format");
                        }

                        if (readSize < 0)
                            return;
                        long now = System.currentTimeMillis();
                        long diff = (now - last) * sampleRate / 1000;
                        last = now;

                        int samples = readSize / Sound.getChannels(context); // mono samples (for booth channels)

                        if (stableRefresh || diff >= samples) {
                            stableRefresh = true;

                            e.encode(buffer, 0, readSize);

                            AudioTrack.SamplesBuffer dbBuf;
                            int dbSize;
                            int readSizeUpdate;
                            if (dbBuffer != null) {
                                AudioTrack.SamplesBuffer bb = new AudioTrack.SamplesBuffer(info.format, dbBuffer.pos + readSize);
                                dbBuffer.flip();
                                bb.put(dbBuffer);
                                bb.put(buffer, 0, readSize);
                                dbBuf = new AudioTrack.SamplesBuffer(info.format, bb.pos);
                                dbSize = dbBuf.capacity;
                                bb.flip();
                                bb.get(dbBuf, 0, dbBuf.capacity);
                            } else {
                                dbBuf = buffer;
                                dbSize = readSize;
                            }
                            readSizeUpdate = dbSize / samplesUpdateStereo * samplesUpdateStereo;
                            for (int i = 0; i < readSizeUpdate; i += samplesUpdateStereo) {
                                double a = RawSamples.getAmplitude(dbBuf, i, samplesUpdateStereo);
                                if (a != 0)
                                    silence = samplesTime + (i + samplesUpdateStereo) / Sound.getChannels(context);
                                double dB = RawSamples.getDB(a);
                                Post(PINCH, dB);
                            }
                            int readSizeLen = dbSize - readSizeUpdate;
                            if (readSizeLen > 0) {
                                dbBuffer = new AudioTrack.SamplesBuffer(info.format, readSizeLen);
                                dbBuffer.put(dbBuf, readSizeUpdate, readSizeLen);
                            } else {
                                dbBuffer = null;
                            }

                            samplesTime += samples;
                            samplesTimeCount += samples;
                            if (samplesTimeCount > samplesTimeUpdate) {
                                Post(UPDATESAMPLES, samplesTime);
                                samplesTimeCount -= samplesTimeUpdate;
                            }
                            session += samples;

                            if (samplesTime - silence > 2 * sampleRate) { // 2 second of mic muted
                                if (!silenceDetected) {
                                    silenceDetected = true;
                                    Post(MUTED, null);
                                }
                            } else {
                                if (silenceDetected) {
                                    silenceDetected = false;
                                    Post(UNMUTED, null);
                                }
                            }
                            diff = (now - start) * sampleRate / 1000; // number of samples we expect by this moment
                            if (diff - session > 2 * sampleRate) { // 2 second of silence / paused by os
                                Post(PAUSED, null);
                                session = diff; // reset
                            }
                        }
                    }
                } catch (final RuntimeException e) {
                    Post(e);
                } finally {
                    wlcpu.release();

                    // redraw view, we may add one last pich which is not been drawen because draw tread already interrupted.
                    // to prevent resume recording jump - draw last added pitch here.
                    Post(END, null);

                    if (recorder != null)
                        recorder.release();

                    if (!shared.getBoolean(AudioApplication.PREFERENCE_FLY, false)) { // keep encoder open if encoding on fly enabled
                        try {
                            if (e != null) {
                                e.close();
                                e = null;
                            }
                        } catch (RuntimeException e) {
                            Post(e);
                        }
                    }
                }
            }
        };
        thread.start();
    }

    public void stopRecording() {
        if (thread != null) {
            interrupt.set(true);
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        sound.unsilent();
    }

    // calcuale buffer length dynamically, this way we can reduce thread cycles when activity in background
    // or phone screen is off.
    public void updateBufferSize(boolean pause) {
        synchronized (bufferSizeLock) {
            int samplesUpdate;

            if (pause) {
                // we need make buffer multiply of pitch.getPitchTime() (100 ms).
                // to prevent missing blocks from view otherwise:

                // file may contain not multiply 'samplesUpdate' count of samples. it is about 100ms.
                // we can't show on pitchView sorter then 100ms samples. we can't add partial sample because on
                // resumeRecording we have to apply rest of samplesUpdate or reload all samples again
                // from file. better then confusing user we cut them on next resumeRecording.

                long l = 1000L / pitchTime * pitchTime;
                samplesUpdate = (int) (l * sampleRate / 1000.0);
            } else {
                samplesUpdate = this.samplesUpdate;
            }

            bufferSize = samplesUpdate * Sound.getChannels(context);
        }
    }

    public boolean isForeground() {
        synchronized (bufferSizeLock) {
            return bufferSize == this.samplesUpdate * Sound.getChannels(context);
        }
    }

    public void Post(Throwable e) {
        Post(ERROR, e);
    }

    public void Post(int what, Object p) {
        synchronized (handlers) {
            for (Handler h : handlers)
                h.obtainMessage(what, p).sendToTarget();
        }
    }
}
