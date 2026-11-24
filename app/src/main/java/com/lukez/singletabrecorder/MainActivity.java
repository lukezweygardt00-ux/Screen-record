package com.lukez.singletabrecorder;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_PERMS = 1;
    private static final int REQ_PROJECTION = 2;

    private static final String[] LABELS = {
            "180p (tiny file)", "480p", "720p", "1080p (best quality)"};
    private static final int[] HEIGHTS = {180, 480, 720, 1080};

    private Spinner resolution;
    private CheckBox micBox, internalBox;
    private Button toggle;
    private TextView status;
    private ListView list;
    private final List<Uri> uris = new ArrayList<>();
    private final List<String> names = new ArrayList<>();
    private ArrayAdapter<String> adapter;

    private final BroadcastReceiver stateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            refresh();
        }
    };

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(28), dp(20), dp(16));
        root.setBackgroundColor(Color.parseColor("#F4F6FB"));

        TextView title = new TextView(this);
        title.setText("Screen Recorder Single Tab");
        title.setTextSize(24);
        title.setTextColor(Color.parseColor("#1B2340"));
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("When the system asks what to share, choose \"A single app\" "
                + "to record just one app window.");
        sub.setTextColor(Color.parseColor("#55608A"));
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        resolution = new Spinner(this);
        resolution.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, LABELS));
        resolution.setSelection(3);
        root.addView(resolution);

        micBox = new CheckBox(this);
        micBox.setText("Record microphone");
        micBox.setChecked(true);
        root.addView(micBox);

        internalBox = new CheckBox(this);
        internalBox.setText("Record internal audio (what the app/headset plays)");
        internalBox.setChecked(true);
        root.addView(internalBox);

        toggle = new Button(this);
        toggle.setAllCaps(false);
        toggle.setTextSize(18);
        toggle.setTextColor(Color.WHITE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(60));
        lp.topMargin = dp(12);
        toggle.setLayoutParams(lp);
        toggle.setOnClickListener(v -> onToggle());
        root.addView(toggle);

        status = new TextView(this);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(8), 0, dp(12));
        root.addView(status);

        TextView hdr = new TextView(this);
        hdr.setText("Saved recordings (tap to play, long-press to share)");
        hdr.setTextColor(Color.parseColor("#1B2340"));
        hdr.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(hdr);

        list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names);
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> {
            Intent i = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uris.get(pos), "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try { startActivity(i); } catch (Exception e) {
                Toast.makeText(this, "No video player found", Toast.LENGTH_SHORT).show();
            }
        });
        list.setOnItemLongClickListener((p, v, pos, id) -> {
            Intent i = new Intent(Intent.ACTION_SEND).setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, uris.get(pos))
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share recording"));
            return true;
        });
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(RecorderService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, f);
        }
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(stateReceiver);
    }

    private void refresh() {
        boolean rec = RecorderService.running;
        toggle.setText(rec ? "■  Stop and save" : "●  Start recording");
        toggle.setBackgroundColor(Color.parseColor(rec ? "#3B4A7A" : "#E0263B"));
        status.setText(rec ? "Recording…" : "Ready");
        status.setTextColor(Color.parseColor(rec ? "#E0263B" : "#55608A"));
        resolution.setEnabled(!rec);
        micBox.setEnabled(!rec);
        internalBox.setEnabled(!rec);
        loadRecordings();
    }

    private void loadRecordings() {
        uris.clear();
        names.clear();
        String[] proj = {MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME};
        try (Cursor c = getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ?",
                new String[]{RecorderService.FOLDER + "%"},
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            while (c != null && c.moveToNext()) {
                uris.add(ContentUris.withAppendedId(
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI, c.getLong(0)));
                names.add(c.getString(1));
            }
        } catch (Exception ignored) { }
        adapter.notifyDataSetChanged();
    }

    private void onToggle() {
        if (RecorderService.running) {
            startService(new Intent(this, RecorderService.class)
                    .setAction(RecorderService.ACTION_STOP));
            return;
        }
        List<String> need = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.RECORD_AUDIO);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        } else {
            askProjection();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (code == REQ_PERMS) askProjection(); // audio simply turns off if denied
    }

    private void askProjection() {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        if (req != REQ_PROJECTION) return;
        if (result != RESULT_OK || data == null) {
            Toast.makeText(this, "Screen capture was not allowed", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent i = new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACTION_START)
                .putExtra("code", result)
                .putExtra("data", data)
                .putExtra("height", HEIGHTS[resolution.getSelectedItemPosition()])
                .putExtra("mic", micBox.isChecked())
                .putExtra("internal", internalBox.isChecked());
        startForegroundService(i);
        // Move out of the way so the app being recorded is what's on screen.
        moveTaskToBack(true);
    }
}
