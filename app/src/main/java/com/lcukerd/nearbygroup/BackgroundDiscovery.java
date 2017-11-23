package com.lcukerd.nearbygroup;

import android.app.Fragment;
import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Binder;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by Programmer on 23-11-2017.
 */

public class BackgroundDiscovery extends Service {

    private final IBinder mBinder = new LocalBinder();
    private static final String tag = BackgroundDiscovery.class.getSimpleName();
    public static final String TXTRECORD_PROP_AVAILABLE = "available";
    public static final String SERVICE_INSTANCE = "_NearbyGroup";
    public static final String SERVICE_REG_TYPE = "_presence._tcp";

    public static final String NAME = "Owner_Name", STATUS = "status";
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private static final IntentFilter intentFilter = new IntentFilter();

    private BroadcastReceiver receiver = null;

    private static WifiP2pDnsSdServiceRequest serviceRequest;
    private static CountDownTimer countDownTimer;

    @Override
    public void onCreate()
    {
        Log.d(tag, "onCreate started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        Log.d(tag, "onStartCommand started");

        manager = (WifiP2pManager) getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);
        startWifiP2P("service",manager,channel);
        receiver = new WiFiDirectBroadcastReceiver();
        registerReceiver(receiver, intentFilter);

        Notification notification = new Notification.Builder(this)
                .setContentTitle("Nearby Group")
                .setTicker("Ticker")
                .setWhen(System.currentTimeMillis())
                .setContentText("Running")
                .setPriority(Notification.PRIORITY_MIN)
                .build();
        startForeground(509,notification);

        return START_STICKY;
    }

    public static IntentFilter startWifiP2P(final String caller,final WifiP2pManager manager,
                                    final WifiP2pManager.Channel channel)
    {
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        setupregitery(caller,manager,channel);

        countDownTimer = new CountDownTimer(120000, 120000) {

            public void onTick(long millisUntilFinished)
            {

            }

            public void onFinish()
            {
                Log.d(tag, "Restarting");
                setupregitery(caller,manager,channel);
                countDownTimer.start();
            }
        }.start();
        return intentFilter;
    }

    private static void setupregitery(String caller,WifiP2pManager manager,WifiP2pManager.Channel channel)
    {
        if (!caller.equals("service"))
            startRegistrationAndDiscovery("connect","id",manager,channel);
        else
        {
            startRegistrationAndDiscovery("discover","mummy",manager,channel);
            discoverService(manager,channel);
        }
    }


    private static void startRegistrationAndDiscovery(String status,String id,WifiP2pManager manager,WifiP2pManager.Channel channel)
    {
        Map<String, String> record = new HashMap<String, String>();
        record.put(TXTRECORD_PROP_AVAILABLE, "visible");
        record.put(STATUS,status);
        record.put(NAME, id);

        WifiP2pDnsSdServiceInfo service = WifiP2pDnsSdServiceInfo.newInstance(
                SERVICE_INSTANCE, SERVICE_REG_TYPE, record);
        manager.removeGroup(channel, null);
        manager.addLocalService(channel, service, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess()
            {
                appendStatus("Added Local Service");
            }

            @Override
            public void onFailure(int error)
            {
                appendStatus("Failed to add a service");
            }
        });
    }

    private static void discoverService(WifiP2pManager manager,WifiP2pManager.Channel channel)
    {

        manager.setDnsSdResponseListeners(channel,
                new WifiP2pManager.DnsSdServiceResponseListener() {

                    @Override
                    public void onDnsSdServiceAvailable(String instanceName,
                                                        String registrationType, WifiP2pDevice srcDevice)
                    {

                        if (instanceName.equalsIgnoreCase(SERVICE_INSTANCE)) {
                            Log.d(tag, "Service Available " + instanceName);
                        }
                    }


                }, new WifiP2pManager.DnsSdTxtRecordListener() {

                    @Override
                    public void onDnsSdTxtRecordAvailable(
                            String fullDomainName, Map<String, String> record,
                            WifiP2pDevice device)
                    {
                        if (record.get(STATUS).equals("connect"))
                            record.get(NAME);                   //start nearby
                        Log.d(tag,record.get(NAME) + " is " + record.get(TXTRECORD_PROP_AVAILABLE));
                    }
                });


        serviceRequest = WifiP2pDnsSdServiceRequest.newInstance();
        manager.addServiceRequest(channel, serviceRequest, new WifiP2pManager.ActionListener() {

                    @Override
                    public void onSuccess()
                    {
                        appendStatus("Added service discovery request");
                    }

                    @Override
                    public void onFailure(int arg0)
                    {
                        appendStatus("Failed adding service discovery request");
                    }
                });
        manager.discoverServices(channel, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess()
            {
                appendStatus("Service discovery initiated");
            }

            @Override
            public void onFailure(int arg0)
            {
                appendStatus("Service discovery failed");

            }
        });
    }

    public static void appendStatus(String status)
    {
        Log.d(tag, "Status : " + status);
    }

    @Override
    public void onDestroy()
    {
        Log.d(tag,"onDestroy called");
        if (manager != null && channel != null) {
            unregisterReceiver(receiver);
            manager.removeGroup(channel, new WifiP2pManager.ActionListener() {

                @Override
                public void onFailure(int reasonCode)
                {
                    Log.d(tag, "Disconnect failed. Reason :" + reasonCode);
                }

                @Override
                public void onSuccess()
                {
                }

            });
        }
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
