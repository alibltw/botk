package com.botplus.core;

import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.other.Health;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Olum raporu (2.1.0): son 30 sn saniyede bir ornek tutulur; gemi patlayinca loga ozet yazilir
 * (can / kalkan akisi, hangi is yapiliyordu, kim ates etti, en cok hasarin geldigi an).
 */
public final class DeathLog {

    private static final long KEEP_MS = 30_000;
    private static final long STEP_MS = 1_000;

    private static final class Sample {
        long at;
        double hp;
        String shieldText = "-";
        long total; // can + kalkan (hasar farki icin)
        String job;
        String target;
        String attackers;
        String enemy;
        String loc;
    }

    private final Context context;
    private final ArrayDeque<Sample> samples = new ArrayDeque<>();
    private long lastAt;

    public DeathLog(Context context) {
        this.context = context;
    }

    /** Her tik cagrilir, saniyede bir ornek alir. Gemi oluyken / yuklenmemisken ornek alinmaz. */
    public void sample(String job) {
        long now = Context.now();
        if (now - lastAt < STEP_MS) return;
        try {
            if (context.repair.isDestroyed() || !context.heroLoaded()) return;
            // Uzun bosluk (bot durdu, harita yuklendi): onceki ornekler bu ana ait degil.
            if (lastAt > 0 && now - lastAt > 3 * STEP_MS) samples.clear();
            lastAt = now;
            Sample s = new Sample();
            s.at = now;
            Health h = context.hero.getHealth();
            s.hp = context.heroHp();
            s.shieldText = Log.shield(h);
            s.total = h == null ? 0 : (long) h.getHp() + h.getShield();
            s.job = job == null ? "-" : job;
            Npc t = context.targetNpc();
            s.target = t == null ? "-" : Log.name(t) + " " + Log.pct(Context.hp(t)) + " " + (int) t.distanceTo(context.hero);
            StringBuilder a = new StringBuilder();
            for (Player p : context.ents.getPlayers()) {
                if (!context.isEnemyPlayer(p) || !p.isAttacking(context.hero)) continue;
                if (a.length() > 0) a.append(", ");
                a.append(Log.name(p)).append(' ').append((int) p.distanceTo(context.hero));
            }
            s.attackers = a.length() == 0 ? "-" : a.toString();
            Player e = context.nearestEnemy(context.hero);
            s.enemy = e == null ? "-" : Log.name(e) + " " + (int) e.distanceTo(context.hero);
            s.loc = Log.loc(context.hero);
            samples.addLast(s);
            while (!samples.isEmpty() && now - samples.peekFirst().at > KEEP_MS) samples.pollFirst();
        } catch (RuntimeException ignored) {
            // rapor botu durdurmamali
        }
    }

    /**
     * 2.8.0: olum aninda yeteneklerin durumu: ayarda acik mi, hazir mi (bekleme / yok), en son ne zaman basildi.
     * Ornegin "EMP: acik, hazirdi, basilmadi" ayar hatasini ilk olumde gosterir.
     */
    private String abilityLine(long deathAt) {
        BotPlusConfig.Protect pc = context.config.PROTECT;
        boolean on = pc != null && pc.ENABLED;
        return "EMP " + ability(on && pc.EMP_ON, eu.darkbot.api.game.items.SelectableItem.Special.EMP_01, context.empUsedAt, deathAt)
                + " | Insta-Shield " + ability(on && pc.ISH_ON, eu.darkbot.api.game.items.SelectableItem.Special.ISH_01, context.ishUsedAt, deathAt)
                + " | HP Link " + (on && pc.HP_LINK != null && pc.HP_LINK.ENABLED ? "acik" : "kapali")
                + ", " + (context.hpLinkUsedAt > 0 && deathAt - context.hpLinkUsedAt < 120_000
                ? (deathAt - context.hpLinkUsedAt) / 1000 + " sn once kullanildi" : "son 2 dk kullanilmadi");
    }

    private String ability(boolean on, eu.darkbot.api.game.items.SelectableItem item, long usedAt, long deathAt) {
        String ready;
        try {
            java.util.Optional<eu.darkbot.api.game.items.Item> o = context.items.getItem(item);
            ready = !o.isPresent() ? "gemide yok" : o.get().isReady() ? "hazirdi" : "beklemedeydi";
        } catch (RuntimeException e) {
            ready = "durum okunamadi";
        }
        String used = usedAt > 0 && deathAt - usedAt < 120_000 ? (deathAt - usedAt) / 1000 + " sn once basildi" : "basilmadi";
        return (on ? "acik" : "kapali") + ", " + ready + ", " + used;
    }

    /** Son ornegin cani (maxAgeMs'den eskiyse -1): bot duraksadiginda olum ekrani mi ayirt etmek icin (2.6.0). */
    public double lastHp(long maxAgeMs) {
        Sample l = samples.peekLast();
        return l == null || Context.now() - l.at > maxAgeMs ? -1 : l.hp;
    }

    /** Gemi patladi: son 30 sn ozeti loga (OLUM). Ornekler silinir. */
    public void report(String killer, long deathAt) {
        report(killer, deathAt, false);
    }

    /** npcKiller: olduren NPC (Push Korumasi'na yazilmaz, push sadece oyuncular icin). */
    public void report(String killer, long deathAt, boolean npcKiller) {
        try {
            if (killer != null && !killer.isEmpty() && !npcKiller) context.push.died(killer);
        } catch (RuntimeException ignored) {
        }
        // 2.8.0 VERIM: oyuncu / NPC olumu ayri; olum aninda EMP / Insta-Shield / HP Link durumu.
        if (killer != null && !killer.isEmpty()) Stats.count(npcKiller ? "olum npc" : "olum oyuncu");
        try {
            Log.i("OLUM", "yetenekler: " + abilityLine(deathAt));
        } catch (RuntimeException ignored) {
        }
        // Olumden sonraki ornekler (canlandiktan sonra alinmis) atilir; olumden 10 sn'den eski ornekler bu olumu
        // anlatmaz (bot durmustu / gecici moduldeydi).
        while (!samples.isEmpty() && samples.peekLast().at > deathAt) samples.pollLast();
        if (!samples.isEmpty() && deathAt - samples.peekLast().at > 10_000) samples.clear();
        if (samples.isEmpty()) {
            Log.i("OLUM", "rapor: son 30 sn icin ornek yok (gemi gecici modulde / yuklenirken patladi)");
            return;
        }
        Sample first = samples.peekFirst(), last = samples.peekLast();
        // 2.6.0: DarkBot olumu gec bildirir (olum ekrani acilinca bot tiklemez, birkac sn sonra "patladi" gelir).
        // Bot son ornekten sonra durakladiysa olum ani o duraklamanin basi sayilir (eskiden "olumden 4 sn once"
        // yazan an aslinda olum aniydi).
        long stop = context.stopStartedAt;
        if (stop > last.at && stop < deathAt && deathAt - stop < 10_000) {
            Log.i("OLUM", "DarkBot olumu " + (deathAt - stop) / 1000 + " sn gec bildirdi: olum ani bot duraksadigi an sayiliyor");
            deathAt = stop;
        }
        long span = Math.max(1, (last.at - first.at) / 1000);
        // En cok hasar alinan saniye ve isler / saldiranlar.
        long worst = 0, worstSpan = 1;
        Sample worstAt = null;
        Sample prev = null;
        Map<String, Integer> jobs = new LinkedHashMap<>();
        Map<String, Integer> who = new LinkedHashMap<>();
        for (Sample s : samples) {
            jobs.merge(s.job, 1, Integer::sum);
            if (!"-".equals(s.attackers))
                for (String w : s.attackers.split(", ")) who.merge(w.replaceAll(" \\d+$", ""), 1, Integer::sum);
            if (prev != null && prev.total - s.total > worst) {
                worst = prev.total - s.total;
                worstAt = s;
                worstSpan = Math.max(1, Math.round((s.at - prev.at) / 1000.0));
            }
            prev = s;
        }
        Log.i("OLUM", "===== OLUM RAPORU (son " + span + " sn) | olduren: " + (killer == null || killer.isEmpty() ? "bilinmiyor" : killer)
                + " =====");
        Log.i("OLUM", "isler: " + jobs + " | ates eden oyuncular (sn): " + (who.isEmpty() ? "yok (NPC)" : who.toString()));
        if (worstAt != null)
            Log.i("OLUM", "en cok hasar: " + worst + " (" + worstSpan + " sn) | olumden " + (deathAt - worstAt.at) / 1000
                    + " sn once | is " + worstAt.job
                    + " | hedef " + worstAt.target + " | " + worstAt.loc);
        // Akis: en fazla 10 satir (3 sn arayla, son 5 sn tamami).
        int i = 0, n = samples.size();
        for (Sample s : samples) {
            boolean show = n - i <= 5 || i % 3 == 0;
            i++;
            if (!show) continue;
            Log.i("OLUM", "-" + Math.max(0, deathAt - s.at) / 1000 + " sn | can " + Log.pct(s.hp) + " kalkan " + s.shieldText
                    + " | " + s.job + " | hedef " + s.target + " | ates eden " + s.attackers + " | en yakin dusman " + s.enemy
                    + " | " + s.loc);
        }
        samples.clear();
    }
}