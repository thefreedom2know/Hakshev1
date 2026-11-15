package com.example.hakshev;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/** Rows with a value and big +1 / -1 buttons. Used by the counter and soldier screens. */
public class CountAdapter extends RecyclerView.Adapter<CountAdapter.VH> {
    public interface Listener {
        void onDelta(Counters.Row row, int delta);

        /** Tap on the name: add a custom amount with a note. */
        void onClick(Counters.Row row);

        /** Long press: history / remove. */
        void onLongClick(Counters.Row row);
    }

    private final List<Counters.Row> items = new ArrayList<>();
    private final Listener listener;

    public CountAdapter(Listener l) {
        this.listener = l;
    }

    public void setItems(List<Counters.Row> rows) {
        items.clear();
        items.addAll(rows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_count, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Counters.Row r = items.get(position);
        h.title.setText(r.title);
        h.sub.setText(r.sub);
        h.sub.setVisibility(r.sub.isEmpty() ? View.GONE : View.VISIBLE);
        h.value.setText(r.valueText);
        h.value.setTextColor(r.reached
                ? ContextCompat.getColor(h.itemView.getContext(), R.color.hk_ok)
                : h.title.getCurrentTextColor());
        h.itemView.setOnClickListener(v -> listener.onClick(r));
        h.itemView.setOnLongClickListener(v -> {
            listener.onLongClick(r);
            return true;
        });
        h.minus.setOnClickListener(v -> listener.onDelta(r, -1));
        h.plus.setOnClickListener(v -> listener.onDelta(r, 1));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView title, sub, value;
        final MaterialButton minus, plus;

        VH(View v) {
            super(v);
            title = v.findViewById(R.id.tvTitle);
            sub = v.findViewById(R.id.tvSub);
            value = v.findViewById(R.id.tvValue);
            minus = v.findViewById(R.id.btnMinus);
            plus = v.findViewById(R.id.btnPlus);
        }
    }
}
