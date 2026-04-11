package com.coara.browser.util;

import android.os.Bundle;
import android.os.Parcel;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;

public final class TabStateStore {
    private TabStateStore() {}

    public static void saveBundleToFile(File dir, Bundle bundle, String fileName) {
        if (dir == null || bundle == null || fileName == null) return;
        File target = new File(dir, fileName);
        File temp = new File(dir, fileName + ".tmp");
        Parcel parcel = Parcel.obtain();
        try {
            bundle.writeToParcel(parcel, 0);
            byte[] bytes = parcel.marshall();
            try (FileOutputStream fos = new FileOutputStream(temp)) {
                fos.write(bytes);
                fos.flush();
            }
            if (target.exists() && !target.delete()) {
                // best effort
            }
            if (!temp.renameTo(target)) {
                try (FileOutputStream fos = new FileOutputStream(target)) {
                    fos.write(bytes);
                    fos.flush();
                }
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
            }
        } catch (Exception ignored) {
            // ignore state write errors silently to preserve browser stability
        } finally {
            parcel.recycle();
        }
    }

    public static Bundle loadBundleFromFile(File dir, String fileName) {
        if (dir == null || fileName == null) return null;
        File file = new File(dir, fileName);
        if (!file.exists()) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int offset = 0;
            while (offset < bytes.length) {
                int read = fis.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset <= 0) return null;
            Parcel parcel = Parcel.obtain();
            try {
                parcel.unmarshall(bytes, 0, offset);
                parcel.setDataPosition(0);
                return Bundle.CREATOR.createFromParcel(parcel);
            } finally {
                parcel.recycle();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    public static File ensureSentinel(File dir, String name) {
        File sentinel = new File(dir, name);
        if (!sentinel.exists()) {
            try {
                //noinspection ResultOfMethodCallIgnored
                sentinel.createNewFile();
            } catch (Exception ignored) {
            }
        }
        return sentinel;
    }
}
