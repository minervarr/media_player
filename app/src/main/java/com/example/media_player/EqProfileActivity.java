package com.example.media_player;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.matrixplayer.audioengine.EqProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EqProfileActivity extends AppCompatActivity {

    private AppSettings settings;
    private List<Object> displayItems = new ArrayList<>(); // String (header) or EqProfile
    private List<EqProfile> allProfiles;
    private ProfileAdapter adapter;
    private String selectedName;
    private String selectedSource;
    private String selectedForm;

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_PROFILE = 1;
    private static final int TYPE_NONE = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_eq_profile);
        getWindow().setNavigationBarColor(getColor(R.color.bg_primary));

        Toolbar toolbar = findViewById(R.id.eq_toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(R.string.eq_title);
        }

        settings = new AppSettings(this);
        selectedName = settings.getEqProfileName();
        selectedSource = settings.getEqProfileSource();
        selectedForm = settings.getEqProfileForm();

        RecyclerView recycler = findViewById(R.id.eq_recycler);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ProfileAdapter();
        recycler.setAdapter(adapter);

        // Load profiles in background
        new Thread(() -> {
            allProfiles = EqProfile.loadAll(this);
            runOnUiThread(() -> {
                buildDisplayList("");
                adapter.notifyDataSetChanged();
            });
        }).start();

        EditText search = findViewById(R.id.eq_search);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                buildDisplayList(s.toString());
                adapter.notifyDataSetChanged();
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void buildDisplayList(String query) {
        displayItems.clear();

        // "None" option always first
        displayItems.add(null); // sentinel for "None"

        if (allProfiles == null) return;

        String lowerQuery = query.toLowerCase(Locale.ROOT).trim();

        // Group by form factor
        List<EqProfile> overEar = new ArrayList<>();
        List<EqProfile> inEar = new ArrayList<>();
        List<EqProfile> earbud = new ArrayList<>();
        List<EqProfile> other = new ArrayList<>();

        for (EqProfile p : allProfiles) {
            if (!lowerQuery.isEmpty() && !p.name.toLowerCase(Locale.ROOT).contains(lowerQuery)) {
                continue;
            }
            switch (p.form) {
                case "over-ear":
                    overEar.add(p);
                    break;
                case "in-ear":
                    inEar.add(p);
                    break;
                case "earbud":
                    earbud.add(p);
                    break;
                default:
                    other.add(p);
                    break;
            }
        }

        addSection(getString(R.string.eq_section_over_ear), overEar);
        addSection(getString(R.string.eq_section_in_ear), inEar);
        addSection(getString(R.string.eq_section_earbud), earbud);
        addSection(getString(R.string.eq_section_other), other);
    }

    private void addSection(String header, List<EqProfile> profiles) {
        if (profiles.isEmpty()) return;
        displayItems.add(header);
        displayItems.addAll(profiles);
    }

    private void selectProfile(EqProfile profile) {
        if (profile == null) {
            selectedName = "";
            selectedSource = "";
            selectedForm = "";
            settings.setEqProfile("", "", "");
        } else {
            selectedName = profile.name;
            selectedSource = profile.source;
            selectedForm = profile.form;
            settings.setEqProfile(profile.name, profile.source, profile.form);
        }
        setResult(RESULT_OK);
        adapter.notifyDataSetChanged();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // -- Adapter --

    private class ProfileAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

        @Override
        public int getItemViewType(int position) {
            Object item = displayItems.get(position);
            if (item == null) return TYPE_NONE;
            if (item instanceof String) return TYPE_HEADER;
            return TYPE_PROFILE;
        }

        @Override
        public int getItemCount() {
            return displayItems.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LayoutInflater inf = LayoutInflater.from(parent.getContext());
            if (viewType == TYPE_HEADER) {
                View v = inf.inflate(R.layout.item_eq_profile, parent, false);
                return new HeaderHolder(v);
            } else {
                View v = inf.inflate(R.layout.item_eq_profile, parent, false);
                return new ProfileHolder(v);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            Object item = displayItems.get(position);

            if (holder instanceof HeaderHolder) {
                HeaderHolder hh = (HeaderHolder) holder;
                hh.title.setText((String) item);
                hh.title.setTextColor(getColor(R.color.green_bright));
                hh.subtitle.setVisibility(View.GONE);
            } else if (holder instanceof ProfileHolder) {
                ProfileHolder ph = (ProfileHolder) holder;
                if (item == null) {
                    // "None" item
                    ph.name.setText(R.string.eq_none);
                    ph.source.setVisibility(View.GONE);
                    boolean isSelected = selectedName == null || selectedName.isEmpty();
                    ph.name.setTextColor(getColor(isSelected
                            ? R.color.green_bright : R.color.text_primary));
                    ph.itemView.setOnClickListener(v -> selectProfile(null));
                } else {
                    EqProfile p = (EqProfile) item;
                    ph.name.setText(p.name);
                    ph.source.setText(p.source);
                    ph.source.setVisibility(View.VISIBLE);
                    boolean isSelected = p.name.equals(selectedName)
                            && p.source.equals(selectedSource)
                            && p.form.equals(selectedForm);
                    ph.name.setTextColor(getColor(isSelected
                            ? R.color.green_bright : R.color.text_primary));
                    ph.itemView.setOnClickListener(v -> selectProfile(p));
                }
            }
        }
    }

    private static class ProfileHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView source;

        ProfileHolder(View v) {
            super(v);
            name = v.findViewById(R.id.tv_profile_name);
            source = v.findViewById(R.id.tv_profile_source);
        }
    }

    private static class HeaderHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView subtitle;

        HeaderHolder(View v) {
            super(v);
            title = v.findViewById(R.id.tv_profile_name);
            subtitle = v.findViewById(R.id.tv_profile_source);
        }
    }
}
