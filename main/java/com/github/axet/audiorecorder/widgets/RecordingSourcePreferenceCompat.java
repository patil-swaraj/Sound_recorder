package com.github.axet.audiorecorder.widgets;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.TypedArray;
import android.media.AudioManager;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.v7.preference.ListPreference;
import android.support.v7.preference.ListPreferenceDialogFragmentCompat;
import android.support.v7.preference.Preference;
import android.support.v7.preference.PreferenceViewHolder;
import android.util.AttributeSet;

import com.github.axet.audiolibrary.app.Sound;
import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.app.AudioApplication;
import com.github.axet.audiorecorder.app.Storage;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;

public class RecordingSourcePreferenceCompat extends com.github.axet.audiolibrary.widgets.RecordingSourcePreferenceCompat {
    public RecordingSourcePreferenceCompat(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public RecordingSourcePreferenceCompat(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public RecordingSourcePreferenceCompat(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RecordingSourcePreferenceCompat(Context context) {
        super(context);
    }

    public boolean isSourceSupported(String s) {
        if (s.equals(AudioApplication.PREFERENCE_SOURCE_RAW) && !Sound.isUnprocessedSupported(getContext()))
            return false;
        if (s.equals(AudioApplication.PREFERENCE_SOURCE_INTERNAL) && Build.VERSION.SDK_INT < 29)
            return false;
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        if (s.equals(AudioApplication.PREFERENCE_SOURCE_BLUETOOTH) && !am.isBluetoothScoAvailableOffCall())
            return false;
        return true;
    }

    @Override
    public LinkedHashMap<String, String> filter(LinkedHashMap<String, String> mm) {
        LinkedHashMap<String, String> map = new LinkedHashMap<>();
        for (String v : mm.keySet()) {
            String t = mm.get(v);
            if (!isSourceSupported(v))
                continue;
            map.put(v, t);
        }
        return map;
    }
}
