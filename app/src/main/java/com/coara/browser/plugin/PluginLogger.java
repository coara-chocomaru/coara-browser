package com.coara.browser.plugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

final class PluginLogger {
    private static final long MAX_BYTES = 512L * 1024L;
    private static final int MAX_MESSAGE = 4000;
    private static final Pattern RECORD_START = Pattern.compile("^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3} .*");

    private final File file;
    private final File old;
    private final String processTag;
    private final SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

    PluginLogger(File dir, String processTag) {
        if (!dir.exists()) {
            dir.mkdirs();
        }
        this.file = new File(dir, "plugin.log");
        this.old = new File(dir, "plugin.log.1");
        this.processTag = processTag;
    }

    synchronized void log(String level, String id, String message) {
        try {
            String msg = message == null ? "" : message.replace("\r", "");
            if (msg.length() > MAX_MESSAGE) {
                msg = msg.substring(0, MAX_MESSAGE) + "...";
            }
            msg = msg.replace("\n", "\n    ");
            String line = format.format(new Date()) + " " + level + "/" + processTag
                    + " [" + (id == null || id.isEmpty() ? "-" : id) + "] " + msg + "\n";
            if (file.exists() && file.length() > MAX_BYTES) {
                if (old.exists()) {
                    old.delete();
                }
                file.renameTo(old);
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
        }
    }

    synchronized String readTail(int maxChars) {
        try {
            String text = readFileTail(old, maxChars) + readFileTail(file, maxChars);
            if (text.length() > maxChars) {
                text = text.substring(text.length() - maxChars);
                int nl = text.indexOf('\n');
                if (nl >= 0 && nl + 1 < text.length()) {
                    text = text.substring(nl + 1);
                }
            }
            return text;
        } catch (Exception e) {
            return "";
        }
    }

    synchronized String readFor(String id, int maxChars) {
        String all = readTail(maxChars * 2);
        if (all.isEmpty()) {
            return "";
        }
        String marker = "[" + id + "]";
        StringBuilder out = new StringBuilder();
        StringBuilder record = new StringBuilder();
        boolean match = false;
        String[] lines = all.split("\n", -1);
        for (String line : lines) {
            if (RECORD_START.matcher(line).matches()) {
                if (match && record.length() > 0) {
                    out.append(record);
                }
                record.setLength(0);
                match = line.contains(marker);
            }
            record.append(line).append('\n');
        }
        if (match && record.length() > 0) {
            out.append(record);
        }
        String result = out.toString();
        if (result.length() > maxChars) {
            result = result.substring(result.length() - maxChars);
        }
        return result;
    }

    synchronized void clear() {
        if (file.exists()) {
            file.delete();
        }
        if (old.exists()) {
            old.delete();
        }
    }

    synchronized void exportTo(OutputStream out) throws IOException {
        copy(old, out);
        copy(file, out);
        out.flush();
    }

    private static void copy(File f, OutputStream out) throws IOException {
        if (!f.exists()) {
            return;
        }
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static String readFileTail(File f, int maxChars) throws IOException {
        if (!f.exists()) {
            return "";
        }
        long maxBytes = (long) maxChars * 3L;
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0L, len - maxBytes);
            raf.seek(start);
            ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(len - start, 1 << 20));
            byte[] buf = new byte[8192];
            int n;
            while ((n = raf.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            String s = new String(bos.toByteArray(), StandardCharsets.UTF_8);
            if (start > 0) {
                int nl = s.indexOf('\n');
                if (nl >= 0 && nl + 1 < s.length()) {
                    s = s.substring(nl + 1);
                }
            }
            return s;
        }
    }
}
