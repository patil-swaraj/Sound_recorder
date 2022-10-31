package com.github.axet.audiorecorder.app;

import android.content.Context;
import android.net.Uri;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.github.axet.androidlibrary.widgets.ErrorDialog;
import com.github.axet.audiorecorder.R;
import com.github.axet.audiorecorder.services.EncodingService;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class Recordings extends com.github.axet.audiolibrary.app.Recordings {
    public View progressEmpty;
    public TextView progressText;
    public View refresh;

    public Recordings(Context context, RecyclerView list) {
        super(context, list);
    }

    public void setEmptyView(View empty) {
        this.empty.setEmptyView(empty);
        progressEmpty = empty.findViewById(R.id.progress_empty);
        progressText = (TextView) empty.findViewById(android.R.id.text1);
        refresh = empty.findViewById(R.id.refresh);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                load(false, null);
            }
        });
    }

    @Override
    public void load(Uri mount, boolean clean, Runnable done) {
        refresh.setVisibility(View.GONE);
        progressText.setText(R.string.recording_list_is_empty);
        if (!Storage.exists(context, mount)) {
            items.clear();
            if (done != null)
                done.run();
            return;
        }
        try {
            super.load(mount, clean, done);
        } catch (RuntimeException e) {
            Log.e(TAG, "load", e);
            progressText.setText(ErrorDialog.toMessage(e));
            refresh.setVisibility(View.VISIBLE);
            items.clear();
            if (done != null)
                done.run();
        }
    }

    @Override
    public void scan(List<Storage.Node> nn, boolean clean, Runnable done) {
        EncodingStorage encodings = ((AudioApplication)context.getApplicationContext()).encodings;
        for (Storage.Node n : new ArrayList<>(nn)) {
            for (File key : encodings.keySet()) {
                EncodingStorage.Info info = encodings.get(key);
                if (n.uri.equals(info.targetUri))
                    nn.remove(n);
            }
        }
        super.scan(nn, clean, done);
    }
}
