package org.mcsmtp.blescanner.data;

public class BeaconDevice {

    private final String macAddress;
    private final String name;
    private final int rssi;
    private final long timestamp;

    public BeaconDevice(String macAddress, String name, int rssi, long timestamp) {
        this.macAddress = macAddress;
        this.name = name;
        this.rssi = rssi;
        this.timestamp = timestamp;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public String getName() {
        return name;
    }

    public int getRssi() {
        return rssi;
    }

    public long getTimestamp() {
        return timestamp;
    }
}
