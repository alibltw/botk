package com.botplus.combat;

import com.botplus.*;
import com.botplus.core.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.config.types.NpcFlag;
import eu.darkbot.api.game.entities.Box;
import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.other.Location;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Combat Plus (tam kontrol): calisma haritasinda gemiyi BotPlus yonetir, DarkBot'a birakilmaz.
 * Hedef secimi (DarkBot'un "yanlis NPC'ye saldirma" kurallari korunarak), dovus, kutu toplama ve dolasma bu istedir.
 * Daha onemli isler (yenileme, tamir, dusman, calma, yok sayma, bekleme, bulucu) bu isi keser, bitince tekrar baslar.
 * Butun dovus mesafeleri NPC'nin tablodaki menzilinden hesaplanir.
 *
 * Dovus: dogrudan yaklasma (on kilit, yolu kesme / varis noktasina onceden yerlesme) -> yaya teget giris (gemi durmaz)
 * -> sabit ortali yay (uclarda yon degisir, her ikinci gidiste biraz iceri) -> olum -> kutu -> durmadan siradaki.
 * HE isaretli NPC: durup cok vuruyorsa menzilden cikilir, NPC hareket edince geri girilir.
 */
public final class CombatLogic {

    static final double RANGE_BUFFER_IN = 0.85;   // menzil tamponu: bu aralikta yaricap duzeltilmez
    static final double RANGE_BUFFER_OUT = 1.05;
    // Bundan uzaksa yaklasma. 2.1.0: 1.15 idi; 613 menzilde 705'te "menzile girildi" denip 650-669'dan ates
    // aciliyor, ilk atislar "menzil disi" aliyordu. Tablo menzili tek referans: menzile girmeden ilk vurus yok.
    static final double APPROACH_OUT = 1.0;
    static final double INWARD = 0.10;            // yayda iceri adim: tablo menzilinin %10'u
    static final long CENTER_REPICK_MS = 3000;
    static final long CENTER_CHANGE_MIN_MS = 10_000; // yay ortasi en sik 10 sn'de bir degisir
    static final double CENTER_MARGIN = 1200;     // yay ortasi sadece belirgin daha iyiyse degisir
    static final double SAFE_HALF = Math.toRadians(20);
    static final double FRONT_AVOID = Math.toRadians(60);
    static final long FLIP_MIN_MS = 1000;
    static final int NEXT_SEARCH = 3000;
    static final int BOX_DETOUR = 300;
    static final long DROP_WAIT_MS = 1000;        // olen NPC'nin kutusu icin bekleme
    static final long BOX_MAX_MS = 6000;
    static final int BOX_TRIES = 3;
    static final long NO_DAMAGE_MS = 6000;        // DarkBot'un 20 sn bozuk kilit kuralindan once biter
    static final long NO_DAMAGE_STEP_MS = 2500;
    static final double LOCAL_MIN_SCALE = 0.8; // eskiden 0.5: 660 menzil 330'a kadar dusebiliyordu
    static final long DEAD_TARGET_IGNORE_MS = 20_000;
    static final long HALF_MS = 120_000;
    static final long CROWD_CHECK_MS = 1000;
    static final double MULTI_HP_DROP = 0.08;     // 2 sn'de %8'den fazla can kaybi
    static final long DEATH_PREDICT_MS = 2500;
    static final long APPROACH_MIN_MS = 20_000;   // ulasilamayan NPC: yaklasma zaman asimi
    static final long UNREACH_IGNORE_MS = 60_000;
    static final long UNREACH_ZONE_MS = 180_000;
    static final int UNREACH_ZONE = 300;
    static final long RUN_SWITCH_MS = 5500;       // DarkBot konfig degisimi icin 5,5 sn bekletir
    static final double RUN_ON_SEC = 6, RUN_OFF_SEC = 6; // degisim (5,5 sn) varistan once biter
    /** DarkBot'un dusuk canli / kacan NPC'de kullandigi en buyuk menzil. */
    static final double LOW_HP_RADIUS = 600;
    /** Lazer siniri ogrenildiyse saldiri mesafesi bundan bu kadar icerde. */
    static final double LASER_MARGIN = 25;
    /** Ilk vurus: lazer sinirinin bu kadar icinde (lazer yetisir yetismez ates). */
    static final double FIRST_HIT_MARGIN = 50;
    /** Ilk isabet bu kadar gecikirse biraz daha iceri girilir; toplam bu kadar beklenir. */
    static final long FIRST_HIT_CLOSER_MS = 2000, FIRST_HIT_GIVEUP_MS = 4000;
    /** Kilit bozuk sayilan (menzil disinda kaldigi icin) NPC'de en fazla bu kadar kilit yenilenir. */
    static final int BUGGED_RESETS = 3;
    /** Zayif NPC Once: aday NPC'nin cani vurulan hedefin su anki caninin bu oranindan az olmali. */
    static final double WEAK_RATIO = 0.5;
    /** Bir asil hedef icin en fazla bu kadar araya NPC alinir (asil hedef elden gitmesin). */
    static final int WEAK_MAX = 2;
    /** "Menzil disi" mesajlariyla saldiri mesafesi en fazla bu orana iner. */
    static final double OOR_MIN_SCALE = 0.8;

    final Context context;
    final Targeting targeting;

    /** Yarida kalan NPC (tamir / dusman yuzunden birakildi): hala yasiyorsa ona donulur. */
    int halfId = -1;
    long halfUntil;

    /** Su an calisan is (bitir-sonra-tamir ve PET icin). */
    CombatJob active;

    /** NPC turune gore calisan saldiri mesafesi orani (hasar alamayan hedefte dusurulen, oturum boyunca). */
    final Map<String, Double> scale = new HashMap<>();
    /** Ulasilamayan NPC bolgeleri (engel): bu bolgede dogan NPC'ler bir sure secilmez. */
    final List<double[]> unreachable = new ArrayList<>();
    /** Alinamayan kutular (id -> bitis): ayni kutuya sonsuza kadar gidilmez. */
    final Map<Integer, Long> badBoxes = new HashMap<>();
    int boxFocus = -1;
    long boxFocusAt;

    /** Hedeflenen kutu: alinana (kaybolana) kadar degistirilmez; DarkBot'un tekrar deneme suresinde de korunur. */
    boolean committed(Box b) {
        if (b == null || !b.isValid()) return false;
        if (Context.isResourceBox(b) && context.cargoFull()) return false; // kargo doldu: birakilir
        Long until = badBoxes.get(b.getId());
        return until == null || until < Context.now();
    }

    /** Toplanabilir ve son 60 sn icinde alinamayan kutu degil. */
    boolean boxOk(Box b) {
        if (!context.collectable(b)) return false;
        // Kutu en fazla uzaklik (menude): haritanin obur ucundaki kutulara kosulmaz.
        if (context.hero.distanceTo(b) > context.config.BOX_MAX_DIST) return false;
        Long until = badBoxes.get(b.getId());
        return until == null || until < Context.now();
    }

    /** Kutuya yaklasma takibi: mesafe azaldikca sure sifirlanir (uzak kutuya da gidilebilir). */
    double boxBestDist;
    long boxProgressAt, boxSeenAt;

    /**
     * Kutu sadece ilerleme yoksa atlanir (sabit en fazla sure yok, haritanin uzagindaki kutuya da gidilir):
     * 8 sn boyunca kutuya 100 birim bile yaklasilamadiysa ya da kutunun yanindayken 8 sn icinde alinamadiysa 60 sn atlanir.
     */
    void boxFocus(Box b, long now) {
        double d = context.hero.distanceTo(b);
        // Araya baska is girdiyse (dovus, kacis, toplama beklemesi) sure bastan baslar.
        if (b.getId() == boxFocus && now - boxSeenAt > 1500) {
            boxBestDist = d;
            boxProgressAt = now;
        }
        boxSeenAt = now;
        if (b.getId() != boxFocus) {
            boxFocus = b.getId();
            boxFocusAt = now;
            boxBestDist = d;
            boxProgressAt = now;
            badBoxes.values().removeIf(t -> t < now);
            return;
        }
        if (d < boxBestDist - 100) {
            boxBestDist = d;
            boxProgressAt = now;
        }
        boolean near = d < 300;
        long stuck = now - boxProgressAt;
        if (stuck > 8000) {
            badBoxes.put(b.getId(), now + 60_000);
            boxFocus = -1;
            Log.i("COMBAT", b.getTypeName() + " " + Log.loc(b) + (near ? " yanindayken alinamadi" : " kutuya yaklasilamiyor (engel / yol yok)")
                    + " (" + (now - boxFocusAt) / 1000 + " sn), 60 sn atlaniyor");
        }
    }

    public CombatLogic(Context context, Targeting targeting) {
        this.context = context;
        this.targeting = targeting;
        // RSB: kararli ates (yayda, menzilde, ilk isabet alinmis, NPC son 1,5 sn'de hasar almis) ve RSB disi ates sorunu nedeni.
        context.rsbStable = n -> {
            CombatJob k = active;
            return k != null && k.rsbStable(n);
        };
        context.rsbExcuse = n -> {
            CombatJob k = active;
            return k == null ? null : k.rsbExcuse(n);
        };
        // Dovusun sectigi hedef (yaklasirken saldiri hedefi henuz kurulmamis olabilir): bulucu ayni NPC'ye tekrar gitmesin.
        context.combatTargetId = () -> {
            CombatJob k = active;
            return k == null ? -1 : k.npcId;
        };
    }

    public boolean enabled() {
        return context.config.COMBAT.ENABLED;
    }

    /** Su an dovusuluyor mu (PET icin). */
    public boolean fighting() {
        CombatJob k = active;
        return k != null && k.npcId >= 0 && k.phase != Phase.BOX;
    }

    // ---------------------------------------------------------------- baslatma

    /** Combat Plus modu: kutu toplanacak mi (Sadece NPC disinda). */
    boolean collectBoxes() {
        return context.botMode() != BotPlusConfig.BotMode.NPC;
    }

    boolean npcs() {
        return context.botMode() != BotPlusConfig.BotMode.BOX;
    }

    /** Combat Plus acikken her zaman tam kontrol isi doner (dovus, kutu ya da dolasma). */
    public PlusModule.Job check() {
        if (!enabled()) return null;
        return new CombatJob(this);
    }

    /** Kesilebilir mi: tabloda kesiliyor, yok sayilmamis (bizim kisa tutmamiz haric), baskasi vurmuyor. */
    boolean usable(Npc n) {
        if (!Context.killable(n)) return false;
        if (n.isBlacklisted() && !targeting.heldByUs(n)) return false;
        // DarkBot'un kurali: baskasinin vurdugu NPC (IGNORE_ATTACKED isaretli degilse) alinmaz.
        return Context.ignoresAttacked(n) || !context.attackedByOthers(n);
    }

    /** DarkBot'un sececegi NPC (bayraklar), ulasilabilir, mesafe sinirinda, hatirlanan dusmandan uzak. */
    /** HP Bekle (dolas, "HB isaretli"): can dusukken HB isaretli NPC'ler secilmez (CombatJob her secimde kurar). */
    boolean skipHpFlagged;

    boolean selectable(Npc n) {
        if (!usable(n)) return false;
        if (skipHpFlagged && n.getInfo() != null && n.getInfo().hasExtraFlag(BotPlusFlag.WAIT_HP)) return false;
        // Ucuz mesafe kontrolu once (getClosestDistance pahali).
        if (context.hero.distanceTo(n) > context.npcDistanceIgnore()) return false;
        // Sadece bizim tutmamiz (usable gecti): darkBotWouldKill yok sayilmis NPC'yi reddeder, kurallara tek tek
        // bakilir. Tutma burada kaldirilmaz (sadece kontrol); setTarget kaldirir.
        if (n.isBlacklisted() ? !heldWouldKill(n) : !context.darkBotWouldKill(n)) return false;
        if (context.closestDistance(n) >= 500) return false;
        if (inUnreachable(n)) return false;
        return !context.config.PROTECT.KILLER.ENABLED || context.killerZone(n, 2500) == null;
    }

    /** Bizim tuttugumuz (yok sayilmis) NPC icin DarkBot'un secim kurallari (Context.darkBotWouldKill ile ayni, yok sayma haric). */
    boolean heldWouldKill(Npc n) {
        if (!Context.killable(n) || !targeting.heldByUs(n)) return false;
        eu.darkbot.api.config.types.NpcInfo i = n.getInfo();
        try {
            if (Boolean.TRUE.equals(context.onlyKillPreferred.getValue()) && !context.move.isInPreferredZone(n)) return false;
        } catch (RuntimeException ignored) {
        }
        if (i.hasExtraFlag(NpcFlag.LEECH_ONLY) && !context.hero.hasEffect(eu.darkbot.api.game.enums.EntityEffect.ENERGY_LEECH))
            return false;
        if (i.hasExtraFlag(NpcFlag.ATTACK_SECOND) && !context.attackedByOthers(n)) return false;
        String user = n.getEntityInfo() == null ? "" : n.getEntityInfo().getUsername();
        return user.contains("Invoke") || !i.hasExtraFlag(NpcFlag.PASSIVE) || n.isAttacking(context.hero);
    }

    boolean inUnreachable(Npc n) {
        long now = Context.now();
        Iterator<double[]> it = unreachable.iterator();
        while (it.hasNext()) {
            double[] z = it.next();
            if (z[2] < now) {
                it.remove();
                continue;
            }
            if (Math.hypot(n.getX() - z[0], n.getY() - z[1]) < UNREACH_ZONE && context.closestDistance(n) > 100) return true;
        }
        return false;
    }

    /** Dovus bu NPC'yi su an secebilir mi (bulucu: sinyaldeki NPC'yi dovus alamiyorsa isarete gidilir). */
    public boolean canTake(Npc n) {
        return enabled() && n != null && selectable(n);
    }

    /** Gemiye r yakininda secilebilir NPC var mi (PET savas modulunde kalsin mi). */
    public boolean selectableNear(double r) {
        for (Npc n : context.ents.getNpcs()) if (context.hero.distanceTo(n) <= r && selectable(n)) return true;
        return false;
    }

    /** Beklemesiz (HB / YB disi) secilebilir NPC var mi: tam kontrolde bekleme isi bunun icin kesilir. */
    public boolean freeTarget() {
        // Kill.select ile ayni engeller: secim yapilmayacaksa bekleme isi bosuna kesilmesin.
        if (!npcs() || context.fightThreat() != null) return false;
        if (Context.now() - context.refreshDueAt < 5000 && !context.config.RENEW.own()) return false;
        int min = context.config.COMBAT.FIGHT_MIN_HP;
        if (min > 0 && context.heroHp() < min / 100.0 && context.attackerOfHero() == null) return false;
        for (Npc n : context.ents.getNpcs())
            if (selectable(n) && (targeting.waitReasonPublic(n) == null || targeting.fought(n))) return true;
        return false;
    }

    /** Oyun "Menzil disi" dedi: dovusteyse saldiri mesafesi kucultulur (PET hasari "vuruyoruz" sandirmasin). */
    public void outOfRangeMessage() {
        CombatJob k = active;
        if (k != null) k.outOfRange();
    }

    /** Bitir, sonra tamir: kesim surerken tamir ertelenmeli mi. */
    public boolean finishFirst() {
        CombatJob k = active;
        if (k == null || !enabled() || !context.config.COMBAT.FINISH_FIRST) return false;
        return k.finishFirst();
    }

    // ---------------------------------------------------------------- hasar hizi olcumu

    /** Bir geminin can+kalkan dusus hizi (saniyede). En az 3 olcum ve 2 sn veri olmadan bilinmiyor sayilir. */
    static final class Rate {
        private double lastTotal = -1;
        private long lastAt, firstAt;
        private double rate;
        private int samples;

        public void sample(double total, long now) {
            if (lastTotal < 0) {
                lastTotal = total;
                lastAt = firstAt = now;
                return;
            }
            long dt = now - lastAt;
            if (dt < 500) return;
            if (dt > 2000) {
                // Uzun ara (baska is / bosta): aradaki dusus hiza katilmaz, sadece yeni baslangic noktasi.
                lastTotal = total;
                lastAt = now;
                return;
            }
            double r = Math.max(0, (lastTotal - total) * 1000.0 / dt);
            rate = samples == 0 ? r : rate * 0.6 + r * 0.4;
            samples++;
            lastTotal = total;
            lastAt = now;
        }

        /** Bu araliktaki dusus sayilmaz (ornegin baskasi da vuruyor): sadece yeni baslangic noktasi alinir. */
        public void skip(double total, long now) {
            if (lastTotal < 0) firstAt = now;
            lastTotal = total;
            lastAt = now;
        }

        public boolean known() {
            return samples >= 3 && lastAt - firstAt >= 2000;
        }

        public double perSec() {
            return known() ? rate : 0;
        }

        public void reset() {
            lastTotal = -1;
            rate = 0;
            samples = 0;
        }
    }

    static double total(eu.darkbot.api.game.entities.Ship s) {
        try {
            return s.getHealth().getHp() + s.getHealth().getShield();
        } catch (RuntimeException e) {
            return 0;
        }
    }

    static double maxTotal(eu.darkbot.api.game.entities.Ship s) {
        try {
            return Math.max(1, s.getHealth().getMaxHp() + s.getHealth().getMaxShield());
        } catch (RuntimeException e) {
            return 1;
        }
    }

    // ---------------------------------------------------------------- kor aci gozlemi (sadece log)

    // Ekrandaki taraf (NPC'ye gore gemi nerede): 0 = sag, saat yonunde (ekranda y asagi).
    static final String[] ABS = {"Sağ", "Sağ Alt", "Alt", "Sol Alt", "Sol", "Sol Üst", "Üst", "Sağ Üst"};
    // NPC'nin gittigi yone gore.
    static final String[] REL = {"önü", "sağ ön", "sağı", "sağ arka", "arkası", "sol arka", "solu", "sol ön"};
    static final long ANGLE_SAMPLE_MS = 500;
    // Bolge basina en az 6 sn veri, en az 3 bolgede (15 sn ile ~12 kesim surup hic ogrenmiyordu).
    static final double ANGLE_MIN_SEC = 10;
    /** Ogrenilen taraf icin en az kesim ve iki degisiklik arasi en az sure. */
    static final int ANGLE_MIN_KILLS = 5;
    static final long ANGLE_SWITCH_MS = 5 * 60_000;
    static final int ANGLE_MIN_SECTORS = 3;

    /** Bir NPC turu icin aci / mesafe bolgelerinde gecen sure ve alinan hasar (oturum boyunca). */
    static final class AngleStats {
        public final double[] absTime = new double[8], absDmg = new double[8];
        public final double[] relTime = new double[8], relDmg = new double[8];
        public final Map<Integer, double[]> band = new HashMap<>(); // 50'lik mesafe bandi -> {sure, hasar}
        public int kills;
        /** Guvenli Aci tarziyla yapilan kesim (deneme kesimi en az 10 kesimden sonra). */
        public int safeKills;
        /** NPC'nin bize hasar verdigi en uzak mesafe (Menzil Disindan Vur). */
        public double reach;
    }

    /** Ogrenilmis guvenli taraf. */
    static final class Learned {
        public final boolean relative;
        public final int sector;
        public final double rate, worst;

        public Learned(boolean relative, int sector, double rate, double worst) {
            this.relative = relative;
            this.sector = sector;
            this.rate = rate;
            this.worst = worst;
        }
    }

    /** En az hasarli bolge (en az 15 sn veri, en kotuden %40 az); yoksa null. */
    static Learned best(double[] time, double[] dmg, boolean relative) {
        int best = -1, worst = -1, count = 0;
        for (int i = 0; i < 8; i++) {
            if (time[i] < ANGLE_MIN_SEC) continue;
            count++;
            double rate = dmg[i] / time[i];
            if (best < 0 || rate < dmg[best] / time[best]) best = i;
            if (worst < 0 || rate > dmg[worst] / time[worst]) worst = i;
        }
        if (count < ANGLE_MIN_SECTORS || best < 0 || worst < 0 || best == worst) return null;
        double b = dmg[best] / time[best], w = dmg[worst] / time[worst];
        return w > 0 && b < w * 0.6 ? new Learned(relative, best, b, w) : null;
    }

    /** Yeterli verisi olan bolge sayisi (ekran ya da NPC yonune gore, hangisi fazlaysa). */
    int angleProgress(String name) {
        AngleStats a = angles.get(name);
        if (a == null) return 0;
        int x = 0, y = 0;
        for (int i = 0; i < 8; i++) {
            if (a.absTime[i] >= ANGLE_MIN_SEC) x++;
            if (a.relTime[i] >= ANGLE_MIN_SEC) y++;
        }
        return Math.max(x, y);
    }

    /** Kullanilan ogrenilmis taraf (NPC turu -> taraf) ve secildigi an: sik degismesin. */
    final Map<String, Learned> chosen = new HashMap<>();
    final Map<String, Long> chosenAt = new HashMap<>();

    /**
     * NPC turu icin ogrenilmis guvenli taraf. Hareketli NPC'de hasar NPC'nin yonune bagli oldugu icin once NPC'nin
     * yonune gore olan; en az 5 kesim ve her bolgede 10 sn veri olmadan karar yok. Secilen taraf ancak 5 dk sonra ve
     * yeni taraf belirgin daha iyiyse (%40 az hasar) degisir (eskiden her kesimde taraf donuyordu).
     */
    Learned learned(String name) {
        if (!context.config.COMBAT.SAFE.LEARN) return null;
        AngleStats a = angles.get(name);
        if (a == null || a.kills < ANGLE_MIN_KILLS) return chosen.get(name);
        Learned rel = best(a.relTime, a.relDmg, true), abs = best(a.absTime, a.absDmg, false);
        Learned cand = rel != null ? rel : abs;
        Learned cur = chosen.get(name);
        long now = Context.now();
        if (cand == null) return cur;
        if (cur == null) {
            chosen.put(name, cand);
            chosenAt.put(name, now);
            Log.i("KOR ACI", name + " guvenli taraf ogrenildi: " + (cand.relative ? "NPC'nin " + REL[cand.sector] : ABS[cand.sector])
                    + " (" + Math.round(cand.rate) + "/sn, en kotu " + Math.round(cand.worst) + "/sn)");
            return cand;
        }
        if (cand.relative == cur.relative && cand.sector == cur.sector) return cur;
        if (context.config.COMBAT.SAFE.LOCK) return cur; // Aciyi Degistirme: secilen taraf kilitli
        double[] t = cur.relative ? a.relTime : a.absTime, d = cur.relative ? a.relDmg : a.absDmg;
        double curRate = t[cur.sector] > 0 ? d[cur.sector] / t[cur.sector] : cur.rate;
        if (now - chosenAt.getOrDefault(name, 0L) < ANGLE_SWITCH_MS || cand.rate > curRate * 0.6) return cur;
        chosen.put(name, cand);
        chosenAt.put(name, now);
        Log.i("KOR ACI", name + " guvenli taraf degisti: " + (cand.relative ? "NPC'nin " + REL[cand.sector] : ABS[cand.sector])
                + " (" + Math.round(cand.rate) + "/sn, onceki " + Math.round(curRate) + "/sn)");
        return cand;
    }

    /** En az hasarli mesafe bandinin ortasi (en az 10 sn veri), yoksa -1. */
    double safeBand(String name, double min, double max) {
        AngleStats a = angles.get(name);
        if (a == null) return -1;
        double best = -1, bestRate = Double.MAX_VALUE;
        if (max < min) return -1;
        for (Map.Entry<Integer, double[]> e : a.band.entrySet()) {
            double lo = e.getKey() * 50, hi = lo + 50;
            double[] v = e.getValue();
            // Bant pencereyle kesisiyorsa sayilir, hedef pencereye kirpilir (eskiden bant ortasi pencere disindaysa
            // en az hasarli 600-650 bandi hic secilemiyordu).
            if (v[0] < 10 || hi <= min || lo >= max) continue;
            double rate = v[1] / v[0];
            if (rate < bestRate) {
                bestRate = rate;
                best = Math.max(min, Math.min(max, lo + 25));
            }
        }
        return best;
    }

    /**
     * En Iyi Aciyi Ogren: ekrandaki tarafta belirgin fark bulununca "Guvenli Taraf" ayari ona cevrilir
     * ("Aciyi Degistirme" aciksa degismez). NPC'nin yonune gore bulunan fark ayara yazilamaz, arka planda kullanilir.
     */
    void applyLearned(String name) {
        // Ogrenilen taraf menudeki "Guvenli Taraf" ayarina yazilmaz (Otomatik silinmesin); sadece Otomatik iken kullanilir.
        BotPlusConfig.SafeSettings c = context.config.COMBAT.SAFE;
        if (c.LOCK || context.config.COMBAT.STYLE != BotPlusConfig.CombatStyle.SAFE) return;
        if (c.SIDE == BotPlusConfig.SafeSide.AUTO) learned(name);
    }

    final Map<String, AngleStats> angles = new HashMap<>();

    static int sector(double deg) {
        deg = ((deg % 360) + 360) % 360;
        return (int) Math.floor((deg + 22.5) / 45) % 8;
    }

    /** Tablo: her bolge icin saniyede hasar ve gecen sure; yeterli veri olan en az / en cok hasarli bolge. */
    static String table(String[] names, double[] time, double[] dmg) {
        StringBuilder sb = new StringBuilder();
        int best = -1, worst = -1;
        for (int i = 0; i < 8; i++) {
            if (time[i] < 1) continue;
            double rate = dmg[i] / time[i];
            sb.append(names[i]).append(' ').append(Math.round(rate)).append("/sn(").append(Math.round(time[i])).append("sn) ");
            if (time[i] >= ANGLE_MIN_SEC) {
                if (best < 0 || rate < dmg[best] / time[best]) best = i;
                if (worst < 0 || rate > dmg[worst] / time[worst]) worst = i;
            }
        }
        if (best >= 0 && worst >= 0 && best != worst) {
            double b = dmg[best] / time[best], w = dmg[worst] / time[worst];
            boolean diff = w > 0 && b < w * 0.6;
            sb.append("| en az: ").append(names[best]).append(", en cok: ").append(names[worst])
                    .append(diff ? " -> belirgin fark var" : " -> belirgin fark yok");
        } else sb.append("| henuz yeterli veri yok (en az " + ANGLE_MIN_SECTORS + " bolgede " + (int) ANGLE_MIN_SEC + " sn)");
        return sb.toString();
    }

    void logAngles(String name) {
        AngleStats a = angles.get(name);
        if (a == null) return;
        StringBuilder bands = new StringBuilder();
        java.util.List<Integer> keys = new ArrayList<>(a.band.keySet());
        java.util.Collections.sort(keys);
        for (Integer k : keys) {
            double[] v = a.band.get(k);
            if (v[0] < 1) continue;
            bands.append(k * 50).append("-").append(k * 50 + 50).append(' ').append(Math.round(v[1] / v[0])).append("/sn(")
                    .append(Math.round(v[0])).append("sn) ");
        }
        Log.i("KOR ACI", name + " (" + a.kills + " kesim) | harita yonu: " + table(ABS, a.absTime, a.absDmg));
        Log.i("KOR ACI", name + " | NPC'nin yonune gore: " + table(REL, a.relTime, a.relDmg));
        Log.i("KOR ACI", name + " | mesafe: " + bands + (a.reach > 0 ? "| NPC'nin vurdugu en uzak: " + (int) a.reach : ""));
        applyLearned(name);
    }

    // ---------------------------------------------------------------- dolasma: kare tarama (Akilli Dolasma)

    /**
     * Harita PET radar menzili buyuklugunde karelere bolunur; gemi bir kareyi radar menziliyle gorunce kare kisa sure
     * (75 sn) "tarandi" sayilir. NPC'ler oldurulunce rastgele yerde dogdugu icin kalici hafiza tutulmaz.
     */
    static final long SCAN_KEEP_MS = 75_000;
    final Map<Long, Long> scanned = new HashMap<>();
    int scanMap = Integer.MIN_VALUE;
    long scanAt;

    /** Dolasma hedefi (isler arasinda korunur: bulucu / kutu araya girince kaybolmaz). */
    Location roamGoal;
    /** Kisa Dolasma ara adimi (hedefe dogru 1000-2000). */
    Location roamStep;
    /** Dolasma hedefine sadece bosta (dolasirken) gecen sure: dovus sirasinda sayilmaz. */
    long roamIdleMs, roamIdleTick;
    /** Hedef secildikten sonra dovus oldu: dolasma hedefi yenilenir (gemi baska yerde). */
    boolean roamDirty;
    /** Dolasma hedefinin secildigi yer (gidis yonu icin). */
    Location roamFrom;

    /** Birakilan NPC'nin bolgesi: bu sure icinde dolasma burada yapilir. */
    Location localCenter;
    /** Oturum boyunca toplam kesim (log icin). */
    int totalKills;
    long localUntil;
    /** Calisma haritasina girilen an (harita degisimi): PET / bulucu hazir olana kadar uzak dolasma secilmez. */
    long mapEnteredAt;
    /** Son kesim ani: kesimden hemen sonra yeni bulucu sinyali beklenir (uzak koseye gidilmez). */
    long lastKillAt;
    /** Son olen NPC'nin yeri: hemen ustundeki bulucu sinyali (eski NPC'nin yeri) yok sayilir. */
    Location lastKillLoc;

    int radar() {
        return context.config.PLUS.RADAR();
    }

    static long cellKey(long ix, long iy) {
        return (ix << 32) ^ (iy & 0xffffffffL);
    }

    /** Geminin radar menzilinin gordugu kareler taranmis sayilir (saniyede bir; her iste, bulucu dahil). */
    public void markScanned(long now) {
        if (now - scanAt < 1000) return;
        scanAt = now;
        int map = mapId();
        if (map != scanMap) {
            scanned.clear();
            scanMap = map;
            roamGoal = roamStep = null;
            // Eski haritanin engel bolgeleri, birakilan NPC bolgesi ve yarim NPC'si yeni haritada gecersiz.
            unreachable.clear();
            localCenter = null;
            localUntil = 0;
            halfId = -1;
            mapEnteredAt = now;
        }
        double cell = radar();
        long cx = (long) Math.floor(context.hero.getX() / cell), cy = (long) Math.floor(context.hero.getY() / cell);
        for (long ix = cx - 2; ix <= cx + 2; ix++)
            for (long iy = cy - 2; iy <= cy + 2; iy++)
                if (Math.hypot((ix + 0.5) * cell - context.hero.getX(), (iy + 0.5) * cell - context.hero.getY()) <= cell)
                    scanned.put(cellKey(ix, iy), now);
        scanned.values().removeIf(t -> now - t > SCAN_KEEP_MS);
    }

    boolean recentlyScanned(Location l, long now) {
        double cell = radar();
        Long t = scanned.get(cellKey((long) Math.floor(l.getX() / cell), (long) Math.floor(l.getY() / cell)));
        return t != null && now - t < SCAN_KEEP_MS;
    }

    /**
     * Yeni dolasma hedefi: haritanin kenarina radar menzilinden yakin olmayan, son 75 sn taranmamis en yakin kare
     * (Akilli Dolasma kapaliysa rastgele bir kare). DarkBot'un tercih bolgesi varsa once oradaki kareler.
     */
    Location pickRoamGoal(long now) {
        Location g = pickRoamGoal(now, 2.0);
        return g != null ? g : pickRoamGoal(now, 0.9); // uzak kare kalmadiysa yakin kareler
    }

    /**
     * minCells: hedef en az bu kadar kare uzakta. Gidilen yone yakin kareler tercih edilir (geri donus cezali):
     * eskiden hedef 0,9 karede secilip 0,7'de "varildi" sayiliyor, gemi 2-4 sn'de bir ileri geri gidiyordu.
     */
    Location pickRoamGoal(long now, double minCells) {
        double cell = radar();
        // Onceki gidis yonu (onceki hedefin secildigi yerden hedefe).
        double heading = roamGoal != null && roamFrom != null
                ? Math.atan2(roamGoal.getY() - roamFrom.getY(), roamGoal.getX() - roamFrom.getX()) : Double.NaN;
        eu.darkbot.api.game.other.Area.Rectangle b = null;
        try {
            b = context.star.getCurrentMapBounds();
        } catch (RuntimeException ignored) {
        }
        if (b == null || b.getWidth() <= 0 || b.getHeight() <= 0)
            return context.ringPoint(context.hero, 2500 + ThreadLocalRandom.current().nextDouble() * 1500,
                    ThreadLocalRandom.current().nextDouble() * Math.PI * 2, Math.PI);
        // Kenar payi: max(radar, 6000) (harita kenarinda NPC az, radar kenari zaten gorur); harita buna yetmiyorsa
        // ortada en az bir kare genislik kalacak kadar, en az eski pay (radar, kucuk haritada boyutun dortte biri).
        double mx = edgeMargin(b.getWidth(), cell), my = edgeMargin(b.getHeight(), cell);
        double cxm = (b.getX() + b.getX2()) / 2, cym = (b.getY() + b.getY2()) / 2;
        double minX = b.getX() + mx, maxX = b.getX2() - mx, minY = b.getY() + my, maxY = b.getY2() - my;
        boolean smart = context.config.PLUS.SMART_ROAM();
        List<Location> all = new ArrayList<>(), zone = new ArrayList<>();
        for (long ix = (long) Math.floor(minX / cell); ix <= (long) Math.floor(maxX / cell); ix++) {
            for (long iy = (long) Math.floor(minY / cell); iy <= (long) Math.floor(maxY / cell); iy++) {
                Location l = Location.of(Math.max(minX, Math.min(maxX, (ix + 0.5) * cell)),
                        Math.max(minY, Math.min(maxY, (iy + 0.5) * cell)));
                // Varis esigi (0,7 kare) ile ayni ya da daha yakin kare secilmez: secilir secilmez "varildi" sayilmasin.
                if (context.hero.distanceTo(l) < cell * minCells || !context.move.canMove(l)) continue;
                if (smart && recentlyScanned(l, now)) continue;
                if (context.config.PROTECT.KILLER.ENABLED && context.killerZone(l, 2500) != null) continue;
                all.add(l);
                try {
                    if (context.move.isInPreferredZone(l)) zone.add(l);
                } catch (RuntimeException ignored) {
                }
            }
        }
        List<Location> pool = zone.isEmpty() ? all : zone;
        if (pool.isEmpty()) {
            if (smart && !scanned.isEmpty() && minCells < 1) {
                scanned.clear(); // hepsi yakin zamanda tarandi: bastan
                return pickRoamGoal(now, minCells);
            }
            return null;
        }
        if (!smart) return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
        // En yakin taranmamis kare (biraz rastgele: hep ayni sira olmasin).
        Location best = null;
        double bestScore = Double.MAX_VALUE;
        for (Location l : pool) {
            double score = context.hero.distanceTo(l) + ThreadLocalRandom.current().nextDouble() * cell * 0.5
                    + Math.hypot(l.getX() - cxm, l.getY() - cym) * 0.1; // esitlikte harita ortasina yakin kare
            // Geri donus cezasi: onceki gidis yonune ters kareler (Location.of acisi: hedefe dogru angleTo + PI).
            if (!Double.isNaN(heading)) score += cell * 1.5
                    * (1 - Math.cos(norm(Math.atan2(l.getY() - context.hero.getY(), l.getX() - context.hero.getX()) - heading)));
            if (score < bestScore) {
                bestScore = score;
                best = l;
            }
        }
        return best;
    }

    /** Bir eksende kenar payi: max(radar, 6000), harita izin verdigi kadar. */
    static double edgeMargin(double size, double cell) {
        double old = Math.min(cell, size / 4);
        double want = Math.max(cell, 6000);
        if (size - 2 * want < cell) want = (size - cell) / 2;
        return Math.max(old, want);
    }

    int mapId() {
        try {
            return context.star.getCurrentMap() == null ? -1 : context.star.getCurrentMap().getId();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    // ---------------------------------------------------------------- tam kontrol isi

    public enum Phase { APPROACH, FIRST_HIT, ARC, HOLD, BOX }


    static double norm(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a < -Math.PI) a += 2 * Math.PI;
        return a;
    }
}