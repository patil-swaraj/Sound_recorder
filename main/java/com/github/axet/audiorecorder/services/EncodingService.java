package com.github.axet.audiorecorder.services;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.view.View;

import com.github.axet.androidlibrary.app.AlarmManager;
import com.github.axet.androidlibrary.app.ProximityShader;
import com.github.axet.androidlibrary.preferences.OptimizationPreferenceCompat;
import com.github.axet.androidlibrary.services.PersistentService;
import com.github.axet.androidlibrary.widgets.RemoteNotificationCompat;
import com.github.axet.audiolibrary.app.RawSamples;
import com.github.axet.audiolibrary.encoders.FileEncoder;
import com.github.axet.audiolibrary.encoders.FormatWAV;
import com.github.axet.audiolibrary.encoders.OnFlyEncoding;
import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.activities.MainActivity;
import com.github.axet.audiorecorder.activities.RecordingActivity;
import com.github.axet.audiorecorder.app.AudioApplication;
import com.github.axet.audiorecorder.app.EncodingStorage;
import com.github.axet.audiorecorder.app.Storage;

import org.json.JSONException;

import java.io.File;

public class EncodingService extends PersistentService {
    public static final String TAG = EncodingService.class.getSimpleName();

    public static final int NOTIFICATION_RECORDING_ICON = 2;

    public static String SHOW_ACTIVITY = EncodingService.class.getCanonicalName() + ".SHOW_ACTIVITY";
    public static String SAVE_AS_WAV = EncodingService.class.getCanonicalName() + ".SAVE_AS_WAV";
    public static String START_ENCODING = EncodingService.class.getCanonicalName() + ".START_ENCODING";

    Storage storage; // for storage path
    Handler handler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == EncodingStorage.UPDATE) {
                optimization.icon.updateIcon((Intent) msg.obj);
            }
            if (msg.what == EncodingStorage.DONE) {
                EncodingStorage encodings = ((AudioApplication) getApplication()).encodings;
                encodings.restart();
            }
            if (msg.what == EncodingStorage.EXIT) {
                stopSelf();
            }
            if (msg.what == EncodingStorage.ERROR) {
                stopSelf();
            }
        }
    };

    public static void startIfPending(Context context) { // if encoding pending
        EncodingStorage encodings = ((AudioApplication) context.getApplicationContext()).encodings;
        encodings.load();
        if (!encodings.isEmpty()) {
            start(context);
            return;
        }
    }

    public static void start(Context context) { // start persistent icon service
        start(context, new Intent(context, EncodingService.class));
    }

    public static void saveAsWAV(Context context, File in, File out, RawSamples.Info info) { // start encoding process for selected file
        try {
            start(context, new Intent(context, EncodingService.class).setAction(SAVE_AS_WAV)
                    .putExtra("in", in)
                    .putExtra("out", out)
                    .putExtra("info", info.save().toString())
            );
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public static void stop(Context context) {
        stop(context, new Intent(context, EncodingService.class));
    }

    public static File startEncoding(Context context, File in, Uri targetUri, RawSamples.Info info) {
        try {
            EncodingStorage encodings = ((AudioApplication) context.getApplicationContext()).encodings;
            in = encodings.save(in, targetUri, info);
            start(context, new Intent(context, EncodingService.class).setAction(START_ENCODING)
                    .putExtra("in", in)
                    .putExtra("targetUri", targetUri)
                    .putExtra("info", info.save().toString())
            );
            return in;
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }

    public EncodingService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onCreateOptimization() {
        storage = new Storage(this);
        optimization = new OptimizationPreferenceCompat.ServiceReceiver(this, NOTIFICATION_RECORDING_ICON, null, AudioApplication.PREFERENCE_NEXT) {
            Intent notificationIntent;

            @Override
            public void onCreateIcon(Service service, int id) {
                icon = new OptimizationPreferenceCompat.OptimizationIcon(service, id, key) {
                    @Override
                    public void updateIcon() {
                        icon.updateIcon(new Intent());
                    }

                    @Override
                    public void updateIcon(Intent intent) {
                        super.updateIcon(intent);
                        notificationIntent = intent;
                    }

                    @SuppressLint("RestrictedApi")
                    public Notification build(Intent intent) {
                        String targetFile = intent.getStringExtra("targetFile");
                        long cur = intent.getLongExtra("cur", -1);
                        long total = intent.getLongExtra("total", -1);
                        long progress = cur * 100 / total;

                        PendingIntent main;

                        RemoteNotificationCompat.Builder builder;

                        String title;
                        String text;

                        title = getString(R.string.encoding_title);
                        text = ".../" + targetFile + " (" + progress + "%)";
                        builder = new RemoteNotificationCompat.Low(context, R.layout.notifictaion);
                        builder.setViewVisibility(R.id.notification_record, View.VISIBLE);
                        builder.setViewVisibility(R.id.notification_pause, View.GONE);
                        main = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);

                        builder.setViewVisibility(R.id.notification_pause, View.GONE);
                        builder.setViewVisibility(R.id.notification_record, View.GONE);

                        builder.setTheme(AudioApplication.getTheme(context, R.style.RecThemeLight, R.style.RecThemeDark))
                                .setChannel(AudioApplication.from(context).channelStatus)
                                .setImageViewTint(R.id.icon_circle, builder.getThemeColor(R.attr.colorButtonNormal))
                                .setTextViewText(R.id.app_name_text, title)
                                .setTitle(title)
                                .setText(text)
                                .setWhen(icon.notification)
                                .setMainIntent(main)
                                .setAdaptiveIcon(R.drawable.ic_launcher_foreground)
                                .setSmallIcon(R.drawable.ic_launcher_notification)
                                .setOngoing(true);

                        return builder.build();
                    }
                };
                icon.create();
            }

            @Override
            public boolean isOptimization() {
                return true; // we are not using optimization preference
            }
        };
        optimization.create();
        EncodingStorage encodings = ((AudioApplication) getApplication()).encodings;
        synchronized (encodings.handlers) {
            encodings.handlers.add(handler);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        EncodingStorage encodings = ((AudioApplication) getApplication()).encodings;
        synchronized (encodings.handlers) {
            encodings.handlers.remove(handler);
            handler.removeCallbacksAndMessages(null);
        }
    }

    @Override
    public void onStartCommand(Intent intent) {
        String a = intent.getAction();
        final EncodingStorage encodings = ((AudioApplication) getApplication()).encodings;
        if (a == null) {
            optimization.icon.updateIcon(intent);
        } else if (a.equals(SHOW_ACTIVITY)) {
            ProximityShader.closeSystemDialogs(this);
            if (intent.getStringExtra("targetFile") == null)
                MainActivity.startActivity(this);
            else
                RecordingActivity.startActivity(this, !intent.getBooleanExtra("recording", false));
        } else if (a.equals(SAVE_AS_WAV)) {
            try {
                File in = (File) intent.getSerializableExtra("in");
                File out = (File) intent.getSerializableExtra("out");
                RawSamples.Info info = new RawSamples.Info(intent.getStringExtra("info"));
                if (encodings.encoder == null)
                    encodings.saveAsWAV(in, out, info);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        } else if (a.equals(START_ENCODING)) {
            try {
                File in = (File) intent.getSerializableExtra("in");
                Uri targetUri = intent.getParcelableExtra("targetUri");
                RawSamples.Info info = new RawSamples.Info(intent.getStringExtra("info"));
                if (encodings.encoder == null)
                    encodings.encoding(in, targetUri, info);
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }
        encodings.startEncoding();
    }
}
