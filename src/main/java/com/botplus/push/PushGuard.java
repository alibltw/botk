package com.botplus.push;

import com.botplus.core.BotPlusConfig;
import com.botplus.core.Log;

import eu.darkbot.api.game.entities.Player;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Push Korumasi: DarkOrbit ayni oyuncuyu tekrar tekrar oldurmeyi (push) yasaklar. Oyuncu bazinda oldurme ve olme
 * kayitlari tutulur (logs/botplus-push.txt, bot yeniden baslasa da hatirlanir):
 * - Oldurme Siniri: ayni oyuncu "Sure" icinde en fazla bu kadar oldurulur; sinirdaki oyuncuya saldirilmaz.
 * - Olme Siniri: ayni oyuncu tarafindan bu kadar olunduyse ona saldirilmaz, saldirirsa kacilir.
 * - Ayni Klan: ayni klandan oyuncuya hic saldirilmaz.
 */
public final class PushGuard {

    private static final String FILE = "botplus-push.txt";

    private final Supplier<eu.darkbot.api.game.other.EntityInfo> heroInfo;
    private final Supplier<BotPlusConfig.PushSettings> settings;
    private final Map<String, List<Long>> kills = new HashMap<>();
    private final Map<String, List<Long>> deaths = new HashMap<>();
    private boolean loaded;

    public PushGuard(Supplier<eu.darkbot.api.game.other.EntityInfo> heroInfo, Supplier<BotPlusConfig.PushSettings> settings) {
        this.heroInfo = heroInfo;
        this.settings = settings;
    }

    private BotPlusConfig.PushSettings cfg() {
        BotPlusConfig.PushSettings s = settings.get();
        return s == null ? new BotPlusConfig.PushSettings() : s;
    }

    private long window() {
        return Math.max(1, cfg().WINDOW_H) * 3_600_000L;
    }

    /** Bu oyuncuya saldirilabilir mi; null: evet, degilse nedeni. */
    public synchronized String blockReason(Player p) {
        if (p == null || p.getEntityInfo() == null) return null;
        BotPlusConfig.PushSettings c = cfg();
        if (!c.ENABLED) return null;
        load();
        try {
            eu.darkbot.api.game.other.EntityInfo me = heroInfo.get();
            int clan = p.getEntityInfo().getClanId();
            if (c.SAME_CLAN && me != null && clan > 0 && clan == me.getClanId()) return "ayni klan";
        } catch (RuntimeException ignored) {
        }
        String name = p.getEntityInfo().getUsername();
        int k = count(kills, name);
        if (k >= c.KILL_LIMIT) return "push siniri: " + k + " kez olduruldu (" + c.WINDOW_H + " saatte en fazla " + c.KILL_LIMIT + ")";
        int d = count(deaths, name);
        if (d >= c.DEATH_LIMIT) return "bu oyuncudan " + d + " kez olundu (" + c.WINDOW_H + " saatte siniri " + c.DEATH_LIMIT + ")";
        return null;
    }

    /** Bu oyuncu tarafindan olme siniri asildi mi (saldirirsa savasilmaz, kacilir). */
    public synchronized boolean feared(String name) {
        BotPlusConfig.PushSettings c = cfg();
        if (!c.ENABLED || name == null) return false;
        load();
        return count(deaths, name) >= c.DEATH_LIMIT;
    }

    public synchronized void killed(String name) {
        record(kills, "K", name);
        Log.i("PUSH", name + " olduruldu: " + count(kills, name) + ". kez (" + cfg().WINDOW_H + " saatte siniri " + cfg().KILL_LIMIT + ")");
    }

    public synchronized void died(String name) {
        if (name == null || name.isEmpty()) return;
        record(deaths, "D", name);
        Log.i("PUSH", name + " tarafindan olundu: " + count(deaths, name) + ". kez (siniri " + cfg().DEATH_LIMIT + ")");
    }

    private int count(Map<String, List<Long>> m, String name) {
        List<Long> l = m.get(name);
        if (l == null) return 0;
        long from = System.currentTimeMillis() - window();
        l.removeIf(t -> t < from);
        return l.size();
    }

    private void record(Map<String, List<Long>> m, String kind, String name) {
        if (name == null || name.isEmpty()) return;
        load();
        long now = System.currentTimeMillis();
        m.computeIfAbsent(name, x -> new ArrayList<>()).add(now);
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file(), true), StandardCharsets.UTF_8)) {
            w.write(kind + "|" + now + "|" + name.replace("\n", " ") + "\n");
        } catch (Exception e) {
            Log.every("pushfile", 60_000, "PUSH", "kayit yazilamadi: " + e);
        }
    }

    private File file() {
        File dir = new File("logs");
        if (!dir.isDirectory() && !dir.mkdirs()) dir = new File(".");
        return new File(dir, FILE);
    }

    private void load() {
        if (loaded) return;
        loaded = true;
        File f = file();
        if (!f.isFile()) return;
        long from = System.currentTimeMillis() - 72 * 3_600_000L;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                String[] p = line.split("\\|", 3);
                if (p.length < 3) continue;
                long t;
                try {
                    t = Long.parseLong(p[1]);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (t < from) continue;
                (p[0].equals("K") ? kills : deaths).computeIfAbsent(p[2], x -> new ArrayList<>()).add(t);
            }
        } catch (Exception e) {
            Log.every("pushfile", 60_000, "PUSH", "kayit okunamadi: " + e);
        }
    }
}