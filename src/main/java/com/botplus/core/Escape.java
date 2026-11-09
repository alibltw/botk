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
 * Is bitince sabit bolge yasaklanmaz: saldiran takip edilir (Context.trackedZone; gorunurken uzak durulur,
 * yaklasirsa ates etmeden once kacilir, gorunmezse son yeri 60 sn yasak).
 */
public final class Escape {

    /** Hizla Atlat'ta yeni kacis noktasi (adim). */
    private static final int STEP = 2000;
    /** Kacis noktasi en az bu kadar surede bir yenilenir. */
    private static final long REPICK_MS = 2500;
    /** Tam Tersine Kac: yon bu sikla guncellenir. */
    private static final long REVERSE_REPICK_MS = 500;
    /** Tam ters: denenecek sapmalar (derece). */
    private static final double[] FRONT_DEG = {0, 8, -8, 15, -15};
    /** Alt / ust: denenecek yan acilar (derece); 90'dan fazlasi dusman tarafi. */
    private static final double[] SIDE_DEG = {45, 60, 75, 90};
    /** Secilen yan bu kadar sure korunur. */
    private static final long SIDE_LOCK_MS = 2000;
    /** Kenar Boyunca Kay: kacis noktasi kenara bundan yakin olamaz. */
    private static final double EDGE_MIN = 1000;
    /** Kesme Onleme: gemi noktaya dusmandan en az bu oranda once varmali. */
    private static final double ANTI_CUT_MARGIN = 1.15;
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
    /** Onceden Kac: takip edilen saldiran ates etmeden bu kadar yaklasir (ve yaklasmaya devam ederse) kacilir. */
    private static final double PRE_DIST = 1500;
    /** Onceden Kac: bundan yakinsa yaklasma beklenmez. */
    private static final double PRE_CLOSE = 800;
    /** Kacis yonu: dusman bundan uzak ve ates etmiyorsa yon yeniden hesaplanmaz. */
    private static final double RELOCK_DIST = 2500;
    /** Kacis yonu: dusman bu kadar dereceden fazla yer degistirirse (ve ates ediyorsa) yon yenilenir. */
    private static final double RELOCK_DEG = 60;
    /** Yan secimi: secilen yanin noktasi kenara bundan yakinsa obur yana gecilir. */
    private static final double SIDE_EDGE_SWITCH = 1500;
    /** Yon secimi: denenecek sapmalar (derece; 90'dan fazlasi dusman tarafi). */
    private static final double[] CAND_DEG = {0, 8, 15, 25, 35, 45, 60, 75, 90};
    /** Yonde kenara kadar yol en fazla bu kadar olculur; yol kenara bundan yakin bitmis sayilir. */
    private static final int RUNWAY_MAX = 6000;
    private static final double RUNWAY_EDGE = 600;
    /** Bu kadar yolu olmayan yon (kose / kenar) secilmez. */
    private static final double MIN_RUNWAY = 2500;
    /** EMP Donusu: sert donus noktasi bu kadar korunur. */
    private static final long EMP_TURN_MS = 2500;
    /** Sikisinca Yandan Gec: dusman en az bu uzaklikta olmali, yol dusmana bundan yaklasmamali, gecis bu kadar korunur. */
    private static final double SIDE_PASS_MIN_DIST = 900;
    private static final double SIDE_PASS_GAP = 1100;
    private static final long SIDE_PASS_MS = 3500;
    /** Dusman peşimizden geliyorsa (son bu kadar surede yaklastiysa) atlatma bitmez. */
    private static final long CHASE_HOLD_MS = 3000;
    /** Yaklasinca Hiz Yetenegi: dusman bu kadar yaklasip hala yaklasiyorsa basilir. */
    private static final double LATE_SPEED_DIST = 600;
    /** Kacarken Portaldan Atla: portal bu yakinda, kacis yonune en fazla bu aci; bu kadar suredir hasar yoksa. */
    private static final double HOP_DIST = 2500;
    private static final double HOP_DEG = 30;
    private static final long HOP_CALM_MS = 3000;
    /** Kacarken Portaldan Atla: portala gidip atlamak icin en fazla bu kadar sure (ates olmasa da kacis surer). */
    private static final long HOP_HOLD_MS = 20_000;
    private static final double HOP_PASS = 400;
    private static final int HOP_TRIES = 3;
    /** Kacis noktasi diger takip edilen saldiranlara bundan yakin olamaz. */
    private static final double OTHER_ATTACKER_R = 2000;
    /** Dusman hizi bizim hizimizin bu oranina ulasiyorsa hizli sayilir (2.8.0: %90 -> %95). */
    private static final double FASTER_RATIO = 0.95;
    /** 2.8.0 Kacarken EMP (portala varmadan): portala bu kadar kala basilir. */
    private static final double EMP_PORTAL_DIST = 800;
    /** 2.8.0 Kacarken Portaldan Atla: dusman bu yakinda (ya da ates ediyorsa) atlanir; peşimizi biraktiysa atlanmaz. */
    private static final double HOP_CHASE_DIST = 1500;
    /** 2.8.0 Onceden Kac: dusuk can + yakin dusman, katil, kalabalik mesafeleri. */
    private static final double PRE_LOW_DIST = 2000;
    private static final double PRE_LOW_HP = 0.6;
    private static final double PRE_KILLER_DIST = 3000;
    private static final double PRE_MULTI_DIST = 2000;
    /** Hizli Can Kaybi: olcum penceresi. */
    private static final long RAPID_WINDOW_MS = 2000;
    /** Kacisi Bitirme Sarti: can bunun altinda ve dusman END_HOLD_DIST icindeyse kacis bitmez (en fazla END_HOLD_MAX_MS). */
    private static final double END_HOLD_HP = 0.5;
    private static final double END_HOLD_DIST = 3000;
    private static final long END_HOLD_MAX_MS = 60_000;
    /** Arkaya Mayin: dusman bu yakinda ve yaklasiyorsa; iki mayin arasi. */
    private static final double MINE_DIST = 2000;
    private static final long MINE_GAP_MS = 10_000;
    /** Kose Korumasi: harita ortasina yonelen yona puan, koseye dayanan yona ceza. */
    private static final double CENTER_BONUS = 700;
    private static final double CORNER_PENALTY = 900;

    /** Gemi cani gecmisi (zaman, can): Hizli Can Kaybi. */
    private final java.util.ArrayDeque<double[]> hpHist = new java.util.ArrayDeque<>();

    /** Her tik: gemi cani orneklenir (BotPlus tiki cagirir). */
    public void sampleHp() {
        long now = Context.now();
        double hp;
        try {
            if (context.repair.isDestroyed()) {
                hpHist.clear();
                return;
            }
            hp = context.heroHp();
        } catch (RuntimeException e) {
            return;
        }
        if (hpHist.isEmpty() || now - (long) hpHist.peekLast()[0] >= 150) hpHist.addLast(new double[]{now, hp});
        while (!hpHist.isEmpty() && now - (long) hpHist.peekFirst()[0] > RAPID_WINDOW_MS) hpHist.pollFirst();
        learnBurst(now, hp);
    }

    // ------------------------------------------------------------ 2.6.0 Akilli Kacis

    /** Saldirgan sayilma suresi: EMP dusmanin kilidini kirinca "ates ediyor" gorunmez, son 3 sn'de ates eden sayilir. */
    private static final long SMART_ATTACKER_MS = 3000;
    /** Akilli Kacis: 1 sn'de bu kadar can dususu. */
    private static final double SMART_DROP_1S = 0.15;
    /** Akilli Kacis: olume kalan sure bunun altindaysa kacilir. */
    private static final double SMART_TTD_SEC = 4.0;
    /** Bilinmeyen oyuncunun 1 sn'lik en buyuk hasari (can orani) varsayilani. */
    private static final double SMART_DEFAULT_BURST = 0.35;
    /** Oyuncu adi -> bize 1 sn'de verdigi en buyuk can kaybi (oran). */
    private final java.util.Map<String, Double> burst = new java.util.HashMap<>();
    private String smartWhy;

    /** Son ms icindeki can dususu (0..1). */
    private double dropWithin(long ms) {
        long now = Context.now();
        double max = -1;
        for (double[] h : hpHist) if (now - (long) h[0] <= ms) max = Math.max(max, h[1]);
        return max < 0 ? 0 : Math.max(0, max - context.heroHp());
    }

    /** Bize ates eden oyuncu: su an ates eden ya da son 3 sn icinde ates etmis olan (EMP sonrasi da sayilir). */
    private Player smartShooter() {
        Player p = playerShooter();
        if (p != null) return p;
        Player r = context.recentAttacker(SMART_ATTACKER_MS);
        return r != null && !context.petPlayer(r) && !context.isGroupMember(r) ? r : null;
    }

    /** Oyuncu ateş ederken 1 sn'lik en buyuk can kaybi ogrenilir (oyuncu basina). */
    private void learnBurst(long now, double hp) {
        try {
            if (!context.config.ON_ENEMY.PLUS.SMART) return;
            Player s = smartShooter();
            if (s == null) return;
            double d = dropWithin(1000);
            if (d <= 0.02) return;
            String n = Log.name(s);
            Double old = burst.get(n);
            if (old == null || d > old) {
                burst.put(n, d);
                if (old == null || d > old + 0.05)
                    Log.d("DUSMAN", n + " 1 sn'de en fazla " + Log.pct(d) + " can kaybettirdi (Akilli Kacis ogrendi)");
            }
            if (burst.size() > 100) burst.clear();
        } catch (RuntimeException ignored) {
        }
    }

    /**
     * Akilli Kacis: guclu dusman. Can 1 sn'de %15 dustu, olume kalan sure 4 sn alti ya da can o oyuncunun bilinen en
     * buyuk 1 sn'lik hasarinin 1,2 katinin altinda. Nedeni smartWhy'a yazilir.
     */
    private boolean smartDanger() {
        smartWhy = null;
        try {
            if (!context.config.ON_ENEMY.PLUS.SMART) return false;
            Player s = smartShooter();
            if (s == null || context.ownSilent(s)) return false;
            double hp = context.heroHp();
            double d1 = dropWithin(1000);
            if (d1 >= SMART_DROP_1S) {
                smartWhy = "can 1 sn'de " + Log.pct(d1) + " dustu";
                return true;
            }
            double d15 = dropWithin(1500);
            if (d15 > 0.03) {
                double ttd = hp / (d15 / 1.5);
                if (ttd < SMART_TTD_SEC) {
                    smartWhy = "bu hizla " + String.format(java.util.Locale.ROOT, "%.1f", ttd) + " sn'de olunur";
                    return true;
                }
            }
            Double b = burst.get(Log.name(s));
            double limit = 1.2 * (b == null ? SMART_DEFAULT_BURST : Math.max(b, 0.25)); // en az %30
            if (hp < limit) {
                smartWhy = "can " + Log.pct(hp) + " < " + Log.name(s) + " icin sinir " + Log.pct(limit)
                        + (b == null ? " (varsayilan)" : " (1 sn'de en fazla " + Log.pct(b) + ")");
                return true;
            }
        } catch (RuntimeException ignored) {
        }
        return false;
    }

    /** Son 2 sn'deki can dususu (0..1). */
    private double hpDrop() {
        double max = -1;
        for (double[] h : hpHist) max = Math.max(max, h[1]);
        return max < 0 ? 0 : Math.max(0, max - context.heroHp());
    }

    /**
     * Hizli Can Kaybi: oyuncu ateşiyle can 2 sn'de ayardaki yuzdeden fazla dustu mu (her modda; NPC hasari sayilmaz:
     * bize ates eden bir oyuncu olmali). Kendi sirketi Yok Say'da da gecerli (12:22 CEPNI: can 5 sn'de %92 -> %47).
     */
    public boolean rapidLoss() {
        // 2.7.0: sadece bize ates eden bir oyuncu varken (kendi sirketi dahil; grup uyesi ve PET haric), 2 sn'de
        // %50 (sabit). NPC de vuruyorsa esik artirilmaz; Akilli Kacis kaldirildi.
        smartWhy = null;
        try {
            int pct = context.config.ON_ENEMY.PLUS.RAPID_LOSS;
            if (pct <= 0 || playerShooter() == null) return false;
            return hpDrop() >= pct / 100.0;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Son 2 sn icinde bize ates eden, kendi sirketi oldugu bilinen en yakin oyuncu (Karsilik Ver icin). */
    private Player ownShooter() {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : context.ents.getPlayers()) {
            if (p.getId() == context.hero.getId() || !p.isValid() || context.petPlayer(p) || context.isGroupMember(p)) continue;
            if (!Boolean.TRUE.equals(context.firmKnown(p))) continue;
            if (!p.isAttacking(context.hero) && !context.attackedBy(p, 2000)) continue;
            double d = p.distanceTo(context.hero);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    /** Bize su an ates eden en yakin oyuncu (PET ve grup uyeleri haric; Yok Say'daki kendi sirketi dahil). */
    private Player playerShooter() {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : context.ents.getPlayers()) {
            if (p.getId() == context.hero.getId() || !p.isValid() || context.petPlayer(p) || context.isGroupMember(p)) continue;
            if (!p.isAttacking(context.hero)) continue;
            double d = p.distanceTo(context.hero);
            if (d < bd) {
                bd = d;
                best = p;
            }
        }
        return best;
    }

    private final Context context;
    private Job running;
    /** PvP alt eklentisinin mantigi (Ne Yapilsin = Saldir). */
    private final com.botplus.pvp.PvpLogic pvp;
    /** Sadece Bulastir / kacarken roket: dusmana kilitli kalinan sure ve en fazla uzaklik. */
    private static final long STRIKE_MS = 1500;
    private static final double STRIKE_DIST = 800;

    public Escape(Context context) {
        this.context = context;
        context.escapeWanted = () -> running != null && !running.done && running.portal && (running.wantNow() || running.holdEnd());
        context.rapidLossNow = this::rapidLoss;
        // Takip Edilirse Atla: kacilan dusman pesimizde mi (son 3 sn ates etti ya da 1800 icinde ve yaklasiyor).
        context.threatFollowing = () -> {
            Job r = running;
            return r == null || r.done || r.following();
        };
        // 2.8.0: Takip Edilirse Atla portalda 15 sn beklediyse (tehdit yok) kacis biter.
        context.portalWaitGiveUp = () -> {
            Job r = running;
            if (r != null && !r.done) r.waitGiveUp = true;
        };
        this.pvp = new com.botplus.pvp.PvpLogic(context);
        context.strikeLaser = () -> running != null && !running.done ? running.strikeLaser() : null;
        // Yaklasinca Hiz Yetenegi: Hizla Atlat surerken hiz turundeki Gemi Yetenegi kacis basana kadar tutulur.
        context.holdSpeed = () -> {
            Job r = running;
            return r != null && !r.done && !r.portal && !r.speedUsed
                    && context.config.ON_ENEMY.OUTRUN_OPTS.contains(BotPlusConfig.OutrunOpt.LATE_SPEED);
        };
    }

    /** Bize su an ates eden en yakin dusman oyuncu (PET'ler haric). */
    private Player attacker() {
        Player best = null;
        double bd = Double.MAX_VALUE;
        for (Player p : context.ents.getPlayers()) {
            if (!context.isEnemyPlayer(p) || !p.isAttacking(context.hero) || context.ignoredOwn(p)) continue;
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
            // Hiz esitligi: dusman bizim hizimizin %90'ina ulasiyorsa "hizli" (eskiden %97: 631 / 633 hizli sayilmadi).
            return p.getSpeed() >= context.hero.getSpeed() * FASTER_RATIO;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean combatMap() {
        return context.safety instanceof PlusSafety && ((PlusSafety) context.safety).combatMap();
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
        // Hizli Can Kaybi: mod ne olursa olsun (Devam Et / Saldir / Yok Say dahil) saldirmadan portala kacilir.
        if (rapidLoss()) {
            Player s = playerShooter();
            if (s == null && smartWhy != null) s = smartShooter();
            if (s != null) {
                // 2.7.0 Hizli Can Kaybinda: Porta Kac ya da Hizinla Atlat (saldirandan uzaga). Calisma haritasi disinda /
                // zaten portala kacilirken portal kacisi surer.
                boolean toPortal = (oe.RAPID_MODE != BotPlusConfig.RapidMode.OUTRUN && !combatMap())
                        || context.safetyEscaping() || !context.onWorkingMap();
                String why = "can 2 sn'de " + Log.pct(hpDrop()) + " dustu (Hizli Can Kaybi %" + oe.PLUS.RAPID_LOSS + ")";
                if (pvp.busy()) pvp.abort("hizli can kaybi");
                Stats.count("hizli can kaybi");
                Log.i("DUSMAN", Log.ship(s) + " ates ediyor: " + why + ", saldirmadan "
                        + (toPortal ? "portala kaciliyor" : "hizinla atlatiliyor") + " | gemi can " + Log.pct(context.heroHp())
                        + " | " + context.firmInfo(s));
                running = new Job(s, toPortal, false);
                running.rapid = true;
                context.releaseAttack();
                if (!toPortal) running.outrun(s, Context.now());
                return running;
            }
        }
        if (pvp.busy()) return null;
        // Kendi Sirketi = Karsilik Ver (2.6.0): bize ates eden kendi sirketinden oyuncuya ates ettigi surece karsilik
        // verilir (kovalanmaz). Can Kacis Cani'nin altindaysa karsilik verilmez, asagida portala kacilir.
        if (oe.OWN_MODE == BotPlusConfig.OwnMode.COUNTER && !hpLow()) {
            Player own = ownShooter();
            if (own != null) {
                PlusModule.Job j = pvp.counter(own);
                if (j != null) return j;
            }
        }
        if (oe.MODE == BotPlusConfig.OnEnemyMode.ATTACK && oe.PVP_ON) {
            PlusModule.Job j = pvp.check(oe.PVP);
            if (j != null) return j;
        }
        Player t = attacker();
        boolean pre = false;
        if (t == null) {
            // Kendi Sirketi = Yok Say: kendi sirketinden saldirandan kacilmaz; ama can Kacis Cani'nin altina indiyse
            // (olmek uzere) yine portala kacilir (12:22 CEPNI: Yok Say iken hic kacilmadan patlandi).
            Player own = playerShooter();
            if (own != null && context.ignoredOwn(own) && hpLow()) {
                // 2.8.0: portal sabit degil; secili kacis modu (Hizinla Atlat seciliyse haritada atlatilir).
                boolean toPortal = oe.MODE != BotPlusConfig.OnEnemyMode.OUTRUN || context.safetyEscaping() || !context.onWorkingMap();
                Log.i("DUSMAN", Log.ship(own) + " (kendi sirketi, " + oe.OWN_MODE + ") ates ediyor ve can " + Log.pct(context.heroHp())
                        + " <= %" + oe.HP + ": " + (toPortal ? "portala kaciliyor" : "hizinla atlatiliyor") + " | " + context.firmInfo(own));
                running = new Job(own, toPortal, false);
                context.releaseAttack();
                if (!toPortal) running.outrun(own, Context.now());
                return running;
            }
            t = approaching(oe);
            if (t == null) return null;
            pre = true;
        }
        boolean low = hpLow();
        // Push Korumasi: bu oyuncudan olme siniri asildiysa onunla hic savasilmaz, portala kacilir.
        boolean feared = context.push.feared(Log.name(t));
        // Kendi Sirketi Saldirinca = Sadece Kac: kendi sirketinden saldiran icin Devam Et / Saldir yerine Hizla Atlat.
        boolean ownFlee = oe.OWN_MODE == BotPlusConfig.OwnMode.FLEE && context.ownFirm(t);
        BotPlusConfig.OnEnemyMode mode = ownFlee && oe.MODE != BotPlusConfig.OnEnemyMode.ESCAPE
                ? BotPlusConfig.OnEnemyMode.OUTRUN : oe.MODE;
        if (mode == BotPlusConfig.OnEnemyMode.CONTINUE && !low && !feared && !pre) {
            Log.every("onenemycont", 15_000, "DUSMAN", Log.ship(t) + " ates ediyor, Dusman Saldirinca: Devam Et (can "
                    + Log.pct(context.heroHp()) + (oe.HP > 0 ? " >= %" + oe.HP : "") + ")");
            return null;
        }
        // Tamir / yenileme icin portala kacilirken ya da calisma haritasi disindayken haritada atlatilmaz: portal
        // kacisi dusman kacisi olarak surer (yoksa portaldan uzaklasiliyor ya da kacis kayboluyordu).
        boolean already = context.safetyEscaping() || !context.onWorkingMap();
        // Saldir secili ama saldirilmadi (kalabalik, can, push, sartli saldiri): portala kacilir.
        // Onceden Kac (henuz ates etmedi): Saldir seciliyken de portala degil haritada uzaklasilir.
        // 2.8.0: Onceden Kac her zaman haritada atlatmadir (portal / push / Hizliysa Portala yok). Dusuk can Hizinla
        // Atlat seciliyken portala zorlamaz (secili mod uygulanir).
        boolean lowPortal = low && mode != BotPlusConfig.OnEnemyMode.OUTRUN;
        // 2.8.0 Radar: onceden kacista portal kararini radar verir (Dusmandan Kac: secili mod, Trenden Kac: portal).
        boolean portal = already || (pre ? prePortal : (mode == BotPlusConfig.OnEnemyMode.ESCAPE || mode == BotPlusConfig.OnEnemyMode.ATTACK
                || lowPortal || feared));
        String why = already ? "zaten portala kaciliyor / calisma haritasi disinda"
                : pre ? "radar " + preWhy
                : feared ? "push korumasi: bu oyuncudan olme siniri" : lowPortal ? "can " + Log.pct(context.heroHp()) + " <= %" + oe.HP
                : mode == BotPlusConfig.OnEnemyMode.ESCAPE ? "Kac secili"
                : mode == BotPlusConfig.OnEnemyMode.ATTACK ? (oe.PVP_ON ? "Saldir secili ama saldirilmiyor" : "Saldir secili ama PvP Plus alt eklentisi kapali")
                : ownFlee ? "kendi sirketi: Sadece Kac"
                : "Hizla Atlat secili";
        if (!portal && !pre && oe.FASTER_PORTAL && faster(t)) {
            portal = true;
            why = "dusman hizi bizimkine yakin (" + t.getSpeed() + " / " + context.hero.getSpeed() + ", %95 ve ustu)";
        }
        // 2.7.0: savasta portaldan atlanamayan haritada portalda beklenmez, hizinla atlatilir (push korumasi haric).
        if (portal && !already && !feared && !pre && combatMap()) {
            portal = false;
            why += " (bu haritada savasta atlanamiyor: hizinla atlatiliyor)";
        }
        Stats.count(pre ? "onceden kacis" : "dusmandan kacis");
        Log.i("DUSMAN", Log.ship(t) + (pre ? " yaklasiyor, ates etmeden kaciliyor (" : " ates ediyor (")
                + (int) t.distanceTo(context.hero) + "): "
                + (portal ? "portala kaciliyor" : "hizla uzaklasiliyor") + " | " + why + " | gemi can " + Log.pct(context.heroHp())
                + " | " + context.firmInfo(t));
        running = new Job(t, portal, pre);
        // Aninda Donus: hedef hemen birakilir; Hizla Atlat'ta ilk komut ayni tikte kacis yonune verilir.
        if (oe.START_OPTS.contains(BotPlusConfig.StartOpt.INSTANT_TURN)) {
            context.releaseAttack();
            if (!portal) running.outrun(t, Context.now());
        }
        return running;
    }

    /** Onceden Kac: oyuncu id -> {son uzaklik, olcum zamani}. */
    private final java.util.Map<Integer, double[]> approach = new java.util.HashMap<>();
    /** Son onceden kacis nedeni (log). */
    private String preWhy = "";
    /** 2.8.0 Radar: onceden kacis portala mi (Dusmandan Kac: secili kacis modu; Trenden Kac: portal). */
    private boolean prePortal;

    /**
     * 2.8.0 Dusman Radari (dusman ates etmeden kacis; Ne Yapilsin'dan bagimsiz), secilen kosullardan biri tutarsa o
     * oyuncu, yoksa null:
     * - Trenden Kac: tren kacma mesafesi icinde ve bize dogru geliyor (portala).
     * - Dusmandan Kac: dusman (dusman sirket / daha once saldiran / hatirlanan) kacma mesafesi icinde ve yaklasiyor
     *   (mesafenin yarisi icindeyse her zaman). Hizinla Atlat seciliyse haritada atlatma, degilse portal.
     * - HP Dusukken Kac: can "HP Dusukken Kac Cani"nin altinda ve 2000 icinde dusman (haritada atlatma).
     * - Hatirlanan Dusmandan Kac: hatirlanan olduren / push sinirindaki oyuncu 3000 icinde (haritada atlatma).
     * - Kalabaliktan Kac: 2000 icinde 2+ dusman (haritada atlatma).
     */
    private Player approaching(BotPlusConfig.OnEnemy oe) {
        java.util.Set<BotPlusConfig.RadarOpt> ro = oe.RADAR;
        if (ro == null || ro.isEmpty()) return null;
        boolean eFlee = ro.contains(BotPlusConfig.RadarOpt.ENEMY_FLEE), lowFlee = ro.contains(BotPlusConfig.RadarOpt.LOW_HP_FLEE),
                remFlee = ro.contains(BotPlusConfig.RadarOpt.REMEMBERED_FLEE), crowdFlee = ro.contains(BotPlusConfig.RadarOpt.CROWD_FLEE);
        // Tren once (portala).
        Radar.Train tr = context.radar.approachingTrain();
        if (tr != null) {
            Player n = tr.nearest(context.hero);
            if (n != null) {
                prePortal = true;
                preWhy = "trenden kac: " + tr.info() + " " + (int) tr.distanceTo(context.hero) + " uzakta ve yaklasiyor";
                return n;
            }
        }
        if (!eFlee && !lowFlee && !remFlee && !crowdFlee) return null;
        long now = Context.now();
        double hp = context.heroHp();
        double flee = oe.RADAR_ENEMY_FLEE;
        Player best = null, nearestMulti = null;
        String bestWhy = null;
        boolean bestPortal = false;
        double bd = Double.MAX_VALUE, md = Double.MAX_VALUE;
        int multi = 0;
        for (Player p : context.ents.getPlayers()) {
            if (!p.isValid() || p.getId() == context.hero.getId() || context.petPlayer(p) || context.isGroupMember(p)) continue;
            boolean killer = (context.isKiller(p) && !context.attackStays(Log.name(p))) || context.push.feared(Log.name(p));
            if (context.ignoredOwn(p) && !killer) continue;
            boolean hostile = context.tracked(p) || killer || (context.isEnemyPlayer(p) && Boolean.FALSE.equals(context.firmKnown(p)));
            if (!hostile) continue;
            double d = p.distanceTo(context.hero);
            double[] prev = approach.get(p.getId());
            boolean closing;
            if (prev == null || now - (long) prev[1] >= 400) {
                closing = prev != null && now - (long) prev[1] < 2000 && d < prev[0] - 20;
                approach.put(p.getId(), new double[]{d, now, closing ? 1 : 0});
            } else closing = prev[2] > 0;
            String why = null;
            boolean portal = false;
            if (eFlee && d <= flee && (closing || d <= flee * 0.5)) {
                why = "dusmandan kac: " + (int) d + " uzakta ve yaklasiyor";
                portal = oe.MODE != BotPlusConfig.OnEnemyMode.OUTRUN;
            } else if (lowFlee && d <= PRE_LOW_DIST && hp < oe.RADAR_LOW_HP / 100.0)
                why = "HP dusukken kac: " + (int) d + " uzakta, gemi cani " + Log.pct(hp) + " < %" + oe.RADAR_LOW_HP;
            else if (remFlee && killer && d <= PRE_KILLER_DIST)
                why = "hatirlanan dusmandan kac: " + (int) d + " uzakta";
            if (d <= PRE_MULTI_DIST) {
                multi++;
                if (d < md) {
                    md = d;
                    nearestMulti = p;
                }
            }
            if (why != null && d < bd) {
                bd = d;
                best = p;
                bestWhy = why;
                bestPortal = portal;
            }
        }
        if (approach.size() > 50) approach.clear();
        if (best == null && crowdFlee && multi >= 2) {
            best = nearestMulti;
            bestWhy = "kalabaliktan kac: " + (int) PRE_MULTI_DIST + " icinde " + multi + " dusman";
            bestPortal = false;
        }
        if (best != null) {
            preWhy = bestWhy;
            prePortal = bestPortal;
        }
        return best;
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
        /** Tam Tersine Kac: secilen yan (alt / ust) ve kilit suresi. */
        private int side;
        private long sideUntil;
        /** Sadece Bulastir / kacarken roket atisi. */
        private boolean strikeDone;
        private long strikeUntil;
        /** Onceden Kac: dusman henuz ates etmedi (ates etmeden yaklasti). */
        private final boolean pre;
        /** Kacis yonu kilidi (radyan, gemiden dusmana bakan yon; Location.of tersine koyar). */
        private Double lockAway;
        private Location lastFrom;
        /** EMP Donusu yapildi; EMP Donusu / Yandan Gec noktasi bu zamana kadar korunur. */
        private boolean empTurnDone;
        private long holdUntil;
        /** Peşimizden gelme takibi: dusmanin son yaklastigi an. */
        private long approachAt, approachSampleAt;
        private double approachLast = -1;
        /** Yaklasinca Hiz Yetenegi basildi mi / son deneme. */
        volatile boolean speedUsed;
        private long speedTryAt;
        /** Kacarken Portaldan Atla: son kontrol; secilen portala gidilirken kacis bu zamana kadar surer. */
        private long hopCheckAt, hopUntil;
        private boolean lastFallback;
        /** Hizli can kaybiyla baslayan kacis. */
        boolean rapid;
        /** 2.8.0: portalda 15 sn tehdit gelmedi, kacis biter. */
        volatile boolean waitGiveUp;
        /** 2.8.0 Kacarken EMP: bu kacista ilk hasar goruldu mu. */
        private boolean firstHitSeen;

        boolean striking() {
            return strikeUntil > 0 && Context.now() < strikeUntil;
        }

        /** Bulastirma atisinda cephane secicisine PIB-100 (yoksa DarkBot kilitlerken normal cephaneye dondurur). */
        eu.darkbot.api.game.items.SelectableItem.Laser strikeLaser() {
            BotPlusConfig.Protect pc = context.config.PROTECT;
            return striking() && pc.ENABLED && pc.INFECT ? eu.darkbot.api.game.items.SelectableItem.Laser.PIB_100 : null;
        }

        /**
         * Sadece Bulastir (Koruma): kacisin basinda dusman yakinsa ona kisa sure kilitlenilir, PIB-100 (zehir) ile bir
         * seri atilir (Dusmana Karsi'da roket seciliyse roket de), sonra kilit birakilir, eski cephaneye donulur.
         */
        private void strike(long now) {
            BotPlusConfig.Protect pc = context.config.PROTECT;
            boolean infect = pc.ENABLED && pc.INFECT;
            boolean rockets = pc.ENABLED && pc.COUNTER != null && (pc.COUNTER.contains(BotPlusConfig.Counter.DCR_250)
                    || pc.COUNTER.contains(BotPlusConfig.Counter.R_IC3) || pc.COUNTER.contains(BotPlusConfig.Counter.PLD_8));
            if (strikeUntil > 0) {
                Player p = refresh();
                // Kimlere: kendi sirketi 5 sn ates etmezse atis hemen kesilir (bayragi dusunce seref kaybi).
                if (now < strikeUntil && p != null && context.mayFire(p, pc.FIRMS)) {
                    if (!java.util.Objects.equals(context.attack.getTarget(), p)) context.attack.setTarget(p);
                    context.attack.tryLockAndAttack();
                    return;
                }
                strikeUntil = 0;
                // Kilit Birakma: ates CTRL ile kesilir, kilit yakindaki NPC'ye tasinir. Eski cephaneye DONULMEZ:
                // kilit oyuncudayken slot cubugundan cephane secmek saldiriyi yeniden baslatiyordu (x4 oyuncuya).
                context.unlock.request("bulastirma bitti: " + threatName);
                Log.i("DUSMAN", "bulastirma atisi bitti" + (p != null && !context.mayFire(p, pc.FIRMS)
                        ? " (kendi sirketi " + Context.OWN_FIRM_FIRE_MS / 1000 + " sn ates etmedi)" : "")
                        + ", ates kesiliyor ve kilit birakiliyor, kacisa devam");
                return;
            }
            if (strikeDone || !(infect || rockets) || pre) return;
            Player p = refresh();
            if (p == null || p.distanceTo(context.hero) > STRIKE_DIST) return;
            if (!context.mayFire(p, pc.FIRMS)) {
                strikeDone = true;
                Log.i("DUSMAN", "bulastirma / roket yok: " + threatName + " " + context.firmInfo(p)
                        + ", Kime Karsi'da secili degil" + (context.ownFirm(p) && pc.FIRMS != null
                        && pc.FIRMS.contains(BotPlusConfig.Firm.OWN_FIRM) ? " ya da " + Context.OWN_FIRM_FIRE_MS / 1000 + " sn ates etmedi" : ""));
                return;
            }
            strikeDone = true;
            strikeUntil = now + STRIKE_MS;
            // PIB-100 dogrudan secilmez: slot cubugundan secmek o an secili olana (baska bir oyuncu olabilir) atesi
            // baslatir. Cephane secici (strikeLaser) PIB'i verir; DarkBot kilit bu oyuncuya oturunca degistirir.
            context.attack.setTarget(p);
            context.attack.tryLockAndAttack();
            Stats.count("bulastirma");
            Log.i("DUSMAN", (infect ? "PIB-100 ile bulastiriliyor" : "roket atiliyor") + ": " + threatName + " "
                    + (int) p.distanceTo(context.hero) + " uzakta, " + STRIKE_MS + " ms");
        }

        Job(Player t, boolean portal, boolean pre) {
            this.pre = pre;
            this.threatId = t.getId();
            this.threatName = Log.name(t);
            this.threat = t;
            this.portal = portal;
            Stats.count(portal ? "kacis portal" : "kacis atlatma"); // 2.8.0 VERIM
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

        /**
         * Kilit Kir: dusmanin kilidi dogrulaninca (kacis portala giderken de) hemen EMP basilir; "Atlatirken EMP Bas"
         * yolun gidilmesini bekler, bu beklemez.
         */
        @Override
        public void beforeSafety() {
            long now = Context.now();
            context.escapeActiveAt = now;
            strike(now);
            if (striking()) return;
            // 2.8.0 Kacarken EMP (Savunma -> EMP -> Ne Zaman): portal kacisinda da (job.tick calismazken) burada bakilir.
            escapeEmp(refresh(), now);
        }

        /**
         * 2.8.0 Kacarken EMP: Savunma'nin EMP listesindeki "Kacarken: ..." secenekleri. Her kacista en fazla bir kez
         * (bekleme / sira Defense'te: EMP sonra Insta-Shield). Eski "Kilit Kir" baslangic secenegi de ayni yoldan.
         */
        private void escapeEmp(Player p, long now) {
            if (empUsed || p == null || striking()) return;
            BotPlusConfig.Protect pc = context.config.PROTECT;
            if (pc == null || !pc.ENABLED || !pc.EMP_ON || pc.EMP_WHEN == null) return;
            java.util.Set<BotPlusConfig.EmpWhen> w = pc.EMP_WHEN;
            sampleApproach(p, now); // portal kacisinda tick() calismaz: yaklasma burada da olculur
            double d = p.distanceTo(context.hero);
            boolean chased = locksUs(p) || context.attackedBy(threatId, 3000);
            if (!firstHitSeen && (p.isAttacking(context.hero) || context.attackedBy(threatId, 500))) firstHitSeen = true;
            String why = null;
            boolean lockOk = (w.contains(BotPlusConfig.EmpWhen.ESC_LOCK)
                    || context.config.ON_ENEMY.START_OPTS.contains(BotPlusConfig.StartOpt.LOCK_BREAK)) && lockConfirmed(p, now);
            if (lockOk) why = "kacarken kilitlendi";
            else if (w.contains(BotPlusConfig.EmpWhen.ESC_FIRST_HIT) && firstHitSeen && chased) why = "kacarken ilk hasar";
            else if (w.contains(BotPlusConfig.EmpWhen.ESC_CATCH) && chased && d <= Math.max(100, pc.EMP_CATCH_DIST)
                    && now - approachAt < 1000) why = "dusman yakalayacak (" + (int) d + ")";
            else if (w.contains(BotPlusConfig.EmpWhen.ESC_PORTAL) && chased) {
                double pd = portal && context.safety instanceof PlusSafety ? ((PlusSafety) context.safety).enemyEscapeDist()
                        : hopP != null && hopP.isValid() ? context.hero.distanceTo(hopP) : -1;
                if (pd >= 0 && pd <= EMP_PORTAL_DIST) why = "portala " + (int) pd + " kala";
            }
            if (why != null) emp(p, d, now, why);
        }

        @Override
        public String status() {
            return portal ? "düşmandan portala kaçılıyor (" + threatName + ")" : "düşmandan hızla uzaklaşılıyor (" + threatName + ")";
        }

        /** Saldiri suruyor mu (son ESCAPE_MEMORY_MS icinde ates etti mi). */
        boolean wantNow() {
            // Gorunmese de (id ile) son ates zamani bilinir: kisa gorus kaybi kacisi bitirmez.
            // Kacarken Portaldan Atla: secilen portala gidilirken (hopUntil) ates olmasa da kacis surer.
            // 2.8.0: portalda 15 sn tehdit gelmediyse kacis biter.
            if (waitGiveUp) return false;
            return context.attackedBy(threatId, Context.ESCAPE_MEMORY_MS) || Context.now() - start < Context.ESCAPE_MEMORY_MS
                    || Context.now() < hopUntil;
        }

        /**
         * Kacisi Bitirme Sarti: can %50 altinda ve kacilan dusman 3000 icindeyse kacis bitmez (en fazla 60 sn).
         * 15:29 AVSAR: kacis can %16'da, dusman 1236 uzaktayken "ates etmiyor" diye bitti, haritada tamire baslandi.
         */
        boolean holdEnd() {
            if (!context.config.ON_ENEMY.PLUS.END_HOLD || waitGiveUp) return false;
            long now = Context.now();
            Player p = threat;
            if (p == null || !p.isValid()) return false;
            boolean hold = context.heroHp() < END_HOLD_HP && p.distanceTo(context.hero) < END_HOLD_DIST;
            if (!hold) return false;
            if (holdSince == 0) holdSince = now;
            if (now - holdSince > END_HOLD_MAX_MS) return false; // en fazla 60 sn uzatilir
            if (hold) Log.every("eschold", 5000, "DUSMAN", "kacis bitmiyor: can " + Log.pct(context.heroHp()) + " < %50 ve "
                    + threatName + " " + (int) p.distanceTo(context.hero) + " uzakta (< 3000)");
            return hold;
        }

        private long holdSince;

        /** Takip Edilirse Atla: dusman son 3 sn ates etti ya da 1800 icinde ve yaklasiyor. */
        boolean following() {
            if (context.attackedBy(threatId, 3000)) return true;
            Player p = refresh();
            if (p == null || !p.isValid()) return false;
            long now = Context.now();
            sampleApproach(p, now); // portal kacisinda tick() calismaz: yaklasma burada da olculur
            double d = p.distanceTo(context.hero);
            return d < 1800 && now - approachAt < 2000;
        }

        /** Dusman yaklasiyor mu: 500 ms'de bir uzaklik olculur (15'ten fazla azaldiysa yaklasiyor). */
        private void sampleApproach(Player p, long now) {
            if (p == null || (approachSampleAt != 0 && now - approachSampleAt < 500)) return;
            double dd = p.distanceTo(context.hero);
            if (approachLast >= 0 && dd < approachLast - 15) approachAt = now;
            approachLast = dd;
            approachSampleAt = now;
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
            // Peşimizden geliyorsa (son 3 sn icinde yaklasti ve DIST'in 1,6 katindan yakin) atlatma bitmez: kacis
            // yeniden baslayip yon sifirlanmasin (06:18'de her 2500'de durup 1 sn sonra yeniden basliyordu).
            // Sinirli: ates eden dusmanda son ates QUIET_END_MS icinde, onceden kacista en fazla OUTRUN_MAX_MS (ates
            // etmeden golge gibi takip eden oyuncu kacisi sonsuza kadar surdurmesin).
            boolean chaseOk = pre ? Context.now() - start < OUTRUN_MAX_MS : context.attackedBy(p, QUIET_END_MS);
            if (context.config.ON_ENEMY.CHASE_HOLD && chaseOk && Context.now() - approachAt < CHASE_HOLD_MS && p.distanceTo(context.hero) < context.config.ON_ENEMY.DIST * 1.6)
                return false;
            // Onceden Kac: hic ates etmediyse yeterince uzaklasinca biter.
            if (pre && !context.attackedBy(p, Math.max(QUIET_END_MS, calmMs * 3)))
                return p.distanceTo(context.hero) >= Math.max(context.config.ON_ENEMY.DIST, RELOCK_DIST);
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
            sampleApproach(p, now);
            if (portal) {
                // Buraya sadece kacis sistemi "guvende" deyince gelinir (portalda bekleme bitti ya da kacis yok).
                if (now >= hopUntil && (!wantNow() || calm()) && !holdEnd()) {
                    Log.i("DUSMAN", "kacis bitti (" + (now - start) / 1000 + " sn), " + threatName + " artik ates etmiyor");
                    afterOutrun(p);
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
            if (hopMap != 0 && mapId() != 0 && mapId() != hopMap) {
                Log.i("DUSMAN", "Kacarken Portaldan Atla: portaldan atlandi, kacis bitti (" + (now - start) / 1000 + " sn)");
                Stats.count("kacarken portaldan atlandi");
                return false;
            }
            // 2.8.0: dusuk can Hizinla Atlat'i portala cevirmez (secili mod); onceden kacis da portala donmez.
            if (hpLow() && !pre && context.config.ON_ENEMY.MODE != BotPlusConfig.OnEnemyMode.OUTRUN) {
                toPortal("can " + Log.pct(context.heroHp()) + " < %" + context.config.ON_ENEMY.HP);
                return true;
            }
            if (!combatMap() && context.config.ON_ENEMY.RAPID_MODE == BotPlusConfig.RapidMode.PORTAL && rapidLoss()) {
                toPortal("hizli can kaybi: 2 sn'de " + Log.pct(hpDrop()));
                return true;
            }
            if (p == null || calm()) {
                if (p == null && wantNow()) {
                    outrun(null, now); // gorus disina cikti ama yeni ates etti: bir sure daha uzaklas
                    return true;
                }
                if (p != null && holdEnd()) {
                    outrun(p, now); // can dusuk ve dusman yakin: atlatma bitmez
                    return true;
                }
                afterOutrun(p);
                Log.i("DUSMAN", "dusman atlatildi (" + (now - start) / 1000 + " sn)"
                        + (p == null ? ", gorus disinda" : ", " + (int) p.distanceTo(context.hero) + " uzakta"));
                return false;
            }
            BotPlusConfig.OnEnemy oe = context.config.ON_ENEMY;
            track();
            double d = p.distanceTo(context.hero);
            // 2.8.0: atlatirken EMP artik beforeSafety -> escapeEmp (Savunma -> EMP -> Ne Zaman).
            // EMP'den sonra 5 sn dusman kilitsiz: hizli / yaklasiyor diye hemen portala gecilmez, atlatma denenir.
            boolean empGrace = empAt > 0 && now - empAt < 5000;
            if (now - start > OUTRUN_MAX_MS && context.attackedBy(p, 3000)) {
                toPortal(OUTRUN_MAX_MS / 1000 + " sn'dir atlatilamadi, hala ates ediyor");
                return true;
            }
            if (!combatMap() && !empGrace && !pre && oe.FASTER_PORTAL && faster(p)) {
                toPortal("dusman hizi bizimkine yakin (" + p.getSpeed() + " / " + context.hero.getSpeed() + ", %95 ve ustu)");
                return true;
            }
            // Dusman ates ederek surekli yaklasiyorsa (atlatilamiyor) portala gecilir.
            if (empGrace || now < holdUntil) closingSince = 0; // EMP Donusu / Yandan Gec sirasinda yaklasma sayilmaz
            if (lastDist >= 0 && d < lastDist - 5 && context.attackedBy(p, 2000)) {
                if (closingSince == 0) closingSince = now;
            } else if (lastDist >= 0 && d > lastDist + 5) closingSince = 0;
            lastDist = d;
            if (!combatMap() && !pre && oe.FASTER_PORTAL && closingSince > 0 && now - closingSince > CLOSING_MS) {
                toPortal("dusman " + CLOSING_MS / 1000 + " sn'dir yaklasiyor (" + (int) d + ")");
                return true;
            }
            // Yaklasinca Hiz Yetenegi: dusman LATE_SPEED_DIST icinde ve hala yaklasiyorsa hiz yetenegi simdi basilir.
            if (oe.OUTRUN_OPTS.contains(BotPlusConfig.OutrunOpt.LATE_SPEED) && !speedUsed && d < LATE_SPEED_DIST
                    && now - approachAt < 1000 && now - speedTryAt > 2000) {
                speedTryAt = now;
                if (context.useSpeedNow("Yaklasinca Hiz Yetenegi: " + threatName + " " + (int) d + " uzakta ve yaklasiyor"))
                    speedUsed = true;
            }
            // 2.7.0 Kacarken Portaldan Atla: kacis yonunde (en fazla 30 derece sapma, dusmandan once varilan) yakin
            // portal varsa yol onun ustunden alinir; durmadan gecerken atlama denenir. Oyun reddederse (savas) atlatma
            // aynen surer, portal birakilir.
            if (oe.OUTRUN_OPTS.contains(BotPlusConfig.OutrunOpt.PORTAL_HOP) && lockAway != null && context.safety instanceof PlusSafety) {
                if (hopTick(p, now)) return true;
            }
            outrun(p, now);
            return true;
        }

        /** Kacarken Portaldan Atla: secilen portal, son atlama denemesi, deneme sayisi. */
        private eu.darkbot.api.game.entities.Portal hopP;
        private long hopTryAt, hopPickAt;
        private int hopTries;
        private final java.util.Set<Integer> hopSkip = new java.util.HashSet<>();
        private int hopMap;

        private int mapId() {
            try {
                return context.star.getCurrentMap().getId();
            } catch (RuntimeException e) {
                return 0;
            }
        }

        /** true: bu tikte portalin ustunden geciliyor (hareket verildi). */
        private boolean hopTick(Player p, long now) {
            PlusSafety ps = (PlusSafety) context.safety;
            if (hopP != null && (!hopP.isValid() || hopTries >= HOP_TRIES || context.hero.distanceTo(hopP) > HOP_DIST * 1.5)) {
                if (hopTries >= HOP_TRIES) {
                    hopSkip.add(hopP.getId());
                    Log.i("DUSMAN", "Kacarken Portaldan Atla: " + hopTries + " denemede atlanamadi, hizinla atlatmaya devam");
                }
                hopP = null;
            }
            if (hopP == null && now - hopPickAt > 1000) {
                hopPickAt = now;
                Location l = ps.hopPortal(context.hero, lockAway, HOP_DIST, HOP_DEG, p);
                if (l != null) for (eu.darkbot.api.game.entities.Portal po : context.ents.getPortals())
                    if (po.isValid() && !hopSkip.contains(po.getId()) && po.distanceTo(l) < 50) {
                        hopP = po;
                        hopTries = 0;
                        Log.i("DUSMAN", "Kacarken Portaldan Atla: yol " + Log.loc(l) + " portalinin ustunden ("
                                + (int) context.hero.distanceTo(po) + " uzakta), gecerken atlanacak");
                        break;
                    }
            }
            if (hopP == null) return false;
            if (hopMap == 0) hopMap = mapId();
            // Portalin kacis yonundeki obur tarafi: gemi durmadan ustunden gecer.
            double run = lockAway + Math.PI;
            Location beyond = Location.of(hopP.getX() + Math.cos(run) * HOP_PASS, hopP.getY() + Math.sin(run) * HOP_PASS);
            if (context.hero.distanceTo(hopP) > HOP_PASS * 0.8 && context.hero.distanceTo(beyond) < context.hero.distanceTo(hopP)) {
                // Portali gectik ama atlanamadi: bir dahaki gecis icin ayni yonde devam (yeni portal aranir).
                hopTries = HOP_TRIES;
                return false;
            }
            context.hero.setRunMode();
            context.moveTo(context.hero.distanceTo(hopP) > 400 ? Location.of(hopP.getX(), hopP.getY()) : beyond);
            // 2.8.0: dusman peşimizi biraktiysa (HOP_CHASE_DIST disinda ve ates etmiyor) atlanmaz, ustunden gecip
            // atlatmaya devam edilir.
            boolean chasing = (p != null && p.distanceTo(context.hero) < HOP_CHASE_DIST) || context.attackedBy(threatId, 3000)
                    || playerShooter() != null;
            if (!chasing && context.hero.distanceTo(hopP) <= 250) {
                if (!hopSkip.contains(hopP.getId()))
                    Log.i("DUSMAN", "Kacarken Portaldan Atla: " + threatName + " pesimizi birakti, portaldan atlanmiyor, atlatmaya devam");
                hopSkip.add(hopP.getId());
                hopP = null;
                return false;
            }
            if (chasing && context.hero.distanceTo(hopP) <= 250 && now - hopTryAt >= 1000) {
                hopTryAt = now;
                hopTries++;
                hopUntil = now + 3000;
                try {
                    context.move.jumpPortal(hopP);
                } catch (RuntimeException ignored) {
                }
                Stats.count("kacarken portal");
                Log.i("DUSMAN", "Kacarken Portaldan Atla: gecerken atlama denendi (" + hopTries + ". deneme)");
            }
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
                context.unlock.request("EMP oncesi kendi kilidimiz " + threatName + " uzerinde");
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

        /**
         * EMP: dusmanin kilidi kirilir (bekleme suresindeyse saniyede bir yeniden denenir). 2.8.0: basma Defense'te
         * (context.empPress): bekleme, sira (EMP sonra Insta-Shield, ayni anda basilmaz) ve sayac orada.
         */
        private void emp(Player p, double d, long now, String why) {
            if (now - empTryAt < 1000) return;
            empTryAt = now;
            // Kendi kilidimiz EMP ile karismasin: basmadan once hedef birakilir (Kilit Birakma rutini).
            if (context.attack.hasTarget()) context.unlock.request("EMP oncesi");
            boolean ok;
            try {
                ok = context.empPress.test("kacis: " + why + ", " + threatName);
            } catch (RuntimeException e) {
                ok = false;
            }
            if (ok) {
                empUsed = true;
                empAt = now;
                out = null; // yeni kacis noktasi: dusmanin tam tersi
                Log.i("DUSMAN", "EMP basildi (" + why + "): " + threatName + " " + (int) d + " uzakta ("
                        + (int) traveled + " yol kacildi), kilit kirildi, tersine kacmaya devam");
            } else {
                Log.every("escemp", 10_000, "DUSMAN", "EMP basilamadi (" + why + "), kacmaya devam");
            }
        }

        /** Haritada dusmanin tersine uzaklasma (kacis konfigi, hiz yetenegi). */
        private void outrun(Player p, long now) {
            if (!striking()) {
                boolean firing;
                try {
                    firing = context.hero.isAttacking();
                } catch (RuntimeException e) {
                    firing = false;
                }
                // Kilit Birakma: ates CTRL ile kontrollu kesilir (stopAttack ikinci komutu yutuyordu).
                if (context.attack.hasTarget() || firing) context.unlock.request("kacis: " + threatName);
            }
            context.hero.setRunMode();
            if (p != null) lastFrom = Location.of(p.getX(), p.getY());
            // Gorus disina cikinca son bilinen yerin tersine devam edilir (yon kilidi korunur).
            Location from = p != null ? lastFrom : lockAway != null ? lastFrom : null;
            java.util.Set<BotPlusConfig.OutrunOpt> oo = context.config.ON_ENEMY.OUTRUN_OPTS;
            boolean reverse = from != null && oo.contains(BotPlusConfig.OutrunOpt.REVERSE);
            // EMP Donusu: bu kacista EMP basildiysa (Defense ya da kacis) bir kez 70-90 derece sert donulur.
            if (from != null && oo.contains(BotPlusConfig.OutrunOpt.EMP_TURN) && !empTurnDone
                    && context.empUsedAt >= start - 1000 && now - context.empUsedAt < 3000) {
                Location t = empTurn(from);
                empTurnDone = true;
                if (t != null) {
                    out = t;
                    outAt = now;
                    holdUntil = now + EMP_TURN_MS;
                }
            }
            // Yon kilitliyken dusman uzak ve ates etmiyorsa nokta sadece varilinca yenilenir (2,5 sn / 0,5 sn degil).
            boolean settled = lockAway != null && (p == null || (p.distanceTo(context.hero) >= RELOCK_DIST
                    && !context.attackedBy(p, 3000)));
            boolean hold = now < holdUntil; // EMP Donusu / Sikisinca Yandan Gec suruyor
            boolean bad = out != null && from != null && out.distanceTo(from) < context.hero.distanceTo(from);
            // Tam Tersine Kac: yon dusman hareket ettikce surekli guncellenir (eskiden 2,5 sn'de bir rastgele nokta).
            long repick = settled || hold ? Long.MAX_VALUE : reverse && !lastFallback ? REVERSE_REPICK_MS : REPICK_MS;
            if (out == null || context.hero.distanceTo(out) < 200 || now - outAt > repick || (bad && !settled && !hold)) {
                Location best = reverse ? reversePoint(p, from, oo, now) : null;
                // Yon bulunamadi (kose): rastgele guvenli nokta 0,5 sn'de bir degil 2,5 sn'de bir yenilenir (zikzak olmasin).
                lastFallback = reverse && best == null;
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
            mineBack(p, now);
            if (out != null) {
                // Yaklasinca Hiz Yetenegi acikken uzun yol hizi basilmaz (yetenek dusman yetisince basilir).
                if (!oo.contains(BotPlusConfig.OutrunOpt.LATE_SPEED))
                    context.speedAbility(context.hero.distanceTo(out) + 3000, "dusmandan kacis");
                context.moveTo(out);
            }
        }

        /** EMP Donusu: kacis yonune gore 70-90 derece yan; kenar / engel / dusman kesmesi olmayan taraf. */
        private Location empTurn(Location from) {
            double away = lockAway != null ? lockAway
                    : Math.atan2(from.getY() - context.hero.getY(), from.getX() - context.hero.getX());
            double heroSpeed = Math.max(1, context.hero.getSpeed());
            Player p = threat;
            double enemySpeed = p != null && p.getSpeed() > 0 ? p.getSpeed() : heroSpeed;
            Location best = null;
            double bestRun = -1, bestA = away;
            for (double deg : new double[]{80, 70, 90}) {
                for (int sign : new int[]{1, -1}) {
                    double a = away + sign * Math.toRadians(deg);
                    Location c = Location.of(context.hero, a, STEP);
                    if (!okPoint(c, from, heroSpeed, enemySpeed, false, true)) continue;
                    double run = runway(a);
                    if (run > bestRun) {
                        bestRun = run;
                        best = c;
                        bestA = a;
                    }
                }
                if (best != null) break;
            }
            if (best == null) {
                Log.i("DUSMAN", "EMP Donusu: iki yan da kapali, donulmedi");
                return null;
            }
            // lockAway degistirilmez (gemiden dusmana gercek yon kalir); donus noktasi holdUntil boyunca korunur.
            Log.i("DUSMAN", "EMP Donusu: dusmanin kilidi kirildi, " + (int) Math.toDegrees(angleDiff(away, bestA))
                    + " derece sert donuldu " + Log.loc(best));
            return best;
        }

        /** Yonde harita kenarina / engele kadar ne kadar yol var (en fazla RUNWAY_MAX). */
        private double runway(double a) {
            for (int d = 500; d <= RUNWAY_MAX; d += 500) {
                Location q = Location.of(context.hero, a, d);
                if (context.edgeDist(q) < RUNWAY_EDGE || !context.move.canMove(q)) return d - 500;
            }
            return RUNWAY_MAX;
        }

        /**
         * Tam Tersine Kac: dusmanin tersi (+-15 derece) ve alt / ust yanlar (+-25..90 derece) puanlanir: o yonde kenara
         * kadar kalan yol (koseye / kenara dayanan yon secilmez), dusmanin tersine yakinlik ve secilen yanda kalma.
         * Dusmanin tarafi (90 dereceden fazla) secilmez. Hic uygun yon yoksa (kose): Sikisinca Yandan Gec.
         * Uygun nokta yoksa null (eski yontem: dusmanin tersindeki yarim dairede en guvenli nokta).
         */
        private Location reversePoint(Player p, Location from, java.util.Set<BotPlusConfig.OutrunOpt> oo, long now) {
            // DarkBot API: Location.of(merkez, aci, r) noktayi acinin TERSINE koyar (merkez - cos/sin). Bu yuzden
            // "dusmandan uzaga" icin aci, gemiden dusmana bakan yondur (eskiden ters: noktalar dusmana dogruydu).
            double cur = Math.atan2(from.getY() - context.hero.getY(), from.getX() - context.hero.getX());
            // Kacis Yonu Kilidi: yon basta secilir; sadece dusman RELOCK_DIST icindeyken belirgin yer degistirirse
            // (RELOCK_DEG) yenilenir; uzaklastiysa yon degismez.
            boolean hot = p != null && p.distanceTo(context.hero) < RELOCK_DIST;
            if (lockAway == null || (hot && Math.toDegrees(angleDiff(lockAway, cur)) > RELOCK_DEG)) {
                if (lockAway != null) Log.i("DUSMAN", "kacis yonu yenilendi: " + threatName + " yer degistirdi ("
                        + (int) Math.toDegrees(angleDiff(lockAway, cur)) + " derece), " + (int) p.distanceTo(context.hero) + " uzakta");
                lockAway = cur;
            }
            double away = lockAway;
            boolean antiCut = oo.contains(BotPlusConfig.OutrunOpt.ANTI_CUT);
            boolean edge = oo.contains(BotPlusConfig.OutrunOpt.EDGE_SLIDE);
            double heroSpeed = Math.max(1, context.hero.getSpeed());
            double enemySpeed = p != null && p.getSpeed() > 0 ? p.getSpeed() : heroSpeed;
            Location best = null;
            double bestScore = -Double.MAX_VALUE, bestDeg = 0;
            int bestSign = 0;
            for (double deg : CAND_DEG) {
                for (int sign : deg == 0 ? new int[]{1} : new int[]{1, -1}) {
                    double a = away + sign * Math.toRadians(deg);
                    Location c = Location.of(context.hero, a, STEP);
                    if (!okPoint(c, from, heroSpeed, enemySpeed, antiCut, edge)) continue;
                    double run = runway(a);
                    // Koseye / kenara dayanan yon secilmez (kisa yol: dusman orada yetisiyordu).
                    if (run < MIN_RUNWAY) continue;
                    double score = Math.min(run, 5000) - deg * 25 + (deg > 0 && sign == side ? 400 : 0) + cornerScore(c, run);
                    if (score > bestScore) {
                        bestScore = score;
                        best = c;
                        bestDeg = deg;
                        bestSign = deg == 0 ? 0 : sign;
                    }
                }
            }
            if (best != null) {
                if (bestDeg >= 25 && (bestSign != side || now >= sideUntil)) {
                    Log.every("escside", 2000, "DUSMAN", "tam ters yol " + frontReason(away, from, heroSpeed, enemySpeed, antiCut, edge)
                            + ", " + (bestSign > 0 ? "sag" : "sol") + " yana " + (int) bestDeg + " derece kaciliyor");
                }
                if (bestSign != 0) {
                    side = bestSign;
                    sideUntil = now + SIDE_LOCK_MS;
                }
                return best;
            }
            // Kose: hicbir yonde yol yok.
            if (p != null && oo.contains(BotPlusConfig.OutrunOpt.SIDE_PASS)) {
                Location c = sidePass(p, from, away, heroSpeed, enemySpeed, now);
                if (c != null) return c;
            }
            Log.every("esccorner", 3000, "DUSMAN", "kacis yolu kapali (kose / kenar), en guvenli noktaya gidiliyor");
            return null;
        }

        /**
         * Sikisinca Yandan Gec: kose / kenarda yol bitince (bizim hizimiz belirgin yuksekse) dusmanin yanindan en az
         * SIDE_PASS_GAP mesafeyle gecilip arkasindaki acik alana kacilir; yon kilidi sifirlanir (gecince yeni yon).
         */
        private Location sidePass(Player p, Location from, double away, double heroSpeed, double enemySpeed, long now) {
            double d = p.distanceTo(context.hero);
            if (heroSpeed < enemySpeed * 1.05 || d < SIDE_PASS_MIN_DIST) {
                Log.every("escsidepassno", 5000, "DUSMAN", "Sikisinca Yandan Gec yapilamadi: " + (heroSpeed < enemySpeed * 1.05
                        ? "hizimiz yetmiyor (" + (int) heroSpeed + " / " + (int) enemySpeed + ")" : "dusman cok yakin (" + (int) d + ")"));
                return null;
            }
            Location best = null;
            double bestRun = -1;
            for (double deg = 100; deg <= 150; deg += 10) {
                for (int sign : new int[]{1, -1}) {
                    double a = away + sign * Math.toRadians(deg);
                    Location c = Location.of(context.hero, a, STEP);
                    if (!context.move.canMove(c) || context.edgeDist(c) < RUNWAY_EDGE) continue;
                    // Gecis yolu dusmana SIDE_PASS_GAP'ten yaklasmamali.
                    if (segDist(Location.of(context.hero.getX(), context.hero.getY()), c, from) < SIDE_PASS_GAP) continue;
                    double run = runway(a);
                    if (run > bestRun) {
                        bestRun = run;
                        best = c;
                    }
                }
            }
            if (best == null || bestRun < MIN_RUNWAY) {
                Log.every("escsidepassno", 5000, "DUSMAN", "Sikisinca Yandan Gec: dusmanin yanindan gececek yol yok");
                return null;
            }
            holdUntil = now + SIDE_PASS_MS;
            lockAway = null; // gecince dusmanin yeni yerine gore yon secilir
            Log.i("DUSMAN", "Sikisinca Yandan Gec: kose / kenar, " + threatName + " (" + (int) d + ") yanindan gecilip acik alana kaciliyor "
                    + Log.loc(best));
            return best;
        }

        /** b noktasinin a-c dogru parcasina uzakligi. */
        private double segDist(Location a, Location c, Location b) {
            double dx = c.getX() - a.getX(), dy = c.getY() - a.getY();
            double len = dx * dx + dy * dy;
            double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((b.getX() - a.getX()) * dx + (b.getY() - a.getY()) * dy) / len));
            return Math.hypot(a.getX() + t * dx - b.getX(), a.getY() + t * dy - b.getY());
        }

        /** Tam ters yon neden kapali (log icin). */
        private String frontReason(double away, Location from, double heroSpeed, double enemySpeed, boolean antiCut, boolean edge) {
            Location c = Location.of(context.hero, away, STEP);
            if (!context.move.canMove(c)) return "engelli";
            if (edge && context.edgeDist(c) < EDGE_MIN) return "harita kenarinda";
            if (antiCut && context.hero.distanceTo(c) / heroSpeed * ANTI_CUT_MARGIN >= from.distanceTo(c) / enemySpeed)
                return "dusmani kesiyor";
            if (context.trackedZone(c, OTHER_ATTACKER_R, threatId) != null) return "baska saldirana dogru";
            return "kapali";
        }

        private double angleDiff(double a, double b) {
            return Math.abs(Math.atan2(Math.sin(a - b), Math.cos(a - b)));
        }

        private boolean okPoint(Location c, Location from, double heroSpeed, double enemySpeed, boolean antiCut, boolean edge) {
            if (!context.move.canMove(c)) return false;
            // Baska bir saldiranin (gorunen ya da 60 sn icinde goruldugu) yanina kacilmaz.
            if (context.trackedZone(c, OTHER_ATTACKER_R, threatId) != null) return false;
            if (edge && context.edgeDist(c) < EDGE_MIN) return false;
            if (antiCut) {
                // Capraz Kalkan: yolun tamami (1/3, 2/3, son) icin gemi dusmandan once varmali (sadece son nokta degil).
                for (double f : new double[]{0.35, 0.7, 1.0}) {
                    Location q = Location.of(context.hero.getX() + (c.getX() - context.hero.getX()) * f,
                            context.hero.getY() + (c.getY() - context.hero.getY()) * f);
                    double ours = context.hero.distanceTo(q) / heroSpeed;
                    double theirs = from.distanceTo(q) / enemySpeed;
                    if (ours * ANTI_CUT_MARGIN >= theirs) return false;
                }
            }
            return true;
        }

        /**
         * Kose Korumasi: harita ortasina yonelen yon puan alir; yolun ucu (en fazla 3000) iki kenara birden yakinsa
         * (kose) ceza. 15:29: gemi sol alt koseye kacti, "tam ters yol harita kenarinda" deyip dusmana yakalandi.
         */
        private double cornerScore(Location c, double run) {
            if (!context.config.ON_ENEMY.PLUS.CORNER_GUARD) return 0;
            Location center = context.mapCenter();
            if (center == null) return 0;
            double hx = context.hero.getX(), hy = context.hero.getY();
            double dir = Math.atan2(c.getY() - hy, c.getX() - hx);
            double toCenter = Math.atan2(center.getY() - hy, center.getX() - hx);
            double score = CENTER_BONUS * Math.cos(dir - toCenter);
            double far = Math.min(run, 3000);
            Location end = Location.of(hx + Math.cos(dir) * far, hy + Math.sin(dir) * far);
            if (context.cornerDist(end) < 1500) score -= CORNER_PENALTY;
            return score;
        }

        /** Arkaya Mayin: dusman MINE_DIST icinde ve yaklasiyorsa yavaslatici mayin (10 sn'de bir). */
        private long mineAt;

        private void mineBack(Player p, long now) {
            if (p == null || !context.config.ON_ENEMY.PLUS.MINE_BACK || now - mineAt < MINE_GAP_MS) return;
            if (p.distanceTo(context.hero) > MINE_DIST || now - approachAt > 1500) return;
            mineAt = now;
            try {
                eu.darkbot.api.game.items.ItemUseResult r = context.items.useItem(eu.darkbot.api.game.items.SelectableItem.Mine.SLM_01, 1000);
                if (r == eu.darkbot.api.game.items.ItemUseResult.SUCCESS) {
                    Stats.count("arkaya mayin");
                    Log.i("DUSMAN", "Arkaya Mayin: " + threatName + " " + (int) p.distanceTo(context.hero) + " uzakta ve yaklasiyor, SLM-01 birakildi");
                } else Log.every("escmine", 15_000, "DUSMAN", "Arkaya Mayin basilamadi (" + r + ")");
            } catch (RuntimeException e) {
                Log.every("escmine", 15_000, "DUSMAN", "Arkaya Mayin basilamadi (" + e + ")");
            }
        }

        /** Atlatma Sonrasi Mesafe: atlatilan dusmanin yanindaki NPC'ler bir sure secilmez. */
        private void afterOutrun(Player p) {
            int sec = context.config.ON_ENEMY.PLUS.AFTER_SEC;
            if (sec <= 0) return;
            double r = Math.max(2500, context.config.ON_ENEMY.DIST * 1.5);
            Location at = p != null ? Location.of(p.getX(), p.getY()) : lastFrom != null ? lastFrom : context.attackerSeenAt(threatId);
            if (at == null) return;
            context.keepAwayFrom(threatId, threatName, at, r, sec * 1000L);
        }

        @Override
        public void end(String why) {
            if (!done) Stats.time("kacis", Context.now() - start); // 2.8.0 VERIM: kacista gecen sure
            done = true;
            // Sabit 5 dk bolge yok: saldiran takip edilir (Context.trackedZone). Atis / kilit kaldiysa birakilir.
            if (striking()) context.unlock.request("kacis bitti");
            strikeUntil = 0;
        }
    }
}