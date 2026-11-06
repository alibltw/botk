package com.botplus.core;

import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.other.Location;

/**
 * Dusman Saldirinca (BotPlus menusu): dusman oyuncu bize ates edince.
 * - Devam Et: is surer; can "Can %X altindaysa kac" sinirinin altindaysa portala kacilir.
 * - Kac: Kacis Rotasi, yani DarkBot'un kacis sistemiyle (PlusSafety) en uygun portala gidilir; dusman takip ederse /
 *   hasar suruyorsa atlanir, portalda can dolana ve saldiri bitene kadar beklenir.
 * - Hizla Atlat: haritada dusmanin tersine hizla uzaklasilir (kacis konfigi, hiz yetenegi). Dusman bizden hizliysa,
 *   yaklasiyorsa ya da can sinirin altina inerse portala gecilir.
 * Is bitince dusmanin oldugu bolge 5 dk bulucu ve dovus icin yasaklanir.
 */
public final class Escape {

    /** Hizla Atlat'ta yeni kacis noktasi (adim). */
    private static final int STEP = 2000;
    /** Kacis noktasi en az bu kadar surede bir yenilenir. */
    private static final long REPICK_MS = 2500;
    /** Dusman surekli yaklasiyorsa (bu kadar sure) portala gecilir. */
    private static final long CLOSING_MS = 3000;
    /**
     * Portal secilemezse (kacis noktasi yok) haritada uzaklasmaya gecis gecikmesi. Kacis sistemi isi "guvende"
     * diye tiklettigi halde saldiri suruyorsa portal yok demektir: beklemeden uzaklasilir (eskiden 1,5 sn
     * dusuk canla NPC'nin etrafinda donuluyordu).
     */
    private static final long NO_SAFETY_MS = 0;
    /** Hizla Atlat bu kadar surede bitmezse ve dusman hala ates ediyorsa portala gecilir. */
    private static final long OUTRUN_MAX_MS = 120_000;
    /** Ates kesildiyse uzaklik yetmese de bu kadar sure sonra atlatma biter (dusman pesimizde ama ates etmiyor). */
    private static final long QUIET_END_MS = 15_000;
    /** EMP icin dusman kilidinin kesintisiz gorulmesi gereken sure (bizim kilidimizla karismasin). */
    private static final long LOCK_CONFIRM_MS = 300;
    /** Kacilan bolgenin yasak suresi ve yaricapi. */
    private static final long ZONE_MS = 5 * 60_000;
    private static final double ZONE_R = 3000;

    private final Context context;
    private Job running;

    public Escape(Context context) {
        this.context = context;
        context.escapeWanted = () -> running != null && !running.done && running.portal && running.wantNow();
    }

    /** Bize su an ates eden en yakin dusman oyuncu (PET'ler haric). */
    private Player attacker() {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : context.ents.getPlayers()) {
            if (!context.isEnemyPlayer(p) || !p.isAttacking(context.hero)) continue;
            double d = p.distanceTo(context.hero);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private boolean faster(Player p) {
        try {
            return p.getSpeed() >= context.hero.getSpeed() * 0.97;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Can "Kacis Cani" sinirinda ya da altinda mi (dusman ates ederken). */
    public boolean hpLow() {
        int hp = context.config.ON_ENEMY.HP;
        return hp > 0 && context.heroHp() <= hp / 100.0;
    }

    /** Kacis gerekiyorsa isi doner. */
    public PlusModule.Job check() {
        if (running != null && !running.done) return null;
        BotPlusConfig.OnEnemy oe = context.config.ON_ENEMY;
        Player t = attacker();
        if (t == null) return null;
        boolean low = hpLow();
        if (oe.MODE == BotPlusConfig.OnEnemyMode.CONTINUE && !low) {
            Log.every("onenemycont", 15_000, "DUSMAN", Log.ship(t) + " ates ediyor, Dusman Saldirinca: Devam Et (can "
                    + Log.pct(context.heroHp()) + (oe.HP > 0 ? " >= %" + oe.HP : "") + ")");
            return null;
        }
        // Tamir / yenileme icin portala kacilirken ya da calisma haritasi disindayken haritada atlatilmaz: portal
        // kacisi dusman kacisi olarak surer (yoksa portaldan uzaklasiliyor ya da kacis kayboluyordu).
        boolean already = context.safetyEscaping() || !context.onWorkingMap();
        boolean portal = oe.MODE == BotPlusConfig.OnEnemyMode.ESCAPE || low || already;
        String why = low ? "can " + Log.pct(context.heroHp()) + " <= %" + oe.HP
                : already ? "zaten portala kaciliyor / calisma haritasi disinda"
                : oe.MODE == BotPlusConfig.OnEnemyMode.ESCAPE ? "Kac secili" : "Hizla Atlat secili";
        if (!portal && oe.FASTER_PORTAL && faster(t)) {
            portal = true;
            why = "dusman bizden hizli (" + t.getSpeed() + " >= " + context.hero.getSpeed() + ")";
        }
        Stats.count("dusmandan kacis");
        Log.i("DUSMAN", Log.ship(t) + " ates ediyor (" + (int) t.distanceTo(context.hero) + "): "
                + (portal ? "portala kaciliyor" : "hizla uzaklasiliyor") + " | " + why + " | gemi can " + Log.pct(context.heroHp()));
        running = new Job(t, portal);
        return running;
    }

    public final class Job implements PlusModule.Job {
        private final int threatId;
        private final String threatName;
        private Player threat;
        private boolean portal;
        private boolean done;
        private final long start = Context.now();
        private Location out;
        private long outAt, closingSince, safeOkSince;
        private double lastDist = -1;
        /** Hizla Atlat: kacarken gidilen yol, EMP. */
        private double traveled;
        private Location lastPos;
        private boolean empUsed;
        private long empTryAt, empAt, enemyLockSince;

        Job(Player t, boolean portal) {
            this.threatId = t.getId();
            this.threatName = Log.name(t);
            this.threat = t;
            this.portal = portal;
        }

        @Override
        public String name() {
            return "DUSMAN";
        }

        @Override
        public int priority() {
            return PlusModule.P_ESCAPE;
        }

        /** Portala kacarken PlusModule kacis sistemini tikler (DarkBot'un portal secimi / atlama / bekleme). */
        @Override
        public boolean safety() {
            return portal;
        }

        @Override
        public String status() {
            return portal ? "düşmandan portala kaçılıyor (" + threatName + ")" : "düşmandan hızla uzaklaşılıyor (" + threatName + ")";
        }

        /** Saldiri suruyor mu (son ESCAPE_MEMORY_MS icinde ates etti mi). */
        boolean wantNow() {
            // Gorunmese de (id ile) son ates zamani bilinir: kisa gorus kaybi kacisi bitirmez.
            return context.attackedBy(threatId, Context.ESCAPE_MEMORY_MS) || Context.now() - start < Context.ESCAPE_MEMORY_MS;
        }

        private Player refresh() {
            for (Player p : context.ents.getPlayers())
                if (p.getId() == threatId) {
                    threat = p;
                    return p;
                }
            threat = null;
            return null;
        }

        private boolean calm() {
            long calmMs = context.config.ON_ENEMY.CALM_SEC * 1000L;
            Player p = refresh();
            if (p == null) return true; // gorus disinda
            if (context.attackedBy(p, calmMs)) return false;
            // Uzaklasildi ya da uzun suredir ates etmiyor (pesimizden geliyor ama saldirmiyor): is biter.
            return p.distanceTo(context.hero) >= context.config.ON_ENEMY.DIST
                    || !context.attackedBy(p, Math.max(QUIET_END_MS, calmMs * 3));
        }

        private void toPortal(String why) {
            portal = true;
            Log.i("DUSMAN", "portala geciliyor: " + why + " | gemi can " + Log.pct(context.heroHp()));
        }

        @Override
        public boolean tick() {
            long now = Context.now();
            Player p = refresh();
            if (portal) {
                // Buraya sadece kacis sistemi "guvende" deyince gelinir (portalda bekleme bitti ya da kacis yok).
                if (!wantNow() || calm()) {
                    Log.i("DUSMAN", "kacis bitti (" + (now - start) / 1000 + " sn), " + threatName + " artik ates etmiyor");
                    return false;
                }
                // Kacis noktasi (portal) bulunamadi: haritada uzaklasilir.
                if (safeOkSince == 0) safeOkSince = now;
                if (now - safeOkSince < NO_SAFETY_MS) return true;
                Log.every("escnosafety", 10_000, "DUSMAN", "kacis icin portal / guvenli nokta bulunamadi, haritada uzaklasiliyor");
                outrun(p, now);
                return true;
            }
            safeOkSince = 0;
            if (hpLow()) {
                toPortal("can " + Log.pct(context.heroHp()) + " < %" + context.config.ON_ENEMY.HP);
                return true;
            }
            if (p == null || calm()) {
                if (p == null && wantNow()) {
                    outrun(null, now); // gorus disina cikti ama yeni ates etti: bir sure daha uzaklas
                    return true;
                }
                Log.i("DUSMAN", "dusman atlatildi (" + (now - start) / 1000 + " sn)"
                        + (p == null ? ", gorus disinda" : ", " + (int) p.distanceTo(context.hero) + " uzakta"));
                return false;
            }
            BotPlusConfig.OnEnemy oe = context.config.ON_ENEMY;
            track();
            double d = p.distanceTo(context.hero);
            boolean wouldPortal = oe.FASTER_PORTAL && (faster(p) || (closingSince > 0 && now - closingSince > CLOSING_MS));
            // Atlatmak icin EMP: belli yol kacildiktan sonra (ya da portala gecilmek uzereyken) dusman hala ates
            // ediyorsa kilidi kirilir; dusmanin tersine kacmaya devam edilir.
            if (oe.EMP && !empUsed && lockConfirmed(p, now) && (traveled >= oe.EMP_DIST || wouldPortal)) emp(p, d, now);
            // EMP'den sonra 5 sn dusman kilitsiz: hizli / yaklasiyor diye hemen portala gecilmez, atlatma denenir.
            boolean empGrace = empAt > 0 && now - empAt < 5000;
            if (now - start > OUTRUN_MAX_MS && context.attackedBy(p, 3000)) {
                toPortal(OUTRUN_MAX_MS / 1000 + " sn'dir atlatilamadi, hala ates ediyor");
                return true;
            }
            if (!empGrace && oe.FASTER_PORTAL && faster(p)) {
                toPortal("dusman bizden hizli (" + p.getSpeed() + " >= " + context.hero.getSpeed() + ")");
                return true;
            }
            // Dusman ates ederek surekli yaklasiyorsa (atlatilamiyor) portala gecilir.
            if (empGrace) closingSince = 0;
            if (lastDist >= 0 && d < lastDist - 5 && context.attackedBy(p, 2000)) {
                if (closingSince == 0) closingSince = now;
            } else if (lastDist >= 0 && d > lastDist + 5) closingSince = 0;
            lastDist = d;
            if (oe.FASTER_PORTAL && closingSince > 0 && now - closingSince > CLOSING_MS) {
                toPortal("dusman " + CLOSING_MS / 1000 + " sn'dir yaklasiyor (" + (int) d + ")");
                return true;
            }
            outrun(p, now);
            return true;
        }

        /**
         * Dusmanin kilidi hala bizde mi: dusmanin hedefi bizim gemimiz (ates etsin etmesin). Sadece DUSMANIN kilidine
         * bakilir; bizim gemimizin kilidi (hedefimiz) sayilmaz (kacarken kendi kilidimiz zaten birakilir).
         */
        private boolean locksUs(Player p) {
            try {
                return java.util.Objects.equals(p.getTarget(), context.hero);
            } catch (RuntimeException e) {
                return false;
            }
        }

        /** Bizim gemimiz hala bu dusmana kilitli mi (birakildi gorunse de takili kalabilir). */
        private boolean ourLockOn(Player p) {
            try {
                return java.util.Objects.equals(context.hero.getTarget(), p) || java.util.Objects.equals(context.attack.getTarget(), p);
            } catch (RuntimeException e) {
                return false;
            }
        }

        /**
         * Kilit karisiklik kontrolu: once bizim kilidimiz takiliysa birakilir; EMP ancak bizim kilidimiz temizken
         * dusmanin kilidi en az LOCK_CONFIRM_MS kesintisiz bizde gorulurse onaylanir.
         */
        private boolean lockConfirmed(Player p, long now) {
            if (ourLockOn(p)) {
                context.attack.stopAttack();
                context.attack.setTarget(null);
                try {
                    context.hero.setLocalTarget(null);
                } catch (RuntimeException ignored) {
                }
                Log.every("escownlock", 5000, "DUSMAN", "kendi kilidimiz " + threatName + " uzerinde takili kalmis, birakildi (EMP kontrolu)");
                enemyLockSince = 0;
                return false;
            }
            if (!locksUs(p)) {
                enemyLockSince = 0;
                return false;
            }
            if (enemyLockSince == 0) enemyLockSince = now;
            return now - enemyLockSince >= LOCK_CONFIRM_MS;
        }

        /** Kacarken gidilen yol (harita atlamasi / isinlanma sayilmaz). */
        private void track() {
            Location h = Location.of(context.hero.getX(), context.hero.getY());
            if (lastPos != null) {
                double step = lastPos.distanceTo(h);
                if (step < 1000) traveled += step;
            }
            lastPos = h;
        }

        /** EMP: dusmanin kilidi kirilir (bekleme suresindeyse saniyede bir yeniden denenir). */
        private void emp(Player p, double d, long now) {
            if (now - empTryAt < 1000) return;
            empTryAt = now;
            // Kendi kilidimiz EMP ile karismasin: basmadan once hedef birakilir (outrun zaten birakiyor).
            context.attack.stopAttack();
            if (context.attack.hasTarget()) context.attack.setTarget(null);
            try {
                eu.darkbot.api.game.items.ItemUseResult r =
                        context.items.useItem(eu.darkbot.api.game.items.SelectableItem.Special.EMP_01, 1000);
                if (r == eu.darkbot.api.game.items.ItemUseResult.SUCCESS) {
                    empUsed = true;
                    empAt = now;
                    out = null; // yeni kacis noktasi: dusmanin tam tersi
                    Stats.count("emp");
                    Log.i("DUSMAN", "EMP basildi: " + threatName + " " + (int) d + " uzakta, kilidi hala bizdeydi ("
                            + (int) traveled + " yol kacildi), kilit kirildi, tersine kacmaya devam");
                } else {
                    Log.every("escemp", 10_000, "DUSMAN", "EMP basilamadi (" + r + "), kacmaya devam");
                }
            } catch (RuntimeException e) {
                Log.every("escemp", 10_000, "DUSMAN", "EMP basilamadi (" + e + ")");
            }
        }

        /** Haritada dusmanin tersine uzaklasma (kacis konfigi, hiz yetenegi). */
        private void outrun(Player p, long now) {
            context.attack.stopAttack();
            if (context.attack.hasTarget()) context.attack.setTarget(null);
            context.hero.setRunMode();
            Location from = p != null ? Location.of(p.getX(), p.getY()) : null;
            boolean bad = out != null && from != null && out.distanceTo(from) < context.hero.distanceTo(from);
            if (out == null || context.hero.distanceTo(out) < 200 || now - outAt > REPICK_MS || bad) {
                Location best = null;
                for (int i = 0; i < 3 && best == null; i++) {
                    Location c = context.safePoint(STEP, from);
                    if (c != null && context.move.canMove(c)) best = c;
                }
                if (best == null && from != null) {
                    // Gidilebilir guvenli nokta bulunamadi (kose / engel): en azindan dusmanin tersine.
                    double a = Math.atan2(context.hero.getY() - from.getY(), context.hero.getX() - from.getX());
                    best = Location.of(context.hero.getX() + Math.cos(a) * STEP, context.hero.getY() + Math.sin(a) * STEP);
                }
                if (best != null) {
                    out = best;
                    outAt = now;
                    Log.every("escout", 3000, "DUSMAN", "uzaklasiliyor " + Log.loc(out) + (p == null ? ""
                            : " | " + threatName + " " + (int) p.distanceTo(context.hero) + " uzakta, hiz " + p.getSpeed()
                            + " / biz " + context.hero.getSpeed()));
                }
            }
            if (out != null) {
                context.speedAbility(context.hero.distanceTo(out) + 3000, "dusmandan kacis");
                context.moveTo(out);
            }
        }

        @Override
        public void end(String why) {
            done = true;
            // Dusmanin bize ates ettigi son yer ve o harita (portaldan atladiktan sonra da eski haritaya yazilir).
            Location at = context.attackerSeenAt(threatId);
            int map = context.attackerMapOf(threatId);
            if (at != null) context.dangerZone(at, ZONE_R, ZONE_MS, "dusman " + threatName, map);
        }
    }
}