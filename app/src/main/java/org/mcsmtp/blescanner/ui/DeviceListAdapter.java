package org.mcsmtp.blescanner.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.mcsmtp.blescanner.R;
import org.mcsmtp.blescanner.data.BeaconDevice;

import java.util.ArrayList;
import java.util.List;

public class DeviceListAdapter extends RecyclerView.Adapter<DeviceListAdapter.ViewHolder> {

    public interface OnDeviceClickListener {
        void onDeviceClick(BeaconDevice device);
    }

    private final List<BeaconDevice> devices = new ArrayList<>();
    private final OnDeviceClickListener listener;

    public DeviceListAdapter(OnDeviceClickListener listener) {
        this.listener = listener;
    }

    public void submitList(List<BeaconDevice> newDevices) {
        devices.clear();
        devices.addAll(newDevices);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_device, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        BeaconDevice device = devices.get(position);
        holder.name.setText(device.getName() != null ? device.getName() : "N/A");
        holder.mac.setText(device.getMacAddress());
        holder.rssi.setText("RSSI: " + device.getRssi() + " dBm");
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onDeviceClick(device);
        });
    }

    @Override
    public int getItemCount() {
        return devices.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView mac;
        final TextView rssi;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.textDeviceName);
            mac = itemView.findViewById(R.id.textDeviceMac);
            rssi = itemView.findViewById(R.id.textDeviceRssi);
        }
    }
}
