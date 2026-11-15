package com.example.hakshev;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Generic list of text rows with click / long-click callbacks. */
public class TextAdapter extends RecyclerView.Adapter<TextAdapter.VH> {
    public interface Listener {
        void onClick(int position);

        void onLongClick(int position);
    }

    private final List<String> items = new ArrayList<>();
    private Listener listener;

    public void setListener(Listener l) {
        this.listener = l;
    }

    public void setItems(List<String> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_text, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        h.text.setText(items.get(position));
        h.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onClick(h.getBindingAdapterPosition());
        });
        h.itemView.setOnLongClickListener(v -> {
            if (listener != null) listener.onLongClick(h.getBindingAdapterPosition());
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView text;

        VH(View v) {
            super(v);
            text = v.findViewById(R.id.tvText);
        }
    }
}
