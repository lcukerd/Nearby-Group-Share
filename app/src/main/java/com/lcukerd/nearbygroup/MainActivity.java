package com.lcukerd.nearbygroup;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Map;

import static com.lcukerd.nearbygroup.BackgroundDiscovery.NAME;
import static com.lcukerd.nearbygroup.BackgroundDiscovery.STATUS;
import static com.lcukerd.nearbygroup.BackgroundDiscovery.TXTRECORD_PROP_AVAILABLE;

public class MainActivity extends AppCompatActivity {

    private ArrayAdapter<String> devicesAdapter;
    private ArrayList<String> devicesToConnect = new ArrayList<>();
    private WifiP2pManager manager;
    private WifiP2pManager.Channel channel;
    private BroadcastReceiver receiver;
    private static final String tag = MainActivity.class.getSimpleName();

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        stopService(new Intent(this, BackgroundDiscovery.class));

        devicesAdapter =
                new ArrayAdapter<String>(
                        this, // The current context (this activity)
                        R.layout.list_devices, // The name of the layout ID.
                        R.id.list_devices_textview, // The ID of the textview to populate.
                        new ArrayList<String>());
        ListView listView = (ListView) findViewById(R.id.device_list);
        listView.setAdapter(devicesAdapter);

        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {

            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int position, long l)
            {
                devicesToConnect.add(devicesAdapter.getItem(position));
            }
        });
        findViewById(R.id.done).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view)
            {

            }
        });

        manager = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
        channel = manager.initialize(this, getMainLooper(), null);
        IntentFilter intentFilter = BackgroundDiscovery.startWifiP2P("service", manager, channel);
        discoverServiceandpopulateAdapter(manager, channel);
        receiver = new WiFiDirectBroadcastReceiver();
        registerReceiver(receiver, intentFilter);
    }

    private void discoverServiceandpopulateAdapter(WifiP2pManager manager, WifiP2pManager.Channel channel)
    {

        manager.setDnsSdResponseListeners(channel, null,
                new WifiP2pManager.DnsSdTxtRecordListener() {
                    @Override
                    public void onDnsSdTxtRecordAvailable(
                            String fullDomainName, Map<String, String> record,
                            WifiP2pDevice device)
                    {
                        try {
                            if (!record.get(STATUS).equals("connect")) {
                                devicesAdapter.add(record.get(NAME));
                                devicesAdapter.notifyDataSetChanged();
                                Log.d(tag, record.get(NAME) + " is " + record.get(TXTRECORD_PROP_AVAILABLE));
                            }
                        } catch (NullPointerException e) {
                            Log.e(tag, "Not this apps service");
                        }
                    }
                });

        WifiP2pDnsSdServiceRequest serviceRequest = WifiP2pDnsSdServiceRequest.newInstance();
        manager.addServiceRequest(channel, serviceRequest, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess()
            {
                BackgroundDiscovery.appendStatus("Added service discovery request");
            }

            @Override
            public void onFailure(int arg0)
            {
                BackgroundDiscovery.appendStatus("Failed adding service discovery request");
            }
        });
        manager.discoverServices(channel, new WifiP2pManager.ActionListener() {

            @Override
            public void onSuccess()
            {
                BackgroundDiscovery.appendStatus("Service discovery initiated");
            }

            @Override
            public void onFailure(int arg0)
            {
                BackgroundDiscovery.appendStatus("Service discovery failed");

            }
        });
    }

    @Override
    protected void onStop()
    {
        super.onStop();
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
        startService(new Intent(this, BackgroundDiscovery.class));
    }

}
