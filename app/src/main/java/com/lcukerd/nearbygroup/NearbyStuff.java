package com.lcukerd.nearbygroup;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.annotation.CallSuper;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.annotation.UiThread;
import android.support.annotation.WorkerThread;
import android.support.v4.content.ContextCompat;
import android.text.format.DateFormat;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static com.lcukerd.nearbygroup.Constants.TAG;

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
            };

    private static final int REQUEST_CODE_REQUIRED_PERMISSIONS = 1;
    private static final Strategy STRATEGY = Strategy.P2P_STAR;
    private GoogleApiClient mGoogleApiClient;
    private final Map<String, Endpoint> mDiscoveredEndpoints = new HashMap<>();
    private final Map<String, Endpoint> mPendingConnections = new HashMap<>();
    private final Map<String, Endpoint> mEstablishedConnections = new HashMap<>();
    private boolean mIsConnecting = false;
    private boolean mIsDiscovering = false;
    private boolean mIsAdvertising = false;
    private final ConnectionLifecycleCallback mConnectionLifecycleCallback =
            new ConnectionLifecycleCallback() {
                @Override
                public void onConnectionInitiated(String endpointId, ConnectionInfo connectionInfo)
                {
                    logD(
                            String.format(
                                    "onConnectionInitiated(endpointId=%s, endpointName=%s)",
                                    endpointId, connectionInfo.getEndpointName()));
                    Endpoint endpoint = new Endpoint(endpointId, connectionInfo.getEndpointName());
                    mPendingConnections.put(endpointId, endpoint);
                    onConnectionInitiated(endpoint, connectionInfo);
                }

                @Override
                public void onConnectionResult(String endpointId, ConnectionResolution result)
                {
                    logD(String.format("onConnectionResponse(endpointId=%s, result=%s)", endpointId, result));

                    // We're no longer connecting
                    mIsConnecting = false;

                    if (!result.getStatus().isSuccess()) {
                        logW(
                                String.format(
                                        "Connection failed. Received status %s.",
                                        toString(result.getStatus())));
                        onConnectionFailed(mPendingConnections.remove(endpointId));
                        return;
                    }
                    connectedToEndpoint(mPendingConnections.remove(endpointId));
                }

                @Override
                public void onDisconnected(String endpointId)
                {
                    if (!mEstablishedConnections.containsKey(endpointId)) {
                        logW("Unexpected disconnection from endpoint " + endpointId);
                        return;
                    }
                    disconnectedFromEndpoint(mEstablishedConnections.get(endpointId));
                }
            };

    /**
     * Callbacks for payloads (bytes of data) sent from another device to us.
     */
    private final PayloadCallback mPayloadCallback =
            new PayloadCallback() {
                @Override
                public void onPayloadReceived(String endpointId, Payload payload)
                {
                    onReceive(mEstablishedConnections.get(endpointId), payload);
                }

                @Override
                public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update)
                {
                    logD(
                            String.format(
                                    "onPayloadTransferUpdate(endpointId=%s, update=%s)", endpointId, update));
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

    private void createGoogleApiClient()
    {
        if (mGoogleApiClient == null) {
            mGoogleApiClient =
                    new GoogleApiClient.Builder(this)
                            .addApi(Nearby.CONNECTIONS_API)
                            .addConnectionCallbacks(this)
                            .enableAutoManage(this, this)
                            .build();
        }
    }

    /**
     * Our Activity has just been made visible to the user. Our GoogleApiClient will start connecting
     * after super.onStart() is called.
     */
    @Override
    protected void onStart()
    {
        if (hasPermissions(this, getRequiredPermissions())) {
            createGoogleApiClient();
        } else {
            requestPermissions(getRequiredPermissions(), REQUEST_CODE_REQUIRED_PERMISSIONS);
        }
        super.onStart();
    }

    /** We've connected to Nearby Connections' GoogleApiClient. */


    /**
     * The user has accepted (or denied) our permission request.
     */
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
    {
        if (requestCode == REQUEST_CODE_REQUIRED_PERMISSIONS) {
            for (int grantResult : grantResults) {
                if (grantResult == PackageManager.PERMISSION_DENIED) {
                    Toast.makeText(this, R.string.error_missing_permissions, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
            }
            recreate();
        }
    }

    /**
     * Sets the device to advertising mode. It will broadcast to other devices in discovery mode.
     * Either {@link #onAdvertisingStarted()} or {@link #onAdvertisingFailed()} will be called once
     * we've found out if we successfully entered this mode.
     */
    protected void startAdvertising()
    {
        mIsAdvertising = true;
        Nearby.Connections.startAdvertising(
                mGoogleApiClient,
                getName(),
                getServiceId(),
                mConnectionLifecycleCallback,
                new AdvertisingOptions(STRATEGY))
                .setResultCallback(
                        new ResultCallback<Connections.StartAdvertisingResult>() {
                            @Override
                            public void onResult(@NonNull Connections.StartAdvertisingResult result)
                            {
                                if (result.getStatus().isSuccess()) {
                                    logV("Now advertising endpoint " + result.getLocalEndpointName());
                                    onAdvertisingStarted();
                                } else {
                                    mIsAdvertising = false;
                                    logW(
                                            String.format(
                                                    "Advertising failed. Received status %s.",
                                                    ConnectionsActivity.toString(result.getStatus())));
                                    onAdvertisingFailed();
                                }
                            }
                        });
    }

    /**
     * Stops advertising.
     */
    protected void stopAdvertising()
    {
        mIsAdvertising = false;
        Nearby.Connections.stopAdvertising(mGoogleApiClient);
    }

    /**
     * @return True if currently advertising.
     */
    protected boolean isAdvertising()
    {
        return mIsAdvertising;
    }

    /**
     * Advertising has successfully started. Override this method to act on the event.
     */
    protected void onAdvertisingStarted()
    {
    }

    /**
     * Advertising has failed to start. Override this method to act on the event.
     */
    protected void onAdvertisingFailed()
    {
    }

    /**
     * A pending connection with a remote endpoint has been created. Use {@link ConnectionInfo} for
     * metadata about the connection (like incoming vs outgoing, or the authentication token). If we
     * want to continue with the connection, call {@link #acceptConnection(Endpoint)}. Otherwise, call
     * {@link #rejectConnection(Endpoint)}.
     */
    /**
     * Accepts a connection request.
     */
    protected void acceptConnection(final Endpoint endpoint)
    {
        Nearby.Connections.acceptConnection(mGoogleApiClient, endpoint.getId(), mPayloadCallback)
                .setResultCallback(
                        new ResultCallback<Status>() {
                            @Override
                            public void onResult(@NonNull Status status)
                            {
                                if (!status.isSuccess()) {
                                    logW(
                                            String.format(
                                                    "acceptConnection failed. %s", ConnectionsActivity.toString(status)));
                                }
                            }
                        });
    }

    /**
     * Rejects a connection request.
     */
    protected void rejectConnection(Endpoint endpoint)
    {
        Nearby.Connections.rejectConnection(mGoogleApiClient, endpoint.getId())
                .setResultCallback(
                        new ResultCallback<Status>() {
                            @Override
                            public void onResult(@NonNull Status status)
                            {
                                if (!status.isSuccess()) {
                                    logW(
                                            String.format(
                                                    "rejectConnection failed. %s", ConnectionsActivity.toString(status)));
                                }
                            }
                        });
    }

    /**
     * Sets the device to discovery mode. It will now listen for devices in advertising mode. Either
     * {@link #onDiscoveryStarted()} ()} or {@link #onDiscoveryFailed()} ()} will be called once we've
     * found out if we successfully entered this mode.
     */
    protected void startDiscovering()
    {
        mIsDiscovering = true;
        mDiscoveredEndpoints.clear();
        Nearby.Connections.startDiscovery(
                mGoogleApiClient,
                getServiceId(),
                new EndpointDiscoveryCallback() {
                    @Override
                    public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info)
                    {
                        logD(
                                String.format(
                                        "onEndpointFound(endpointId=%s, serviceId=%s, endpointName=%s)",
                                        endpointId, info.getServiceId(), info.getEndpointName()));

                        if (getServiceId().equals(info.getServiceId())) {
                            Endpoint endpoint = new Endpoint(endpointId, info.getEndpointName());
                            mDiscoveredEndpoints.put(endpointId, endpoint);
                            onEndpointDiscovered(endpoint);
                        }
                    }

                    @Override
                    public void onEndpointLost(String endpointId)
                    {
                        logD(String.format("onEndpointLost(endpointId=%s)", endpointId));
                    }
                },
                new DiscoveryOptions(STRATEGY))
                .setResultCallback(
                        new ResultCallback<Status>() {
                            @Override
                            public void onResult(@NonNull Status status)
                            {
                                if (status.isSuccess()) {
                                    onDiscoveryStarted();
                                } else {
                                    mIsDiscovering = false;
                                    logW(
                                            String.format(
                                                    "Discovering failed. Received status %s.",
                                                    ConnectionsActivity.toString(status)));
                                    onDiscoveryFailed();
                                }
                            }
                        });
    }

    /**
     * Stops discovery.
     */
    protected void stopDiscovering()
    {
        mIsDiscovering = false;
        Nearby.Connections.stopDiscovery(mGoogleApiClient);
    }

    /**
     * @return True if currently discovering.
     */
    protected boolean isDiscovering()
    {
        return mIsDiscovering;
    }

    /**
     * Discovery has successfully started. Override this method to act on the event.
     */
    protected void onDiscoveryStarted()
    {
    }

    /**
     * Discovery has failed to start. Override this method to act on the event.
     */
    protected void onDiscoveryFailed()
    {
    }

    /**
     * A remote endpoint has been discovered. Override this method to act on the event. To connect to
     * the device, call {@link #connectToEndpoint(Endpoint)}.
     */
    protected void disconnect(Endpoint endpoint)
    {
        Nearby.Connections.disconnectFromEndpoint(mGoogleApiClient, endpoint.getId());
        mEstablishedConnections.remove(endpoint.getId());
    }

    protected void disconnectFromAllEndpoints()
    {
        for (Endpoint endpoint : mEstablishedConnections.values()) {
            Nearby.Connections.disconnectFromEndpoint(mGoogleApiClient, endpoint.getId());
        }
        mEstablishedConnections.clear();
    }

    /**
     * Sends a connection request to the endpoint.
     */
    protected void connectToEndpoint(final Endpoint endpoint)
    {
        // If we already sent out a connection request, wait for it to return
        // before we do anything else. P2P_STAR only allows 1 outgoing connection.
        if (mIsConnecting) {
            logW("Already connecting, so ignoring this endpoint: " + endpoint);
            return;
        }

        logV("Sending a connection request to endpoint " + endpoint);
        // Mark ourselves as connecting so we don't connect multiple times
        mIsConnecting = true;

        // Ask to connect
        Nearby.Connections.requestConnection(
                mGoogleApiClient, getName(), endpoint.getId(), mConnectionLifecycleCallback)
                .setResultCallback(
                        new ResultCallback<Status>() {
                            @Override
                            public void onResult(@NonNull Status status)
                            {
                                if (!status.isSuccess()) {
                                    logW(
                                            String.format(
                                                    "requestConnection failed. %s", ConnectionsActivity.toString(status)));
                                    mIsConnecting = false;
                                    onConnectionFailed(endpoint);
                                }
                            }
                        });
    }

    /**
     * True if we're currently attempting to connect to another device.
     */
    protected boolean isConnecting()
    {
        return mIsConnecting;
    }

    private void connectedToEndpoint(Endpoint endpoint)
    {
        logD(String.format("connectedToEndpoint(endpoint=%s)", endpoint));
        mEstablishedConnections.put(endpoint.getId(), endpoint);
        onEndpointConnected(endpoint);
    }

    private void disconnectedFromEndpoint(Endpoint endpoint)
    {
        logD(String.format("disconnectedFromEndpoint(endpoint=%s)", endpoint));
        mEstablishedConnections.remove(endpoint.getId());
        onEndpointDisconnected(endpoint);
    }

    /**
     * @return A list of currently connected endpoints.
     */
    protected Set<Endpoint> getDiscoveredEndpoints()
    {
        Set<Endpoint> endpoints = new HashSet<>();
        endpoints.addAll(mDiscoveredEndpoints.values());
        return endpoints;
    }

    /**
     * @return A list of currently connected endpoints.
     */
    protected Set<Endpoint> getConnectedEndpoints()
    {
        Set<Endpoint> endpoints = new HashSet<>();
        endpoints.addAll(mEstablishedConnections.values());
        return endpoints;
    }

    /**
     * Sends a {@link Payload} to all currently connected endpoints.
     *
     * @param payload The data you want to send.
     */
    private void send(Payload payload, Set<String> endpoints)
    {
        Nearby.Connections.sendPayload(mGoogleApiClient, new ArrayList<>(endpoints), payload)
                .setResultCallback(
                        new ResultCallback<Status>() {
                            @Override
                            public void onResult(@NonNull Status status)
                            {
                                if (!status.isSuccess()) {
                                    logW(
                                            String.format(
                                                    "sendUnreliablePayload failed. %s",
                                                    toString(status)));
                                }
                            }
                        });
    }

    private static String toString(Status status)
    {
        return String.format(
                Locale.US,
                "[%d]%s",
                status.getStatusCode(),
                status.getStatusMessage() != null
                        ? status.getStatusMessage()
                        : ConnectionsStatusCodes.getStatusCodeString(status.getStatusCode()));
    }

    /**
     * @return True if the app was granted all the permissions. False otherwise.
     */
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

    protected void logV(String msg)
    {
        Log.v(TAG, msg);
    }

    protected void logD(String msg)
    {
        Log.d(TAG, msg);
    }

    protected void logW(String msg)
    {
        Log.w(TAG, msg);
    }

    protected void logE(String msg, Throwable e)
    {
        Log.e(TAG, msg, e);
    }

    /**
     * Represents a device we can talk to.
     */
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
        super.onConnected(bundle);
        setState(NearbyActivity.State.DISCOVERING);
    }

    /**
     * We were disconnected! Halt everything!
     */
    @Override
    public void onConnectionSuspended(int reason)
    {
        super.onConnectionSuspended(reason);
        setState(NearbyActivity.State.UNKNOWN);
    }

    @Override
    protected void onEndpointDiscovered(Endpoint endpoint)
    {
        // We found an advertiser!
        connectToEndpoint(endpoint);
    }

    @Override
    protected void onConnectionInitiated(Endpoint endpoint, ConnectionInfo connectionInfo)
    {
        // A connection to another device has been initiated! We'll accept the connection immediately.
        acceptConnection(endpoint);
    }

    @Override
    protected void onEndpointConnected(Endpoint endpoint)
    {
        Toast.makeText(
                this, getString(R.string.toast_connected, endpoint.getName()), Toast.LENGTH_SHORT)
                .show();
        setState(NearbyActivity.State.CONNECTED);
    }

    @Override
    protected void onEndpointDisconnected(Endpoint endpoint)
    {
        Toast.makeText(
                this, getString(R.string.toast_disconnected, endpoint.getName()), Toast.LENGTH_SHORT)
                .show();

        // If we lost all our endpoints, then we should reset the state of our app and go back
        // to our initial state (discovering).
        if (getConnectedEndpoints().isEmpty()) {
            setState(NearbyActivity.State.DISCOVERING);
        }
    }

    protected void onConnectionFailed(Endpoint endpoint)
    {
        // Let's try someone else.
        if (getState() == NearbyActivity.State.DISCOVERING && !getDiscoveredEndpoints().isEmpty()) {
            connectToEndpoint(pickRandomElem(getDiscoveredEndpoints()));
        }
    }

    protected void onReceive(Endpoint endpoint, Payload payload)
    {
        if (payload.getType() == Payload.Type.STREAM) {
            AudioPlayer player =
                    new AudioPlayer(payload.asStream().asInputStream()) {
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

    @Override
    protected String getName()
    {
        return mName;
    }

    @Override
    public void onConnected(@Nullable Bundle bundle)
    {
        logV("onConnected");
    }

    /**
     * We've been temporarily disconnected from Nearby Connections' GoogleApiClient.
     */
    @CallSuper
    @Override
    public void onConnectionSuspended(int reason)
    {
        logW(String.format("onConnectionSuspended(reason=%s)", reason));
        resetState();
    }

    /**
     * We are unable to connect to Nearby Connections' GoogleApiClient. Oh uh.
     */
    @Override
    public void onConnectionFailed(@NonNull ConnectionResult connectionResult)
    {
        logW(
                String.format(
                        "onConnectionFailed(%s)",
                        ConnectionsActivity.toString(new Status(connectionResult.getErrorCode()))));
    }

    /**
     * {@see ConnectionsActivity#getServiceId()}
     */
    @Override
    public String getServiceId()
    {
        return SERVICE_ID;
    }

    protected String[] getRequiredPermissions()
    {
        return join(
                super.getRequiredPermissions(),
                Manifest.permission.RECORD_AUDIO);
    }

    private static String[] join(String[] a, String... b)
    {
        String[] join = new String[a.length + b.length];
        System.arraycopy(a, 0, join, 0, a.length);
        System.arraycopy(b, 0, join, a.length, b.length);
        return join;
    }

    private void onStateChanged(NearbyActivity.State oldState, NearbyActivity.State newState)
    {
        if (mCurrentAnimator != null && mCurrentAnimator.isRunning()) {
            mCurrentAnimator.cancel();
        }

        // Update Nearby Connections to the new state.
        switch (newState) {
            case DISCOVERING:
                if (isAdvertising()) {
                    stopAdvertising();
                }
                disconnectFromAllEndpoints();
                startDiscovering();
                break;
            case ADVERTISING:
                if (isDiscovering()) {
                    stopDiscovering();
                }
                disconnectFromAllEndpoints();
                startAdvertising();
                break;
            case CONNECTED:
                if (isDiscovering()) {
                    stopDiscovering();
                } else if (isAdvertising()) {
                    // Continue to advertise, so others can still connect,
                    // but clear the discover runnable.
                    removeCallbacks(mDiscoverRunnable);
                }
                break;
            default:
                // no-op
                break;
        }
    }
}
