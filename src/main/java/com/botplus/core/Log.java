package com.botplus.core;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.entities.Ship;
import eu.darkbot.api.game.other.Locatable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * Log: DarkBot konsoluna ve (acikse) logs/botplus-<hesap>.log dosyasina yazar (hesap bilinene kadar satirlar bellekte bekler).
 * Konsolda Turkce harfler cevrilir (DarkBot konsolu "?" yaziyor), dosyaya UTF-8 olarak aynen yazilir.
 * i: her zaman, d: sadece "Ayrintili" log seviyesinde, game: oyun mesajlari (sadece dosyaya).
 */
public final class Log {

    public static boolean enabled = true;
    public static boolean detailed = false;
    public static boolean toFile = true;
    /** Hesap adi: dosya adi botplus-<hesap>.log. Bilinene kadar satirlar bellekte tutulur. */
    public static volatile String account;
    /** Hesap adi beklenmeye baslanan an (30 sn sonra "genel" dosyaya yazilir). */
    private static long firstPendingAt;

    private static final long MAX_FILE = 20L * 1024 * 1024;
    private static final int MAX_PENDING = 500;
    private static final long FLUSH_MS = 1000;
    /** Hesap adi gelmezse ortak dosya: ayni klasorde birden cok DarkBot ayni dosyaya yazmasin diye surec no'lu. */
    private static final String GENERAL = generalName();

    private static String generalName() {
        try {
            String n = java.lang.management.ManagementFactory.getRuntimeMXBean().getName(); // "pid@host"
            int at = n == null ? -1 : n.indexOf('@');
            String pid = fileName(at > 0 ? n.substring(0, at) : n);
            return pid == null ? "genel" : "genel-" + pid;
        } catch (Throwable t) {
            return "genel";
        }
    }
    private static final Map<String, Long> LAST = new HashMap<>();
    private static final Map<String, String> CHANGES = new HashMap<>();
    private static final SimpleDateFormat TIME = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss.SSS");
    /** Dosya acilmadan (ayar / hesap gelmeden) yazilan satirlar. */
    private static final ArrayDeque<String> PENDING = new ArrayDeque<>();

    private static BufferedWriter out;
    private static File file;
    /** Acik dosyanin hesabi (hesap degisince yeni dosya acilir). */
    private static String fileAccount;
    private static boolean fileFailed;
    /** Dosyaya yazilan bayt (her satirda dosya boyu sorulmaz). */
    private static long written;
    /** Ayarlar geldi mi: gelmeden dosya olusturulmaz (satirlar bellekte bekler). */
    public static boolean configured;
    private static long lastPrune;
    private static long lastFlush;
    private static volatile boolean dirty;

    private Log() {
    }

    /** Her zaman yazilir (log acikken). */
    public static void i(String tag, String msg) {
        if (enabled) write(tag, msg, true);
    }

    /** Sadece ayrintili log seviyesinde yazilir. */
    public static void d(String tag, String msg) {
        if (enabled && detailed) write(tag, msg, detailConsole()); // ayrintili satirlar konsolu doldurmasin
    }

    /** Oyun mesaji: sadece dosyaya (konsolda DarkBot zaten yaziyor). */
    public static void game(String msg) {
        if (enabled && toFile) write("OYUN", msg, false);
    }

    /** Sadece ayrintili log seviyesinde yazilir; metin sadece yazilacaksa kurulur. */
    public static void d(String tag, java.util.function.Supplier<String> msg) {
        if (enabled && detailed) write(tag, msg.get(), detailConsole());
    }

    /** Ayni anahtar icin en fazla ms'de bir yazar. */
    public static void every(String key, long ms, String tag, String msg) {
        if (!enabled || !allow(key, ms)) return;
        i(tag, msg);
    }

    /** Ayni anahtar icin en fazla ms'de bir yazar; metin sadece yazilacaksa kurulur. */
    public static void every(String key, long ms, String tag, java.util.function.Supplier<String> msg) {
        if (!enabled || !allow(key, ms)) return;
        i(tag, msg.get());
    }

    /** Ayrintili seviyede, ayni anahtar icin en fazla ms'de bir yazar. */
    public static void dEvery(String key, long ms, String tag, String msg) {
        if (!enabled || !detailed || !allow(key, ms)) return;
        write(tag, msg, detailConsole());
    }

    /** Ayrintili satir konsola da yazilir mi: sadece dosyaya yazilamiyorsa (dosya kapali / hata). */
    private static boolean detailConsole() {
        return !toFile || fileFailed;
    }

    /** Ayrintili seviyede, ayni anahtar icin en fazla ms'de bir yazar; metin sadece yazilacaksa kurulur. */
    public static void dEvery(String key, long ms, String tag, java.util.function.Supplier<String> msg) {
        if (!enabled || !detailed || !allow(key, ms)) return;
        write(tag, msg.get(), detailConsole());
    }

    /** Deger degistiyse yazar (ayni durum tekrar tekrar yazilmaz). */
    public static void change(String key, String value, String tag, String msg) {
        if (!enabled) return;
        synchronized (CHANGES) {
            if (value.equals(CHANGES.get(key))) return;
            CHANGES.put(key, value);
        }
        i(tag, msg);
    }

    public static void forget(String key) {
        synchronized (CHANGES) {
            CHANGES.remove(key);
        }
    }

    /** Ayni anahtar icin ms gectiyse true (yazmadan once pahali metin kurmamak icin). */
    public static boolean due(String key, long ms) {
        return enabled && allow(key, ms);
    }

    /** Ayrintili seviyede due. */
    public static boolean dDue(String key, long ms) {
        return enabled && detailed && allow(key, ms);
    }

    private static boolean allow(String key, long ms) {
        long now = System.currentTimeMillis();
        synchronized (LAST) {
            // NPC'ye ozel anahtarlar birikmesin: dakikada bir, 10 dk'dan eski kayitlar silinir.
            if (LAST.size() > 2000 && now - lastPrune > 60_000) {
                lastPrune = now;
                LAST.values().removeIf(t -> now - t > 600_000);
            }
            Long last = LAST.get(key);
            if (last != null && now - last < ms) return false;
            LAST.put(key, now);
        }
        return true;
    }

    private static synchronized void write(String tag, String msg, boolean console) {
        if (console) System.out.println("[BotPlus] " + tag + " | " + ascii(msg));
        if (fileFailed) return;
        if (configured && !toFile) {
            PENDING.clear();
            close();
            return;
        }
        String line = TIME.format(new Date()) + " | " + tag + " | " + msg;
        String acc = fileName(account);
        // Hesap adi 30 sn'de gelmezse (giris yapilmadi vb.) ortak dosyaya yazilir, satirlar kaybolmaz.
        if (acc != null) firstPendingAt = 0; // hesap geldi: sonraki bekleme 30 sn'yi bastan sayar
        else if (configured) {
            if (firstPendingAt == 0) firstPendingAt = System.currentTimeMillis();
            if (System.currentTimeMillis() - firstPendingAt > 30_000) acc = GENERAL;
        }
        if (!configured || acc == null) {
            // Ayar / hesap bilinene kadar bellekte tutulur (en fazla 500 satir, eskiler atilir).
            if (PENDING.size() >= MAX_PENDING) PENDING.pollFirst();
            PENDING.addLast(line);
            return;
        }
        try {
            if (out != null && !acc.equals(fileAccount)) close();
            if (out == null || written > MAX_FILE) open(acc);
            while (!PENDING.isEmpty()) put(PENDING.pollFirst());
            put(line);
            long now = System.currentTimeMillis();
            if (now - lastFlush >= FLUSH_MS || tag.contains("OLUM") || tag.contains("HATA") || msg.contains("HATA")) flushNow(now);
        } catch (IOException e) {
            fileFailed = true;
            close();
            System.out.println("[BotPlus] LOG | " + (file == null ? "log dosyasi" : file.getName()) + " yazilamiyor: " + e);
        }
    }

    private static void put(String line) throws IOException {
        out.write(line);
        out.newLine();
        dirty = true;
        written += utf8Len(line) + NL; // gercek bayt (Turkce karakterler 2 bayt)
    }

    private static final int NL = System.lineSeparator().length();

    private static int utf8Len(String s) {
        int n = 0;
        for (int i = 0, l = s.length(); i < l; i++) {
            char c = s.charAt(i);
            if (c < 0x80) n++;
            else if (c < 0x800) n += 2;
            else if (Character.isHighSurrogate(c)) { n += 4; i++; }
            else n += 3;
        }
        return n;
    }

    private static void flushNow(long now) throws IOException {
        lastFlush = now;
        if (out != null && dirty) out.flush();
        dirty = false;
    }

    /** Bekleyen satirlar en fazla 1 sn'de bir diske yazilir (her tik cagrilabilir, ucuz). */
    public static void flushIfDue() {
        if (!dirty) return;
        synchronized (Log.class) {
            long now = System.currentTimeMillis();
            if (now - lastFlush < FLUSH_MS) return;
            try {
                flushNow(now);
            } catch (IOException e) {
                fileFailed = true;
                close();
                System.out.println("[BotPlus] LOG | " + (file == null ? "log dosyasi" : file.getName()) + " yazilamiyor: " + e);
            }
        }
    }

    /** Hesap adi dosya adina uygun hale getirilir ([A-Za-z0-9_-]); bos ise null. */
    public static String fileName(String acc) {
        if (acc == null) return null;
        StringBuilder sb = new StringBuilder(acc.length());
        for (char c : acc.trim().toCharArray()) {
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
            sb.append(ok ? c : '_');
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** Dosya kapatilir (log dosyasi kapatilinca / eklenti durunca). Tekrar acilinca yeniden denenir. */
    public static synchronized void close() {
        if (out != null) {
            try {
                out.flush();
            } catch (IOException ignored) {
            }
            try {
                out.close();
            } catch (IOException ignored) {
            }
            out = null;
            fileAccount = null;
            dirty = false;
        }
    }

    /** Dosya ayari tekrar acilinca yazma hatasi sifirlanir. */
    public static synchronized void retryFile() {
        fileFailed = false;
    }

    private static void open(String acc) throws IOException {
        boolean rotate = out != null || (file != null && acc.equals(fileAccount) && written > MAX_FILE);
        close();
        File dir = new File("logs");
        if (!dir.isDirectory() && !dir.mkdirs()) dir = new File(".");
        String base = "botplus-" + acc;
        file = new File(dir, base + ".log");
        if (rotate || file.length() > MAX_FILE) {
            File old = new File(dir, base + ".old.log");
            if (old.exists() && !old.delete()) System.out.println("[BotPlus] LOG | " + old.getName() + " silinemedi");
            if (!file.renameTo(old)) {
                // Tasinamazsa (dosya kilitli) eski log silinmez: zaman damgali yeni dosyaya yazilir.
                File alt = new File(dir, base + "-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date()) + ".log");
                System.out.println("[BotPlus] LOG | " + file.getName() + " tasinamadi, yeni dosya: " + alt.getName());
                file = alt;
            }
        }
        out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), StandardCharsets.UTF_8));
        fileAccount = acc;
        written = file.length();
        lastFlush = System.currentTimeMillis();
        System.out.println("[BotPlus] LOG | dosya: " + file.getAbsolutePath());
    }

    // ---------------------------------------------------------------- bicimler

    public static String loc(Locatable l) {
        return l == null ? "-" : "(" + (int) l.getX() + "," + (int) l.getY() + ")";
    }

    public static String pct(double v) {
        return "%" + Math.round(v * 100);
    }

    public static String dist(double d) {
        return d >= Double.MAX_VALUE / 2 ? "yok" : String.valueOf((int) d);
    }

    public static String npc(Npc n) {
        if (n == null) return "-";
        String name = n.getInfo() == null ? "?" : n.getInfo().getName();
        String hp = n.getHealth() == null || n.getHealth().getMaxHp() <= 0 ? "?" : pct(n.getHealth().hpPercent());
        return name + " #" + n.getId() + " can " + hp + " " + loc(n);
    }

    public static String ship(Ship s) {
        if (s == null) return "-";
        String name = s.getEntityInfo() == null ? "?" : s.getEntityInfo().getUsername();
        String hp = s.getHealth() == null || s.getHealth().getMaxHp() <= 0 ? "?" : pct(s.getHealth().hpPercent());
        return name + " #" + s.getId() + " can " + hp + " " + loc(s);
    }

    public static String name(Ship s) {
        if (s == null) return "-";
        if (s instanceof Npc) return ((Npc) s).getInfo() == null ? "?" : ((Npc) s).getInfo().getName();
        return s.getEntityInfo() == null ? "?" : s.getEntityInfo().getUsername();
    }

    /** Konsol icin: Turkce harfler cevrilir, bilinen isaretler ASCII'ye, diger ASCII disi karakterler '?' olur. */
    public static String ascii(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            if (c < 128) {
                sb.append(c);
                continue;
            }
            int i = "çğıöşüÇĞİÖŞÜ".indexOf(c);
            if (i >= 0) {
                sb.append("cgiosuCGIOSU".charAt(i));
                continue;
            }
            switch (c) {
                case '·': case '–': case '—': case '•':
                    sb.append('-');
                    break;
                case '→':
                    sb.append("->");
                    break;
                case '←':
                    sb.append("<-");
                    break;
                case '’': case '‘':
                    sb.append('\'');
                    break;
                case '“': case '”':
                    sb.append('"');
                    break;
                case '…':
                    sb.append("...");
                    break;
                case '°':
                    sb.append(" der");
                    break;
                default:
                    sb.append('?');
            }
        }
        return sb.toString();
    }
}