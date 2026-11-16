package com.example.hakshev;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Cards for the list of counters: name, period, participants, target and the group total. */
public class CounterListAdapter extends RecyclerView.Adapter<CounterListAdapter.VH> {
    public static class Item {
        public String name = "";
        public String period = "";
        public String members = "";
        public String target = "";
        public String total = "";
    }

    public interface Listener {
        void onClick(int position);
    }

    private final List<Item> items = new ArrayList<>();
    private final Listener listener;

    public CounterListAdapter(Listener l) {
        this.listener = l;
    }

    public void setItems(List<Item> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new VH(LayoutInflater.from(parent.getContext()).inflate(R.layout.item_counter, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Item it = items.get(position);
        Ui.avatar(h.avatar, it.name, it.name);
        h.name.setText(it.name);
        h.period.setText(it.period);
        Ui.chip(h.period, h.name.getContext().getColor(R.color.hk_ok));
        h.members.setText(it.members);
        h.target.setText(it.target);
        h.target.setVisibility(it.target.isEmpty() ? View.GONE : View.VISIBLE);
        h.total.setText(it.total);
        h.itemView.setOnClickListener(v -> listener.onClick(h.getBindingAdapterPosition()));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView avatar, name, period, members, target, total;

        VH(View v) {
            super(v);
            avatar = v.findViewById(R.id.tvAvatar);
            name = v.findViewById(R.id.tvName);
            period = v.findViewById(R.id.tvPeriod);
            members = v.findViewById(R.id.tvMembers);
            target = v.findViewById(R.id.tvTarget);
            total = v.findViewById(R.id.tvTotal);
        }
    }
}
