package org.mcsmtp.blescanner.data;

public class RssiPoint {

    private final long timestamp;
    private final int rssi;

    public RssiPoint(long timestamp, int rssi) {
        this.timestamp = timestamp;
        this.rssi = rssi;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public int getRssi() {
        return rssi;
    }
}
