package com.glasstv.tvhome;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Finds removable USB volumes and opens their root in a file manager. */
public final class UsbStorage {
    private static final String TAG = "TvHome.Usb";
    private static final String MOUNTS = "/proc/mounts";

    private UsbStorage() {}

    /** Only mount points backed by a removable block device are accepted. */
    public static List<File> mounted() {
        List<File> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(MOUNTS))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] fields = line.split("\\s+");
                if (fields.length < 3) continue;
                String device = fields[0];
                String mount = fields[1].replace("\\040", " ");
                String fs = fields[2];
                if (!isRemovableMount(device, mount, fs)) continue;
                File root = new File(mount);
                if (!root.isDirectory() || !root.canRead() || !seen.add(root.getAbsolutePath())) continue;
                out.add(root);
            }
        } catch (IOException e) {
            Log.w(TAG, "cannot read mount table", e);
        }
        return out;
    }

    private static boolean isRemovableMount(String device, String mount, String fs) {
        if (!mount.startsWith("/storage/") && !mount.startsWith("/mnt/media_rw/")) return false;
        if (mount.equals("/storage/emulated") || mount.equals("/storage/self")) return false;
        if (device.startsWith("/data/") || device.startsWith("tmpfs") || device.startsWith("/dev/root")) return false;
        if (fs.equals("sdcardfs") || fs.equals("fuse") || fs.equals("tmpfs")) return false;
        return device.startsWith("/dev/block/") || device.startsWith("/dev/fuse");
    }

    public static boolean isPlugged() {
        return !mounted().isEmpty();
    }

    /** Rechecks the volume before opening so a stale tile cannot open a blank manager. */
    public static boolean open(Context ctx) {
        List<File> mounts = mounted();
        if (mounts.isEmpty()) return false;
        File root = mounts.get(0);

        try {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.fromFile(root), "resource/folder");
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (ctx.getPackageManager().resolveActivity(i, 0) != null) {
                ctx.startActivity(i);
                return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "documentsui", e);
        }

        String[] managers = {
                "com.xiaobaifile.tv",
                "com.estrongs.android.pop",
                "com.alphainventor.filemanager",
                "pl.comolid.fileexplorer"
        };
        for (String pkg : managers) {
            try {
                Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(i);
                    return true;
                }
            } catch (Exception ignored) {}
        }
        return false;
    }
}
