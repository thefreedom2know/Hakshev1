package com.example.hakshev;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * The same bottom bar on every main screen: soldiers, counters, duties, inspections, settings.
 * Call after setContentView.
 */
final class Nav {
    private Nav() {}

    static void attach(Activity a, int selectedId) {
        attach(a, selectedId, true);
    }

    /** {@code root} is false on screens opened from a tab (a counter, a soldier). */
    static void attach(Activity a, int selectedId, boolean root) {
        ViewGroup content = a.findViewById(android.R.id.content);
        View old = content.getChildAt(0);
        content.removeView(old);
        LinearLayout column = new LinearLayout(a);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(old, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        BottomNavigationView nav = (BottomNavigationView) a.getLayoutInflater()
                .inflate(R.layout.view_bottom_nav, column, false);
        column.addView(nav);
        content.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        nav.setSelectedItemId(selectedId);
        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == selectedId && root) return true;
            Class<?> target = id == R.id.nav_counters ? CountersActivity.class
                    : id == R.id.nav_duties ? DutiesActivity.class
                    : id == R.id.nav_inspections ? InspectionActivity.class
                    : id == R.id.nav_settings ? SettingsActivity.class
                    : RosterActivity.class;
            Intent i = new Intent(a, target);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            a.startActivity(i);
            if ((!root || selectedId != R.id.nav_soldiers) && target != RosterActivity.class) a.finish();
            a.overridePendingTransition(0, 0);
            return true;
        });
    }
}
