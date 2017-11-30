package com.lcukerd.nearbygroup.models;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.CountDownTimer;
import android.util.Log;
import android.widget.ArrayAdapter;

import com.lcukerd.nearbygroup.NearbyActivity;
import com.lcukerd.nearbygroup.WiFiDirectBroadcastReceiver;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static android.os.Looper.getMainLooper;

/**
 * Created by Programmer on 30-11-2017.
 */

public class WifiService {

    private static final String tag = WifiService.class.getSimpleName();
    private String caller, ServiceName;
    private final String TXTRECORD_PROP_AVAILABLE = "available";
    private final String SERVICE_INSTANCE = "_NearbyGroup";
    private final String SERVICE_REG_TYPE = "_presence._tcp";
    private final String NAME = "Owner_Name", STATUS = "status";
    private ArrayAdapter<String> devicesAdapter;

    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver = null;
    private Context context;
    private final IntentFilter intentFilter = new IntentFilter();
    private CountDownTimer countDownTimer;
    private WifiP2pDnsSdServiceRequest serviceRequest;

    public WifiService(String caller, String ServiceName, Context context, ArrayAdapter<String> devicesAdapter)
    {
        this.caller = caller;
        this.context = context;
        this.ServiceName = ServiceName;
        this.devicesAdapter = devicesAdapter;
    }

    public void setup()
    {
        manager = (WifiP2pManager) context.getSystemService(Context.WIFI_P2P_SERVICE);
        channel = manager.initialize(context, getMainLooper(), null);
        receiver = new WiFiDirectBroadcastReceiver();
        startWifiP2P();
    }

    private void startWifiP2P()
    {
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

        setupregistery();
        countDownTimer = new CountDownTimer(120000, 120000) {

            public void onTick(long millisUntilFinished)
            {
            }

            public void onFinish()
            {
                Log.d(tag, "Restarting");
                setupregistery();
                countDownTimer.start();
            }
        }.start();
        context.registerReceiver(receiver, intentFilter);
    }

    private void setupregistery()
    {

        if (caller.equals("Activity"))
            RegisterService("connect");
        else
            RegisterService("discover");
        discoverService();
    }

    private void RegisterService(String status)
    {
        Map<String, String> record = new HashMap<String, String>();
        record.put(TXTRECORD_PROP_AVAILABLE, "visible");
        record.put(STATUS, status);
        record.put(NAME, ServiceName);

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

    private void discoverService()
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
                        try {
                            if (caller.equals("Service") && record.get(STATUS).equals("connect"))
                                startNearby(record.get(NAME));
                            else if (caller.equals("Activity") && record.get(STATUS).equals("discover")) {
                                devicesAdapter.add(record.get(NAME));
                                devicesAdapter.notifyDataSetChanged();
                            }
                            Log.d(tag, record.get(NAME) + " is "
                                    + record.get(TXTRECORD_PROP_AVAILABLE) + " " + record.get(STATUS));
                        }catch (NullPointerException e)
                        {
                            Log.e(tag,"Not this app service");
                        }
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

    private void appendStatus(String status)
    {
        Log.d(tag, "Status : " + status);
    }

    public void startNearby(String ServerName)
    {
        stopService();
        Intent intent = new Intent(context, NearbyActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("MyName", ServiceName);
        intent.putExtra("type", caller);
        intent.putExtra("ReqdDevices",ServerName);
        context.startActivity(intent);
    }

    public void stopService()
    {
        countDownTimer.cancel();
        if (manager != null && channel != null) {
            try {
                context.unregisterReceiver(receiver);
            } catch (IllegalArgumentException e)
            {
                Log.e(tag,"Reciever not registeres");
            }
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
}
