package com.botplus.repair;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.core.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.entities.Ship;
import eu.darkbot.api.game.other.Locatable;
import eu.darkbot.api.game.other.Location;

/**
 * Haritada Tamir: can "Tamir Baslama Cani"nin altina inince portala gitmeden haritada tamir edilir.
 * Tamir bitene kadar saldiri yok. Ayarlara gore NPC'lerden ve dusman oyunculardan belirlenen mesafede uzak durulur.
 * Dusman yaklasirsa: "Uzaklas" secilirse BotPlus uzaklasir, "Portala Kac" secilirse DarkBot'un kendi kacisi
 * (SafetyFinder) devreye girer ve kacis bitince tamir kaldigi yerden surer.
 * Daha onemli bir is (yenileme) gelirse tamir kesilir, sonra devam edilir.
 */
public final class RepairLogic {

    private static final int STEP = 1500;
    private static final int EDGE_MIN = 1500;
    private static final long TIMEOUT_MS = 10 * 60_000;
    private static final long TIMEOUT_COOLDOWN_MS = 60_000;
    /** Bosta tamirde bu kadar suredir surekli uzaklasiliyorsa (tehlike pesinde) birakilir. */
    private static final long IDLE_AWAY_MAX_MS = 30_000;

    private final Context context;
    private long cooldownUntil;
    /** Tamir yarida kaldi: can bitis degerine ulasana kadar tekrar baslatilir. */
    private boolean pending;

    public RepairLogic(Context context) {
        this.context = context;
    }

    private double startHp() {
        return context.config.REPAIR.START_HP / 100.0;
    }

    private double endHp() {
        return Math.max(context.config.REPAIR.START_HP, context.config.REPAIR.END_HP) / 100.0;
    }

    public PlusModule.Job check() {
        if (!context.config.REPAIR.onMap() || Context.now() < cooldownUntil) {
            pending = false;
            return null;
        }
        double hp = context.heroHp();
        if (hp >= endHp()) pending = false;
        if (!pending && hp >= startHp()) return null;
        // Combat Plus: once NPC bitirilir (can %10'un altindaysa asla beklenmez).
        if (!pending && hp >= 0.10 && context.finishFirst.getAsBoolean()) return null;
        warn();
        Log.i("TAMIR", (pending ? "DEVAM " : "BASLADI ") + "can " + Log.pct(hp) + " kalkan " + shield() + ", hedef "
                + Log.pct(endHp()) + (pending ? "" : konfig()) + " | " + Log.loc(context.hero) + " | " + context.dangerInfo(context.hero));
        if (!pending) Stats.count("tamir");
        pending = true;
        return new Job();
    }

    // ---------------------------------------------------------------- hasar takibi / bosta tamir

    private double lastTotal = -1;
    /** Dovus oncesi tamir can dolduramadiysa (tamir robotu yok vb.) bir sure tekrar denenmez. */
    private long fightMinBlockUntil;
    private long lastHitAt;

    /** Her tik: gemiye hasar geldi mi (can + kalkan azaldi ya da biri ates ediyor). */
    public void track() {
        try {
            double total = context.hero.getHealth().getHp() + context.hero.getHealth().getShield();
            if ((lastTotal >= 0 && total < lastTotal - 1) || context.attackerOfHero() != null) lastHitAt = Context.now();
            lastTotal = total;
        } catch (RuntimeException ignored) {
        }
    }

    private boolean recentlyHit(long ms) {
        return Context.now() - lastHitAt < ms;
    }

    /** Bosta tamir icin bir sey var mi: hedef, bulucu sinyali, dovusun alabilecegi NPC, dusman. */
    private String busy() {
        if (context.targetNpc() != null) return "hedef var";
        if (context.config.LOCATOR.GO && context.ping().isPresent()) return "bulucu sinyali var";
        if (context.enemyThreat() != null) return "dusman var";
        for (Npc n : context.ents.getNpcs())
            if (context.hero.distanceTo(n) < 3000 && (context.config.COMBAT.ENABLED ? context.combatCanTake.test(n)
                    : context.darkBotWouldKill(n))) return "yakinda NPC var (" + Log.name(n) + ")";
        return null;
    }

    private boolean idleOn;

    /**
     * Bosta tamir (Combat Plus tam kontrolde): can bosta tamir sinirinin altina inince baslar, bitis canina kadar surer.
     * Gemi durmaz: dolasmaya / bulucuya devam eder; bu surede tamir konfigurasyonu, PET Kombo Gemi Tamiri ve liste disi
     * NPC'lerden uzak durma. Listedeki NPC gorulunce dovus normal sekilde alir (tamir beklenmez).
     */
    public boolean idleRepairing() {
        BotPlusConfig.Repair c = context.config.REPAIR;
        if (!c.IDLE || !c.onMap() || pending || !context.config.ENABLED || !context.config.COMBAT.ENABLED) {
            idleOn = false;
            return false;
        }
        double hp = context.heroHp();
        if (idleOn && hp >= endHp()) {
            idleOn = false;
            Log.i("TAMIR", "BOSTA TAMAM can " + Log.pct(hp) + " (dolasirken)");
        } else if (!idleOn && hp < c.IDLE_HP / 100.0 && hp < endHp()) {
            idleOn = true;
            Stats.count("bosta tamir");
            Log.i("TAMIR", "BOSTA BASLADI can " + Log.pct(hp) + " < %" + c.IDLE_HP + ": dolasmaya devam, tamir konfigurasyonu"
                    + (c.PET_SHIP ? " + PET Kombo Gemi Tamiri" : "") + ", liste disi NPC'lerden uzak duruluyor |" + konfig());
        }
        return idleOn;
    }

    /** Hasar Almiyorken Tamir: hedef / sinyal yokken, 5 sn hasar alinmadiysa ve can sinirin altindaysa yerinde can doldurulur. */
    public PlusModule.Job checkIdle() {
        BotPlusConfig.Repair c = context.config.REPAIR;
        if (!c.IDLE || !c.onMap() || !context.config.ENABLED || pending) return null;
        double hp = context.heroHp();
        if (hp >= c.IDLE_HP / 100.0 || hp >= endHp() || recentlyHit(5000) || busy() != null) return null;
        Log.i("TAMIR", "BOSTA BASLADI can " + Log.pct(hp) + " < %" + c.IDLE_HP + ", 5 sn hasar yok, hedef / sinyal yok |" + konfig() + " | "
                + context.dangerInfo(context.hero));
        Stats.count("bosta tamir");
        return new IdleJob();
    }

    /**
     * Dovuse Baslama Cani: can bu sinirin altindaysa (ve kimse vurmuyorsa) yeni NPC'ye girmeden once yerinde can
     * doldurulur (dusuk canla girilen dovus ortada tamire kesilip NPC yarim kaliyordu).
     */
    public PlusModule.Job checkFightMin() {
        BotPlusConfig.HpWait w = context.config.PLUS.TARGET.HP_WAIT;
        if (!context.config.ENABLED || !w.ENABLED || w.WHICH != BotPlusConfig.HpWaitWhich.ALL || pending || Context.now() < fightMinBlockUntil) return null;
        double hp = context.heroHp();
        if (hp >= w.HP / 100.0 || hp >= endHp() || context.targetNpc() != null || context.enemyThreat() != null
                || context.attackerOfHero() != null || recentlyHit(3000)) return null;
        Log.i("TAMIR", "HP BEKLE can " + Log.pct(hp) + " < %" + w.HP + " (tum NPC'ler), saldiridan once yerinde can doldurulacak | "
                + context.dangerInfo(context.hero));
        Stats.count("hp bekle tamir");
        return new IdleJob(true);
    }

    public final class IdleJob implements PlusModule.Job {
        private final long start = Context.now();
        /** Dovuse Baslama Cani icin: NPC / sinyal varken de surer, sadece hedef / dusman / hasar keser. */
        private final boolean forFight;

        public IdleJob() {
            this(false);
        }

        public IdleJob(boolean forFight) {
            this.forFight = forFight;
        }

        private double target() {
            BotPlusConfig.Repair c = context.config.REPAIR;
            if (!forFight) return endHp();
            return Math.min(endHp(), context.config.PLUS.TARGET.HP_WAIT.HP / 100.0);
        }

        @Override
        public String name() {
            return "BOSTA TAMIR";
        }

        @Override
        public int priority() {
            return forFight ? PlusModule.P_LOCATOR + 1 : PlusModule.P_IDLE_REPAIR;
        }

        @Override
        public String status() {
            return (forFight ? "HP bekleniyor (" : "boşta tamir (") + Log.pct(context.heroHp()) + " / " + Log.pct(target()) + ")"
                    + (awayNow ? ", NPC'den uzaklaşılıyor" : "");
        }

        @Override
        public boolean tick() {
            if (!context.config.ENABLED) return false;
            if (forFight ? !context.config.PLUS.TARGET.HP_WAIT.ENABLED
                    : !context.config.REPAIR.IDLE || !context.config.REPAIR.onMap()) {
                Log.i("TAMIR", "BOSTA birakildi: ayar kapatildi");
                return false;
            }
            double hp = context.heroHp();
            if (hp >= target()) {
                Log.i("TAMIR", "BOSTA TAMAM can " + Log.pct(hp) + " (" + (Context.now() - start) / 1000 + " sn)");
                return false;
            }
            // HP Bekle: 15 sn'de can %0,5'ten fazla artmadiysa (tamir robotu yok vb.) beklenmez.
            if (forFight) {
                long t = Context.now();
                // Ilerleme: son ilerleme anindaki candan net artis, ya da hasar almiyorken en dusuk noktadan artis
                // (vurulurken dus-cik salinimi ilerleme sayilmaz; %16 -> %1 -> %8 gibi hasarsiz toparlanma sayilir).
                if (gainAt == 0 || hp > bestHp + 0.005 || (hp > lowHp + 0.005 && !recentlyHit(2000))) {
                    bestHp = hp;
                    lowHp = hp;
                    gainAt = t;
                } else if (hp < lowHp) {
                    lowHp = hp;
                }
                if (t - gainAt > 15_000) {
                    Log.i("TAMIR", "HP BEKLE 15 sn'de can artmadi (" + Log.pct(hp) + "), 60 sn beklenmeden saldirilacak");
                    fightMinBlockUntil = t + 60_000;
                    return false;
                }
            }
            if (forFight && Context.now() - start > 90_000) {
                Log.i("TAMIR", "HP BEKLE 90 sn'de can dolmadi (" + Log.pct(hp) + "), 60 sn beklenmeden saldirilacak");
                fightMinBlockUntil = Context.now() + 60_000;
                return false;
            }
            // Sinyal, kesilecek NPC, hedef ya da dusman varsa gorev oncelikli: tamir birakilir.
            String b = forFight ? (context.targetNpc() != null ? "hedef var" : context.enemyThreat() != null ? "dusman var" : null) : busy();
            if (b != null) {
                Log.i("TAMIR", "BOSTA birakildi: " + b + ", can " + Log.pct(hp) + " (" + (Context.now() - start) / 1000 + " sn)");
                return false;
            }
            context.attack.stopAttack();
            if (context.attack.hasTarget()) context.attack.setTarget(null);
            long now = Context.now();
            // HP Bekle ("NPC ates ederken degil"): gemi 2 sn'den uzun vuruluyorsa beklenmez, saldirilir.
            if (forFight) {
                if (context.attackerOfHero() == null) shotSince = 0;
                else if (shotSince == 0) shotSince = now;
                else if (now - shotSince > 2000) {
                    Log.i("TAMIR", "HP BEKLE birakildi: " + Log.name(context.attackerOfHero())
                            + " vuruluyor, beklenmeden saldiriliyor, can " + Log.pct(hp));
                    return false;
                }
            }
            // Yaklasan / ates eden NPC ya da dusmandan uzaklasilir, tamir surer (eskiden gemi durup vurulunca birakiyordu).
            Locatable from = threat();
            if (from != null) {
                if (awaySince == 0) awaySince = now;
                if (now - awaySince > IDLE_AWAY_MAX_MS) {
                    Log.i("TAMIR", "BOSTA birakildi: " + IDLE_AWAY_MAX_MS / 1000 + " sn'dir uzaklasiliyor, tehlike gecmedi, can " + Log.pct(hp));
                    return false;
                }
                boolean reached = away == null || context.hero.distanceTo(away) < 150;
                boolean bad = away != null && (away.distanceTo(from) < context.hero.distanceTo(from) + 300 || !context.move.canMove(away));
                if (reached || (bad && now - awayAt > 1500)) {
                    away = context.safePoint(STEP, from);
                    awayAt = now;
                    Log.every("idleaway", 3000, "TAMIR", "BOSTA: " + Log.name(from instanceof Ship ? (Ship) from : null) + " "
                            + (int) from.distanceTo(context.hero) + " yakin, uzaklasiliyor " + Log.loc(away) + ", tamir suruyor, can " + Log.pct(hp));
                }
                awayNow = true;
                context.hero.setRoamMode();
                context.moveTo(away);
                return true;
            }
            awaySince = 0;
            away = null;
            awayNow = false;
            context.stop();
            return true;
        }

        private Location away;
        private long awayAt, awaySince, shotSince;
        /** HP Bekle ilerleme takibi: en yuksek can ve son artis ani. */
        private double bestHp = -1, lowHp = -1;
        private long gainAt;
        private boolean awayNow;

        /** Uzak durulacak: gemiye ates eden, ya da "Tamirde Kacin" mesafelerinin icindeki NPC / dusman oyuncu. */
        private Locatable threat() {
            Ship hit = context.attackerOfHero();
            if (hit != null) return hit;
            BotPlusConfig.Repair c = context.config.REPAIR;
            Player e = context.nearestEnemy(context.hero);
            if (c.enemyAway() && e != null && e.distanceTo(context.hero) < c.ENEMY_DIST) return e;
            Npc n = context.nearestNpc(context.hero);
            if (c.npcAway() && n != null && n.distanceTo(context.hero) < c.NPC_DIST) return n;
            return null;
        }
    }

    /** DarkBot'un portal tamiri sinirlari BotPlus'tan yuksekse DarkBot once portala gider. */
    public void warn() {
        double dbMin = context.darkBotRepairMin();
        double noNpc = Context.value(context.repairNoNpc, -1.0);
        // Baslama / bitis cani artik DarkBot'un tamir ayarindan okunuyor (ikisi bilerek ayni): eski uyari kaldirildi.
        if (noNpc >= startHp() && !context.config.COMBAT.ENABLED)
            Log.every("repairwarn2", 60_000, "TAMIR", "UYARI: DarkBot \"hedef yokken tamir\" siniri " + Log.pct(noNpc)
                    + " >= BotPlus baslama " + Log.pct(startHp()) + ". Hedef yokken DarkBot portala tamire gidebilir, DarkBot ayarini dusur."
                    + " (Combat Plus acikken BotPlus bunu yok sayar.)");
    }

    /** Tamir konfigurasyonu (kullanici dogrulayabilsin). */
    private String konfig() {
        try {
            eu.darkbot.api.config.types.ShipMode m = context.config.REPAIR.MODE;
            return m == null ? " konfig ?" : " konfig " + m.getConfiguration() + "/" + m.getFormation();
        } catch (RuntimeException e) {
            return " konfig ?";
        }
    }

    private String shield() {
        try {
            return Log.shield(context.hero.getHealth());
        } catch (RuntimeException e) {
            return "?";
        }
    }

    public final class Job implements PlusModule.Job {
        private final long start = Context.now();
        private Location target;
        private long targetAt, runUntil;
        /** Dusman oyuncudan kaciliyor (kacis konfigurasyonu icin). */
        private boolean enemyRun;

        @Override
        public String name() {
            return "TAMIR";
        }

        @Override
        public int priority() {
            return PlusModule.P_REPAIR;
        }

        /** Dusman oyuncu ates ederse DarkBot'un kacisi tamir sirasinda da calisir (eskiden 2,5 sn sonra kapaniyordu). */
        @Override
        public boolean safety() {
            // Basladiysa kacis yarida birakilmaz; oyuncu ates ediyorsa DarkBot kacar. Sadece yakindaki dusman: uzaklasilir.
            return context.safetyEscaping() || context.attackerOfHero() instanceof Player;
        }

        /** Can artisini izleme: 20 sn'de %0,5 artmazsa haritada tamir birakilip portal tamirine gecilir. */
        private double gainHp = -1, gainLow = -1;
        private long gainAt;
        /** NPC'nin kesintisiz ates ettigi sure basi (kovalama). */
        private long npcHitSince;

        public boolean enemyRun() {
            return enemyRun;
        }

        @Override
        public String status() {
            return "haritada tamir (" + Log.pct(context.heroHp()) + " / " + Log.pct(endHp()) + ")"
                    + (enemyRun ? ", düşmandan uzaklaşılıyor" : "");
        }

        @Override
        public boolean tick() {
            long now = Context.now();
            BotPlusConfig.Repair c = context.config.REPAIR;
            if (!context.config.ENABLED || !c.onMap()) {
                Log.i("TAMIR", "ayar kapatildi");
                pending = false;
                return false;
            }
            if (context.repair.isDestroyed()) {
                Log.i("TAMIR", "gemi patladi, tamir birakildi");
                pending = false;
                return false;
            }
            if (context.heroHp() >= endHp()) {
                Log.i("TAMIR", "TAMAM can " + Log.pct(context.heroHp()) + " kalkan " + shield() + " (" + (now - start) / 1000
                        + " sn) | " + context.dangerInfo(context.hero));
                pending = false;
                return false;
            }
            if (now - start > TIMEOUT_MS) {
                Log.i("TAMIR", "10 dk doldu, can " + Log.pct(context.heroHp()) + ", 1 dk ara veriliyor");
                pending = false;
                cooldownUntil = now + TIMEOUT_COOLDOWN_MS;
                return false;
            }

            // Tamirde saldiri yok. Konfigurasyonu BotPlus'in mod secicisi verir (her tik setMode cagrilmaz).
            context.attack.stopAttack();
            if (context.attack.hasTarget()) context.attack.setTarget(null);
            context.pet.setEnabled(true);

            Ship hit = context.attackerOfHero();
            Npc npc = context.nearestNpc(context.hero);
            Player enemy = context.nearestEnemy(context.hero);
            double npcD = npc == null ? Double.MAX_VALUE : npc.distanceTo(context.hero);
            double enemyD = enemy == null ? Double.MAX_VALUE : enemy.distanceTo(context.hero);

            Locatable from = null;
            String why = null;
            if (hit != null) {
                from = hit;
                why = "ates ediyor " + Log.name(hit) + " " + (int) hit.distanceTo(context.hero);
            } else if (c.enemyAway() && enemyD < c.ENEMY_DIST) {
                from = enemy;
                why = "dusman yakin " + Log.name(enemy) + " " + (int) enemyD + " < " + c.ENEMY_DIST;
            } else if (c.npcAway() && npcD < c.NPC_DIST) {
                from = npc;
                why = "NPC yakin " + Log.name(npc) + " " + (int) npcD + " < " + c.NPC_DIST;
            }
            // Dusman Yakinken Portalda Tamir: can %50 altinda ve dusman oyuncu 3000 icindeyse haritada tamir edilmez,
            // DarkBot'un portal tamirine gecilir (15:29: can %16, AVSAR 1236 uzaktayken haritada tamire baslanip patlandi).
            // NPC kovaliyor mu: 3 sn'den uzun suredir NPC ates ediyorsa kacis konfigurasyonu (tamir konfigu yavas kalir).
            if (hit instanceof Npc) {
                if (npcHitSince == 0) npcHitSince = now;
            } else npcHitSince = 0;
            boolean npcChase = npcHitSince > 0 && now - npcHitSince >= 3000;
            // Kacis konfigurasyonu en az 6 sn tutulur (DarkBot konfig degisimi 5,5 sn: gidip gelmesin).
            if (c.ENEMY_RUN_MODE && (from instanceof Player || npcChase)) runUntil = now + 6000;

            // Can artmiyorsa (tamir robotu yok / surekli hasar) 20 sn sonra DarkBot'un portal tamirine birakilir.
            double hpNow = context.heroHp();
            // Referans: son artistan beri gorulen en dusuk can. Can dusunce referans da duser (eskiden sadece
            // yukari gidiyordu: %16'dan %1'e dusup %8'e cikan can "artmiyor" sayilip tamir birakiliyordu).
            // Vurulurken dus-cik salinimi (NPC kovaliyor, tamir robotu yetismiyor) ilerleme sayilmaz: ya son ilerleme
            // anindaki candan net artis ya da hasar almiyorken en dusuk noktadan artis gerekir.
            if (gainHp < 0 || hpNow > gainHp + 0.005 || (hpNow > gainLow + 0.005 && !recentlyHit(2000))) {
                gainHp = hpNow;
                gainLow = hpNow;
                gainAt = now;
            } else if (hpNow < gainLow) {
                gainLow = hpNow;
            }
            if (now - gainAt > 20_000) {
                Log.i("TAMIR", "20 sn'dir can artmiyor (" + Log.pct(hpNow) + (npcChase ? ", NPC kovaliyor" : "")
                        + "): haritada tamir birakildi, 2 dk DarkBot'un portal tamiri calisacak");
                context.portalRepairUntil = now + 120_000;
                cooldownUntil = now + 120_000;
                pending = false;
                return false;
            }
            enemyRun = c.ENEMY_RUN_MODE && now < runUntil;
            // Portalin dibinde tamir edilir: dusman saldirirsa kacis (oncelik daha yuksek) portaldan hemen atlar.
            // DarkBot'un portal tamirine birakilmaz (DarkBot'un tamir siniri daha dusukse hic gitmiyordu).
            if (context.config.ON_ENEMY.PLUS.REPAIR_PORTAL && context.heroHp() < 0.5
                    && (hit instanceof Player || (enemy != null && enemyD < 3000))
                    && context.safety instanceof com.botplus.core.PlusSafety) {
                eu.darkbot.api.game.other.Location port = ((com.botplus.core.PlusSafety) context.safety).harborPortal(context.hero, 30_000);
                Player e = hit instanceof Player ? (Player) hit : enemy;
                // Dusman portala bizden yakinsa (aramizda) o portala gidilmez: normal haritada uzaklasma.
                if (port != null && e != null && e.distanceTo(port) > context.hero.distanceTo(port)) {
                    Log.every("repairportal", 5000, "TAMIR", "can " + Log.pct(context.heroHp()) + ", dusman " + Log.name(e) + " "
                            + (int) e.distanceTo(context.hero) + " uzakta: tamir portalin dibinde " + Log.loc(port) + " ("
                            + (int) context.hero.distanceTo(port) + ")");
                    enemyRun = c.ENEMY_RUN_MODE;
                    target = Location.of(port.getX(), port.getY());
                    targetAt = now;
                    if (context.hero.distanceTo(port) > 150) context.moveTo(target);
                    return true;
                }
            }

            boolean reached = target == null || context.hero.distanceTo(target) < 100;
            boolean bad = target != null && (!safeEnough(target) || context.edgeDist(target) < EDGE_MIN || !context.move.canMove(target));
            // Zikzak olmasin: nokta en erken 2 sn'de bir degisir (varilinca ya da nokta tehlikeli olunca hemen).
            boolean stale = now - targetAt > 2000;
            // Tehlikeli nokta da en erken 1,5 sn'de bir degisir (her tik yeni nokta secilmez).
            bad = bad && now - targetAt >= 1500;
            if (from != null && (reached || bad || (stale && target.distanceTo(from) < context.hero.distanceTo(from) + 300))) {
                target = repairPoint(from);
                targetAt = now;
                Log.every("repairhit", 3000, "TAMIR", why + " " + Log.loc(from) + ", uzaklasiliyor " + Log.loc(target)
                        + " (" + (int) context.hero.distanceTo(target) + ")");
            } else if (reached || bad) {
                target = repairPoint(null);
                targetAt = now;
                Log.every("repairmove", 5000, "TAMIR", "yeni nokta " + Log.loc(target) + " kenara "
                        + (int) context.edgeDist(target) + (bad ? " (eski nokta tehlikeli)" : ""));
            }
            context.moveTo(target);

            Log.every("repairstat", 3000, "TAMIR", () -> "can " + Log.pct(context.heroHp()) + " kalkan " + shield()
                    + " | en yakin NPC: " + (npc == null ? "yok" : Log.name(npc) + " " + (int) npcD)
                    + " | en yakin dusman: " + (enemy == null ? "yok" : Log.name(enemy) + " " + (int) enemyD)
                    + " | ates eden: " + (hit == null ? "yok" : Log.name(hit) + " " + (int) hit.distanceTo(context.hero))
                    + " | hedef " + Log.loc(target) + " " + (int) context.hero.distanceTo(target)
                    + " | konfig " + (enemyRun ? "kacis" : "tamir") + " | " + (now - start) / 1000 + " sn");
            return true;
        }

        private boolean safeEnough(Location p) {
            BotPlusConfig.Repair c = context.config.REPAIR;
            if (c.npcAway()) {
                Npc n = context.nearestNpc(p);
                if (n != null && n.distanceTo(p) < c.NPC_DIST) return false;
            }
            if (c.enemyAway()) {
                Player e = context.nearestEnemy(p);
                return e == null || e.distanceTo(p) >= c.ENEMY_DIST;
            }
            return true;
        }

        /** Kenardan (radyasyondan) uzak, ayarlanan mesafelere gore NPC ve dusmandan uzak nokta. */
        private Location repairPoint(Locatable away) {
            BotPlusConfig.Repair c = context.config.REPAIR;
            Location best = null;
            double bestScore = -Double.MAX_VALUE;
            double step = away instanceof Player ? Math.max(STEP, c.ENEMY_DIST * 0.6) : STEP;
            Location zone = c.NEAR_NPC ? npcZone() : null;
            for (int k = 0; k < 16; k++) {
                double base = away != null ? context.hero.angleTo(away) + Math.PI
                        : zone != null && k < 8 ? context.hero.angleTo(zone) : Math.random() * 2 * Math.PI; // Location.of: aci = hedefe dogru
                double spread = away != null ? Math.PI / 2 : zone != null && k < 8 ? Math.PI / 4 : Math.PI;
                Location p = context.ringPoint(context.hero, step, base, spread);
                // Radyasyon / engel noktasi secilmez.
                if (!context.move.canMove(p)) continue;
                double edge = context.edgeDist(p);
                double score = Math.min(edge, 3000) * 0.5 - (edge < EDGE_MIN ? 5000 : 0);
                // NPC bolgesine yakin tamir: NPC'lere mesafe ayarinin hemen disinda (bant) kalan nokta tercih edilir.
                if (zone != null) {
                    double band = zone.distanceTo(p) - c.NPC_DIST;
                    score += band < 0 ? band * 3 : -Math.min(band, 3000);
                }
                if (c.npcAway()) {
                    Npc n = context.nearestNpc(p);
                    score += Math.min(n == null ? 1e9 : n.distanceTo(p), c.NPC_DIST * 2.0);
                }
                if (c.enemyAway()) {
                    Player e = context.nearestEnemy(p);
                    score += 2 * Math.min(e == null ? 1e9 : e.distanceTo(p), c.ENEMY_DIST * 2.0);
                }
                if (away != null) score += Math.min(away.distanceTo(p), 3000);
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
            return best != null ? best : context.ringPoint(context.hero, step, Math.random() * 2 * Math.PI, Math.PI);
        }

        /** NPC bolgesi: en yakin kesilecek NPC, yoksa bulucu sinyali. */
        private Location npcZone() {
            Npc best = null;
            double bd = Double.MAX_VALUE;
            for (Npc n : context.ents.getNpcs()) {
                if (!Context.killable(n)) continue;
                double d = context.hero.distanceTo(n);
                if (d < bd) {
                    bd = d;
                    best = n;
                }
            }
            if (best != null) return Location.of(best.getX(), best.getY());
            java.util.Optional<Location> ping = context.ping();
            return ping.orElse(null);
        }

        @Override
        public void end(String why) {
            enemyRun = false;
            if (pending) Log.i("TAMIR", "yarida kaldi (" + why + "), "
                    + (context.repair.isDestroyed() ? "gemi patladi" : "can " + Log.pct(context.heroHp())) + ", sonra devam edilecek");
        }
    }
}