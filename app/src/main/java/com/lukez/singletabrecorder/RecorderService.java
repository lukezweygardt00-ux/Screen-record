package com.lukez.singletabrecorder;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.Toast;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Records the screen (or a single app, when the system offers that choice) to MP4.
 * Audio = microphone and/or internal (app/system) audio, mixed into one AAC track.
 */
public class RecorderService extends Service {

    public static final String ACTION_START = "com.lukez.singletabrecorder.START";
    public static final String ACTION_STOP = "com.lukez.singletabrecorder.STOP";
    public static final String ACTION_STATE = "com.lukez.singletabrecorder.STATE";
    public static final String FOLDER = "Movies/ScreenRecorderSingleTab";

    public static volatile boolean running = false;

    private static final String CHANNEL = "rec";
    private static final int NOTIF_ID = 42;
    private static final int SR = 44100;
    private static final int CHUNK_FRAMES = 1024;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final Object lock = new Object();

    private MediaProjection projection;
    private MediaProjection.Callback projectionCallback;
    private VirtualDisplay display;
    private MediaCodec venc, aenc;
    private MediaMuxer muxer;
    private ParcelFileDescriptor pfd;
    private Uri outUri;
    private AudioRecord micRec, pbRec;
    private Thread vThread, aThread;
    private volatile boolean stopping;

    private boolean muxStarted;
    private int tracksAdded, tracksExpected;
    private int vTrack = -1, aTrack = -1;
    private long startUs;
    private volatile boolean wroteAnything;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording();
        } else if (ACTION_START.equals(action) && !running) {
            begin(intent);
        }
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ start

    private void begin(Intent i) {
        int code = i.getIntExtra("code", 0);
        Intent data = i.getParcelableExtra("data");
        int shortSide = i.getIntExtra("height", 1080);
        boolean wantMic = i.getBooleanExtra("mic", true);
        boolean wantInternal = i.getBooleanExtra("internal", true);

        boolean audioPerm = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        if (!audioPerm) {
            wantMic = false;
            wantInternal = false;
        }

        try {
            makeChannel();
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            if (audioPerm) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE;
            startForeground(NOTIF_ID, buildNotification(), type);

            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(code, data);
            projectionCallback = new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopRecording();
                }
            };
            projection.registerCallback(projectionCallback, main);

            // ---- video size: chosen short side, never upscaled
            Rect b = getSystemService(WindowManager.class).getCurrentWindowMetrics().getBounds();
            int sw = b.width(), sh = b.height();
            int dpi = getResources().getDisplayMetrics().densityDpi;
            float scale = Math.min(1f, (float) shortSide / Math.min(sw, sh));
            int outW = even(Math.round(sw * scale));
            int outH = even(Math.round(sh * scale));

            int bitrate;
            if (shortSide <= 180) bitrate = 500_000;
            else if (shortSide <= 480) bitrate = 1_800_000;
            else if (shortSide <= 720) bitrate = 5_000_000;
            else bitrate = 10_000_000;

            openOutput();

            MediaFormat vf = MediaFormat.createVideoFormat("video/avc", outW, outH);
            vf.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            vf.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            vf.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            vf.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            vf.setLong("repeat-previous-frame-after", 100_000L);
            venc = MediaCodec.createEncoderByType("video/avc");
            venc.configure(vf, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface surface = venc.createInputSurface();
            venc.start();

            // ---- audio sources
            if (wantMic) micRec = buildMic();
            if (wantInternal) pbRec = buildPlayback();
            boolean audio = micRec != null || pbRec != null;
            if (audio) {
                MediaFormat af = MediaFormat.createAudioFormat("audio/mp4a-latm", SR, 2);
                af.setInteger(MediaFormat.KEY_AAC_PROFILE,
                        MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                af.setInteger(MediaFormat.KEY_BIT_RATE, 160_000);
                af.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384);
                aenc = MediaCodec.createEncoderByType("audio/mp4a-latm");
                aenc.configure(af, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
                aenc.start();
            }
            tracksExpected = audio ? 2 : 1;

            startUs = System.nanoTime() / 1000;
            display = projection.createVirtualDisplay("SingleTab", outW, outH, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, null);

            running = true;
            vThread = new Thread(this::videoLoop, "video-drain");
            vThread.start();
            if (audio) {
                if (micRec != null) micRec.startRecording();
                if (pbRec != null) pbRec.startRecording();
                aThread = new Thread(this::audioLoop, "audio-mix");
                aThread.start();
            }
            broadcastState();
            toast("Recording " + outW + "x" + outH
                    + (audio ? "" : " (no audio)"));
        } catch (Throwable t) {
            toast("Could not start: " + t.getMessage());
            running = true; // let cleanup run through the normal path
            stopRecording();
        }
    }

    private AudioRecord buildMic() {
        try {
            int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord r = new AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.MIC)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                    .setBufferSizeInBytes(Math.max(min, SR * 2))
                    .build();
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return null;
            }
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private AudioRecord buildPlayback() {
        try {
            AudioPlaybackCaptureConfiguration cfg =
                    new AudioPlaybackCaptureConfiguration.Builder(projection)
                            .addMatchingUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .addMatchingUsage(android.media.AudioAttributes.USAGE_GAME)
                            .addMatchingUsage(android.media.AudioAttributes.USAGE_UNKNOWN)
                            .build();
            int min = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_STEREO,
                    AudioFormat.ENCODING_PCM_16BIT);
            AudioRecord r = new AudioRecord.Builder()
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SR)
                            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
                    .setBufferSizeInBytes(Math.max(min, SR * 4))
                    .setAudioPlaybackCaptureConfig(cfg)
                    .build();
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return null;
            }
            return r;
        } catch (Throwable t) {
            return null;
        }
    }

    private void openOutput() throws Exception {
        String name = "SingleTab_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".mp4";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Video.Media.DISPLAY_NAME, name);
        cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        cv.put(MediaStore.Video.Media.RELATIVE_PATH, FOLDER);
        cv.put(MediaStore.Video.Media.IS_PENDING, 1);
        outUri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
        if (outUri == null) throw new IllegalStateException("cannot create output file");
        pfd = getContentResolver().openFileDescriptor(outUri, "rw");
        muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    }

    // ------------------------------------------------------------- encoders

    private void addTrack(boolean video, MediaFormat f) {
        synchronized (lock) {
            int idx = muxer.addTrack(f);
            if (video) vTrack = idx; else aTrack = idx;
            tracksAdded++;
            if (tracksAdded == tracksExpected) {
                muxer.start();
                muxStarted = true;
                lock.notifyAll();
            }
        }
    }

    private void writeSample(int track, ByteBuffer buf, MediaCodec.BufferInfo info) {
        synchronized (lock) {
            while (!muxStarted && !stopping) {
                try { lock.wait(50); } catch (InterruptedException e) { return; }
            }
            if (!muxStarted) return;
            try {
                muxer.writeSampleData(track, buf, info);
                wroteAnything = true;
            } catch (Throwable ignored) { }
        }
    }

    private void videoLoop() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (true) {
                int idx = venc.dequeueOutputBuffer(info, 10_000);
                if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    addTrack(true, venc.getOutputFormat());
                } else if (idx >= 0) {
                    ByteBuffer out = venc.getOutputBuffer(idx);
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                    if (info.size > 0 && out != null) {
                        info.presentationTimeUs = Math.max(0, info.presentationTimeUs - startUs);
                        out.position(info.offset);
                        out.limit(info.offset + info.size);
                        writeSample(vTrack, out, info);
                    }
                    venc.releaseOutputBuffer(idx, false);
                    if (eos) break;
                } else if (idx == MediaCodec.INFO_TRY_AGAIN_LATER && stopping) {
                    // encoder had no more output after we stopped; give it a moment
                    if (System.nanoTime() / 1000 - stopAtUs > 2_000_000) break;
                }
            }
        } catch (Throwable ignored) { }
    }

    private volatile long stopAtUs;

    /** Simple stereo FIFO of 16-bit samples. */
    private static final class Fifo {
        final short[] d = new short[SR * 2 * 2];
        int head = 0, size = 0;

        void pushStereo(short[] s, int n) {
            if (n > d.length) { s = java.util.Arrays.copyOfRange(s, n - d.length, n); n = d.length; }
            if (size + n > d.length) {
                int drop = size + n - d.length;
                head = (head + drop) % d.length;
                size -= drop;
            }
            for (int i = 0; i < n; i++) d[(head + size + i) % d.length] = s[i];
            size += n;
        }

        void pushMono(short[] s, int n) {
            short[] st = new short[n * 2];
            for (int i = 0; i < n; i++) { st[2 * i] = s[i]; st[2 * i + 1] = s[i]; }
            pushStereo(st, n * 2);
        }

        /** Adds up to n samples into dst (mixing), consuming them. */
        void popMixInto(int[] dst, int n) {
            int take = Math.min(n, size);
            for (int i = 0; i < take; i++) dst[i] += d[(head + i) % d.length];
            head = (head + take) % d.length;
            size -= take;
        }
    }

    private void audioLoop() {
        Fifo fm = new Fifo(), fp = new Fifo();
        short[] tmp = new short[4096];
        int[] acc = new int[CHUNK_FRAMES * 2];
        short[] pcm = new short[CHUNK_FRAMES * 2];
        long t0 = System.nanoTime();
        long basePtsUs = t0 / 1000 - startUs;
        long produced = 0;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!stopping) {
                int n;
                if (micRec != null) {
                    while ((n = micRec.read(tmp, 0, 2048, AudioRecord.READ_NON_BLOCKING)) > 0)
                        fm.pushMono(tmp, n);
                }
                if (pbRec != null) {
                    while ((n = pbRec.read(tmp, 0, 4096, AudioRecord.READ_NON_BLOCKING)) > 0)
                        fp.pushStereo(tmp, n);
                }
                long wantFrames = (System.nanoTime() - t0) * SR / 1_000_000_000L;
                while (wantFrames - produced >= CHUNK_FRAMES) {
                    java.util.Arrays.fill(acc, 0);
                    fm.popMixInto(acc, acc.length);
                    fp.popMixInto(acc, acc.length);
                    for (int k = 0; k < acc.length; k++) {
                        int v = acc[k];
                        pcm[k] = (short) (v > 32767 ? 32767 : (v < -32768 ? -32768 : v));
                    }
                    long pts = basePtsUs + produced * 1_000_000L / SR;
                    feedAudio(pcm, pcm.length, pts, false);
                    drainAudio(info, 0);
                    produced += CHUNK_FRAMES;
                }
                try { Thread.sleep(5); } catch (InterruptedException e) { break; }
            }
            long pts = basePtsUs + produced * 1_000_000L / SR;
            feedAudio(pcm, 0, pts, true);
            long deadline = System.nanoTime() + 1_500_000_000L;
            while (System.nanoTime() < deadline) {
                if (drainAudio(info, 20_000)) break;
            }
        } catch (Throwable ignored) { }
    }

    private void feedAudio(short[] pcm, int shorts, long ptsUs, boolean eos) {
        int idx = aenc.dequeueInputBuffer(20_000);
        if (idx < 0) return;
        ByteBuffer b = aenc.getInputBuffer(idx);
        b.clear();
        if (shorts > 0) b.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm, 0, shorts);
        aenc.queueInputBuffer(idx, 0, shorts * 2, ptsUs,
                eos ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
    }

    /** @return true once end-of-stream has been seen */
    private boolean drainAudio(MediaCodec.BufferInfo info, long timeoutUs) {
        while (true) {
            int idx = aenc.dequeueOutputBuffer(info, timeoutUs);
            if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                addTrack(false, aenc.getOutputFormat());
            } else if (idx >= 0) {
                ByteBuffer out = aenc.getOutputBuffer(idx);
                boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0 && out != null) {
                    out.position(info.offset);
                    out.limit(info.offset + info.size);
                    writeSample(aTrack, out, info);
                }
                aenc.releaseOutputBuffer(idx, false);
                if (eos) return true;
            } else {
                return false;
            }
        }
    }

    // ------------------------------------------------------------------ stop

    private void stopRecording() {
        if (!stopRequested.compareAndSet(false, true)) return;
        new Thread(this::finish, "finish").start();
    }

    private void finish() {
        stopAtUs = System.nanoTime() / 1000;
        stopping = true;
        synchronized (lock) { lock.notifyAll(); }
        try { if (venc != null && vThread != null) venc.signalEndOfInputStream(); } catch (Throwable ignored) { }
        join(aThread, 3000);
        join(vThread, 3000);

        try { if (projection != null && projectionCallback != null) projection.unregisterCallback(projectionCallback); } catch (Throwable ignored) { }
        try { if (display != null) display.release(); } catch (Throwable ignored) { }
        try { if (projection != null) projection.stop(); } catch (Throwable ignored) { }
        try { if (micRec != null) { micRec.stop(); micRec.release(); } } catch (Throwable ignored) { }
        try { if (pbRec != null) { pbRec.stop(); pbRec.release(); } } catch (Throwable ignored) { }
        try { if (venc != null) { venc.stop(); venc.release(); } } catch (Throwable ignored) { }
        try { if (aenc != null) { aenc.stop(); aenc.release(); } } catch (Throwable ignored) { }
        synchronized (lock) {
            try { if (muxer != null && muxStarted) muxer.stop(); } catch (Throwable ignored) { }
            try { if (muxer != null) muxer.release(); } catch (Throwable ignored) { }
        }
        try { if (pfd != null) pfd.close(); } catch (Throwable ignored) { }

        if (outUri != null) {
            try {
                if (wroteAnything) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                    getContentResolver().update(outUri, cv, null, null);
                    toast("Saved to " + FOLDER);
                } else {
                    getContentResolver().delete(outUri, null, null);
                }
            } catch (Throwable ignored) { }
        }

        running = false;
        broadcastState();
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private static void join(Thread t, long ms) {
        if (t == null) return;
        try { t.join(ms); } catch (InterruptedException ignored) { }
    }

    // --------------------------------------------------------------- helpers

    private static int even(int v) {
        return Math.max(2, v & ~1);
    }

    private void broadcastState() {
        Intent i = new Intent(ACTION_STATE).setPackage(getPackageName());
        sendBroadcast(i);
    }

    private void toast(String msg) {
        main.post(() -> Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_LONG).show());
    }

    private void makeChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Recording",
                NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        Intent stop = new Intent(this, RecorderService.class).setAction(ACTION_STOP);
        PendingIntent pi = PendingIntent.getService(this, 0, stop,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("Screen Recorder Single Tab")
                .setContentText("Recording… tap Stop to save")
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(null, "Stop", pi).build())
                .build();
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
