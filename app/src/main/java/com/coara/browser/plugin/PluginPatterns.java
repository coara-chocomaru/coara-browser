package com.coara.browser.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

public final class PluginPatterns {
    public static final List<String> DEFAULT_MATCHES;

    private static final Map<String, Pattern> PATTERN_CACHE = new ConcurrentHashMap<>();
    private static final String SPECIALS = "\\.[]{}()+^$|";

    static {
        List<String> d = new ArrayList<>();
        d.add("http://*");
        d.add("https://*");
        DEFAULT_MATCHES = java.util.Collections.unmodifiableList(d);
    }

    private PluginPatterns() {
    }

    public static List<String> parseList(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        String[] tokens = raw.trim().split("[\\s;]+");
        for (String token : tokens) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.length() > 2 && t.startsWith("/") && t.endsWith("/")) {
                out.add(t);
                continue;
            }
            for (String part : t.split(",")) {
                String p = part.trim();
                if (!p.isEmpty()) {
                    out.add(p);
                }
            }
        }
        return out;
    }

    public static String toRegex(String pattern) {
        if (pattern == null) {
            return null;
        }
        String p = pattern.trim();
        if (p.isEmpty() || p.length() > 2048) {
            return null;
        }
        if (p.equals("*")) {
            return "^.*$";
        }
        if (p.equalsIgnoreCase("<all_urls>")) {
            return "^(https?|file)://.*$";
        }
        if (p.length() > 2 && p.startsWith("/") && p.endsWith("/")) {
            String inner = p.substring(1, p.length() - 1);
            try {
                Pattern.compile(inner);
            } catch (Exception e) {
                return null;
            }
            return inner;
        }
        StringBuilder sb = new StringBuilder(p.length() + 8);
        sb.append('^');
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if (c == '?') {
                sb.append('.');
            } else if (SPECIALS.indexOf(c) >= 0) {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        sb.append('$');
        String regex = sb.toString();
        try {
            Pattern.compile(regex);
        } catch (Exception e) {
            return null;
        }
        return regex;
    }

    public static List<String> toRegexList(List<String> patterns) {
        List<String> out = new ArrayList<>();
        if (patterns == null) {
            return out;
        }
        for (String p : patterns) {
            String r = toRegex(p);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    public static List<String> effectiveMatches(List<String> patterns) {
        if (patterns == null || patterns.isEmpty()) {
            return DEFAULT_MATCHES;
        }
        return patterns;
    }

    public static boolean matchesAny(List<String> regexes, String url) {
        if (url == null || regexes == null) {
            return false;
        }
        for (String r : regexes) {
            Pattern p = PATTERN_CACHE.get(r);
            if (p == null) {
                try {
                    p = Pattern.compile(r);
                } catch (Exception e) {
                    continue;
                }
                if (PATTERN_CACHE.size() > 512) {
                    PATTERN_CACHE.clear();
                }
                PATTERN_CACHE.put(r, p);
            }
            if (p.matcher(url).find()) {
                return true;
            }
        }
        return false;
    }

    public static boolean isTarget(PluginInfo info, String url) {
        if (info == null || url == null) {
            return false;
        }
        return info.isTarget(url);
    }

    public static String quoteRegex(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (SPECIALS.indexOf(c) >= 0 || c == '/' || c == '*' || c == '?' || c == '-') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public static String globBodyToRegex(String glob) {
        StringBuilder sb = new StringBuilder(glob.length() + 8);
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                sb.append(".*");
            } else if (c == '?') {
                sb.append('.');
            } else {
                sb.append(quoteRegex(String.valueOf(c)));
            }
        }
        return sb.toString();
    }

    public static String fromMatchPattern(String raw) {
        if (raw == null) {
            return null;
        }
        String p = raw.trim();
        if (p.isEmpty() || p.length() > 512) {
            return null;
        }
        if (p.equals("<all_urls>")) {
            return "<all_urls>";
        }
        if (p.equals("*")) {
            return "<all_urls>";
        }
        int sep = p.indexOf("://");
        if (sep <= 0) {
            return null;
        }
        String scheme = p.substring(0, sep).toLowerCase(java.util.Locale.US);
        String rest = p.substring(sep + 3);
        String schemeRegex;
        switch (scheme) {
            case "*":
                schemeRegex = "(?:https?|wss?)";
                break;
            case "http":
            case "https":
            case "file":
            case "ftp":
            case "ws":
            case "wss":
                schemeRegex = scheme;
                break;
            default:
                return null;
        }
        int slash = rest.indexOf('/');
        String host;
        String path;
        if (slash < 0) {
            if (scheme.equals("file")) {
                return null;
            }
            host = rest;
            path = "/";
        } else {
            host = rest.substring(0, slash);
            path = rest.substring(slash);
        }
        String hostRegex;
        if (scheme.equals("file")) {
            hostRegex = "";
        } else {
            if (host.isEmpty()) {
                return null;
            }
            String portPart = "";
            int colon = host.lastIndexOf(':');
            if (colon >= 0 && host.indexOf(']') < colon) {
                portPart = host.substring(colon);
                host = host.substring(0, colon);
            }
            if (host.equals("*")) {
                hostRegex = "[^/:]*";
            } else if (host.startsWith("*.")) {
                hostRegex = "(?:[^/:]*\\.)?" + quoteRegex(host.substring(2));
            } else if (host.contains("*")) {
                return null;
            } else {
                hostRegex = quoteRegex(host);
            }
            if (portPart.isEmpty()) {
                hostRegex += "(?::\\d+)?";
            } else if (portPart.equals(":*")) {
                hostRegex += "(?::\\d+)?";
            } else {
                hostRegex += quoteRegex(portPart);
            }
        }
        String regex = "^" + schemeRegex + ":\\/\\/" + hostRegex + globBodyToRegex(path) + "$";
        try {
            Pattern.compile(regex);
        } catch (Exception e) {
            return null;
        }
        return "/" + regex + "/";
    }

    public static String intersect(String slashRegexA, String slashRegexB) {
        if (slashRegexA == null || slashRegexB == null) {
            return null;
        }
        String a = innerOf(slashRegexA);
        String b = innerOf(slashRegexB);
        if (a == null || b == null) {
            return null;
        }
        String regex = "^(?=" + a + ")(?=" + b + ")";
        try {
            Pattern.compile(regex);
        } catch (Exception e) {
            return null;
        }
        return "/" + regex + "/";
    }

    private static String innerOf(String p) {
        if (p.equals("<all_urls>")) {
            return "^(?:https?|file):\\/\\/.*$";
        }
        if (p.length() > 2 && p.startsWith("/") && p.endsWith("/")) {
            return p.substring(1, p.length() - 1);
        }
        String r = toRegex(p);
        return r;
    }
}
