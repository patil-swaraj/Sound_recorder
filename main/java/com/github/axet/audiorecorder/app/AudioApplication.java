package com.github.axet.audiorecorder.app;

import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.media.MediaRecorder;
import android.os.Build;
import android.support.v7.preference.PreferenceManager;
import android.util.Log;
import android.view.View;

import com.github.axet.androidlibrary.app.NotificationManagerCompat;
import com.github.axet.androidlibrary.app.Prefs;
import com.github.axet.androidlibrary.widgets.NotificationChannelCompat;
import com.github.axet.androidlibrary.widgets.RemoteNotificationCompat;
import com.github.axet.audiolibrary.app.Sound;
import com.github.axet.audiolibrary.encoders.FormatFLAC;
import com.github.axet.audiolibrary.encoders.FormatM4A;
import com.github.axet.audiolibrary.encoders.FormatOGG;
import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.activities.MainActivity;

import java.util.Locale;

public class AudioApplication extends com.github.axet.audiolibrary.app.MainApplication {
    public static final String PREFERENCE_CONTROLS = "controls";
    public static final String PREFERENCE_TARGET = "target";
    public static final String PREFERENCE_FLY = "fly";
    public static final String PREFERENCE_SOURCE = "bluetooth";
    public static final String PREFERENCE_SOURCE_MIC = Prefs.PrefString(R.string.source_mic);
    public static final String PREFERENCE_SOURCE_BLUETOOTH = Prefs.PrefString(R.string.source_bluetooth);
    public static final String PREFERENCE_SOURCE_RAW = Prefs.PrefString(R.string.source_raw);
    public static final String PREFERENCE_SOURCE_INTERNAL = Prefs.PrefString(R.string.source_internal);

    public static final String PREFERENCE_VERSION = "version";

    public static final String PREFERENCE_NEXT = "next";

    public NotificationChannelCompat channelStatus;
    public NotificationChannelCompat channelPersistent;
    public RecordingStorage recording;
    public EncodingStorage encodings;

    public static AudioApplication from(Context context) {
        return (AudioApplication) com.github.axet.audiolibrary.app.MainApplication.from(context);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        channelStatus = new NotificationChannelCompat(this, "status", "Status", NotificationManagerCompat.IMPORTANCE_LOW);
        channelPersistent = new NotificationChannelCompat(this, "persistent", "Persistent", NotificationManagerCompat.IMPORTANCE_LOW);
        encodings = new EncodingStorage(this);

        switch (getVersion(PREFERENCE_VERSION, R.xml.pref_general)) {
            case -1:
                SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
                SharedPreferences.Editor edit = shared.edit();
                if (!FormatOGG.supported(this)) {
                    if (Build.VERSION.SDK_INT >= 18)
                        edit.putString(AudioApplication.PREFERENCE_ENCODING, FormatM4A.EXT);
                    else
                        edit.putString(AudioApplication.PREFERENCE_ENCODING, FormatFLAC.EXT);
                }
                edit.putInt(PREFERENCE_VERSION, 4);
                edit.commit();
                break;
            case 0:
                version_0_to_1();
                version_1_to_2();
                break;
            case 1:
                version_1_to_2();
                break;
            case 2:
                version_2_to_3();
                break;
            case 3:
                version_3_to_4();
                break;
        }
    }

    void version_0_to_1() {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = shared.edit();
        edit.putFloat(PREFERENCE_VOLUME, shared.getFloat(PREFERENCE_VOLUME, 0) + 1); // update volume from 0..1 to 0..1..4
        edit.putInt(PREFERENCE_VERSION, 1);
        edit.commit();
    }

    void show(String title, String text) {
        PendingIntent main = PendingIntent.getService(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
        RemoteNotificationCompat.Builder builder = new RemoteNotificationCompat.Builder(this, R.layout.notifictaion);
        builder.setViewVisibility(R.id.notification_record, View.GONE);
        builder.setViewVisibility(R.id.notification_pause, View.GONE);
        builder.setTheme(AudioApplication.getTheme(this, R.style.RecThemeLight, R.style.RecThemeDark))
                .setImageViewTint(R.id.icon_circle, builder.getThemeColor(R.attr.colorButtonNormal))
                .setTitle(title)
                .setText(text)
                .setMainIntent(main)
                .setChannel(channelStatus)
                .setSmallIcon(R.drawable.ic_launcher_notification);
        NotificationManagerCompat nm = NotificationManagerCompat.from(this);
        nm.notify((int) System.currentTimeMillis(), builder.build());
    }

    @SuppressLint("RestrictedApi")
    void version_1_to_2() {
        Locale locale = Locale.getDefault();
        if (locale.toString().startsWith("ru")) {
            String title = "Программа переименована";
            String text = "'Аудио Рекордер' -> '" + getString(R.string.app_name) + "'";
            show(title, text);
        }
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = shared.edit();
        edit.putInt(PREFERENCE_VERSION, 2);
        edit.commit();
    }

    @SuppressLint("RestrictedApi")
    void version_2_to_3() {
        Locale locale = Locale.getDefault();
        if (locale.toString().startsWith("tr")) {
            String title = "Application renamed";
            String text = "'Audio Recorder' -> '" + getString(R.string.app_name) + "'";
            show(title, text);
        }
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = shared.edit();
        edit.putInt(PREFERENCE_VERSION, 3);
        edit.commit();
    }

    @SuppressLint("RestrictedApi")
    void version_3_to_4() {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor edit = shared.edit();
        edit.remove(PREFERENCE_SORT);
        edit.putInt(PREFERENCE_VERSION, 4);
        edit.commit();
    }

    public int getSource() {
        final SharedPreferences shared = android.preference.PreferenceManager.getDefaultSharedPreferences(this);
        String source = shared.getString(AudioApplication.PREFERENCE_SOURCE, AudioApplication.PREFERENCE_SOURCE_MIC);
        int user;
        if (source.equals(AudioApplication.PREFERENCE_SOURCE_RAW)) {
            if (Sound.isUnprocessedSupported(this))
                user = MediaRecorder.AudioSource.UNPROCESSED;
            else
                user = MediaRecorder.AudioSource.VOICE_RECOGNITION;
        } else if (source.equals(AudioApplication.PREFERENCE_SOURCE_INTERNAL)) {
            user = Sound.SOURCE_INTERNAL_AUDIO;
        } else {
            user = MediaRecorder.AudioSource.MIC;
        }
        return user;
    }
}
