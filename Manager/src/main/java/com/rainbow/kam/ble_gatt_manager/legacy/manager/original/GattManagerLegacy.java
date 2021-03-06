
package com.rainbow.kam.ble_gatt_manager.legacy.manager.original;

import android.app.Activity;
import android.app.Application;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import com.google.common.primitives.Bytes;
import com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattDisconnectException;
import com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattConnectException;
import com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattNotificationCharacteristicException;
import com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattReadCharacteristicException;
import com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattWriteCharacteristicException;
import com.rainbow.kam.ble_gatt_manager.model.BluetoothGatts;
import com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattResourceNotDiscoveredException;
import com.rainbow.kam.ble_gatt_manager.exceptions.gatt.GattRssiException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import rx.Observable;
import rx.Subscription;

/**
 * Created by kam6512 on 2015-10-29.
 */

public class GattManagerLegacy {

    private final static String TAG = GattManagerLegacy.class.getSimpleName();

    private final static long RSSI_UPDATE_TIME_INTERVAL = 3;

    private final static UUID CLIENT_CHARACTERISTIC_CONFIG_UUID = BluetoothGatts.CLIENT_CHARACTERISTIC_CONFIG;
    private final static UUID BATTERY_SERVICE_UUID = BluetoothGatts.BATTERY_SERVICE_UUID;
    private final static UUID BATTERY_CHARACTERISTIC_UUID = BluetoothGatts.BATTERY_CHARACTERISTIC_UUID;

    private final Application application;

    private final GattCustomCallbacks gattCustomCallbacks;

    private BluetoothManager bluetoothManager;
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothDevice bluetoothDevice;
    private BluetoothGatt bluetoothGatt;
    private BluetoothGattDescriptor notificationDescriptor;

    private Subscription rssiSubscription;

    private BluetoothGattCharacteristic writeCharacteristic;
    private BluetoothGattCharacteristic notificationCharacteristic;


    public GattManagerLegacy(Activity activity, GattCustomCallbacks gattCustomCallbacks) {
        this(activity.getApplication(), gattCustomCallbacks);
    }


    public GattManagerLegacy(Application application, GattCustomCallbacks gattCustomCallbacks) {
        this.application = application;
        this.gattCustomCallbacks = gattCustomCallbacks;

        if (bluetoothManager == null || bluetoothAdapter == null) {
            bluetoothManager = (BluetoothManager) this.application.getSystemService(Context.BLUETOOTH_SERVICE);
            bluetoothAdapter = bluetoothManager.getAdapter();
        }
    }


    public BluetoothGatt getGatt() {
        return bluetoothGatt;
    }


    public void connect(final String deviceAddress) {
        if (TextUtils.isEmpty(deviceAddress)) {
            gattCustomCallbacks.onError(new GattConnectException(deviceAddress, "Address is not available"));
            return;
        }
        if (bluetoothGatt != null && bluetoothGatt.getDevice().getAddress().equals(deviceAddress)) {
            bluetoothGatt.connect();
        } else {
            bluetoothDevice = bluetoothAdapter.getRemoteDevice(deviceAddress);
            if (bluetoothDevice == null) {
                gattCustomCallbacks.onError(new GattConnectException(deviceAddress, "RemoteDevice is not available"));
            } else {
                bluetoothGatt = bluetoothDevice.connectGatt(application, false, bluetoothGattCallback);
            }
        }
    }


    public void disconnect() {
        String deviceAddress = bluetoothGatt.getDevice().getAddress();
        if (bluetoothGatt != null) {
            if (isConnected() || isBluetoothAvailable()) {
                bluetoothGatt.disconnect();
            } else {
                gattCustomCallbacks.onError(new GattDisconnectException(deviceAddress, "Device already Disconnected"));
            }
        } else {
            gattCustomCallbacks.onError(new GattDisconnectException(deviceAddress, "BluetoothGatt is not Available"));
        }
    }


    public boolean isBluetoothAvailable() {
        return bluetoothAdapter.isEnabled();
    }


    public boolean isConnected() {
        List<BluetoothDevice> bluetoothDevices = bluetoothManager.getConnectedDevices(BluetoothProfile.GATT);
        for (BluetoothDevice bluetoothDeviceItem : bluetoothDevices) {
            if (bluetoothDevice != null &&
                    bluetoothDevice.getAddress().equals(bluetoothDeviceItem.getAddress())) {
                return true;
            }
        }
        return false;
    }


    private void readRssiValue() {
        if (isConnected()) {
            rssiSubscription = Observable.interval(RSSI_UPDATE_TIME_INTERVAL, TimeUnit.SECONDS).subscribe(aLong -> {
                bluetoothGatt.readRemoteRssi();
            });
        } else {
            if (rssiSubscription != null) {
                rssiSubscription.unsubscribe();
            }
        }
    }


    private void startServiceDiscovery() {
        bluetoothGatt.discoverServices();
    }


    public void setNotification(BluetoothGattCharacteristic notificationCharacteristic, boolean enabled) {

        Observable.just(notificationCharacteristic)
                .map(characteristic -> {
                    bluetoothGatt.setCharacteristicNotification(notificationCharacteristic, enabled);
                    notificationDescriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID);
                    byte[] value = enabled ? BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE : BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE;
                    notificationDescriptor.setValue(value);
                    return notificationDescriptor;
                })
                .subscribe(bluetoothGattDescriptor -> {
                    bluetoothGatt.writeDescriptor(notificationDescriptor);
                    gattCustomCallbacks.onSetNotificationSuccess();
                }, throwable -> {
                    gattCustomCallbacks.onError(new GattNotificationCharacteristicException(notificationDescriptor, throwable.getMessage()));
                })
                .unsubscribe();
    }


    public boolean isNotificationEnabled(BluetoothGattCharacteristic notificationCharacteristic) {
        byte[] descriptorValue = notificationCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID).getValue();
        if (descriptorValue == BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) {
            return true;
        } else if (descriptorValue == BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE) {
            return false;
        }
        return false;
    }


    public void readValue(BluetoothGattCharacteristic bluetoothGattCharacteristic) {
        bluetoothGatt.readCharacteristic(bluetoothGattCharacteristic);
    }


    public void writeValue(final BluetoothGattCharacteristic bluetoothGattCharacteristic, final List<Byte> dataToWrite) {
        this.writeValue(bluetoothGattCharacteristic, Bytes.toArray(dataToWrite));
    }


    public void writeValue(final BluetoothGattCharacteristic bluetoothGattCharacteristic, final byte[] dataToWrite) {
        Observable
                .create(subscriber -> {
                    if (bluetoothGattCharacteristic != null) {
                        if (dataToWrite.length != 0) {
                            subscriber.onNext(bluetoothGattCharacteristic);
                        } else {
                            subscriber.onError(new Exception("data is Null or Empty"));
                        }
                    } else {
                        subscriber.onError(new Exception("write Characteristic is null"));
                    }
                })
                .map(bytes -> {
                    bluetoothGattCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
                    bluetoothGattCharacteristic.setValue(dataToWrite);
                    return bluetoothGattCharacteristic;
                })
                .subscribe(characteristic -> {
                    bluetoothGatt.writeCharacteristic(bluetoothGattCharacteristic);
                }, throwable -> {
                    gattCustomCallbacks.onError(new GattWriteCharacteristicException(writeCharacteristic, throwable.getMessage()));
                })
                .unsubscribe();
    }


    public void setNotification(UUID notificationUUID, boolean enabled) {
        if (notificationCharacteristic == null || !notificationCharacteristic.getUuid().equals(notificationUUID)) {
            notificationCharacteristic = getCharacteristic(notificationUUID);
        }
        setNotification(notificationCharacteristic, enabled);
    }


    public void readBatteryValue() {
        readValue(bluetoothGatt.getService(BATTERY_SERVICE_UUID).getCharacteristic(BATTERY_CHARACTERISTIC_UUID));
    }


    public void writeValue(final UUID writeUUID, final byte[] dataToWrite) {
        if (writeCharacteristic == null || !writeCharacteristic.getUuid().equals(writeUUID)) {
            writeCharacteristic = getCharacteristic(writeUUID);
        }
        writeValue(writeCharacteristic, dataToWrite);
    }


    private BluetoothGattCharacteristic getCharacteristic(UUID uuid) {

        List<BluetoothGattService> serviceList = bluetoothGatt.getServices();
        List<BluetoothGattCharacteristic> characteristicList;

        for (BluetoothGattService service : serviceList) {
            characteristicList = service.getCharacteristics();
            for (BluetoothGattCharacteristic characteristic : characteristicList) {
                if (characteristic.getUuid().equals(uuid)) {
                    return characteristic;
                }
            }
        }
        return null;
    }


    private final BluetoothGattCallback bluetoothGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt bluetoothGatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {

                startServiceDiscovery();

                gattCustomCallbacks.onDeviceConnected();

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {

                gattCustomCallbacks.onDeviceDisconnected();
                bluetoothGatt.close();
            }
            readRssiValue();
        }


        @Override
        public void onServicesDiscovered(BluetoothGatt bluetoothGatt, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gattCustomCallbacks.onServicesFound(bluetoothGatt);
                Log.i(TAG, "ServicesDiscovered SUCCESS");
            } else {
                gattCustomCallbacks.onError(new GattResourceNotDiscoveredException("ServicesDiscovered FAIL"));
                Log.i(TAG, "ServicesDiscovered FAIL");
            }
        }


        @Override
        public void onCharacteristicRead(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gattCustomCallbacks.onReadSuccess(characteristic);
                Log.i(TAG, "CharacteristicRead SUCCESS " + characteristic.getUuid().toString());
            } else {
                gattCustomCallbacks.onError(new GattReadCharacteristicException(characteristic, "Check Gatt Service Available or Device Connection!", status));
                Log.i(TAG, "CharacteristicRead FAIL " + characteristic.getUuid().toString());
            }
        }


        @Override
        public void onCharacteristicChanged(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic characteristic) {
            gattCustomCallbacks.onDeviceNotify(characteristic);
        }


        @Override
        public void onCharacteristicWrite(BluetoothGatt bluetoothGatt, BluetoothGattCharacteristic characteristic, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gattCustomCallbacks.onWriteSuccess();
                Log.i(TAG, "CharacteristicWrite SUCCESS " + characteristic.getUuid().toString());
            } else {
                gattCustomCallbacks.onError(new GattWriteCharacteristicException(characteristic, "Check Gatt Service Available or Device Connection!", status));
                Log.i(TAG, "CharacteristicWrite FAIL " + characteristic.getUuid().toString());
            }
        }


        @Override
        public void onReadRemoteRssi(BluetoothGatt bluetoothGatt, int rssi, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                gattCustomCallbacks.onRSSIUpdate(rssi);
            } else {
                gattCustomCallbacks.onError(new GattRssiException(status));
            }
        }


        @Override
        public void onDescriptorWrite(BluetoothGatt bluetoothGatt, BluetoothGattDescriptor descriptor, int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {

                gattCustomCallbacks.onSetNotificationSuccess();

                Log.i(TAG, "DescriptorWrite SUCCESS " + descriptor.getCharacteristic().getUuid().toString() + " \n " + descriptor.getUuid().toString());

                if (descriptor.equals(notificationDescriptor)) {
                    gattCustomCallbacks.onDeviceReady();
                }

            } else {
                gattCustomCallbacks.onError(new GattNotificationCharacteristicException(descriptor, "DescriptorWrite FAIL", status));
            }
        }
    };
}
