package com.deku.appmanager;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    /** Never hidden: hiding these can brick the UI or the device. */
    private static final Set<String> PROTECTED = new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.android.phone",
            "com.android.inputmethod.latin",
            "com.sec.android.inputmethod",
            "com.sec.android.app.launcher",
            "com.android.launcher3",
            "com.android.providers.settings"));

    private static final String ADB_OWNER_CMD =
            "adb shell dpm set-device-owner com.deku.appmanager/.AdminReceiver";

    private DevicePolicyManager dpm;
    private ComponentName admin;

    private TextView status;
    private Button adminButton;
    private EditText search;
    private CheckBox showSystem;
    private ListView listView;

    private final List<AppItem> all = new ArrayList<>();
    private final List<AppItem> visible = new ArrayList<>();
    private ArrayAdapter<AppItem> adapter;

    private static final class AppItem {
        final String pkg;
        final String label;
        final boolean system;
        boolean hidden;

        AppItem(String pkg, String label, boolean system, boolean hidden) {
            this.pkg = pkg;
            this.label = label;
            this.system = system;
            this.hidden = hidden;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        dpm = (DevicePolicyManager) getSystemService(DEVICE_POLICY_SERVICE);
        admin = new ComponentName(this, AdminReceiver.class);

        int pad = dp(16);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        status = new TextView(this);
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        root.addView(status, matchWrap());

        adminButton = new Button(this);
        adminButton.setOnClickListener(v -> onAdminButton());
        root.addView(adminButton, matchWrap());

        search = new EditText(this);
        search.setHint("Uygulama ara (ad veya paket)");
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { applyFilter(); }
            @Override public void afterTextChanged(Editable s) { }
        });
        root.addView(search, matchWrap());

        showSystem = new CheckBox(this);
        showSystem.setText("Sistem uygulamalarını göster");
        showSystem.setOnCheckedChangeListener((b, checked) -> applyFilter());
        root.addView(showSystem, matchWrap());

        listView = new ListView(this);
        adapter = new ArrayAdapter<AppItem>(this, android.R.layout.simple_list_item_2, android.R.id.text1, visible) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                AppItem it = visible.get(position);
                TextView t1 = row.findViewById(android.R.id.text1);
                TextView t2 = row.findViewById(android.R.id.text2);
                t1.setText((it.hidden ? "[GİZLİ] " : "") + it.label);
                t2.setText(it.pkg + (it.system ? "  · sistem" : ""));
                return row;
            }
        };
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((parent, view, position, id) -> onAppClicked(visible.get(position)));
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        reload();
    }

    private boolean isOwner() {
        return dpm.isDeviceOwnerApp(getPackageName());
    }

    private void refreshStatus() {
        boolean active = dpm.isAdminActive(admin);
        boolean owner = isOwner();

        StringBuilder sb = new StringBuilder();
        if (owner) {
            sb.append("Device Owner: uygulama gizleme aktif.");
        } else if (active) {
            sb.append("Yönetici izni açık, ancak uygulama gizlemek için Device Owner gerekir.\n")
              .append("PC'den (USB hata ayıklama açık, hesaplar kaldırılmış olmalı):\n")
              .append(ADB_OWNER_CMD);
        } else {
            sb.append("Yönetici izni yok. Aşağıdaki düğmeyle ver.");
        }
        status.setText(sb.toString());

        adminButton.setText(active ? "Yönetici iznini kaldır" : "Yönetici izni ver");
        adminButton.setEnabled(!owner);
    }

    private void onAdminButton() {
        if (dpm.isAdminActive(admin)) {
            dpm.removeActiveAdmin(admin);
            Toast.makeText(this, "Yönetici izni kaldırıldı", Toast.LENGTH_SHORT).show();
        } else {
            Intent i = new Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN);
            i.putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin);
            i.putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                    "Uygulamaları gizlemek/açmak için gerekli.");
            startActivity(i);
        }
        refreshStatus();
    }

    private void reload() {
        boolean owner = isOwner();
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<AppItem> items = new ArrayList<>();
            for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
                boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
                boolean hidden = false;
                if (owner) {
                    try {
                        hidden = dpm.isApplicationHidden(admin, ai.packageName);
                    } catch (SecurityException ignored) {
                    }
                }
                items.add(new AppItem(ai.packageName,
                        String.valueOf(ai.loadLabel(pm)), system, hidden));
            }
            Collections.sort(items, Comparator.comparing(
                    (AppItem a) -> a.label.toLowerCase(Locale.ROOT)));
            runOnUiThread(() -> {
                all.clear();
                all.addAll(items);
                applyFilter();
            });
        }).start();
    }

    private void applyFilter() {
        String q = search == null ? "" : search.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean withSystem = showSystem != null && showSystem.isChecked();
        visible.clear();
        for (AppItem it : all) {
            if (it.system && !withSystem) continue;
            if (!q.isEmpty()
                    && !it.label.toLowerCase(Locale.ROOT).contains(q)
                    && !it.pkg.toLowerCase(Locale.ROOT).contains(q)) continue;
            visible.add(it);
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void onAppClicked(AppItem it) {
        if (!isOwner()) {
            Toast.makeText(this, "Gizleme için Device Owner gerekli (üstteki açıklamaya bak)",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (it.pkg.equals(getPackageName()) || PROTECTED.contains(it.pkg)) {
            Toast.makeText(this, "Bu uygulama korumalı, gizlenmez", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean target = !it.hidden;
        boolean ok = dpm.setApplicationHidden(admin, it.pkg, target);
        if (!ok) {
            Toast.makeText(this, "İşlem başarısız: " + it.pkg, Toast.LENGTH_LONG).show();
            return;
        }
        it.hidden = target;
        adapter.notifyDataSetChanged();
        Toast.makeText(this, (target ? "Gizlendi: " : "Açıldı: ") + it.label,
                Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
