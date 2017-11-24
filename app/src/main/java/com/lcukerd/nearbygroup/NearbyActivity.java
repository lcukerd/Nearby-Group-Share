package com.lcukerd.nearbygroup;

import android.Manifest;
import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.Vibrator;
import android.support.annotation.Nullable;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.KeyEvent;
import android.widget.TextView;

import com.google.android.gms.nearby.connection.Payload;
import com.lcukerd.nearbygroup.NearbyStuff.State;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static com.lcukerd.nearbygroup.NearbyStuff.hasPermissions;

public class NearbyActivity extends AppCompatActivity {

    private NearbyStuff nearbyStuff;
    private static final long VIBRATION_STRENGTH = 500;
    private static final String tag = NearbyActivity.class.getSimpleName();
    private String mName;
    private static final String[] REQUIRED_PERMISSIONS =
            new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO
            };

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


    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main_old);
        getSupportActionBar()
                .setBackgroundDrawable(ContextCompat.getDrawable(this, R.drawable.actionBar));

        mName = generateRandomName();

        ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 0);

        ((TextView) findViewById(R.id.name)).setText(mName);
        vibrate();
        nearbyStuff = new NearbyStuff("Server", "");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event)
    {
        if (nearbyStuff.mState == State.CONNECTED && mGestureDetector.onKeyEvent(event)) {
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

        nearbyStuff.setState(State.UNKNOWN);

        mUiHandler.removeCallbacksAndMessages(null);

        super.onStop();
    }

    @Override
    public void onBackPressed()
    {
        if (nearbyStuff.mState == State.CONNECTED || nearbyStuff.mState == State.ADVERTISING) {
            nearbyStuff.setState(State.DISCOVERING);
            return;
        }
        super.onBackPressed();
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

            nearbyStuff.send(Payload.fromStream(payloadPipe[0]));

            // Use the second half of the payload (the write side) in AudioRecorder.
            mRecorder = new AudioRecorder(payloadPipe[1]);
            mRecorder.start();
        } catch (IOException e) {
            Log.e(tag, "startRecording() failed", e);
        }
    }

    private void stopRecording()
    {
        if (mRecorder != null) {
            mRecorder.stop();
            mRecorder = null;
        }
    }

    private boolean isRecording()
    {
        return mRecorder != null && mRecorder.isRecording();
    }


}
