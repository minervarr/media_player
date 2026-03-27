package com.example.media_player;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

public class SearchFilterSheet extends BottomSheetDialogFragment {

    public interface OnFiltersAppliedListener {
        void onFiltersApplied(SearchCriteria criteria);
        void onFiltersClear();
    }

    private OnFiltersAppliedListener listener;

    private EditText etArtist, etAlbumArtist, etComposer, etGenre;
    private EditText etYearFrom, etYearTo;
    private EditText etFormat, etMinBitrate, etMinSampleRate;
    private TextView btnSourceAny, btnSourceLocal, btnSourceTidal;
    private Integer selectedSource = null; // null=any, 0=local, 1=tidal

    public void setListener(OnFiltersAppliedListener listener) {
        this.listener = listener;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.sheet_search_filters, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        etArtist = view.findViewById(R.id.et_artist);
        etAlbumArtist = view.findViewById(R.id.et_album_artist);
        etComposer = view.findViewById(R.id.et_composer);
        etGenre = view.findViewById(R.id.et_genre);
        etYearFrom = view.findViewById(R.id.et_year_from);
        etYearTo = view.findViewById(R.id.et_year_to);
        etFormat = view.findViewById(R.id.et_format);
        etMinBitrate = view.findViewById(R.id.et_min_bitrate);
        etMinSampleRate = view.findViewById(R.id.et_min_sample_rate);
        btnSourceAny = view.findViewById(R.id.btn_source_any);
        btnSourceLocal = view.findViewById(R.id.btn_source_local);
        btnSourceTidal = view.findViewById(R.id.btn_source_tidal);

        // Source toggle buttons
        btnSourceAny.setOnClickListener(v -> setSource(null));
        btnSourceLocal.setOnClickListener(v -> setSource(0));
        btnSourceTidal.setOnClickListener(v -> setSource(1));

        // Pre-populate from arguments
        Bundle args = getArguments();
        if (args != null && args.containsKey("criteria_json")) {
            SearchCriteria c = SearchCriteria.fromJson(args.getString("criteria_json"));
            if (c.artist != null) etArtist.setText(c.artist);
            if (c.albumArtist != null) etAlbumArtist.setText(c.albumArtist);
            if (c.composer != null) etComposer.setText(c.composer);
            if (c.genre != null) etGenre.setText(c.genre);
            if (c.yearFrom != null) etYearFrom.setText(String.valueOf(c.yearFrom));
            if (c.yearTo != null) etYearTo.setText(String.valueOf(c.yearTo));
            if (c.format != null) etFormat.setText(c.format);
            if (c.minBitrate != null) etMinBitrate.setText(String.valueOf(c.minBitrate));
            if (c.minSampleRate != null) etMinSampleRate.setText(String.valueOf(c.minSampleRate));
            setSource(c.source);
        }

        // Apply
        view.findViewById(R.id.btn_apply_filters).setOnClickListener(v -> {
            SearchCriteria c = buildCriteria();
            if (listener != null) {
                if (c.isEmpty()) {
                    listener.onFiltersClear();
                } else {
                    listener.onFiltersApplied(c);
                }
            }
            dismiss();
        });

        // Clear
        view.findViewById(R.id.btn_clear_filters).setOnClickListener(v -> {
            if (listener != null) listener.onFiltersClear();
            dismiss();
        });
    }

    private void setSource(Integer source) {
        selectedSource = source;
        int active = requireContext().getColor(R.color.green_bright);
        int inactive = requireContext().getColor(R.color.text_secondary);
        btnSourceAny.setTextColor(source == null ? active : inactive);
        btnSourceLocal.setTextColor(source != null && source == 0 ? active : inactive);
        btnSourceTidal.setTextColor(source != null && source == 1 ? active : inactive);
    }

    private SearchCriteria buildCriteria() {
        SearchCriteria c = new SearchCriteria();
        c.artist = nonEmpty(etArtist);
        c.albumArtist = nonEmpty(etAlbumArtist);
        c.composer = nonEmpty(etComposer);
        c.genre = nonEmpty(etGenre);
        c.yearFrom = parseIntOrNull(etYearFrom);
        c.yearTo = parseIntOrNull(etYearTo);
        c.source = selectedSource;
        c.format = nonEmpty(etFormat);
        c.minBitrate = parseIntOrNull(etMinBitrate);
        c.minSampleRate = parseIntOrNull(etMinSampleRate);
        return c;
    }

    private static String nonEmpty(EditText et) {
        String s = et.getText().toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static Integer parseIntOrNull(EditText et) {
        String s = et.getText().toString().trim();
        if (s.isEmpty()) return null;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
