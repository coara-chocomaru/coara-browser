package com.coara.browser.util;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class TabHistoryStore {
    public static final class Entry {
        private final String url;
        private final String title;

        public Entry(String url, String title) {
            this.url = url == null ? "" : url;
            this.title = title == null ? "" : title;
        }

        public String getUrl() {
            return url;
        }

        public String getTitle() {
            return title;
        }
    }

    public static final class State {
        private final ArrayList<Entry> entries = new ArrayList<>();
        private int currentIndex = -1;

        public State() {
        }

        public State(List<Entry> source, int currentIndex) {
            if (source != null) {
                entries.addAll(source);
            }
            if (entries.isEmpty()) {
                this.currentIndex = -1;
            } else {
                this.currentIndex = Math.max(0, Math.min(currentIndex, entries.size() - 1));
            }
        }

        public int size() {
            return entries.size();
        }

        public boolean isEmpty() {
            return entries.isEmpty();
        }

        public int getCurrentIndex() {
            return currentIndex;
        }

        public Entry getCurrent() {
            if (currentIndex < 0 || currentIndex >= entries.size()) {
                return null;
            }
            return entries.get(currentIndex);
        }

        public Entry getPrevious() {
            if (currentIndex <= 0 || currentIndex > entries.size() - 1) {
                return null;
            }
            return entries.get(currentIndex - 1);
        }

        public boolean canGoBack() {
            return currentIndex > 0 && currentIndex < entries.size();
        }

        public void moveBack() {
            if (currentIndex > 0) {
                currentIndex--;
            }
        }

        public void setCurrentIndex(int index) {
            if (entries.isEmpty()) {
                currentIndex = -1;
                return;
            }
            currentIndex = Math.max(0, Math.min(index, entries.size() - 1));
        }

        public void replace(List<Entry> source, int index, int maxEntries) {
            entries.clear();
            if (source != null) {
                int start = Math.max(0, source.size() - Math.max(1, maxEntries));
                for (int i = start; i < source.size(); i++) {
                    Entry entry = source.get(i);
                    if (entry != null && !entry.getUrl().isEmpty()) {
                        entries.add(new Entry(entry.getUrl(), entry.getTitle()));
                    }
                }
                int adjusted = index - start;
                if (entries.isEmpty()) {
                    currentIndex = -1;
                } else {
                    currentIndex = Math.max(0, Math.min(adjusted, entries.size() - 1));
                }
            } else {
                currentIndex = -1;
            }
        }

        public boolean record(String url, String title, int maxEntries) {
            if (url == null || url.isEmpty()) {
                return false;
            }
            boolean changed = false;
            if (currentIndex >= 0 && currentIndex < entries.size() - 1) {
                entries.subList(currentIndex + 1, entries.size()).clear();
                changed = true;
            }
            if (currentIndex >= 0 && currentIndex < entries.size()) {
                Entry current = entries.get(currentIndex);
                if (current.getUrl().equals(url)) {
                    String nextTitle = current.getTitle();
                    if (title != null && !title.isEmpty()) {
                        nextTitle = title;
                    }
                    if (current.getTitle().equals(nextTitle)) {
                        return changed;
                    }
                    entries.set(currentIndex, new Entry(url, nextTitle));
                    return true;
                }
            }
            entries.add(new Entry(url, title));
            currentIndex = entries.size() - 1;
            while (entries.size() > Math.max(1, maxEntries)) {
                entries.remove(0);
                currentIndex--;
            }
            if (currentIndex < 0 && !entries.isEmpty()) {
                currentIndex = 0;
            }
            return true;
        }

        public boolean contentEquals(State other) {
            if (other == null || currentIndex != other.currentIndex || entries.size() != other.entries.size()) {
                return false;
            }
            for (int i = 0; i < entries.size(); i++) {
                Entry a = entries.get(i);
                Entry b = other.entries.get(i);
                if (!a.getUrl().equals(b.getUrl()) || !a.getTitle().equals(b.getTitle())) {
                    return false;
                }
            }
            return true;
        }

        public ArrayList<Entry> getEntries() {
            return new ArrayList<>(entries);
        }
    }

    private final File dir;
    private final int maxEntries;

    public TabHistoryStore(File dir, int maxEntries) {
        this.dir = dir;
        this.maxEntries = Math.max(1, maxEntries);
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
    }

    public synchronized State load(int id) {
        if (dir == null || id < 0) {
            return new State();
        }
        File file = fileFor(id);
        if (!file.isFile()) {
            return new State();
        }
        try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file))) {
            long length = file.length();
            if (length <= 0L || length > 1024L * 1024L) {
                return new State();
            }
            byte[] data = new byte[(int) length];
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            if (offset <= 0) {
                return new State();
            }
            String text = new String(data, 0, offset, StandardCharsets.UTF_8);
            JSONObject root = new JSONObject(text);
            JSONArray array = root.optJSONArray("entries");
            ArrayList<Entry> entries = new ArrayList<>();
            if (array != null) {
                for (int i = 0; i < array.length(); i++) {
                    JSONObject item = array.optJSONObject(i);
                    if (item == null) {
                        continue;
                    }
                    String url = item.optString("url", "");
                    if (!url.isEmpty()) {
                        entries.add(new Entry(url, item.optString("title", "")));
                    }
                }
            }
            int currentIndex = root.optInt("currentIndex", entries.size() - 1);
            if (entries.size() > maxEntries) {
                int start = entries.size() - maxEntries;
                entries = new ArrayList<>(entries.subList(start, entries.size()));
                currentIndex -= start;
            }
            return new State(entries, currentIndex);
        } catch (Exception ignored) {
            return new State();
        }
    }

    public synchronized void save(int id, State state) {
        if (dir == null || id < 0 || state == null) {
            return;
        }
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            JSONArray array = new JSONArray();
            ArrayList<Entry> entries = state.getEntries();
            int start = Math.max(0, entries.size() - maxEntries);
            int currentIndex = state.getCurrentIndex() - start;
            if (currentIndex < -1) {
                currentIndex = -1;
            }
            if (currentIndex >= entries.size() - start) {
                currentIndex = Math.max(-1, entries.size() - start - 1);
            }
            root.put("currentIndex", currentIndex);
            for (int i = start; i < entries.size(); i++) {
                Entry entry = entries.get(i);
                JSONObject item = new JSONObject();
                item.put("url", entry.getUrl());
                item.put("title", entry.getTitle());
                array.put(item);
            }
            root.put("entries", array);
            writeAtomic(fileFor(id), root.toString());
        } catch (Exception ignored) {
        }
    }

    public synchronized void delete(int id) {
        if (dir == null || id < 0) {
            return;
        }
        File file = fileFor(id);
        if (file.exists()) {
            file.delete();
        }
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        if (temp.exists()) {
            temp.delete();
        }
    }

    public synchronized void deleteAll() {
        if (dir == null) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file != null && file.getName().startsWith("tab_history_") && file.getName().endsWith(".json")) {
                file.delete();
            }
        }
    }

    private File fileFor(int id) {
        return new File(dir, "tab_history_" + id + ".json");
    }

    private static void writeAtomic(File target, String text) {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return;
        }
        File temp = new File(parent, target.getName() + ".tmp");
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
            out.write(data);
            out.flush();
            out.close();
            if (target.exists() && !target.delete()) {
                temp.delete();
                return;
            }
            if (!temp.renameTo(target)) {
                try (BufferedOutputStream fallback = new BufferedOutputStream(new FileOutputStream(target))) {
                    fallback.write(data);
                    fallback.flush();
                }
                temp.delete();
            }
        } catch (Exception ignored) {
            temp.delete();
        }
    }
}
