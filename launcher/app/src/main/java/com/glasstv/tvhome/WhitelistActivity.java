package com.glasstv.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * 清理白名单管理（2026-10-23 K）：列出已安装应用，按确认键加进/移出白名单。
 * 白名单里的 App 不会被自动清理杀掉。桌面自己永远豁免，不在列表里。
 * 用桌面自己的深色 + 绿色勾选风格，不用系统 ListView 默认样式。
 */
public class WhitelistActivity extends Activity {

    private static final int GREEN = 0xFF4ADE80;

    private static class Item {
        String label, pkg;
        boolean inList;
    }

    private final List<Item> items = new ArrayList<>();
    private ArrayAdapter<Item> adapter;
    private TextView header;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Set<String> white = MemoryCleaner.whitelist(this);
        PackageManager pm = getPackageManager();
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = new ArrayList<>(pm.queryIntentActivities(probe, 0));
        // 国产电视很多 App 没 TV 入口，补手机入口
        Intent phone = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(phone, 0)) {
            String p = ri.activityInfo.packageName;
            boolean dup = false;
            for (ResolveInfo x : found) if (x.activityInfo.packageName.equals(p)) { dup = true; break; }
            if (!dup) found.add(ri);
        }
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName())) continue;   // 桌面自己豁免
            Item it = new Item();
            it.label = ri.loadLabel(pm).toString();
            it.pkg = pkg;
            it.inList = white.contains(pkg);
            items.add(it);
        }
        Collections.sort(items, (a, b) -> a.label.compareToIgnoreCase(b.label));

        // ---- 根视图：黑底 + 标题 + 计数 + 列表 ----
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        int pad = (int) (48 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, (int) (32 * getResources().getDisplayMetrics().density), pad, 0);

        header = new TextView(this);
        header.setTextColor(0xFFFFFFFF);
        header.setTextSize(22);
        header.setText("清理白名单");
        root.addView(header);

        TextView hint = new TextView(this);
        hint.setTextColor(0x99FFFFFF);
        hint.setTextSize(14);
        hint.setPadding(0, (int) (8 * getResources().getDisplayMetrics().density), 0,
                (int) (16 * getResources().getDisplayMetrics().density));
        hint.setText("按确认键（OK）把 App 加进/移出白名单。带 ✓ 的不会被自动清理。");
        root.addView(hint);

        adapter = new ArrayAdapter<Item>(this, android.R.layout.simple_list_item_1, items) {
            @Override public View getView(int pos, View convert, ViewGroup parent) {
                TextView t = (TextView) super.getView(pos, convert, parent);
                Item it = items.get(pos);
                t.setText((it.inList ? "✓  " : "     ") + it.label);
                t.setTextColor(it.inList ? GREEN : 0xFFFFFFFF);
                t.setTextSize(18);
                t.setPadding(0, (int) (18 * getResources().getDisplayMetrics().density),
                        0, (int) (18 * getResources().getDisplayMetrics().density));
                t.setBackground(it.inList ? new ColorDrawable(0x14FFFFFF) : null);
                return t;
            }
        };

        ListView list = new ListView(this);
        list.setAdapter(adapter);
        list.setDivider(new ColorDrawable(0x1AFFFFFF));
        list.setDividerHeight(1);
        list.setSelector(new ColorDrawable(0x33FFFFFF));
        list.setBackgroundColor(Color.BLACK);
        list.setOnItemClickListener((p, v, pos, id) -> toggle(pos));
        root.addView(list, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        updateHeader();
    }

    private void updateHeader() {
        int n = 0;
        for (Item it : items) if (it.inList) n++;
        header.setText("清理白名单　已选 " + n + " 个");
    }

    private void toggle(int pos) {
        Item it = items.get(pos);
        MemoryCleaner.toggleWhitelist(this, it.pkg);
        it.inList = !it.inList;
        adapter.notifyDataSetChanged();
        updateHeader();
    }
}
