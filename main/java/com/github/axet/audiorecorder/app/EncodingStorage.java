package com.github.axet.audiorecorder.app;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.util.Log;
import android.util.Xml;

import com.github.axet.audiolibrary.app.RawSamples;
import com.github.axet.audiolibrary.encoders.FileEncoder;
import com.github.axet.audiolibrary.encoders.FormatWAV;
import com.github.axet.audiolibrary.encoders.OnFlyEncoding;
import com.github.axet.audiolibrary.filters.AmplifierFilter;
import com.github.axet.audiolibrary.filters.SkipSilenceFilter;
import com.github.axet.audiolibrary.filters.VoiceFilter;

import org.apache.commons.io.FileUtils;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FilenameFilter;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;

public class EncodingStorage extends HashMap<File, EncodingStorage.Info> {
    public static final String TAG = EncodingStorage.class.getSimpleName();

    public static final int UPDATE = 1;
    public static final int DONE = 2;
    public static final int EXIT = 3;
    public static final int ERROR = 4;

    public static String JSON_EXT = "json";

    public Storage storage;
    public FileEncoder encoder;
    public final ArrayList<Handler> handlers = new ArrayList<>();

    public static File jsonFile(File f) {
        return new File(f.getParentFile(), Storage.getNameNoExt(f) + "." + JSON_EXT);
    }

    public static class Info {
        public Uri targetUri;
        public RawSamples.Info info;

        public Info() {
        }

        public Info(Uri t, RawSamples.Info i) {
            this.targetUri = t;
            this.info = i;
        }

        public Info(String json) throws JSONException {
            load(new JSONObject(json));
        }

        public Info(JSONObject json) throws JSONException {
            load(json);
        }

        public JSONObject save() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("targetUri", targetUri.toString());
            json.put("info", info.save());
            return json;
        }

        public void load(JSONObject json) throws JSONException {
            targetUri = Uri.parse(json.getString("targetUri"));
            info = new RawSamples.Info(json.getJSONObject("info"));
        }
    }

    public EncodingStorage(Context context) {
        storage = new Storage(context);
        load();
    }

    public void load() {
        clear();
        Context context = storage.getContext();
        load(context.getCacheDir());
        load(context.getExternalCacheDir());
        load(Storage.getFilesDir(context, Storage.RAW));
        load(context.getExternalFilesDir(Storage.RAW));
    }

    public void load(File storage) {
        if (storage == null)
            return;
        File[] ff = storage.listFiles(new FilenameFilter() {
            String start = Storage.getNameNoExt(Storage.TMP_ENC);
            String ext = "." + Storage.getExt(Storage.TMP_ENC);

            @Override
            public boolean accept(File dir, String name) {
                return name.startsWith(start) && name.endsWith(ext);
            }
        });
        if (ff == null)
            return;
        for (File f : ff) {
            File j = jsonFile(f);
            try {
                put(f, new Info(new JSONObject(FileUtils.readFileToString(j, Charset.defaultCharset()))));
            } catch (Exception e) {
                Log.d(TAG, "unable to read json " + j, e);
            }
        }
    }

    public File save(File in, Uri targetUri, RawSamples.Info info) {
        File to = storage.getTempEncoding();
        to = Storage.getNextFile(to);
        to = Storage.move(in, to);
        try {
            File j = jsonFile(to);
            Info rec = new Info(targetUri, info);
            JSONObject json = rec.save();
            FileUtils.writeStringToFile(j, json.toString(), Charset.defaultCharset());
            put(to, rec);
            return to;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public void filters(FileEncoder encoder, RawSamples.Info info) {
        SharedPreferences shared = android.preference.PreferenceManager.getDefaultSharedPreferences(storage.getContext());
        if (shared.getBoolean(AudioApplication.PREFERENCE_VOICE, false))
            encoder.filters.add(new VoiceFilter(info));
        float amp = shared.getFloat(AudioApplication.PREFERENCE_VOLUME, 1);
        if (amp != 1)
            encoder.filters.add(new AmplifierFilter(amp));
        if (shared.getBoolean(AudioApplication.PREFERENCE_SKIP, false))
            encoder.filters.add(new SkipSilenceFilter(info));
    }

    public void startEncoding() {
        if (encoder != null)
            return;
        load();
        for (File in : keySet()) {
            EncodingStorage.Info info = get(in);
            final OnFlyEncoding fly = new OnFlyEncoding(this.storage, info.targetUri, info.info);
            encoder = new FileEncoder(storage.getContext(), in, info.info, fly);
            filters(encoder, info.info);
            encoding(encoder, fly, info.info, new Runnable() {
                @Override
                public void run() {
                    restart();
                }
            });
            return;
        }
        Post(EXIT, null);
    }

    public void encoding(final FileEncoder encoder, final OnFlyEncoding fly, final RawSamples.Info info, final Runnable done) {
        encoder.run(new Runnable() {
            long last = 0;

            @Override
            public void run() { // progress
                try {
                    long now = System.currentTimeMillis();
                    if (last + 1000 < now) {
                        last = now;
                        long cur = encoder.getCurrent();
                        long total = encoder.getTotal();
                        Intent intent = new Intent()
                                .putExtra("cur", cur)
                                .putExtra("total", total)
                                .putExtra("info", info.save().toString())
                                .putExtra("targetUri", fly.targetUri)
                                .putExtra("targetFile", Storage.getName(storage.getContext(), fly.targetUri));
                        Post(UPDATE, intent);
                    }
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            }
        }, new Runnable() {
            @Override
            public void run() { // success
                Storage.delete(encoder.in); // delete raw recording
                Storage.delete(EncodingStorage.jsonFile(encoder.in)); // delete json file
                remove(encoder.in);
                Post(DONE, new Intent()
                        .putExtra("targetUri", fly.targetUri)
                );
                if (done != null)
                    done.run();
            }
        }, new Runnable() {
            @Override
            public void run() { // or error
                Storage.delete(storage.getContext(), fly.targetUri); // fly has fd, delete target manually
                try {
                    Intent intent = new Intent()
                            .putExtra("in", encoder.in)
                            .putExtra("info", info.save().toString())
                            .putExtra("e", encoder.getException());
                    Post(ERROR, intent);
                } catch (JSONException e) {
                    throw new RuntimeException(e);
                }
            }
        });
    }

    public void encoding(File in, Uri targetUri, RawSamples.Info info) {
        OnFlyEncoding fly = new OnFlyEncoding(storage, targetUri, info);
        encoder = new FileEncoder(storage.getContext(), in, info, fly);
        filters(encoder, info);
        encoding(encoder, fly, info, new Runnable() {
            @Override
            public void run() {
                exit();
            }
        });
    }

    public void saveAsWAV(File in, File out, RawSamples.Info info) {
        OnFlyEncoding fly = new OnFlyEncoding(storage, out, info);
        encoder = new FileEncoder(storage.getContext(), in, info, fly);
        encoding(encoder, fly, info, new Runnable() {
            @Override
            public void run() {
                exit();
            }
        });
    }

    public void exit() {
        if (encoder != null) {
            encoder.close();
            encoder = null;
        }
        Post(EXIT, null);
    }

    public void restart() {
        if (encoder != null) {
            encoder.close();
            encoder = null;
        }
        startEncoding();
    }

    public void Post(int what, Object p) {
        synchronized (handlers) {
            for (Handler h : handlers)
                h.obtainMessage(what, p).sendToTarget();
        }
    }
}
