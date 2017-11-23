package com.lcukerd.nearbygroup;

import android.Manifest;
import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Vibrator;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.annotation.WorkerThread;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.text.SpannableString;
import android.text.format.DateFormat;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewAnimationUtils;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.Payload;

import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static com.lcukerd.nearbygroup.NearbyStuff.hasPermissions;

public class NearbyActivity extends AppCompatActivity {

    private static final long ADVERTISING_DURATION = 30000;
    private static final long VIBRATION_STRENGTH = 500;
    /**
     * This service id lets us find other nearby devices that are interested in the same thing. Our
     * sample does exactly one thing, so we hardcode the ID.
     */
    private static final String SERVICE_ID =
            "com.lcukerd.nearbytest.manual.SERVICE_ID";
    /**
     * The state of the app. As the app changes states, the UI will update and advertising/discovery
     * <p>
     * will start/stop.
     */
    private State mState = State.UNKNOWN;
    /**
     * A random UID used as this device's endpoint name.
     */
    private String mName;
    private TextView mDebugLogView;
    private final GestureDetector mGestureDetector =
            new GestureDetector(KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_UP) {
                @Override
                protected void onHold()
                {
                    startRecording();
                }

                @Override
                protected void onRelease()
                {
                    stopRecording();
                }
            };
    @Nullable
    private AudioRecorder mRecorder;
    private final Set<AudioPlayer> mAudioPlayers = new HashSet<>();
    private int mOriginalVolume;
    private final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable mDiscoverRunnable =
            new Runnable() {
                @Override
                public void run()
                {
                    setState(State.DISCOVERING);
                }
            };

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_old);
        getSupportActionBar()
                .setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.actionBar));

        mDebugLogView = (TextView) findViewById(R.id.debug_log);
        mDebugLogView.setVisibility(DEBUG ? View.VISIBLE : View.GONE);
        mDebugLogView.setMovementMethod(new ScrollingMovementMethod());

        mName = generateRandomName();

        ((TextView) findViewById(R.id.name)).setText(mName);
        vibrate();
        setState(State.ADVERTISING);
        postDelayed(mDiscoverRunnable, ADVERTISING_DURATION);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event)
    {
        if (mState == State.CONNECTED && mGestureDetector.onKeyEvent(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onStart()
    {
        super.onStart();
        // Set the media volume to max.
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        mOriginalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC, audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0);
    }

    @Override
    protected void onStop()
    {
        // Restore the original volume.
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, mOriginalVolume, 0);
        setVolumeControlStream(AudioManager.USE_DEFAULT_STREAM_TYPE);

        if (isRecording()) {
            stopRecording();
        }
        if (isPlaying()) {
            stopPlaying();
        }

        setState(State.UNKNOWN);

        mUiHandler.removeCallbacksAndMessages(null);

        super.onStop();
    }

    @Override
    public void onBackPressed()
    {
        if (getState() == State.CONNECTED || getState() == State.ADVERTISING) {
            setState(State.DISCOVERING);
            return;
        }
        super.onBackPressed();
    }

    private void setState(State state)
    {
        if (mState == state) {
            logW("State set to " + state + " but already in that state");
            return;
        }

        logD("State set to " + state);
        State oldState = mState;
        mState = state;
        onStateChanged(oldState, state);
    }

    private State getState()
    {
        return mState;
    }




    private void vibrate()
    {
        Vibrator vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
        if (hasPermissions(this, Manifest.permission.VIBRATE) && vibrator.hasVibrator()) {
            vibrator.vibrate(VIBRATION_STRENGTH);
        }
    }

    private void stopPlaying()
    {
        for (AudioPlayer player : mAudioPlayers) {
            player.stop();
        }
        mAudioPlayers.clear();
    }

    private boolean isPlaying()
    {
        return !mAudioPlayers.isEmpty();
    }

    private void startRecording()
    {
        try {
            ParcelFileDescriptor[] payloadPipe = ParcelFileDescriptor.createPipe();

            send(Payload.fromStream(payloadPipe[0]));

            // Use the second half of the payload (the write side) in AudioRecorder.
            mRecorder = new AudioRecorder(payloadPipe[1]);
            mRecorder.start();
        } catch (IOException e) {
            logE("startRecording() failed", e);
        }
    }

    /**
     * Stops streaming sound from the microphone.
     */
    private void stopRecording()
    {
        if (mRecorder != null) {
            mRecorder.stop();
            mRecorder = null;
        }
    }

    /**
     * @return True if currently streaming from the microphone.
     */
    private boolean isRecording()
    {
        return mRecorder != null && mRecorder.isRecording();
    }

    /**
     * {@see Handler#post()}
     */
    protected void post(Runnable r)
    {
        mUiHandler.post(r);
    }

    /**
     * {@see Handler#postDelayed(Runnable, long)}
     */
    protected void postDelayed(Runnable r, long duration)
    {
        mUiHandler.postDelayed(r, duration);
    }

    /**
     * {@see Handler#removeCallbacks(Runnable)}
     */
    protected void removeCallbacks(Runnable r)
    {
        mUiHandler.removeCallbacks(r);
    }


    @SuppressWarnings("unchecked")
    private static <T> T pickRandomElem(Collection<T> collection)
    {
        return (T) collection.toArray()[new Random().nextInt(collection.size())];
    }

    public enum State {
        UNKNOWN,
        DISCOVERING,
        ADVERTISING,
        CONNECTED
    }
}
