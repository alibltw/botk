package com.botplus.core;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.game.entities.Box;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Verim Sayaci: kesilen NPC, toplanan kutu, olum ve BotPlus'in devreye girdigi isler.
 * Ekranda NPC/saat ve kutu/saat gosterilir, loga 5 dakikada bir ozet yazilir.
 * Her sayac tek yerden artar (kutu sadece gercekten toplanan kutu, tamir sadece yeni baslayan tamir).
 */
public final class Stats {

    private static final long SUMMARY_MS = 5 * 60_000;
    private static final int COLLECT_DIST = 300;
    private static final long RATE_MIN_MS = 5 * 60_000;

    private static final Map<String, Integer> COUNTS = new LinkedHashMap<>();
    /** Botun calistigi sure (ms): saatlik oranlar buna gore (bot dururken gecen sure sayilmaz). */
    private static long runningMs;
    private static long lastRunTick;

    private final Context context;
    private final Map<Integer, Box> nearBoxes = new HashMap<>();
    /** tick() icin tekrar kullanilan tablo (her tik yeni HashMap kurulmaz). */
    private final Map<Integer, Box> nowBoxes = new HashMap<>();
    private long nextSummary = Context.now() + SUMMARY_MS;
    /** Son gorulen harita (harita degisiminde kaybolan yakin kutu toplandi sayilmaz). */
    private int lastMap = -1;

    public Stats(Context context) {
        this.context = context;
    }

    public static synchronized void count(String key) {
        COUNTS.merge(key, 1, Integer::sum);
    }

    public static synchronized int get(String key) {
        return COUNTS.getOrDefault(key, 0);
    }

    /** 2.8.0: sure sayaci (ms) - kacis, tamir gibi islerde gecen toplam sure. */
    private static final Map<String, Long> TIMES = new LinkedHashMap<>();

    public static synchronized void time(String key, long ms) {
        if (ms > 0) TIMES.merge(key, ms, Long::sum);
    }

    public static synchronized long timeMs(String key) {
        return TIMES.getOrDefault(key, 0L);
    }

    public static synchronized void reset() {
        COUNTS.clear();
        TIMES.clear();
        runningMs = 0;
    }

    /** Botun calistigi sure (ms): sadece calisirken gecen tikler (duvar saatinden bagimsiz). */
    public static synchronized long runningMs() {
        return runningMs;
    }

    /** Saatlik oran: ilk dakikalarda sisirilmesin diye sure en az 5 dk sayilir. */
    public static synchronized double perHour(String key) {
        double hours = Math.max(RATE_MIN_MS, runningMs) / 3_600_000.0;
        return get(key) / hours;
    }

    /** Calisma suresi: bot calisirken her tik eklenir (dururken / captcha'da eklenmez). */
    public static synchronized void running(boolean on) {
        long now = System.currentTimeMillis();
        if (on && lastRunTick > 0) runningMs += Math.min(2000, now - lastRunTick);
        lastRunTick = on ? now : 0;
    }

    /** Kutu toplama denemesi (BotPlus ya da toplama modulu tikladi): sadece bunlar sayilir. */
    private static final Map<Integer, Long> TRIED = new HashMap<>();

    public static synchronized void tried(Box b) {
        TRIED.put(b.getId(), Context.now());
    }

    private static synchronized Long triedAt(int id) {
        return TRIED.get(id);
    }

    /**
     * Toplanan kutular: gemi yanindayken (toplama denenmis ya da gemi uzerinde) kaybolan ve toplama acik kutu sayilir.
     * Baskasinin aldigi / suresi dolan uzak kutular, harita degisimi, olum ve captcha sayilmaz.
     */
    public void tick(boolean captcha) {
        long t = Context.now();
        boolean skip = captcha || context.repair.isDestroyed() || !context.heroLoaded();
        Map<Integer, Box> now = nowBoxes;
        now.clear();
        eu.darkbot.api.game.other.Location dest = context.move.getDestination();
        for (Box b : context.ents.getBoxes()) {
            if (!b.isValid()) continue;
            // Gemi kutuya gidiyorsa (BotPlus ya da DarkBot toplayicisi) toplama denemesi sayilir.
            if (dest != null && dest.distanceTo(b) < 30) tried(b);
            if (context.hero.distanceTo(b) < COLLECT_DIST) now.put(b.getId(), b);
        }
        int map = mapId();
        boolean mapChanged = (map != lastMap && lastMap != -1 && map != -1)
                || (now.isEmpty() && context.ents.getBoxes().isEmpty() && nearBoxes.size() > 1);
        if (map != -1) lastMap = map;
        for (Map.Entry<Integer, Box> e : nearBoxes.entrySet()) {
            if (now.containsKey(e.getKey())) continue;
            Box b = e.getValue();
            if (b.isValid() || context.hero.distanceTo(b) >= COLLECT_DIST) continue;
            Long at = triedAt(b.getId());
            boolean ours = (at != null && t - at < 10_000) || context.hero.distanceTo(b) < 60;
            boolean wanted = b.getInfo() != null && b.getInfo().shouldCollect();
            if (skip || mapChanged) {
                Log.d("KUTU", b.getTypeName() + " kayboldu (" + (captcha ? "captcha" : "harita / olum") + ", sayilmadi)");
            } else if (ours && wanted) {
                count("kutu");
                Log.d("KUTU", b.getTypeName() + " toplandi " + Log.loc(b));
            } else {
                Log.d("KUTU", b.getTypeName() + " " + Log.loc(b) + " kayboldu (biz toplamadik, sayilmadi)");
            }
        }
        nearBoxes.clear();
        nearBoxes.putAll(now);
        synchronized (Stats.class) {
            TRIED.values().removeIf(v -> t - v > 30_000);
        }

        if (context.config.PLUS.STATS() && t >= nextSummary) {
            nextSummary = t + SUMMARY_MS;
            Log.i("VERIM", summary());
        }
    }

    private int mapId() {
        try {
            return context.star.getCurrentMap() == null ? -1 : context.star.getCurrentMap().getId();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    public static synchronized String summary() {
        long min = runningMs / 60_000;
        StringBuilder sb = new StringBuilder();
        sb.append(min).append(" dk | NPC ").append(get("npc")).append(" (").append(Math.round(perHour("npc"))).append("/saat)")
                .append(" | kutu ").append(get("kutu")).append(" (").append(Math.round(perHour("kutu"))).append("/saat)")
                .append(" | olum ").append(get("olum"));
        for (Map.Entry<String, Integer> e : COUNTS.entrySet()) {
            String k = e.getKey();
            if (k.equals("npc") || k.equals("kutu") || k.equals("olum")) continue;
            sb.append(" | ").append(k).append(" ").append(e.getValue());
        }
        for (Map.Entry<String, Long> e : TIMES.entrySet())
            sb.append(" | ").append(e.getKey()).append(" suresi ").append(e.getValue() / 1000).append(" sn");
        return sb.toString();
    }

    public static String screen() {
        return "BotPlus · NPC " + Math.round(perHour("npc")) + "/saat · kutu " + Math.round(perHour("kutu")) + "/saat";
    }
}