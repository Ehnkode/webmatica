package io.github.ehnkode.webmatica;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.MinecraftClient;

/**
 * The mod's messages are written in German. If the game language is not
 * German, they are translated via the same table as in the viewer (i18n/en.tsv).
 * In-game messages follow the game language (German or English).
 */
public final class Lang {
    private static final Pattern EDGE = Pattern.compile("^([\\s,:;·–]*)([\\s\\S]*?)([\\s,.:;·–]*)$");
    private static Map<String, String> exact;
    private static List<Object[]> patterns;

    private Lang() {
    }

    public static String tr(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String lang = mc != null && mc.options != null ? mc.options.language : "en_us";
        if (s == null || lang == null || lang.startsWith("de")) {
            return s;
        }
        load();
        return t(s);
    }

    private static synchronized void load() {
        if (exact != null) {
            return;
        }
        exact = new HashMap<>();
        patterns = new ArrayList<>();
        try (InputStream in = Lang.class.getResourceAsStream("/assets/webmatica/i18n/en.tsv")) {
            if (in == null) {
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                int tab = line.indexOf('\t');
                if (line.isEmpty() || line.charAt(0) == '#' || tab < 0) {
                    continue;
                }
                String de = core(line.substring(0, tab).replaceAll("\\s+", " "));
                String en = line.substring(tab + 1);
                if (de.isEmpty()) {
                    continue;
                }
                if (de.contains("{}")) {
                    StringBuilder re = new StringBuilder("^");
                    String[] parts = de.split("\\{\\}", -1);
                    for (int i = 0; i < parts.length; i++) {
                        if (i > 0) {
                            re.append("([\\s\\S]*?)");
                        }
                        re.append(Pattern.quote(parts[i]));
                    }
                    patterns.add(new Object[] {Pattern.compile(re.append('$').toString()), core(en), de.replace("{}", "").length()});
                } else {
                    exact.put(de, core(en));
                }
            }
            patterns.sort((a, b) -> (Integer) b[2] - (Integer) a[2]);
        } catch (Exception e) {
            // without a table it stays German
        }
    }

    private static String core(String s) {
        Matcher m = EDGE.matcher(s);
        return m.matches() ? m.group(2) : s;
    }

    private static String t(String s) {
        if (s.isEmpty()) {
            return s;
        }
        Matcher m = EDGE.matcher(s.replaceAll("\\s+", " "));
        if (!m.matches() || m.group(2).isEmpty()) {
            return s;
        }
        String c = m.group(2);
        String r = exact.get(c);
        if (r == null) {
            for (Object[] p : patterns) {
                Matcher pm = ((Pattern) p[0]).matcher(c);
                if (pm.matches()) {
                    StringBuilder b = new StringBuilder();
                    String[] en = ((String) p[1]).split("\\{\\}", -1);
                    for (int i = 0; i < en.length; i++) {
                        if (i > 0) {
                            b.append(i <= pm.groupCount() ? t(pm.group(i)) : "");
                        }
                        b.append(en[i]);
                    }
                    r = b.toString();
                    break;
                }
            }
        }
        if (r == null) {
            for (String sep : new String[] {" · ", ", "}) {
                if (c.contains(sep)) {
                    String[] parts = c.split(Pattern.quote(sep), -1);
                    boolean changed = false;
                    for (int i = 0; i < parts.length; i++) {
                        String x = t(parts[i]);
                        changed |= !x.equals(parts[i]);
                        parts[i] = x;
                    }
                    if (changed) {
                        r = String.join(sep, parts);
                        break;
                    }
                }
            }
        }
        if (r == null) {
            return s;
        }
        String lead = m.group(1).trim();
        return (lead.isEmpty() ? "" : lead + " ") + r + m.group(3).trim();
    }
}
