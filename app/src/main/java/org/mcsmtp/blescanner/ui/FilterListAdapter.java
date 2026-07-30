package org.mcsmtp.blescanner.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.mcsmtp.blescanner.R;
import org.mcsmtp.blescanner.data.FilterConfig;

import java.util.ArrayList;
import java.util.List;

public class FilterListAdapter extends RecyclerView.Adapter<FilterListAdapter.ViewHolder> {

    public interface OnFilterDeleteListener {
        void onFilterDelete(FilterConfig filter);
    }

    private final List<FilterConfig> filters = new ArrayList<>();
    private final OnFilterDeleteListener listener;

    public FilterListAdapter(OnFilterDeleteListener listener) {
        this.listener = listener;
    }

    public void submitList(List<FilterConfig> newFilters) {
        filters.clear();
        filters.addAll(newFilters);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_filter, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FilterConfig filter = filters.get(position);
        holder.label.setText(filter.getName() + " (윈도우: " + filter.getWindowSize() + ")");
        holder.delete.setOnClickListener(v -> {
            if (listener != null) listener.onFilterDelete(filter);
        });
    }

    @Override
    public int getItemCount() {
        return filters.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView label;
        final TextView delete;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            label = itemView.findViewById(R.id.textFilterLabel);
            delete = itemView.findViewById(R.id.textFilterDelete);
        }
    }
}
