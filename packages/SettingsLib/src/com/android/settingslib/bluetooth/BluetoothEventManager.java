/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settingslib.bluetooth;

import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothHearingAid;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.telephony.TelephonyManager;
import android.util.Log;

import com.android.settingslib.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * BluetoothEventManager receives broadcasts and callbacks from the Bluetooth
 * API and dispatches the event on the UI thread to the right class in the
 * Settings.
 */
public class BluetoothEventManager {
    private static final String TAG = "BluetoothEventManager";

    private final LocalBluetoothAdapter mLocalAdapter;
    private final CachedBluetoothDeviceManager mDeviceManager;
    private final IntentFilter mAdapterIntentFilter, mProfileIntentFilter;
    private final Map<String, Handler> mHandlerMap;
    private final BroadcastReceiver mBroadcastReceiver = new BluetoothBroadcastReceiver();
    private final BroadcastReceiver mProfileBroadcastReceiver = new BluetoothBroadcastReceiver();
    private final Collection<BluetoothCallback> mCallbacks = new ArrayList<>();
    private final android.os.Handler mReceiverHandler;
    private final Context mContext;

    interface Handler {
        void onReceive(Context context, Intent intent, BluetoothDevice device);
    }

    BluetoothEventManager(LocalBluetoothAdapter adapter,
            CachedBluetoothDeviceManager deviceManager, Context context,
            android.os.Handler handler) {
        mLocalAdapter = adapter;
        mDeviceManager = deviceManager;
        mAdapterIntentFilter = new IntentFilter();
        mProfileIntentFilter = new IntentFilter();
        mHandlerMap = new HashMap<>();
        mContext = context;
        mReceiverHandler = handler;

        // Bluetooth on/off broadcasts
        addHandler(BluetoothAdapter.ACTION_STATE_CHANGED, new AdapterStateChangedHandler());
        // Generic connected/not broadcast
        addHandler(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED,
                new ConnectionStateChangedHandler());

        // Discovery broadcasts
        addHandler(BluetoothAdapter.ACTION_DISCOVERY_STARTED, new ScanningStateChangedHandler(true));
        addHandler(BluetoothAdapter.ACTION_DISCOVERY_FINISHED, new ScanningStateChangedHandler(false));
        addHandler(BluetoothDevice.ACTION_FOUND, new DeviceFoundHandler());
        addHandler(BluetoothDevice.ACTION_NAME_CHANGED, new NameChangedHandler());
        addHandler(BluetoothDevice.ACTION_ALIAS_CHANGED, new NameChangedHandler());

        // Pairing broadcasts
        addHandler(BluetoothDevice.ACTION_BOND_STATE_CHANGED, new BondStateChangedHandler());

        // Fine-grained state broadcasts
        addHandler(BluetoothDevice.ACTION_CLASS_CHANGED, new ClassChangedHandler());
        addHandler(BluetoothDevice.ACTION_UUID, new UuidChangedHandler());
        addHandler(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED, new BatteryLevelChangedHandler());

        // Active device broadcasts
        addHandler(BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED, new ActiveDeviceChangedHandler());
        addHandler(BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED, new ActiveDeviceChangedHandler());
        addHandler(BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED,
                new ActiveDeviceChangedHandler());

        // Headset state changed broadcasts
        addHandler(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED,
                new AudioModeChangedHandler());
        addHandler(TelephonyManager.ACTION_PHONE_STATE_CHANGED,
                new AudioModeChangedHandler());

        mContext.registerReceiver(mBroadcastReceiver, mAdapterIntentFilter, null, mReceiverHandler);
    }

    /** Register to start receiving callbacks for Bluetooth events. */
    public void registerCallback(BluetoothCallback callback) {
        synchronized (mCallbacks) {
            mCallbacks.add(callback);
        }
    }

    /** Unregister to stop receiving callbacks for Bluetooth events. */
    public void unregisterCallback(BluetoothCallback callback) {
        synchronized (mCallbacks) {
            mCallbacks.remove(callback);
        }
    }

    void registerProfileIntentReceiver() {
        mContext.registerReceiver(mProfileBroadcastReceiver, mProfileIntentFilter, null, mReceiverHandler);
    }

    void addProfileHandler(String action, Handler handler) {
        mHandlerMap.put(action, handler);
        mProfileIntentFilter.addAction(action);
    }

    boolean readPairedDevices() {
        Set<BluetoothDevice> bondedDevices = mLocalAdapter.getBondedDevices();
        if (bondedDevices == null) {
            return false;
        }

        boolean deviceAdded = false;
        for (BluetoothDevice device : bondedDevices) {
            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            if (cachedDevice == null) {
                mDeviceManager.addDevice(device);
                deviceAdded = true;
            }
        }

        return deviceAdded;
    }

    void dispatchDeviceAdded(CachedBluetoothDevice cachedDevice) {
        synchronized (mCallbacks) {
            for (BluetoothCallback callback : mCallbacks) {
                callback.onDeviceAdded(cachedDevice);
            }
        }
    }

    void dispatchDeviceRemoved(CachedBluetoothDevice cachedDevice) {
        synchronized (mCallbacks) {
            for (BluetoothCallback callback : mCallbacks) {
                callback.onDeviceDeleted(cachedDevice);
            }
        }
    }

    void dispatchProfileConnectionStateChanged(CachedBluetoothDevice device, int state,
            int bluetoothProfile) {
        synchronized (mCallbacks) {
            for (BluetoothCallback callback : mCallbacks) {
                callback.onProfileConnectionStateChanged(device, state, bluetoothProfile);
            }
        }
    }

    private void dispatchConnectionStateChanged(CachedBluetoothDevice cachedDevice, int state) {
        synchronized (mCallbacks) {
            for (BluetoothCallback callback : mCallbacks) {
                callback.onConnectionStateChanged(cachedDevice, state);
            }
        }
    }

    private void dispatchAudioModeChanged() {
        mDeviceManager.dispatchAudioModeChanged();
        synchronized (mCallbacks) {
            for (BluetoothCallback callback : mCallbacks) {
                callback.onAudioModeChanged();
            }
        }
    }

    private void dispatchActiveDeviceChanged(CachedBluetoothDevice activeDevice,
            int bluetoothProfile) {
        mDeviceManager.onActiveDeviceChanged(activeDevice, bluetoothProfile);
        synchronized (mCallbacks) {
            for (BluetoothCallback callback : mCallbacks) {
                callback.onActiveDeviceChanged(activeDevice, bluetoothProfile);
            }
        }
    }

    private void addHandler(String action, Handler handler) {
        mHandlerMap.put(action, handler);
        mAdapterIntentFilter.addAction(action);
    }

    private class BluetoothBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            BluetoothDevice device = intent
                    .getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);

            Handler handler = mHandlerMap.get(action);
            if (handler != null) {
                handler.onReceive(context, intent, device);
            }
        }
    }

    private class AdapterStateChangedHandler implements Handler {
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE,
                    BluetoothAdapter.ERROR);
            // update local profiles and get paired devices
            mLocalAdapter.setBluetoothStateInt(state);
            // send callback to update UI and possibly start scanning
            synchronized (mCallbacks) {
                for (BluetoothCallback callback : mCallbacks) {
                    callback.onBluetoothStateChanged(state);
                }
            }
            // Inform CachedDeviceManager that the adapter state has changed
            mDeviceManager.onBluetoothStateChanged(state);
        }
    }

    private class ScanningStateChangedHandler implements Handler {
        private final boolean mStarted;

        ScanningStateChangedHandler(boolean started) {
            mStarted = started;
        }
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            synchronized (mCallbacks) {
                for (BluetoothCallback callback : mCallbacks) {
                    callback.onScanningStateChanged(mStarted);
                }
            }
            mDeviceManager.onScanningStateChanged(mStarted);
        }
    }

    private class DeviceFoundHandler implements Handler {
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            short rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
            String name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME);
            // TODO Pick up UUID. They should be available for 2.1 devices.
            // Skip for now, there's a bluez problem and we are not getting uuids even for 2.1.
            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            if (cachedDevice == null) {
                cachedDevice = mDeviceManager.addDevice(device);
                Log.d(TAG, "DeviceFoundHandler created new CachedBluetoothDevice: "
                        + cachedDevice);
            } else if (cachedDevice.getBondState() == BluetoothDevice.BOND_BONDED
                    &&!cachedDevice.getDevice().isConnected()) {
                // Dispatch device add callback to show bonded but
                // not connected devices in discovery mode
                dispatchDeviceAdded(cachedDevice);
            }
            cachedDevice.setRssi(rssi);
            cachedDevice.setJustDiscovered(true);
        }
    }

    private class ConnectionStateChangedHandler implements Handler {
        @Override
        public void onReceive(Context context, Intent intent, BluetoothDevice device) {
            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_CONNECTION_STATE,
                    BluetoothAdapter.ERROR);
            dispatchConnectionStateChanged(cachedDevice, state);
        }
    }

    private class NameChangedHandler implements Handler {
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            mDeviceManager.onDeviceNameUpdated(device);
        }
    }

    private class BondStateChangedHandler implements Handler {
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            if (device == null) {
                Log.e(TAG, "ACTION_BOND_STATE_CHANGED with no EXTRA_DEVICE");
                return;
            }
            int bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.ERROR);
            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            if (cachedDevice == null) {
                Log.w(TAG, "Got bonding state changed for " + device +
                        ", but we have no record of that device.");
                cachedDevice = mDeviceManager.addDevice(device);
            }

            synchronized (mCallbacks) {
                for (BluetoothCallback callback : mCallbacks) {
                    callback.onDeviceBondStateChanged(cachedDevice, bondState);
                }
            }
            cachedDevice.onBondingStateChanged(bondState);

            if (bondState == BluetoothDevice.BOND_NONE) {
                /* Check if we need to remove other Hearing Aid devices */
                if (cachedDevice.getHiSyncId() != BluetoothHearingAid.HI_SYNC_ID_INVALID) {
                    mDeviceManager.onDeviceUnpaired(cachedDevice);
                }
                int reason = intent.getIntExtra(BluetoothDevice.EXTRA_REASON,
                        BluetoothDevice.ERROR);

                showUnbondMessage(context, cachedDevice.getName(), reason);
            }
        }

        /**
         * Called when we have reached the unbonded state.
         *
         * @param reason one of the error reasons from
         *            BluetoothDevice.UNBOND_REASON_*
         */
        private void showUnbondMessage(Context context, String name, int reason) {
            int errorMsg;

            switch(reason) {
                case BluetoothDevice.UNBOND_REASON_AUTH_FAILED:
                    errorMsg = R.string.bluetooth_pairing_pin_error_message;
                    break;
                case BluetoothDevice.UNBOND_REASON_AUTH_REJECTED:
                    errorMsg = R.string.bluetooth_pairing_rejected_error_message;
                    break;
                case BluetoothDevice.UNBOND_REASON_REMOTE_DEVICE_DOWN:
                    errorMsg = R.string.bluetooth_pairing_device_down_error_message;
                    break;
                case BluetoothDevice.UNBOND_REASON_DISCOVERY_IN_PROGRESS:
                case BluetoothDevice.UNBOND_REASON_AUTH_TIMEOUT:
                case BluetoothDevice.UNBOND_REASON_REPEATED_ATTEMPTS:
                case BluetoothDevice.UNBOND_REASON_REMOTE_AUTH_CANCELED:
                    errorMsg = R.string.bluetooth_pairing_error_message;
                    break;
                default:
                    Log.w(TAG, "showUnbondMessage: Not displaying any message for reason: " + reason);
                    return;
            }
            BluetoothUtils.showError(context, name, errorMsg);
        }
    }

    private class ClassChangedHandler implements Handler {
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            mDeviceManager.onBtClassChanged(device);
        }
    }

    private class UuidChangedHandler implements Handler {
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            mDeviceManager.onUuidChanged(device);
        }
    }

    private class BatteryLevelChangedHandler implements Handler {
        public void onReceive(Context context, Intent intent,
                BluetoothDevice device) {
            CachedBluetoothDevice cachedDevice = mDeviceManager.findDevice(device);
            if (cachedDevice != null) {
                cachedDevice.refresh();
            }
        }
    }

    private class ActiveDeviceChangedHandler implements Handler {
        @Override
        public void onReceive(Context context, Intent intent, BluetoothDevice device) {
            String action = intent.getAction();
            if (action == null) {
                Log.w(TAG, "ActiveDeviceChangedHandler: action is null");
                return;
            }
            CachedBluetoothDevice activeDevice = mDeviceManager.findDevice(device);
            int bluetoothProfile = 0;
            if (Objects.equals(action, BluetoothA2dp.ACTION_ACTIVE_DEVICE_CHANGED)) {
                bluetoothProfile = BluetoothProfile.A2DP;
            } else if (Objects.equals(action, BluetoothHeadset.ACTION_ACTIVE_DEVICE_CHANGED)) {
                bluetoothProfile = BluetoothProfile.HEADSET;
            } else if (Objects.equals(action, BluetoothHearingAid.ACTION_ACTIVE_DEVICE_CHANGED)) {
                bluetoothProfile = BluetoothProfile.HEARING_AID;
            } else {
                Log.w(TAG, "ActiveDeviceChangedHandler: unknown action " + action);
                return;
            }
            dispatchActiveDeviceChanged(activeDevice, bluetoothProfile);
        }
    }

    private class AudioModeChangedHandler implements Handler {

        @Override
        public void onReceive(Context context, Intent intent, BluetoothDevice device) {
            final String action = intent.getAction();
            if (action == null) {
                Log.w(TAG, "AudioModeChangedHandler() action is null");
                return;
            }
            dispatchAudioModeChanged();
        }
    }
}
