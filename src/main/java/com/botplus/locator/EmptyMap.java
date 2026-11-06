package com.botplus.locator;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.core.*;
import com.botplus.defense.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.game.other.Location;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Bos Harita Modu: bulucu (PET Dusman Bulucu) N sn sinyal vermezse (aranan turden canli NPC yok) haritanin
 * kenarlarinda dolasmak yerine harita ortasinda aranir / beklenir: yeni NPC dogunca sinyale yol kisa olur.
 */
public final class EmptyMap {

    /** Harita kenarindan uzak durulacak mesafe (dolasma hedefi). */
    private static final double EDGE = 6000;

    private final Context context;

    /** Sinyalin son gorulmedigi andan beri (0: sinyal var / izlenmiyor). */
    private long noSignalSince;
    /** Bos mod acik mi ve ne zaman acildi. */
    private boolean empty;
    private long emptySince;
    /** Son bos mod nedeni (log). */
    private String reason = "-";

    public EmptyMap(Context context) {
        this.context = context;
    }

    private BotPlusConfig.EmptyMapSettings cfg() {
        BotPlusConfig.EmptyMapSettings c = context.config.EMPTY;
        return c == null ? new BotPlusConfig.EmptyMapSettings() : c;
    }

    /** Izleme sarti: ozellik acik, "Bulununca Git" acik ve PET aktif ve Dusman Bulucu'da (degilse sinyal de beklenmez). */
    private boolean tracking() {
        if (!cfg().ENABLED || context.config.LOCATOR == null || !context.config.LOCATOR.GO) return false;
        try {
            return context.pet.isActive() && context.pet.getGear() == eu.darkbot.api.game.enums.PetGear.ENEMY_LOCATOR;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Izleme kosulu (PET bulucuda) bu kadar sure bozuk kalmadikca bos mod kapanmaz / sayac sifirlanmaz. */
    private static final long TRACK_LOST_MS = 3000;
    private long trackLostSince;
    private int mapEpoch = Integer.MIN_VALUE;

    /** Her tik cagrilir (BotPlus.onTickBehavior). */
    public void tick() {
        long now = Context.now();
        // Harita degisti: eski haritanin "sinyalsiz" suresi / bos modu yeni haritaya tasinmaz.
        int ep = context.mapEpoch();
        if (ep != mapEpoch) {
            if (mapEpoch != Integer.MIN_VALUE && empty) exit(now, "harita degisti");
            mapEpoch = ep;
            noSignalSince = 0;
            trackLostSince = 0;
        }
        if (!tracking()) {
            // PET bir an bulucudan cikinca (modul degisimi, okuma hatasi) mod hemen kapanmaz (eskiden 15 baslangicin
            // 10'u 0-4 sn icinde kapaniyordu); 3 sn surerse kapanir.
            if (trackLostSince == 0) trackLostSince = now;
            if (now - trackLostSince < TRACK_LOST_MS && cfg().ENABLED) return;
            if (empty) exit(now, "izleme kapandi (ayar / PET " + (now - trackLostSince) / 1000 + " sn bulucuda degil)");
            noSignalSince = 0;
            return;
        }
        trackLostSince = 0;
        Optional<Location> sig = signal();
        if (sig.isPresent()) {
            noSignalSince = 0;
            if (empty) exit(now, "bulucu sinyali geldi " + Log.loc(sig.get()));
            return;
        }
        if (noSignalSince == 0) noSignalSince = now;
        long quiet = now - noSignalSince;
        if (!empty && quiet >= cfg().SEC * 1000L) {
            empty = true;
            emptySince = now;
            reason = "bulucu " + quiet / 1000 + " sn sinyal vermiyor (aranan turden canli NPC yok)";
            Log.i("BOS HARITA", "basladi: " + reason + "; dolasma harita ortasina");
        }
    }

    private void exit(long now, String why) {
        empty = false;
        Log.i("BOS HARITA", "bitti: " + why + ", bos sure " + (now - emptySince) / 1000 + " sn");
        reason = "-";
    }

    /** Gidilebilir bulucu sinyali (olu / bos bulunan / reddedilen yerdeki eski isaret sayilmaz). */
    private Optional<Location> signal() {
        try {
            return context.ping().filter(context.pingOk::test);
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Bos mod acik mi. */
    public boolean empty() {
        return empty;
    }

    /** Bos modun suresi (ms, kapaliysa 0). */
    public long emptyMs() {
        return empty ? Context.now() - emptySince : 0;
    }

    /** Son bos mod nedeni (kapaliysa "-"). */
    public String reason() {
        return reason;
    }

    /**
     * Bos modda dolasma hedefi: harita ortasina yakin, kenarlardan EDGE uzakta (harita kucukse ortaya sikistirilir).
     * Harita bilinmiyorsa null (cagiran kendi hedefini kullanir).
     */
    public Location roamBias() {
        try {
            eu.darkbot.api.game.other.Area.Rectangle r = context.star.getCurrentMapBounds();
            if (r == null || r.getWidth() <= 0 || r.getHeight() <= 0) return null;
            double cx = r.getX() + r.getWidth() / 2, cy = r.getY() + r.getHeight() / 2;
            // Ic dikdortgenin yari boyu: kenardan EDGE iceride; harita dar ise ortanin 1/4'u.
            double hx = Math.max(r.getWidth() / 2 - EDGE, r.getWidth() / 8);
            double hy = Math.max(r.getHeight() / 2 - EDGE, r.getHeight() / 8);
            ThreadLocalRandom rnd = ThreadLocalRandom.current();
            // Ortaya yanli: iki rastgele sayinin ortalamasi (ucgen dagilim).
            double x = cx + hx * (rnd.nextDouble(-1, 1) + rnd.nextDouble(-1, 1)) / 2;
            double y = cy + hy * (rnd.nextDouble(-1, 1) + rnd.nextDouble(-1, 1)) / 2;
            return Location.of(x, y);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private Location goal;
    private long goalAt;

    /**
     * Bos modda gidilecek nokta (her iki modda ortak): gidilebilir (engel / radyasyon degil) nokta secilir, varilinca
     * (800) ya da 60 sn'de varilamazsa yenisi. Bos mod degilse null.
     */
    public Location goal() {
        if (!empty) {
            goal = null;
            return null;
        }
        long now = Context.now();
        boolean renew = goal == null || context.hero.distanceTo(goal) < 800 || now - goalAt > 60_000 || context.move.isOutOfMap();
        if (renew) {
            Location g = null;
            for (int i = 0; i < 8 && g == null; i++) {
                Location c = roamBias();
                if (c != null && context.move.canMove(c)
                        && (!context.config.PROTECT.KILLER.ENABLED || context.killerZone(c, 2500) == null)) g = c;
            }
            goal = g;
            goalAt = now;
            if (g != null && Log.due("emptyroam", 10_000)) Log.i("BOS HARITA", "harita ortasinda dolasiliyor " + Log.loc(g));
        }
        return goal;
    }

    /** Ayar satiri (BotPlus.settingsLine). */
    public String settingsLine() {
        BotPlusConfig.EmptyMapSettings c = cfg();
        return "bos_harita=" + (c.ENABLED ? c.SEC + "sn" : "kapali");
    }
}