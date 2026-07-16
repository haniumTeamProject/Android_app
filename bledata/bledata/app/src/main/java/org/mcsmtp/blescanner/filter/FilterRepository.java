package org.mcsmtp.blescanner.filter;

import android.os.Handler;
import android.os.Looper;

import org.mcsmtp.blescanner.data.FilterConfig;
import org.mcsmtp.blescanner.data.RssiPoint;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.mcsmtp.blescanner.wayfinder.filter.RssiFilterPipeline;


/**
 * 필터 등록/삭제 저장소 (임시 필터 로직: 이동평균).
 * 실제 필터 알고리즘(칼만 필터 등)을 받으면 applyFilter()만 교체하면 된다.
 */
public class FilterRepository {

    public interface Listener {
        void onFiltersChanged(List<FilterConfig> filters);
    }

    private static final FilterRepository INSTANCE = new FilterRepository();

    public static FilterRepository getInstance() {
        return INSTANCE;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final List<FilterConfig> filters = new ArrayList<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();

    private FilterRepository() {
        filters.add(new FilterConfig(UUID.randomUUID().toString(), "중앙값", 3));
        filters.add(new FilterConfig(UUID.randomUUID().toString(), "칼만 필터", 3));
        filters.add(new FilterConfig(UUID.randomUUID().toString(), "히스테리시스", 3));
        filters.add(new FilterConfig(UUID.randomUUID().toString(), "종합 필터", 3));
    }

    public void addListener(Listener listener) {
        listeners.add(listener);
        listener.onFiltersChanged(getFilters());
    }

    public void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    public synchronized List<FilterConfig> getFilters() {
        return Collections.unmodifiableList(new ArrayList<>(filters));
    }

    public synchronized void addFilter(String name, int windowSize) {
        filters.add(new FilterConfig(UUID.randomUUID().toString(), name, windowSize));
        notifyListeners();
    }

    public synchronized void removeFilter(String id) {
        for (int i = 0; i < filters.size(); i++) {
            if (filters.get(i).getId().equals(id)) {
                filters.remove(i);
                break;
            }
        }
        notifyListeners();
    }

    private void notifyListeners() {
        List<FilterConfig> snapshot = getFilters();
        mainHandler.post(() -> {
            for (Listener listener : listeners) {
                listener.onFiltersChanged(snapshot);
            }
        });
    }

    // 임시 필터 로직: 이동평균 (나중에 진짜 팀 필터로 교체 예정)
    public static List<RssiPoint> applyFilter(List<RssiPoint> history, FilterConfig config) {
        List<RssiPoint> result = new ArrayList<>();
        if (history.isEmpty()) return result;

        RssiFilterPipeline pipeline = new RssiFilterPipeline();
        String name = config.getName();

        for (RssiPoint point : history) {
            double filtered;
            if ("중앙값".equals(name)) {
                filtered = pipeline.filterMedianOnly(point.getRssi());
            } else if ("칼만 필터".equals(name)) {
                filtered = pipeline.filterKalmanOnly(point.getRssi());
            } else if ("히스테리시스".equals(name)) {
                filtered = pipeline.filterHysteresisOnly(point.getRssi());
            } else if ("종합 필터".equals(name)) {
                filtered = pipeline.filter(point.getRssi()); // 중앙값 → 칼만 → 히스테리시스 순서로 다 적용
            } else {
                filtered = pipeline.filter(point.getRssi());
            }
            result.add(new RssiPoint(point.getTimestamp(), (int) Math.round(filtered)));
        }
        return result;
    }
}
