package com.lcukerd.nearbygroup;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.annotation.WorkerThread;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.widget.Toast;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.common.api.Status;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.Connections;
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Created by Programmer on 23-11-2017.
 */

public class NearbyStuff implements GoogleApiClient.OnConnectionFailedListener, GoogleApiClient.ConnectionCallbacks {

    private static final String[] REQUIRED_PERMISSIONS =
            new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN,
                    Manifest.permission.ACCESS_WIFI_STATE,
                    Manifest.permission.CHANGE_WIFI_STATE,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.RECORD_AUDIO
            };
    private ArrayList<String> reqdDevices = new ArrayList<>();
    private String serverName;
    private final long ADVERTISING_DURATION = 30000;
    private final Strategy STRATEGY = Strategy.P2P_STAR;
    private GoogleApiClient mGoogleApiClient;
    private final Set<AudioPlayer> mAudioPlayers = new HashSet<>();
    private final Map<String, Endpoint> mDiscoveredEndpoints = new HashMap<>(),
            mPendingConnections = new HashMap<>(),
            mEstablishedConnections = new HashMap<>();
    private boolean mIsConnecting = false, mIsDiscovering = false, mIsAdvertising = false;
    public State mState = State.UNKNOWN;
    private String type, name, SERVICE_ID = "com.lcukerd.NearbyGroupSERVICE";
    private static final String tag = NearbyStuff.class.getSimpleName();
    public final Handler mUiHandler = new Handler(Looper.getMainLooper());
    private final Runnable mDiscoverRunnable =
            new Runnable() {
                @Override
                public void run()
                {
                    setState(State.DISCOVERING);
                }
            };
    private final Runnable mAdvertiseRunnable =
            new Runnable() {
                @Override
                public void run()
                {
                    setState(State.ADVERTISING);
                }
            };

    public NearbyStuff(Context context, String type, String name, Object devices)
    {
        this.type = type;
        this.name = name;
        if (type.equals("Activity"))
            this.reqdDevices.addAll((ArrayList<String>) devices);
        else if (type.equals("Service"))
            this.serverName = (String) devices;
        createNearby(type, context);
    }

    public void createNearby(String type, Context context)
    {
        if (hasPermissions(context, REQUIRED_PERMISSIONS)) {
            createGoogleApiClient(context);
        } else {
            Toast.makeText(context, "You fuckin changed permissions!", Toast.LENGTH_SHORT);
        }
    }

    private void createGoogleApiClient(Context context)
    {
        if (mGoogleApiClient == null) {
            mGoogleApiClient =
                    new GoogleApiClient.Builder(context)
                            .addApi(Nearby.CONNECTIONS_API)
                            .addConnectionCallbacks(this)
                            .build();
        }
    }

    private void startAdvertising()
    {
        Nearby.Connections.startAdvertising(
                mGoogleApiClient,
                name,
                SERVICE_ID,
                mConnectionLifecycleCallback,
                new AdvertisingOptions(STRATEGY))
                .setResultCallback(
                        new ResultCallback<Connections.StartAdvertisingResult>() {
                            @Override
                            public void onResult(@NonNull Connections.StartAdvertisingResult result)
                            {
                                if (result.getStatus().isSuccess()) {
                                    setState(State.ADVERTISING);
                                    Log.d(tag, "Now advertising endpoint " + result.getLocalEndpointName());
                                    mIsAdvertising = true;
                                } else {
                                    mIsAdvertising = false;
                                    Log.e(tag, "Advertising failed. Received status." +
                                            getString(result.getStatus()));
                                }
                            }
                        });
    }

    private void startDiscovering()
    {
        mIsDiscovering = true;
        mDiscoveredEndpoints.clear();
        Nearby.Connections.startDiscovery(
                mGoogleApiClient,
                SERVICE_ID,
                new EndpointDiscoveryCallback() {
                    @Override
                    public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info)
                    {
                        Log.d(tag, String.format(
                                "onEndpointFound(endpointId=%s, serviceId=%s, endpointName=%s)",
                                endpointId, info.getServiceId(), info.getEndpointName()));

                        if (SERVICE_ID.equals(info.getServiceId())) {           //check for connection here
                            Endpoint endpoint = new Endpoint(endpointId, info.getEndpointName());
                            mDiscoveredEndpoints.put(endpointId, endpoint);
                            if (serverName.equals(endpoint.getName()))
                                connectToEndpoint(endpoint);
                        }
                    }

                    @Override
                    public void onEndpointLost(String endpointId)
                    {
                        Log.d(tag, String.format("onEndpointLost(endpointId=%s)", endpointId));
                    }
                },
                new DiscoveryOptions(STRATEGY))
                .setResultCallback(
                        new ResultCallback<Status>() {
                            @Override
                            public void onResult(@NonNull Status status)
                            {
                                if (status.isSuccess()) {
                                    Log.d(tag, "Discovering...");
                                } else {
                                    mIsDiscovering = false;
                                    Log.e(tag, "Discovering failed. Received status %s." +
                                            status.getStatusMessage());
                                }
                            }
                        });
    }

    private void connectToEndpoint(final Endpoint endpoint)
    {
        if (mIsConnecting) {
            Log.d(tag, "Already connecting, so ignoring this endpoint: " + endpoint);
            return;
        }

        Log.d(tag, "Sending a connection request to endpoint " + endpoint);
        mIsConnecting = true;
        Nearby.Connections.requestConnection(mGoogleApiClient, name, endpoint.getId(),
                mConnectionLifecycleCallback)
                .setResultCallback(
                        new ResultCallback<Status>() {
                            @Override
                            public void onResult(@NonNull Status status)
                            {
                                if (!status.isSuccess()) {
                                    Log.e(tag, String.format("requestConnection failed. %s", getString(status)));
                                    mIsConnecting = false;
                                    onConnectionFailed(endpoint);
                                } else {
                                    Log.d(tag, "Connected to " + endpoint.getName());
                                }
                            }
                        });
    }

    private final ConnectionLifecycleCallback mConnectionLifecycleCallback =
            new ConnectionLifecycleCallback() {
                @Override
                public void onConnectionInitiated(final String endpointId, ConnectionInfo connectionInfo)
                {
                    Log.d(tag, String.format("onConnectionInitiated(endpointId=%s, endpointName=%s)",
                            endpointId, connectionInfo.getEndpointName()));
                    Endpoint endpoint = new Endpoint(endpointId, connectionInfo.getEndpointName());
                    mPendingConnections.put(endpointId, endpoint);
                    if (type.equals("Server")) {
                        if (reqdDevices.remove(endpoint.getName()))
                            acceptConnection(endpoint);
                        else
                            rejectConnection(endpoint);
                    }
                }

                @Override
                public void onConnectionResult(String endpointId, ConnectionResolution result)
                {
                    switch (result.getStatus().getStatusCode()) {
                        case ConnectionsStatusCodes.STATUS_OK:
                            Log.d(tag, String.format("onConnectionResponse(endpointId=%s, result=%s)", endpointId, result));
                            connectedToEndpoint(mPendingConnections.remove(endpointId));
                            break;
                        case ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED:
                        case ConnectionsStatusCodes.STATUS_ERROR:
                            if (type.equals("Server")) {
                                Log.e(tag, String.format("Connection with client %s failed. Received status %s.",
                                        endpointId, getString(result.getStatus())));
                            } else {
                                Log.e(tag, String.format("Connection with server %s failed. Received status %s.",
                                        endpointId, getString(result.getStatus())));
                                onConnectionFailed(mPendingConnections.remove(endpointId));
                            }
                            break;
                    }
                    mIsConnecting = false;
                }

                @Override
                public void onDisconnected(String endpointId)
                {
                    if (!mEstablishedConnections.containsKey(endpointId)) {
                        Log.e(tag, "Unexpected disconnection from endpoint " + endpointId);
                        return;
                    }
                    disconnectedFromEndpoint(mEstablishedConnections.get(endpointId));
                }
            };

    public void send(Payload payload)
    {
        ArrayList<String> ids = new ArrayList<>();
        for (Endpoint e : getConnectedEndpoints())
            ids.add(e.getId());
        Nearby.Connections.sendPayload(mGoogleApiClient, ids, payload)
                .setResultCallback(
                        new ResultCallback<Status>() {
                            @Override
                            public void onResult(@NonNull Status status)
                            {
                                if (!status.isSuccess()) {
                                    Log.d(tag, String.format("sendUnreliablePayload failed. %s", getString(status)));
                                }
                            }
                        });
    }

    private final PayloadCallback mPayloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String endpointId, Payload payload)
        {
            onReceive(mEstablishedConnections.get(endpointId), payload);
        }

        @Override
        public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update)
        {
            Log.d(tag, String.format("onPayloadTransferUpdate(endpointId=%s, update=%s)", endpointId, update));
        }
    };

    private void resetState()
    {
        mDiscoveredEndpoints.clear();
        mPendingConnections.clear();
        mEstablishedConnections.clear();
        mIsConnecting = false;
        mIsDiscovering = false;
        mIsAdvertising = false;
    }

    public void stopAdvertising()
    {
        mIsAdvertising = false;
        Nearby.Connections.stopAdvertising(mGoogleApiClient);
    }

    public void stopDiscovering()
    {
        mIsDiscovering = false;
        Nearby.Connections.stopDiscovery(mGoogleApiClient);
    }

    protected void acceptConnection(final Endpoint endpoint)
    {
        Nearby.Connections.acceptConnection(mGoogleApiClient, endpoint.getId(), mPayloadCallback)
                .setResultCallback(
                        new ResultCallback<Status>() {
                            @Override
                            public void onResult(@NonNull Status status)
                            {
                                if (!status.isSuccess()) {
                                    Log.d(tag, String.format("acceptConnection failed. %s", getString(status)));
                                }
                            }
                        });
    }

    protected void rejectConnection(Endpoint endpoint)
    {
        Nearby.Connections.rejectConnection(mGoogleApiClient, endpoint.getId())
                .setResultCallback(
                        new ResultCallback<Status>() {
                            @Override
                            public void onResult(@NonNull Status status)
                            {
                                if (!status.isSuccess()) {
                                    Log.d(tag, String.format("rejectConnection failed. %s", getString(status)));
                                }
                            }
                        });
    }

    public void disconnect(Endpoint endpoint)
    {
        Nearby.Connections.disconnectFromEndpoint(mGoogleApiClient, endpoint.getId());
        mEstablishedConnections.remove(endpoint.getId());
    }

    public void disconnectFromAllEndpoints()
    {
        for (Endpoint endpoint : mEstablishedConnections.values()) {
            Nearby.Connections.disconnectFromEndpoint(mGoogleApiClient, endpoint.getId());
        }
        mEstablishedConnections.clear();
    }

    protected void onReceive(Endpoint endpoint, Payload payload)
    {
        if (payload.getType() == Payload.Type.STREAM) {
            AudioPlayer player = new AudioPlayer(payload.asStream().asInputStream()) {
                @WorkerThread
                @Override
                protected void onFinish()
                {
                    final AudioPlayer audioPlayer = this;
                    post(
                            new Runnable() {
                                @UiThread
                                @Override
                                public void run()
                                {
                                    mAudioPlayers.remove(audioPlayer);
                                }
                            });
                }
            };
            mAudioPlayers.add(player);
            player.start();
        }
    }

    public void stopPlaying()
    {
        for (AudioPlayer player : mAudioPlayers) {
            player.stop();
        }
        mAudioPlayers.clear();
    }

    public boolean isPlaying()
    {
        return !mAudioPlayers.isEmpty();
    }

    private void connectedToEndpoint(Endpoint endpoint)
    {
        Log.d(tag, String.format("connectedToEndpoint(endpoint=%s)", endpoint));
        mEstablishedConnections.put(endpoint.getId(), endpoint);
    }

    private void disconnectedFromEndpoint(Endpoint endpoint)
    {
        Log.d(tag, String.format("disconnectedFromEndpoint(endpoint=%s)", endpoint));
        mEstablishedConnections.remove(endpoint.getId());
        if (getConnectedEndpoints().isEmpty()) {
            setState(State.DISCOVERING);
        }
    }

    public void setState(State state)
    {
        if (mState.equals(state)) {
            Log.d(tag, "State set to " + state + " but already in that state");
            return;
        }

        Log.d(tag, "State set to " + state);
        State oldState = mState;
        mState = state;
        onStateChanged(oldState, state);
    }

    protected Set<Endpoint> getDiscoveredEndpoints()
    {
        Set<Endpoint> endpoints = new HashSet<>();
        endpoints.addAll(mDiscoveredEndpoints.values());
        return endpoints;
    }

    protected Set<Endpoint> getConnectedEndpoints()
    {
        Set<Endpoint> endpoints = new HashSet<>();
        endpoints.addAll(mEstablishedConnections.values());
        return endpoints;
    }

    private String getString(Status status)
    {
        return String.format(
                Locale.US,
                "[%d]%s",
                status.getStatusCode(),
                status.getStatusMessage() != null
                        ? status.getStatusMessage()
                        : ConnectionsStatusCodes.getStatusCodeString(status.getStatusCode()));
    }

    public static boolean hasPermissions(Context context, String... permissions)
    {
        for (String permission : permissions) {
            if (ContextCompat.checkSelfPermission(context, permission)
                    != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    protected static class Endpoint {
        @NonNull
        private final String id;
        @NonNull
        private final String name;

        private Endpoint(@NonNull String id, @NonNull String name)
        {
            this.id = id;
            this.name = name;
        }

        @NonNull
        public String getId()
        {
            return id;
        }

        @NonNull
        public String getName()
        {
            return name;
        }

        @Override
        public boolean equals(Object obj)
        {
            if (obj != null && obj instanceof Endpoint) {
                Endpoint other = (Endpoint) obj;
                return id.equals(other.id);
            }
            return false;
        }

        @Override
        public int hashCode()
        {
            return id.hashCode();
        }

        @Override
        public String toString()
        {
            return String.format("Endpoint{id=%s, name=%s}", id, name);
        }
    }

    @Override
    public void onConnected(@Nullable Bundle bundle)
    {
        Log.d(tag,"googleAPI Connected");
        if (type.equals("Service"))
            setState(State.DISCOVERING);
        else if (type.equals("Activity"))
            setState(State.ADVERTISING);
    }

    @Override
    public void onConnectionSuspended(int reason)
    {
        setState(State.UNKNOWN);
    }

    protected void onConnectionFailed(Endpoint endpoint)
    {
        if (mState == State.DISCOVERING && !getDiscoveredEndpoints().isEmpty()) {
            connectToEndpoint(pickRandomElem(getDiscoveredEndpoints()));
        }
    }

    private static <T> T pickRandomElem(Collection<T> collection)
    {
        return (T) collection.toArray()[new Random().nextInt(collection.size())];
    }

    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult)
    {
        Log.e(tag, String.format("onConnectionFailed(%s)", getString(new Status(connectionResult.getErrorCode()))));
    }

    private void onStateChanged(State oldState, State newState)
    {
        switch (newState) {
            case DISCOVERING:
                if (mIsAdvertising) {
                    stopAdvertising();
                }
                disconnectFromAllEndpoints();
                startDiscovering();
                break;
            case ADVERTISING:
                if (mIsDiscovering) {
                    stopDiscovering();
                }
                disconnectFromAllEndpoints();
                startAdvertising();
                break;
            case CONNECTED:
                if (mIsDiscovering) {
                    stopDiscovering();
                } else if (mIsAdvertising) {
                    removeCallbacks(mDiscoverRunnable);
                }
                break;
            default:
                // no-op
                break;
        }
    }

    protected void post(Runnable r)
    {
        mUiHandler.post(r);
    }

    protected void postDelayed(Runnable r, long duration)
    {
        mUiHandler.postDelayed(r, duration);
    }

    protected void removeCallbacks(Runnable r)
    {
        mUiHandler.removeCallbacks(r);
    }

    public enum State {
        UNKNOWN,
        DISCOVERING,
        ADVERTISING,
        CONNECTED
    }
}
