package com.example.hakshev;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

/** Roster rows: tap opens the profile, the status button changes status in one step. */
public class SoldierAdapter extends RecyclerView.Adapter<SoldierAdapter.VH> {
    public interface Listener {
        void onOpen(Soldier s);

        void onStatus(Soldier s);
    }

    private final List<Soldier> items = new ArrayList<>();
    private final Listener listener;

    public SoldierAdapter(Listener l) {
        this.listener = l;
    }

    public void setItems(List<Soldier> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_soldier, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Soldier s = items.get(position);
        Context c = h.itemView.getContext();
        h.name.setText(s.fullName);
        String sub = s.squad.isEmpty() ? s.personalNumber : s.squad + (s.personalNumber.isEmpty() ? "" : " · " + s.personalNumber);
        h.sub.setText(sub);

        StringBuilder badges = new StringBuilder();
        if (!s.allergies.isEmpty()) badges.append("⚠ ").append(c.getString(R.string.badge_allergy)).append(": ").append(s.allergies);
        if (!s.exemptions.isEmpty()) {
            if (badges.length() > 0) badges.append("\n");
            badges.append(c.getString(R.string.badge_exemption)).append(": ").append(s.exemptions);
        }
        h.badges.setText(badges);
        h.badges.setVisibility(badges.length() == 0 ? View.GONE : View.VISIBLE);

        h.status.setText(Status.label(c, s.status));
        h.itemView.setOnClickListener(v -> listener.onOpen(s));
        h.status.setOnClickListener(v -> listener.onStatus(s));
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class VH extends RecyclerView.ViewHolder {
        final TextView name, sub, badges;
        final MaterialButton status;

        VH(View v) {
            super(v);
            name = v.findViewById(R.id.tvName);
            sub = v.findViewById(R.id.tvSub);
            badges = v.findViewById(R.id.tvBadges);
            status = v.findViewById(R.id.btnStatus);
        }
    }
}
