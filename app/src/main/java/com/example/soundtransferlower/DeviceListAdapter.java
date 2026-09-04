package com.example.soundtransferlower;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
            Md3Ui.applyTree(convertView); // 处理 item_main 中 avatar 的 md3-chip-primary tag
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
            int onSurface = Md3Ui.color(parent.getContext(), R.attr.md3OnSurface);
            int variant = Md3Ui.color(parent.getContext(), R.attr.md3OnSurfaceVariant);
            deviceName.setTextColor(device.getBondState() == BluetoothDevice.BOND_BONDED ?
                    onSurface : variant);
            deviceAddress.setTextColor(variant);
            if (avatar != null) avatar.setText("蓝牙");
        }

        return convertView;
    }
}