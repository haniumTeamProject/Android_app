package org.mcsmtp.blescanner.ui;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.mcsmtp.blescanner.R;
import org.mcsmtp.blescanner.data.FilterConfig;
import org.mcsmtp.blescanner.filter.FilterRepository;

import java.util.List;

public class FilterManageActivity extends AppCompatActivity implements FilterRepository.Listener {

    private FilterListAdapter adapter;
    private EditText nameInput;
    private EditText windowInput;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_filter_manage);
        setTitle(R.string.filter_manage_title);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        nameInput = findViewById(R.id.editFilterName);
        windowInput = findViewById(R.id.editFilterWindow);
        windowInput.setText("3");

        Button addButton = findViewById(R.id.buttonAddFilter);
        addButton.setOnClickListener(v -> {
            String name = nameInput.getText().toString().trim();
            String windowText = windowInput.getText().toString().trim();
            int window;
            try {
                window = Integer.parseInt(windowText);
            } catch (NumberFormatException e) {
                window = 3;
            }
            if (!TextUtils.isEmpty(name)) {
                FilterRepository.getInstance().addFilter(name, window);
                nameInput.setText("");
                windowInput.setText("3");
            }
        });

        RecyclerView recyclerView = findViewById(R.id.recyclerFilters);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new FilterListAdapter(filter -> FilterRepository.getInstance().removeFilter(filter.getId()));
        recyclerView.setAdapter(adapter);
    }

    @Override
    protected void onStart() {
        super.onStart();
        FilterRepository.getInstance().addListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        FilterRepository.getInstance().removeListener(this);
    }

    @Override
    public void onFiltersChanged(List<FilterConfig> filters) {
        adapter.submitList(filters);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}
