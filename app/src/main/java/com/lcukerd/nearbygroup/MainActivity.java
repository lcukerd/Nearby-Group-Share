package com.lcukerd.nearbygroup;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.lcukerd.nearbygroup.models.WifiService;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private ArrayAdapter<String> devicesAdapter;
    private ArrayList<String> devicesToConnect = new ArrayList<>();
    private String name;
    private WifiService wifiService;
    private static final String tag = MainActivity.class.getSimpleName();
    private boolean closingApp = true;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        Log.d(tag,"onCreate");
        setContentView(R.layout.activity_main);
        stopService(new Intent(this, BackgroundDiscovery.class));

        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(this);
        if (preferences.getString("DeviceName", "doncha know").equals("doncha know"))
        {
            Log.d(tag,"Naming Device");
            preferences.edit().putString("DeviceName", "Papa").commit();
        }
        name = preferences.getString("DeviceName", "doncha know");

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
                Intent intent = new Intent(getApplicationContext(),NearbyActivity.class);
                intent.putExtra("ReqdDevices",devicesToConnect);
                intent.putExtra("MyName",name);
                intent.putExtra("type","Activity");
                closingApp = false;
                startActivity(intent);
            }
        });

        wifiService = new WifiService("Activity",name, this,devicesAdapter);
        wifiService.setup();
    }

    @Override
    protected void onStop()
    {
        super.onStop();
        wifiService.stopService();
        Log.d(tag,"onStop");
        if (closingApp)
            startService(new Intent(this, BackgroundDiscovery.class));
    }

}
