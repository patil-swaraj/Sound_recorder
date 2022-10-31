package com.github.axet.audiorecorder.services;

import android.annotation.SuppressLint;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.widget.RemoteViews;

import com.github.axet.androidlibrary.app.AlarmManager;
import com.github.axet.androidlibrary.app.NotificationManagerCompat;
import com.github.axet.androidlibrary.app.ProximityShader;
import com.github.axet.androidlibrary.preferences.OptimizationPreferenceCompat;
import com.github.axet.androidlibrary.services.PersistentService;
import com.github.axet.androidlibrary.widgets.NotificationChannelCompat;
import com.github.axet.androidlibrary.widgets.RemoteNotificationCompat;
import com.github.axet.androidlibrary.widgets.RemoteViewsCompat;
import com.github.axet.audiolibrary.app.Sound;
import com.github.axet.audiolibrary.app.Storage;
import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.activities.MainActivity;
import com.github.axet.audiorecorder.activities.RecordingActivity;
import com.github.axet.audiorecorder.app.AudioApplication;

import java.io.File;

public class ControlsService extends PersistentService {
    public static final String TAG = ControlsService.class.getSimpleName();

    public static final int NOTIFICATION_CONTROLS_ICON = 3;
    public static final int NOTIFICATION_PERSISTENT_ICON = 4;

    Storage storage;
    OptimizationPreferenceCompat.NotificationIcon controls;

    public static String SHOW_ACTIVITY = ControlsService.class.getCanonicalName() + ".SHOW_ACTIVITY";
    public static String RECORD_BUTTON = ControlsService.class.getCanonicalName() + ".RECORD_BUTTON";
    public static String HIDE_ICON = ControlsService.class.getCanonicalName() + ".HIDE_ICON";

    public static void startIfEnabled(Context context) { // notification controls enabled?
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        if (!shared.getBoolean(AudioApplication.PREFERENCE_CONTROLS, false))
            return;
        start(context);
    }

    public static void start(Context context) { // start persistent icon service
        start(context, new Intent(context, ControlsService.class));
    }

    public static void stop(Context context) {
        stop(context, new Intent(context, ControlsService.class));
    }

    public static void hideIcon(Context context) {
        SharedPreferences shared = PreferenceManager.getDefaultSharedPreferences(context);
        if (!shared.getBoolean(AudioApplication.PREFERENCE_CONTROLS, false))
            return;
        start(context, new Intent(context, ControlsService.class).setAction(HIDE_ICON));
    }

    public ControlsService() {
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public void onCreateOptimization() {
        storage = new Storage(this);
        optimization = new OptimizationPreferenceCompat.ServiceReceiver(this, NOTIFICATION_PERSISTENT_ICON, null, AudioApplication.PREFERENCE_NEXT) {
            @Override
            public void onCreateIcon(Service service, int id) {
                controls = new OptimizationPreferenceCompat.NotificationIcon(ControlsService.this, NOTIFICATION_CONTROLS_ICON) {
                    @Override
                    public void updateIcon(Intent intent) {
                        KeyguardManager myKM = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
                        if (myKM.inKeyguardRestrictedInputMode() && !storage.recordingPending())
                            intent = new Intent();
                        else
                            intent = null;
                        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
                        if (intent != null || isOptimization()) {
                            Notification n = build(intent);
                            if (notification == null) {
                                nm.notify(id, n);
                            } else {
                                String co = NotificationChannelCompat.getChannelId(notification);
                                String cn = NotificationChannelCompat.getChannelId(n);
                                if (co == null && cn != null || co != null && cn == null || co != null && cn != null && !co.equals(cn))
                                    nm.cancel(id);
                                nm.notify(id, n);
                            }
                            notification = n;
                        } else {
                            hideIcon();
                        }
                    }

                    public void hideIcon() {
                        NotificationManagerCompat nm = NotificationManagerCompat.from(context);
                        nm.cancel(id);
                        notification = null;
                    }

                    @Override
                    public Notification build(Intent intent) {
                        PendingIntent main;

                        RemoteNotificationCompat.Builder builder;

                        String title;
                        String text;

                        title = getString(R.string.app_name);
                        Uri f = storage.getStoragePath();
                        long free = Storage.getFree(context, f);
                        long sec = Storage.average(context, Sound.getAudioFormat(context), free);
                        text = AudioApplication.formatFree(context, free, sec);
                        builder = new RemoteNotificationCompat.Low(context, R.layout.notifictaion);
                        builder.setViewVisibility(R.id.notification_record, View.VISIBLE);
                        builder.setViewVisibility(R.id.notification_pause, View.GONE);
                        main = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);

                        PendingIntent re = PendingIntent.getService(context, 0,
                                new Intent(context, ControlsService.class).setAction(RECORD_BUTTON),
                                PendingIntent.FLAG_UPDATE_CURRENT);

                        builder.setOnClickPendingIntent(R.id.notification_record, re);

                        builder.setTheme(AudioApplication.getTheme(context, R.style.RecThemeLight, R.style.RecThemeDark))
                                .setChannel(AudioApplication.from(context).channelStatus)
                                .setImageViewTint(R.id.icon_circle, builder.getThemeColor(R.attr.colorButtonNormal))
                                .setTitle(title)
                                .setText(text)
                                .setWhen(controls.notification)
                                .setMainIntent(main)
                                .setAdaptiveIcon(R.drawable.ic_launcher_foreground)
                                .setSmallIcon(R.drawable.ic_launcher_notification)
                                .setOngoing(true);

                        return builder.build();
                    }

                    @Override
                    public boolean isOptimization() {
                        return false;
                    }
                };
                controls.create();
                icon = new OptimizationPreferenceCompat.OptimizationIcon(service, id, key) {
                    @Override
                    public void updateIcon(Intent intent) {
                        super.updateIcon(intent);
                    }

                    @SuppressLint("RestrictedApi")
                    public Notification build(Intent intent) {
                        PendingIntent main;

                        RemoteNotificationCompat.Builder builder;

                        String title;
                        String text;

                        title = getString(R.string.app_name);
                        text = "Persistent Controls Icon";
                        builder = new RemoteNotificationCompat.Low(context, R.layout.notifictaion);
                        builder.setViewVisibility(R.id.notification_record, View.GONE);
                        builder.setViewVisibility(R.id.notification_pause, View.GONE);
                        main = PendingIntent.getActivity(context, 0, new Intent(context, MainActivity.class), PendingIntent.FLAG_UPDATE_CURRENT);

                        builder.setTheme(AudioApplication.getTheme(context, R.style.RecThemeLight, R.style.RecThemeDark))
                                .setChannel(AudioApplication.from(context).channelPersistent)
                                .setImageViewTint(R.id.icon_circle, builder.getThemeColor(R.attr.colorButtonNormal))
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
            public void updateIcon() {
                super.updateIcon();
                controls.updateIcon((Intent) null);
            }

            @Override
            public boolean isOptimization() {
                return true; // we not using optimization preference
            }

            @Override
            public void onReceive(Context context, Intent intent) {
                super.onReceive(context, intent);
                String a = intent.getAction();
                if (a == null)
                    return;
                switch (a) {
                    case Intent.ACTION_USER_PRESENT:
                        optimization.updateIcon();
                        break;
                    case Intent.ACTION_SCREEN_ON:
                        optimization.updateIcon();
                        break;
                    case Intent.ACTION_SCREEN_OFF:
                        optimization.updateIcon();
                        break;
                }
            }
        };
        optimization.filters.addAction(Intent.ACTION_USER_PRESENT);
        optimization.filters.addAction(Intent.ACTION_SCREEN_ON);
        optimization.filters.addAction(Intent.ACTION_SCREEN_OFF);
        optimization.create();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onStartCommand(Intent intent) {
        String a = intent.getAction();
        if (a == null) {
            optimization.updateIcon();
        } else if (a.equals(HIDE_ICON)) {
            controls.hideIcon();
        } else if (a.equals(RECORD_BUTTON)) {
            RecordingActivity.startActivity(this, false);
        } else if (a.equals(SHOW_ACTIVITY)) {
            ProximityShader.closeSystemDialogs(this);
            MainActivity.startActivity(this);
        }
    }
}
