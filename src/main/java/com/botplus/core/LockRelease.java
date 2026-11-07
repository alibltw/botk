package com.botplus.core;

import eu.darkbot.api.game.entities.Entity;
import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.entities.Player;

/**
 * Kilit Birakma: oyunda saldiri tusu (CTRL) ac / kapa calisir; ilk basis saldiriyi baslatir, ikincisi durdurur.
 * DarkBot'un stopAttack'i son saldiri komutundan kisa sure sonra hicbir sey yapmiyor ve setTarget(null) sadece
 * botun hafizasini temizliyor: oyundaki kilit ve ates surerdi (zehirden sonra x4 oyuncuya gidiyordu).
 *
 * Adimlar:
 * 1. Gemi su an ates ediyorsa CTRL'ye basilir. Basistan sonra en az bir bekleme (ping'e gore, en az 350 ms) durumun
 *    "ates yok"a donmesi beklenir; donmediyse yeniden basilir. Bir seferde en fazla MAX_PRESSES basis: gecikmede
 *    ac / kapa dongusune girip atesi yeniden acmasin. Ates etmiyorken asla basilmaz.
 * 2. Kilit bir oyuncudaysa ve calisan is baska hedef istemiyorsa yakindaki NPC sadece secilir (CTRL yok).
 * Kilit oyuncudayken cephane degistirilmez (slot cubugundan cephane secmek saldiriyi yeniden baslatir).
 *
 * Kendiliginden (sadece BotPlus gemiyi yonetirken): kilit bir oyuncudaysa ve ona saldirmak isteyen is yoksa rutin
 * baslar; ilk basistan once bir bekleme yapilir (baska kod az once CTRL'ye basmis olabilir).
 */
public final class LockRelease {

    /** CTRL'den sonra en az bekleme. */
    private static final long MIN_GAP_MS = 350;
    /** Bir seferde en fazla basis. */
    private static final int MAX_PRESSES = 3;
    /** Kilidi tasimak icin NPC secme denemeleri arasi. */
    private static final long SELECT_GAP_MS = 500;
    /** Rutin en fazla bu kadar surer. */
    private static final long MAX_MS = 3000;
    /** Ayni oyuncu icin kendiliginden baslama en sik bu kadar surede bir. */
    private static final long AUTO_REARM_MS = 10_000;
    /** Kilidi tasimak icin secilecek NPC en fazla bu uzaklikta. */
    private static final double SELECT_DIST = 1500;

    private final Context context;
    private long armedUntil, lastPress, lastSelect, autoAt;
    private int autoId = -1;
    private String why;
    private int presses;

    LockRelease(Context context) {
        this.context = context;
    }

    /** Rutin baslatilir: bot hafizasindaki hedef hemen birakilir, oyundaki ates / kilit sonraki tiklerde. */
    public void request(String why) {
        try {
            context.attack.setTarget(null);
        } catch (RuntimeException ignored) {
        }
        long now = Context.now();
        if (armedUntil <= now) {
            presses = 0;
            this.why = why;
        }
        armedUntil = now + MAX_MS;
    }

    public boolean active() {
        return armedUntil > Context.now();
    }

    private long gap() {
        long ping;
        try {
            ping = context.stats.getPing();
        } catch (RuntimeException e) {
            ping = 0;
        }
        return Math.max(MIN_GAP_MS, Math.min(1500, 2 * ping + 100));
    }

    /** Her tik. auto: BotPlus gemiyi yonetiyor (baska modul oyuncuya saldiriyorsa karisilmaz). */
    public void tick(boolean auto) {
        long now = Context.now();
        Entity cur;
        boolean firing;
        try {
            cur = context.hero.getTarget();
            firing = context.hero.isAttacking();
        } catch (RuntimeException e) {
            return;
        }
        Object wanted;
        try {
            wanted = context.attack.getTarget();
        } catch (RuntimeException e) {
            wanted = null;
        }
        // Bir is bu hedefe bilerek saldiriyor (dovus, PvP, bulastirma, calma): karisilmaz.
        if (cur != null && java.util.Objects.equals(cur, wanted)) {
            armedUntil = 0;
            return;
        }
        // Kendiliginden: kilit bir oyuncuda ve ona saldirmak isteyen is yok.
        if (!active() && auto && cur instanceof Player && !context.petPlayer((Player) cur)) {
            Player p = (Player) cur;
            if (p.getId() != autoId || now - autoAt > AUTO_REARM_MS) {
                autoId = p.getId();
                autoAt = now;
                why = "kilit istenmeyen oyuncuda: " + Log.name(p);
                presses = 0;
                armedUntil = now + MAX_MS;
                lastPress = now; // baska kod az once CTRL'ye basmis olabilir: once durum guncellensin
            }
        }
        if (!active()) return;
        long gap = gap();
        // 1. Ates kesilir (sadece ates varken; durum guncellenene kadar beklenir; en fazla MAX_PRESSES).
        if (firing) {
            if (presses < MAX_PRESSES && now - lastPress >= gap) {
                try {
                    if (context.hero.triggerLaserAttack()) {
                        lastPress = now;
                        presses++;
                        Log.d("KILIT", "ates kesiliyor (CTRL " + presses + "): " + describe(cur) + " | " + why);
                    }
                } catch (RuntimeException ignored) {
                }
            } else if (presses >= MAX_PRESSES && now - lastPress >= gap) {
                Log.every("unlockmax", 10_000, "KILIT", "ates " + presses + " CTRL'ye ragmen suruyor gorunuyor, tekrar basilmiyor | " + why);
            }
            // Ates surerken baska hedef secilmez (atesi yeni hedefe tasiyabilir); basis hakki bittiyse secilir.
            if (!(cur instanceof Player) || presses < MAX_PRESSES) return;
        } else if (now - lastPress < gap) {
            return; // CTRL'den hemen sonra: durum guncellenene kadar beklenir
        }
        // 2. Kilit oyuncudaysa yakindaki NPC sadece secilir (is bir NPC istiyorsa onu kendisi kilitler).
        if (cur instanceof Player) {
            if (wanted instanceof Npc) return;
            if (now - lastSelect >= SELECT_GAP_MS) {
                lastSelect = now;
                Npc n = nearestNpc();
                if (n != null) {
                    try {
                        if (n.trySelect(false))
                            Log.i("KILIT", "kilit " + Log.name((Player) cur) + " uzerinden alindi, " + Log.npc(n)
                                    + " secildi (ates yok) | " + why);
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            return;
        }
        if (presses > 0) Log.i("KILIT", "ates kesildi (" + presses + " CTRL) | " + why);
        armedUntil = 0;
    }

    private static String describe(Entity e) {
        if (e == null) return "-";
        if (e instanceof Npc) return Log.npc((Npc) e);
        if (e instanceof eu.darkbot.api.game.entities.Ship) return Log.name((eu.darkbot.api.game.entities.Ship) e);
        return e.getClass().getSimpleName();
    }

    private Npc nearestNpc() {
        Npc best = null;
        double bd = SELECT_DIST;
        for (Npc n : context.ents.getNpcs()) {
            if (!n.isValid() || Context.isFake(n)) continue;
            double d = n.distanceTo(context.hero);
            if (d < bd) {
                bd = d;
                best = n;
            }
        }
        return best;
    }
}