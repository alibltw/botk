package com.botplus.rsb;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.core.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.steal.*;

import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.enums.EntityEffect;
import eu.darkbot.api.game.items.Item;
import eu.darkbot.api.game.items.ItemFlag;
import eu.darkbot.api.game.items.SelectableItem;
import eu.darkbot.api.game.other.Health;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Optional;
import java.util.function.BooleanSupplier;

/**
 * BotPlus RSB: DarkBot'un RSB secimi yerine (o ates kesilmesine yol aciyordu). NPC hedefliyken her zaman bir cephane
 * verilir (normal ya da RSB), boylece DarkBot'un RSB secicisi devreye girmez.
 * Ilk atis asla RSB degil; RSB'ye gecince 800 ms + ping sonra ates / hasar dogrulanir. Dogrulanamazsa normale donulur,
 * 30 sn'de 2 hata olursa RSB 20 sn kapatilir.
 */
public final class RsbLogic {

    private static final long DROP_MS = 1500;
    private static final long VERIFY_MS = 800;
    /** Lazer henuz RSB'ye gecmediyse dogrulama en fazla bu kadar kez, bu kadar ertelenir. */
    private static final int VERIFY_EXTENDS = 2;
    private static final long VERIFY_EXTEND_MS = 700;
    private int verifyExtends;
    private static final long FAIL_WINDOW_MS = 30_000;
    private static final long OFF_MS = 20_000;
    private static final long OUT_RANGE_MS = 1500;
    private static final double MIN_QTY = 50;
    /** Akilli RSB: NPC cani bunun altindayken RSB atilmaz. */
    private static final double SMART_HP = 0.10;

    private final Context context;
    private final BooleanSupplier combatOn;

    /** Su an RSB veriliyor mu (hedef npcId icin). */
    private boolean on;
    private int npcId = -1;
    private long lastSwitchAt;
    private long offUntil;
    private final Deque<Long> fails = new ArrayDeque<>();

    // Hedefin can takibi (son 1,5 sn'de hasar aldi mi)
    private int trackId = -1;
    private long trackTotal = -1;
    private long dropAt;

    // Dogrulama
    private long verifyDue;
    private int verifyId = -1;
    private long verifyTotal;
    private boolean firedSeen;

    public RsbLogic(Context context, BooleanSupplier combatOn) {
        this.context = context;
        this.combatOn = combatOn;
    }

    private BotPlusConfig.Rsb cfg() {
        return context.config.RSB;
    }

    public SelectableItem.Laser rsbLaser() {
        BotPlusConfig.Ammo a = cfg().AMMO;
        return a == null ? SelectableItem.Laser.RSB_75 : a.laser;
    }

    /** Normal / RSB gecisleri arasi en az sure (menude yok). */
    private static final long SWITCH_MS = 1500;
    /** Gemide son gorulen RSB olmayan cephane (NPC tablosunda cephane yoksa o kullanilir). */
    private SelectableItem.Laser lastNormal;

    /** NPC icin normal cephane: NPC tablosundaki cephane; tabloda yoksa gemide son kullanilan normal cephane. */
    public SelectableItem.Laser normal(Npc npc) {
        if (npc != null && npc.getInfo() != null) {
            Optional<SelectableItem.Laser> t = npc.getInfo().getAmmo();
            if (t != null && t.isPresent()) return t.get();
        }
        if (lastNormal != null) return lastNormal;
        // Bilinmiyorsa gemideki mevcut cephane (RSB / SAB degilse); eskiden x4 (UCB-100) seciliyordu.
        try {
            SelectableItem.Laser cur = context.hero.getLaser();
            if (cur != null && cur != rsbLaser() && cur != SelectableItem.Laser.RSB_75 && cur != SelectableItem.Laser.SAB_50) return cur;
        } catch (RuntimeException ignored) {
        }
        return SelectableItem.Laser.LCB_10;
    }

    /** BotPlus RSB su an hedef icin secili / acik mi. */
    public boolean active() {
        return on && npcId >= 0 && cfg().ENABLED;
    }

    /** NPC hedefliyken asla null degil (DarkBot'un RSB secicisi engellenir). */
    public SelectableItem.Laser laser(Npc npc) {
        if (npc.getId() != npcId) {
            // Yeni hedef: ilk atis normal.
            if (on) Log.d("RSB", "hedef degisti, normal cephane: " + Log.npc(npc));
            on = false;
            npcId = npc.getId();
            verifyDue = 0;
        }
        return on ? rsbLaser() : normal(npc);
    }

    private static long total(Npc n) {
        Health h = n.getHealth();
        return h == null ? -1 : (long) h.getHp() + h.getShield();
    }

    /**
     * RSB esyasi engeli; null ise atilabilir. Sadece "RSB hazir degil" (bekleme suresi) atis yapildi sayilir;
     * az cephane / kullanilamaz esya atis sayilmaz.
     */
    /** RSB atisi kaniti: gecis anindaki RSB cephanesi ve azaldi mi. */
    private double qtyAtSwitch = -1;
    private boolean qtyFired;

    /** RSB cephanesi adedi (bilinmiyorsa -1). */
    private double rsbQty() {
        try {
            Optional<Item> o = context.items.getItem(rsbLaser());
            return o.isPresent() ? o.get().getQuantity() : -1;
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private String itemBlock() {
        try {
            Optional<Item> o = context.items.getItem(rsbLaser());
            if (!o.isPresent()) return "RSB yok";
            Item i = o.get();
            if (!i.isUsable()) return "RSB kullanilamaz";
            if (i.getQuantity() <= MIN_QTY) return "RSB az (" + (int) i.getQuantity() + ")";
            if (!i.isReady()) return "RSB hazir degil";
            return null;
        } catch (RuntimeException e) {
            return "RSB yok";
        }
    }

    private int ping() {
        try {
            return context.stats.getPing();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private Npc target() {
        Npc n = context.hero.getLocalTargetAs(Npc.class);
        if (n == null || !n.isValid() || Context.isFake(n)) n = context.targetNpc();
        return n;
    }

    /** RSB icin engel; null ise RSB atilabilir. */
    private String block(Npc npc, long now) {
        BotPlusConfig.Rsb c = cfg();
        if (!c.ENABLED) return "kapali";
        if (now < offUntil) return "devre disi (" + (offUntil - now) / 1000 + " sn)";
        if (npc.getInfo() == null) return "NPC bilgisi yok";
        if (c.WHICH == BotPlusConfig.RsbWhich.FLAGGED && !npc.getInfo().hasExtraFlag(BotPlusFlag.RSB)) return "RSB isareti yok";
        if (combatOn.getAsBoolean()) {
            if (!context.rsbStable.test(npc)) return "atis oturmadi";
        } else {
            // Kendi kilidini yapan modullerde de (DarkBot'un saldiri API'si disinda) gemi hedefi yeterli.
            Object ht = context.hero.getTarget();
            boolean locked = context.attack.isLocked() || (ht instanceof Npc && ((Npc) ht).getId() == npc.getId());
            if (!locked) return "kilit yok";
            // Baska modul (ornegin Galaxy Gate) dovusurken mesafe NPC listesindeki menzile gore degil o modulun
            // kendi mesafesidir: menzil yerine NPC'nin gercekten hasar almasina bakilir (eskiden tablo menzili
            // disinda diye RSB hic atilmiyordu).
            if (trackId != npc.getId() || now - dropAt > DROP_MS) return "hasar yok";
        }
        if (npc.hasEffect(EntityEffect.NPC_ISH)) return "NPC ISH";
        if (c.SMART && Context.hp(npc) < SMART_HP) return "Akilli RSB: NPC cani " + Log.pct(Context.hp(npc)) + " < %10";
        int p = ping();
        if (c.LAG_GUARD && p > c.PING) return "ping " + p;
        return itemBlock();
    }

    /** Her tik: hedef can takibi, RSB gecisi ve dogrulama. */
    public void tick() {
        long now = Context.now();
        try {
            SelectableItem.Laser cur = context.hero.getLaser();
            // Normal cephane kaydi: RSB, SAB ve Cal'in dusmana attigi cephane (Cal acik, hedef oyuncu) kaydedilmez.
            // Cal'in cephanesi de kaydedilmez (Cal bitince hedef temizlenir ama cephane bir an daha Cal'inkidir).
            boolean stealing = context.config.STEAL.ENABLED && (context.hero.getTarget() instanceof eu.darkbot.api.game.entities.Player
                    || cur == context.config.STEAL.AMMO.laser);
            if (cur != null && cur != rsbLaser() && cur != SelectableItem.Laser.RSB_75 && cur != SelectableItem.Laser.SAB_50 && !stealing)
                lastNormal = cur;
        } catch (RuntimeException ignored) {
        }
        Npc npc;
        try {
            npc = context.config.RSB.ENABLED ? target() : null;
        } catch (RuntimeException e) {
            npc = null;
        }
        if (npc == null) {
            on = false;
            verifyDue = 0;
            trackId = -1;
            return;
        }
        try {
            track(npc, now);
            if (npc.getId() != npcId) laser(npc);
            // RSB cephanesi azaldiysa atis kesin yapildi (en saglam kanit).
            if (verifyDue > 0 && !qtyFired && qtyAtSwitch >= 0) {
                double q = rsbQty();
                if (q >= 0 && q < qtyAtSwitch) {
                    qtyFired = true;
                    firedSeen = true;
                    Log.d("RSB", "RSB atildi (cephane " + (long) qtyAtSwitch + " -> " + (long) q + "): " + Log.npc(npc));
                }
            }
            if (verifyDue > 0) {
                if (verifyId != npc.getId()) verifyDue = 0;
                else if (now >= verifyDue) verify(npc, now);
            }
            String why = block(npc, now);
            // Atis oturmasi (rsbStable) sadece RSB'ye girerken bakilir; RSB acikken sadece kesin nedenlerle cikilir.
            if (on && why != null && !"atis oturmadi".equals(why)) {
                // Atis sonrasi RSB beklemede: normale donulur (atis yapildi sayilir).
                if (why.equals("RSB hazir degil")) firedSeen = true;
                    // Baska nedenle cikildiysa hic atilmamis atis dogrulanmaz.
                else verifyDue = 0;
                on = false;
                lastSwitchAt = now;
                Log.d("RSB", "normal cephane (" + why + "): " + Log.npc(npc));
            } else if (!on && why == null && now - lastSwitchAt >= SWITCH_MS && verifyDue == 0) {
                on = true;
                lastSwitchAt = now;
                verifyDue = now + VERIFY_MS + Math.max(0, ping());
                verifyId = npc.getId();
                verifyTotal = total(npc);
                verifyExtends = 0;
                firedSeen = false;
                qtyFired = false;
                qtyAtSwitch = rsbQty();
                Log.d("RSB", "RSB'ye geciliyor: " + Log.npc(npc) + " can " + Log.pct(Context.hp(npc)) + " ping " + ping());
            }
        } catch (RuntimeException e) {
            on = false;
            verifyDue = 0;
        }
    }

    private void track(Npc npc, long now) {
        long t = total(npc);
        if (trackId != npc.getId()) {
            trackId = npc.getId();
            trackTotal = t;
            dropAt = 0;
            return;
        }
        if (t >= 0 && trackTotal >= 0 && t < trackTotal) dropAt = now;
        trackTotal = t;
    }

    private void verify(Npc npc, long now) {
        verifyDue = 0;
        long t = total(npc);
        // Can dususu sadece gemide gercekten RSB secili iken kanit sayilir (eskiden normal mermi / PET hasari da RSB
        // atisi sayiliyordu: RSB 11-26 sn hic atilmadan "dogrulandi" kaliyordu).
        boolean loaded;
        try {
            loaded = context.hero.getLaser() == rsbLaser();
        } catch (RuntimeException e) {
            loaded = false;
        }
        // Cephane degisimi henuz gemiye yansimadi (lazer hala normal): hata sayilmaz, dogrulama kisa sure ertelenir.
        // Eskiden 95 "dogrulanamadi"nin 90'inda lazer hala UCB_100'du: RSB hic yuklenmeden hata sayiliyordu.
        if (!loaded && !qtyFired && !firedSeen && verifyExtends < VERIFY_EXTENDS) {
            verifyExtends++;
            verifyDue = now + VERIFY_EXTEND_MS + Math.max(0, ping());
            Log.d("RSB", "RSB henuz yuklenmedi (lazer " + context.hero.getLaser() + "), dogrulama ertelendi (" + verifyExtends + ")");
            return;
        }
        boolean dropped = loaded && t >= 0 && verifyTotal >= 0 && t < verifyTotal;
        // Sadece gercek kanit: hasar ya da atis (RSB bekleme suresine girdi). "RSB secili ve saldiriyor" yetmez.
        if (qtyFired || dropped || firedSeen) {
            Log.d("RSB", "RSB dogrulandi (" + (qtyFired ? "cephane azaldi" : dropped ? "hasar" : "atildi") + "): " + Log.npc(npc));
            return;
        }
        String excuse = null;
        try {
            excuse = context.rsbExcuse.apply(npc);
        } catch (RuntimeException ignored) {
        }
        if (excuse == null && !context.attack.isLocked()) excuse = "kilit yok";
        if (excuse == null && now - context.lastOutOfRangeAt < OUT_RANGE_MS) excuse = "menzil disi mesaji";
        if (on) {
            on = false;
            lastSwitchAt = now;
        }
        if (excuse != null) {
            Log.d("RSB", "RSB dogrulanamadi ama mazeret var (" + excuse + "), normal cephane: " + Log.npc(npc));
            return;
        }
        fails.addLast(now);
        while (!fails.isEmpty() && now - fails.peekFirst() > FAIL_WINDOW_MS) fails.removeFirst();
        try {
            // Ates tusu burada basilmaz (tus ac / kapa: atesi kapatabiliyordu); DarkBot'un saldiri dongusu atesi surdurur.
            context.items.useItem(normal(npc), 0);
        } catch (RuntimeException ignored) {
        }
        Stats.count("rsb hata");
        Log.i("RSB", "RSB atisi dogrulanamadi (ates / hasar yok, cephane " + context.hero.getLaser() + "), normal cephaneye donuldu: "
                + Log.npc(npc) + " | " + fails.size() + ". hata (30 sn)");
        if (fails.size() >= 2) {
            offUntil = now + OFF_MS;
            fails.clear();
            Log.i("RSB", "30 sn'de 2 RSB hatasi: RSB " + OFF_MS / 1000 + " sn kapali");
        }
    }

    /** Durum satiri icin. */
    public String status() {
        if (!cfg().ENABLED) return "kapali";
        long now = Context.now();
        if (now < offUntil) return "devre disi " + (offUntil - now) / 1000 + " sn";
        return on ? "RSB" : "normal";
    }
}