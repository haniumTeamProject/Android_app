package org.mcsmtp.blescanner.data;

public class FilterConfig {

    private final String id;
    private final String name;
    private final int windowSize;

    public FilterConfig(String id, String name, int windowSize) {
        this.id = id;
        this.name = name;
        this.windowSize = windowSize;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public int getWindowSize() {
        return windowSize;
    }
}
