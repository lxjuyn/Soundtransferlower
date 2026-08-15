package com.example.soundtransferlower;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

public class DeviceListAdapter extends ArrayAdapter<BluetoothDevice> {

    private final LayoutInflater inflater;
    private final int resource;

    public DeviceListAdapter(@NonNull Context context, int resource, @NonNull List<BluetoothDevice> devices) {
        super(context, resource, devices);
        this.inflater = LayoutInflater.from(context);
        this.resource = resource;
    }

    @SuppressLint("MissingPermission")
    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = inflater.inflate(resource, parent, false);
        }

        BluetoothDevice device = getItem(position);
        if (device != null) {
            TextView deviceName = convertView.findViewById(R.id.deviceName);
            TextView deviceAddress = convertView.findViewById(R.id.deviceAddress);

            String name = device.getName();
            if (name == null || name.isEmpty()) {
                name = "未知设备";
            }
            deviceName.setText(name);
            deviceAddress.setText(device.getAddress());
        }

        return convertView;
    }
}