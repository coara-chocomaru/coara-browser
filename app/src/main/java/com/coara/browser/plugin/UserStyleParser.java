package com.coara.browser.plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class UserStyleParser {
    public static final class Block {
        public final List<String> matches = new ArrayList<>();
        public String css = "";
    }

    public static final class Result {
        public String name = "";
        public String namespace = "";
        public String version = "";
        public String description = "";
        public String author = "";
        public String homepage = "";
        public final List<Block> blocks = new ArrayList<>();
    }

    private static final int MAX_BLOCKS = 32;

    private UserStyleParser() {
    }

    public static boolean hasMetaBlock(String src) {
        return src != null && src.contains("==UserStyle==");
    }

    public static Result parse(String src, List<String> warnings) {
        Result r = new Result();
        String body = src;
        Map<String, String> vars = new LinkedHashMap<>();
        int a = src.indexOf("==UserStyle==");
        if (a >= 0) {
            int b = src.indexOf("==/UserStyle==", a);
            if (b > a) {
                String meta = src.substring(a + "==UserStyle==".length(), b);
                parseMeta(meta, r, vars, warnings);
                int commentStart = src.lastIndexOf("/*", a);
                int commentEnd = src.indexOf("*/", b);
                if (commentStart >= 0 && commentEnd > b) {
                    body = src.substring(0, commentStart) + src.substring(commentEnd + 2);
                } else {
                    body = src.substring(b + "==/UserStyle==".length());
                }
            }
        }
        String varBlock = buildVarBlock(vars);
        List<Block> blocks = new ArrayList<>();
        StringBuilder outside = new StringBuilder();
        int i = 0;
        int n = body.length();
        while (i < n) {
            int at = indexOfDocument(body, i);
            if (at < 0) {
                outside.append(body, i, n);
                break;
            }
            outside.append(body, i, at);
            int open = body.indexOf('{', at);
            if (open < 0) {
                break;
            }
            String cond = body.substring(at + "@-moz-document".length(), open);
            int close = matchBrace(body, open);
            if (close < 0) {
                warnings.add("@-moz-documentの括弧が閉じていません");
                close = n - 1;
            }
            String inner = body.substring(open + 1, close);
            Block blk = new Block();
            parseConditions(cond, blk.matches, warnings);
            blk.css = inner;
            blocks.add(blk);
            i = close + 1;
        }
        String rest = outside.toString();
        if (!rest.trim().isEmpty() && !onlyComments(rest)) {
            Block g = new Block();
            g.matches.addAll(PluginPatterns.DEFAULT_MATCHES);
            g.css = rest;
            blocks.add(0, g);
        }
        for (Block b : blocks) {
            if (b.matches.isEmpty()) {
                b.matches.addAll(PluginPatterns.DEFAULT_MATCHES);
            }
            b.css = applyVars(b.css, vars);
            if (!varBlock.isEmpty()) {
                b.css = varBlock + b.css;
            }
            if (r.blocks.size() >= MAX_BLOCKS) {
                warnings.add("スタイルブロックが多すぎるため" + MAX_BLOCKS + "件までに制限しました");
                break;
            }
            if (!b.css.trim().isEmpty()) {
                r.blocks.add(b);
            }
        }
        return r;
    }

    private static boolean onlyComments(String s) {
        String t = s.replaceAll("(?s)/\\*.*?\\*/", "").trim();
        return t.isEmpty();
    }

    private static int indexOfDocument(String s, int from) {
        int idx = s.indexOf("@-moz-document", from);
        if (idx >= 0) {
            return idx;
        }
        return -1;
    }

    private static int matchBrace(String s, int open) {
        int depth = 0;
        char quote = 0;
        for (int i = open; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
                int end = s.indexOf("*/", i + 2);
                if (end < 0) {
                    return -1;
                }
                i = end + 1;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static void parseMeta(String meta, Result r, Map<String, String> vars, List<String> warnings) {
        for (String raw : meta.split("\r?\n")) {
            String line = raw.trim();
            if (!line.startsWith("@")) {
                continue;
            }
            int sp = 1;
            while (sp < line.length() && !Character.isWhitespace(line.charAt(sp))) {
                sp++;
            }
            String key = line.substring(1, sp).toLowerCase(java.util.Locale.US);
            String value = line.substring(sp).trim();
            switch (key) {
                case "name":
                    r.name = value;
                    break;
                case "namespace":
                    r.namespace = value;
                    break;
                case "version":
                    r.version = value;
                    break;
                case "description":
                    r.description = value;
                    break;
                case "author":
                    r.author = value;
                    break;
                case "homepageurl":
                    r.homepage = value;
                    break;
                case "preprocessor":
                    if (!value.equalsIgnoreCase("default")) {
                        warnings.add("プリプロセッサ「" + value + "」は未対応です(defaultとして処理)");
                    }
                    break;
                case "var":
                case "advanced":
                    parseVar(value, vars);
                    break;
                default:
                    break;
            }
        }
    }

    private static void parseVar(String value, Map<String, String> vars) {
        List<String> tokens = tokenize(value);
        if (tokens.size() < 3) {
            return;
        }
        String type = tokens.get(0).toLowerCase(java.util.Locale.US);
        String name = tokens.get(1);
        if (type.equals("select") || type.equals("dropdown") || type.equals("image")) {
            String first = null;
            String starred = null;
            int bracket = value.indexOf('[');
            if (bracket < 0) {
                return;
            }
            java.util.regex.Matcher qm = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"|'([^']*)'").matcher(value.substring(bracket));
            while (qm.find()) {
                String v = qm.group(1) != null ? qm.group(1) : qm.group(2);
                boolean star = v.endsWith("*");
                if (star) {
                    v = v.substring(0, v.length() - 1);
                }
                int colon = v.indexOf(':');
                if (colon > 0) {
                    v = v.substring(0, colon);
                }
                if (first == null) {
                    first = v;
                }
                if (star) {
                    starred = v;
                }
            }
            String chosen = starred != null ? starred : first;
            if (chosen != null) {
                vars.put(name, chosen);
            }
            return;
        }
        if (tokens.size() >= 4) {
            String def = tokens.get(3);
            if (type.equals("range") && def.startsWith("[")) {
                String t = def.substring(1);
                int comma = t.indexOf(',');
                def = comma > 0 ? t.substring(0, comma) : t;
            }
            vars.put(name, def);
        }
    }

    private static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            if (c == '"' || c == '\'') {
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < n && s.charAt(j) != c) {
                    if (s.charAt(j) == '\\' && j + 1 < n) {
                        j++;
                    }
                    sb.append(s.charAt(j));
                    j++;
                }
                out.add(sb.toString());
                i = j + 1;
            } else {
                int j = i;
                while (j < n && !Character.isWhitespace(s.charAt(j))) {
                    j++;
                }
                out.add(s.substring(i, j));
                i = j;
            }
        }
        return out;
    }

    private static String buildVarBlock(Map<String, String> vars) {
        if (vars.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(":root{");
        for (Map.Entry<String, String> e : vars.entrySet()) {
            if (e.getKey().matches("[A-Za-z0-9_\\-]+")) {
                sb.append("--").append(e.getKey()).append(':').append(e.getValue().replace(";", "").replace("}", "")).append(';');
            }
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String applyVars(String css, Map<String, String> vars) {
        String out = css;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            out = out.replace("/*[[" + e.getKey() + "]]*/", e.getValue());
        }
        return out;
    }

    private static void parseConditions(String cond, List<String> out, List<String> warnings) {
        int i = 0;
        int n = cond.length();
        while (i < n) {
            int open = cond.indexOf('(', i);
            if (open < 0) {
                break;
            }
            String fn = cond.substring(i, open).replace(",", "").trim().toLowerCase(java.util.Locale.US);
            int close = findClose(cond, open);
            if (close < 0) {
                break;
            }
            String arg = stripQuotes(cond.substring(open + 1, close).trim());
            i = close + 1;
            String regex = conditionToRegex(fn, arg);
            if (regex == null) {
                warnings.add("未対応の条件を無視: " + fn + "(" + arg + ")");
            } else {
                out.add(regex);
            }
        }
    }

    private static int findClose(String s, int open) {
        char quote = 0;
        for (int i = open + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == '\\') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == ')') {
                return i;
            }
        }
        return -1;
    }

    private static String stripQuotes(String s) {
        if (s.length() >= 2) {
            char f = s.charAt(0);
            char l = s.charAt(s.length() - 1);
            if ((f == '"' || f == '\'') && f == l) {
                return s.substring(1, s.length() - 1).replace("\\\\", "\\");
            }
        }
        return s;
    }

    static String conditionToRegex(String fn, String arg) {
        if (arg.isEmpty()) {
            return null;
        }
        String regex;
        switch (fn) {
            case "url":
                regex = "^" + PluginPatterns.quoteRegex(arg) + "$";
                break;
            case "url-prefix":
                regex = "^" + PluginPatterns.quoteRegex(arg);
                break;
            case "domain":
                regex = "^[a-z][a-z0-9+.\\-]*:\\/\\/(?:[^\\/:]*\\.)?" + PluginPatterns.quoteRegex(arg.toLowerCase(java.util.Locale.US))
                        + "(?::\\d+)?(?:[\\/?#]|$)";
                break;
            case "regexp":
                regex = "^(?:" + arg + ")$";
                break;
            default:
                return null;
        }
        try {
            Pattern.compile(regex);
        } catch (Exception e) {
            return null;
        }
        return "/" + regex + "/";
    }
}
