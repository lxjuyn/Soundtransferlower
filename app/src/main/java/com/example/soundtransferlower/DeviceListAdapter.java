package com.example.soundtransferlower;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.util.List;

public class DeviceListAdapter extends ArrayAdapter<BluetoothDevice> {
    private final LayoutInflater inflater;
    private final int resource;
    private final Context context;

    public DeviceListAdapter(Context context, int resource, List<BluetoothDevice> devices) {
        super(context, resource, devices);
        this.context = context;
        this.inflater = LayoutInflater.from(context);
        this.resource = resource;
    }

    @SuppressLint("MissingPermission")
    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = inflater.inflate(resource, parent, false);
        }
        BluetoothDevice device = getItem(position);
        if (device != null) {
            TextView deviceName = convertView.findViewById(R.id.deviceName);
            TextView deviceAddress = convertView.findViewById(R.id.deviceAddress);
            TextView avatar = convertView.findViewById(R.id.avatar);
            String name = device.getName();
            if (name == null || name.isEmpty()) {
                name = "未知设备";
            }
            deviceName.setText(name);
            deviceAddress.setText(device.getAddress());
            avatar.setText("蓝牙");
        }
        return convertView;
    }
}