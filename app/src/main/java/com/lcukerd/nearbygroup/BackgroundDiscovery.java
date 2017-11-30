package com.lcukerd.nearbygroup;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Binder;
import android.os.CountDownTimer;
import android.os.IBinder;
import android.preference.PreferenceManager;
import android.util.Log;

import com.lcukerd.nearbygroup.models.WifiService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by Programmer on 23-11-2017.
 */

public class BackgroundDiscovery extends Service {

    private final IBinder mBinder = new LocalBinder();
    private static final String tag = BackgroundDiscovery.class.getSimpleName();
    private WifiService wifiService;

    @Override
    public void onCreate()
    {
        Log.d(tag, "onCreate started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        Log.d(tag, "onStartCommand started");

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        wifiService = new WifiService("Service",
                preferences.getString("DeviceName", "doncha know"), this,null);
        wifiService.setup();

        Notification notification = new Notification.Builder(this)
                .setContentTitle("Nearby Group")
                .setTicker("Ticker")
                .setWhen(System.currentTimeMillis())
                .setContentText("Running")
                .setPriority(Notification.PRIORITY_MIN)
                .build();
        startForeground(509, notification);

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy()
    {
        Log.d(tag, "onDestroy called");
        wifiService.stopService();
    }

    public class LocalBinder extends Binder {
        BackgroundDiscovery getService()
        {
            return BackgroundDiscovery.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        return mBinder;
    }
}
