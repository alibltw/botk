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
 * Combat tam kontrol isi (eski CombatPlus.Kill): hedef secimi, yaklasma, ilk vurus, yay / sabit / guvenli aci,
 * ates bekcisi, menzil, kutu ve hedef yokken dolasma. Paylasilan bilgiler (NPC turune gore ogrenilenler, dolasma
 * haritasi, ulasilamayan bolgeler) CombatLogic'te tutulur ve cp uzerinden okunur.
 */
public final class CombatJob implements PlusModule.Job {

    /** Paylasilan Combat bilgileri (yonetici). */
    final CombatLogic cp;

    public CombatJob(CombatLogic cp) {
        this.cp = cp;
        cp.active = this;
        Log.i("COMBAT", "tam kontrol basladi | " + cp.context.dangerInfo(cp.context.hero));
    }

    int npcId = -1;
    String npcName = "-";
    CombatLogic.Phase phase = CombatLogic.Phase.APPROACH;
    final long start = Context.now();
    long npcStart = Context.now(), approachStart = Context.now(), firstHitAt;
    int kills;

    // yay
    double arcCenter = Double.NaN;
    int arcDir = 1;
    long centerAt, flipAt;
    int sweeps;
    boolean inward;

    // konfig
    boolean run;
    long runSwitchAt;
    /** Bu hedefte saldiri konfigurasyonuna gecildi (yaklasmada dolasma konfigurasyonuna geri donulmez). */
    boolean attackCfg;

    // ilk vurus / hasar
    boolean firstHitLogged;
    double firstTotal = -1;
    double lastNpcTotal = -1, lastNpcHp = 1, lastNpcDist;
    Location lastNpcLoc;
    long lastDamageAt = Context.now(), lastStepAt;
    double localScale = 1;
    final CombatLogic.Rate npcRate = new CombatLogic.Rate(), heroRate = new CombatLogic.Rate();

    // kalabalik / coklu saldiri
    long crowdAt;
    double hpAt2s = -1;
    long hpAt2sTime;
    boolean slipping;
    /** Siyrilma basladiginda guvenli taraf en az bu ana kadar devre disi (saldiranlardan uzak orta kullanilir). */
    long slipUntil;

    // ates bekcisi: menzilde ama NPC hasar almiyor (kilit / ates kaybi)
    int wdTries;
    long wdAt, lastDropAt = Context.now(), lastShotAt;
    /** NPC'nin cani / kalkani en son gercekten dustugu an (0: bu hedefte hic). lastDropAt'ten farkli olarak bekci payi icermez. */
    long lastHitAt;
    /** Ates bekcisinin lazer tusuna en son bastigi an (600 ms icinde ikinci basis yok). */
    long lastKeyAt;
    /** Son tikteki saldiri mesafesi (menzil disi mesaji / RSB icin). */
    double curRadius;
    /** Menzil disi mesaji olmadan ates edilen sure (saldiri mesafesi geri buyutulur) ve son olcum. */
    long oorQuietMs, oorTickAt;
    /** Kucultulen saldiri mesafesi kanitli mi (oyunun "menzil disi" mesaji): sadece o zaman NPC turu icin hatirlanir. */
    boolean scaleEvidence;
    /** Bitir sonra tamir karari en son hangi hedef icin / ne olarak yazildi. */
    int finishLogId = -1;
    boolean finishLogOk;

    // kilit bozuk yenileme / menzil disi mesaji / araya alinan NPC sayisi
    int buggedResets, oorCount, weakSwitches, weakMain = -1, weakPick = -1;
    long oorFirstAt;

    // siradaki hedef / kutu
    int nextId = -1;
    long nextSearchAt;
    Box box;
    long boxUntil, boxStart;
    int boxTries;
    Location deathLoc;
    long deathAt;

    // guvenli aci
    long centerChangedAt;
    boolean safeOff, exploring, safeLogged, outLogged;
    long exitSince;

    /**
     * Guvenli Aci tarzinda yay ortasi (Location.of acisi): secili taraf ya da (Otomatik) ogrenilen taraf.
     * Ogrenilene kadar, arada baska aci denenirken ve hasar artinca NaN (normal yay).
     */
    double safeCenter(Npc n, long now) {
        if (style() != BotPlusConfig.CombatStyle.SAFE || safeOff || exploring) return Double.NaN;
        BotPlusConfig.SafeSettings c = cp.context.config.COMBAT.SAFE;
        double deg;
        String why;
        if (c.SIDE != null && c.SIDE.sector >= 0) {
            deg = c.SIDE.sector * 45;
            why = "secili taraf " + CombatLogic.ABS[c.SIDE.sector];
        } else {
            CombatLogic.Learned l = cp.learned(npcName);
            if (l == null) return Double.NaN; // ogrenene kadar yay
            if (l.relative) {
                if (Double.isNaN(lastHeading)) return Double.NaN;
                deg = lastHeading + l.sector * 45;
                why = "ogrenilen: NPC'nin " + CombatLogic.REL[l.sector];
            } else {
                deg = l.sector * 45;
                why = "ogrenilen: " + CombatLogic.ABS[l.sector];
            }
        }
        if (!safeLogged) {
            safeLogged = true;
            Log.i("KOR ACI", npcName + " guvenli aciya yerlesiliyor (" + why + ")");
        }
        // Gemi NPC'nin "deg" yonunde dursun: Location.of(npc, aci, r) = npc - (cos, sin) * r -> aci = deg + 180
        return Math.toRadians(deg) + Math.PI;
    }

    /** Guvenli taraf kontrolunun son calistigi an (500 ms'de bir). */
    long safeExitAt;
    /** Guvenli taraf hasar olcumu: sadece hedefin verdigi hasar (baska NPC / oyuncu vururken olcum alinmaz). */
    final CombatLogic.Rate safeRate = new CombatLogic.Rate();

    /** Hedeften baska NPC ya da oyuncu gemiye ates ediyor mu. */
    boolean othersOnHero(Npc n) {
        // Tik basina bir kez (ayni hedef icin): kor aci gozlemi ve guvenli taraf kontrolu ayni tikte cagirir.
        long tick = cp.context.tickNo();
        if (tick == othersTick && n.getId() == othersId) return othersVal;
        othersTick = tick;
        othersId = n.getId();
        return othersVal = scanOthersOnHero(n);
    }

    long othersTick = -1;
    int othersId = -1;
    boolean othersVal;

    boolean scanOthersOnHero(Npc n) {
        for (Npc o : cp.context.ents.getNpcs())
            if (o != n && o.isValid() && !Context.isFake(o) && o.isAttacking(cp.context.hero)) return true;
        for (Player p : cp.context.ents.getPlayers())
            if (p.getId() != cp.context.hero.getId() && p.isValid() && p.isAttacking(cp.context.hero)) return true;
        return false;
    }

    /**
     * Hasar Artarsa Acidan Cik: guvenli tarafta beklenenin 1,5 katindan fazla hasar 3 sn surerse bu NPC'de normal yay.
     * Sadece hedefin hasari sayilir; deneme kesiminde ve gemi guvenli tarafta degilken karar verilmez.
     */
    void checkSafeExit(Npc n, long now) {
        if (!cp.context.config.COMBAT.SAFE.EXIT || safeOff || exploring || style() != BotPlusConfig.CombatStyle.SAFE) return;
        if (phase != CombatLogic.Phase.ARC && phase != CombatLogic.Phase.HOLD) {
            exitSince = 0;
            return;
        }
        // Kor aci gozlemiyle ayni siklikta (500 ms): olcum her tik gerekmez.
        if (now - safeExitAt < CombatLogic.ANGLE_SAMPLE_MS) return;
        safeExitAt = now;
        double side0 = safeCenter(n, now);
        boolean onSide = !Double.isNaN(side0)
                && Math.abs(CombatLogic.norm(Location.of(n.getX(), n.getY()).angleTo(cp.context.hero) - side0)) <= CombatLogic.SAFE_HALF * 1.5;
        if (othersOnHero(n) || !onSide) {
            safeRate.skip(CombatLogic.total(cp.context.hero), now);
            exitSince = 0;
            return;
        }
        safeRate.sample(CombatLogic.total(cp.context.hero), now);
        if (!safeRate.known()) return;
        // Beklenen: kullanilan taraftaki olculmus hasar hizi (secili taraf ya da ogrenilen).
        BotPlusConfig.SafeSide side = cp.context.config.COMBAT.SAFE.SIDE;
        CombatLogic.AngleStats st = cp.angles.get(npcName);
        double expect = -1;
        if (side != null && side.sector >= 0) {
            if (st != null && st.absTime[side.sector] >= CombatLogic.ANGLE_MIN_SEC) expect = st.absDmg[side.sector] / st.absTime[side.sector];
        } else {
            CombatLogic.Learned l = cp.learned(npcName);
            if (l != null) expect = l.rate;
        }
        if (expect < 0) {
            exitSince = 0;
            return;
        }
        if (safeRate.perSec() > Math.max(expect * 1.5, expect + 2000)) {
            if (exitSince == 0) exitSince = now;
            if (now - exitSince > 3000) {
                safeOff = true;
                arcCenter = Double.NaN;
                Log.i("KOR ACI", npcName + " guvenli tarafta hedefin hasari artti (" + Math.round(safeRate.perSec()) + "/sn, beklenen "
                        + Math.round(expect) + "/sn): bu NPC'de normal yay");
            }
        } else exitSince = 0;
    }

    // kor aci gozlemi
    long angleAt;
    double angleTotal = -1;
    double lastHeading = Double.NaN;
    /** Kor aci gozleminde bir onceki olcumdeki mesafe (NPC'nin vurdugu en uzak mesafe icin). */
    double angleLastDist;

    /**
     * Kor aci gozlemi (dovus degismez, sadece log): yarim saniyede bir NPC'ye gore yon, mesafe ve alinan hasar.
     * Sayilmaz: bize baska NPC / oyuncu da ates ediyor, NPC kaciyor, HE ile menzil disi, gemi cani dusuk.
     */
    void observeAngle(Npc n, double d, long now) {
        if (now - angleAt < CombatLogic.ANGLE_SAMPLE_MS) return;
        long dt = angleTotal < 0 ? 0 : now - angleAt;
        angleAt = now;
        double angleDist = angleLastDist; // araligin basindaki mesafe
        angleLastDist = d;
        double totalNow = CombatLogic.total(cp.context.hero);
        double dmg = angleTotal < 0 ? 0 : Math.max(0, angleTotal - totalNow);
        angleTotal = totalNow;
        if (n.isMoving() && n.getDestination().isPresent()) {
            Location dst = n.getDestination().get();
            lastHeading = Math.toDegrees(Math.atan2(dst.getY() - n.getY(), dst.getX() - n.getX()));
        }
        if (dt <= 0 || dt > 2000) return;
        double floor = Math.max(0.40, cp.context.config.REPAIR.START_HP / 100.0 + 0.10);
        if (cp.context.heroHp() < floor || cp.targeting.fleeing() || phase == CombatLogic.Phase.APPROACH) return;
        if (othersOnHero(n)) return;
        CombatLogic.AngleStats a = cp.angles.computeIfAbsent(npcName, k -> new CombatLogic.AngleStats());
        double sec = dt / 1000.0;
        double deg = Math.toDegrees(Math.atan2(cp.context.hero.getY() - n.getY(), cp.context.hero.getX() - n.getX()));
        int s1 = CombatLogic.sector(deg);
        a.absTime[s1] += sec;
        a.absDmg[s1] += dmg;
        if (!Double.isNaN(lastHeading)) {
            // NPC'nin yonune gore (ekranda y asagi: saga donus pozitif aci)
            int s2 = CombatLogic.sector(deg - lastHeading);
            a.relTime[s2] += sec;
            a.relDmg[s2] += dmg;
        }
        double[] b = a.band.computeIfAbsent((int) (d / 50), k -> new double[2]);
        b[0] += sec;
        b[1] += dmg;
        // Hasar araligin basindaki mesafede de alinmis olabilir: kucuk olan kaydedilir (reach sisirilmez).
        // Bilerek menzilden cikarken (ve hemen sonrasinda) kaydedilmez.
        double dFrom = angleDist > 0 ? Math.min(angleDist, d) : d;
        if (dmg > 0 && now >= outUntil + 1000) a.reach = Math.max(a.reach, dFrom);
    }

    // dolasma
    Location roamTo;

    void setTarget(Npc n, String why) {
        if (n.getId() != npcId) cp.targeting.forgetFlee();
        npcId = n.getId();
        npcName = n.getInfo().getName();
        phase = CombatLogic.Phase.APPROACH;
        npcStart = approachStart = Context.now();
        flipAt = 0;
        arcCenter = Double.NaN;
        firstHitLogged = false;
        firstTotal = -1;
        lastNpcTotal = -1;
        lastNpcHp = Context.hp(n);
        lastNpcDist = cp.context.hero.distanceTo(n);
        lastNpcLoc = Location.of(n.getX(), n.getY());
        lastDamageAt = lastDropAt = Context.now();
        lastShotAt = 0;
        lastHitAt = 0;
        attackCfg = false;
        localScale = cp.scale.getOrDefault(npcName, 1.0);
        scaleEvidence = cp.scale.containsKey(npcName);
        npcRate.reset();
        safeRate.reset();
        // hedefe ozel durum: menzilden cikis, siyrilma, can olcumu, ates bekcisi, menzil disi sayaci
        outUntil = lastOutAt = 0;
        slipping = false;
        slipUntil = 0;
        hpAt2s = -1;
        hpAt2sTime = 0;
        wdTries = 0;
        wdAt = 0;
        oorQuietMs = 0;
        oorTickAt = 0;
        oorFirstAt = 0;
        cp.roamDirty = true;
        nextId = -1;
        buggedResets = 0;
        oorCount = 0;
        // Araya alinan NPC'ye ya da asil hedefe donuste sayac korunur; baska bir hedefte sifirlanir.
        if (n.getId() != weakMain && n.getId() != weakPick) {
            weakSwitches = 0;
            weakMain = -1;
        }
        sweeps = 0;
        inward = false;
        angleTotal = -1;
        lastHeading = Double.NaN;
        safeOff = false;
        safeLogged = false;
        outLogged = false;
        exitSince = 0;
        centerChangedAt = 0;
        // Arada baska acilari dene: 10 kesimde bir normal yay (aci kilitli degilse), NPC davranisi degisirse fark edilsin.
        CombatLogic.AngleStats st = cp.angles.get(npcName);
        // Deneme kesimi: Guvenli Aci ile en az 10 kesim yapildiktan sonra, 10 kesimde bir (stil acilir acilmaz degil).
        exploring = !cp.context.config.COMBAT.SAFE.LOCK && st != null && style() == BotPlusConfig.CombatStyle.SAFE
                && st.safeKills >= 10 && st.safeKills % 10 == 0;
        if (exploring && style() == BotPlusConfig.CombatStyle.SAFE)
            Log.i("KOR ACI", npcName + " bu kesimde baska acilar deneniyor (10 kesimde bir)");
        cp.targeting.release(n);
        // Baska bir NPC'ye ates suruyorsa kesilir (yeni hedefe giderken eski NPC'ye vurulmaya devam edilmesin).
        try {
            Object ht = cp.context.hero.getTarget();
            if (ht instanceof Npc && ((Npc) ht).getId() != n.getId() && cp.context.hero.isAttacking((Npc) ht))
                cp.context.attack.stopAttack();
        } catch (RuntimeException ignored) {
        }
        // DarkBot'un 20 sn "bozuk kilit" sayaci hedef degisince baslar: beklemeden gelen ayni hedefte de sifirlansin.
        if (Objects.equals(cp.context.attack.getTarget(), n)) cp.context.attack.setTarget(null);
        cp.context.attack.setTarget(n);
        Log.i("COMBAT", "HEDEF " + Log.npc(n) + " (" + why + ") uzaklik " + (int) cp.context.hero.distanceTo(n) + " menzil "
                + (int) n.getInfo().getRadius() + (localScale < 1 ? " (saldiri mesafesi %" + Math.round(localScale * 100) + ")" : "")
                + " | " + cp.context.dangerInfo(cp.context.hero));
    }

    void dropTarget(String why) {
        if (npcId >= 0) Log.i("COMBAT", npcName + " #" + npcId + " birakildi: " + why);
        cp.targeting.forgetFlee();
        Npc was = npcId >= 0 ? cp.context.findNpc(npcId) : null;
        if (was != null) {
            // Birakilan NPC'nin bolgesinde kalinir (20 sn): yakinda baska NPC cikabilir ya da yok sayma bitince
            // ayni NPC'ye donulur; haritanin obur ucuna gidilmez.
            cp.localCenter = Location.of(was);
            cp.localUntil = Context.now() + 20_000;
        }
        npcId = -1;
        npcName = "-";
        phase = CombatLogic.Phase.APPROACH;
        run = false;
        // Ates de kesilir: hedefi bot tarafinda birakmak oyundaki kilidi / atesi durdurmaz (lazer eski NPC'de kaliyordu).
        try {
            if (was != null && cp.context.hero.isAttacking(was)) cp.context.attack.stopAttack();
        } catch (RuntimeException ignored) {
        }
        if (cp.context.attack.hasTarget()) cp.context.attack.setTarget(null);
    }

    @Override
    public String name() {
        return "COMBAT";
    }

    @Override
    public int priority() {
        if (phase == CombatLogic.Phase.BOX) return PlusModule.P_BOX;
        if (npcId >= 0) return PlusModule.P_COMBAT;
        // Kutuya gidilirken bulucu araya girmez (kutu - bulucu arasi gidip gelme olmasin). Once NPC modunda
        // sinyal kutudan once gelir: sadece 500'den yakin (almak uzere olunan) kutu bitirilir.
        if (!cp.committed(box) || !cp.collectBoxes()) return PlusModule.P_IDLE;
        boolean boxFirstMode = cp.context.botMode() == BotPlusConfig.BotMode.BOX_NPC;
        return boxFirstMode || cp.context.hero.distanceTo(box) < 500 ? PlusModule.P_BOX : PlusModule.P_IDLE;
    }

    @Override
    public String status() {
        if (npcId < 0) return phase == CombatLogic.Phase.BOX ? (box != null && box.isValid() ? "kutu: " + box.getTypeName()
                + " (" + eta(box) + " sn)" : "kutu bekleniyor")
                : roamStatus;
        Npc n = cp.context.findNpc(npcId);
        // Ilk satir kisa: hangi dovus + hedef + durum. Ikinci satir: lazer ve roket (arayuz satir satir yazar).
        String who = "Combat (" + styleName() + ") · ";
        String name = npcName.replace("(", "").replace(")", "").trim();
        if (n == null) return who + name + " " + phaseName() + weapons(null);
        if (phase == CombatLogic.Phase.APPROACH) {
            long sec = (long) Math.ceil(Math.max(0, cp.context.hero.distanceTo(n) - n.getInfo().getRadius())
                    / Math.max(1, cp.context.hero.getSpeed()));
            return who + name + " yaklaşılıyor (" + sec + " sn)" + weapons(n);
        }
        // Arayuz (Swing) is parcaciginda cagrilir: ogrenme durumu tick'te hazirlanan metinden okunur (yan etki yok).
        return who + name + " " + phaseName() + " (" + (int) cp.context.hero.distanceTo(n) + ")" + learnInfo + weapons(n);
    }

    /**
     * Dolasirken durum satiri: ne arandigi ve neyin beklendigi (Modul Secimi, kutu, dusman bulucu).
     * Ornek: "NPC aranıyor · kutu görülürse alınacak · düşman bulucu sinyal bekliyor".
     */
    String searchInfo() {
        boolean npc = cp.npcs(), box = cp.collectBoxes();
        StringBuilder b = new StringBuilder(npc ? "NPC aranıyor" : box ? "kutu aranıyor" : "bekleniyor");
        if (npc && box) b.append("\nKutu görülürse alınacak");
        if (npc && cp.context.config.LOCATOR.GO) {
            boolean loc;
            try {
                loc = cp.context.pet.isActive() && cp.context.pet.getGear() == eu.darkbot.api.game.enums.PetGear.ENEMY_LOCATOR;
            } catch (RuntimeException e) {
                loc = false;
            }
            b.append(loc ? "\nDüşman bulucu aktif, sinyal bekleniyor" : "\nDüşman bulucu bekleniyor (PET bulucuda değil)");
        }
        if (cp.context.idleRepair.getAsBoolean()) b.append("\nDolaşırken tamir");
        return b.toString();
    }

    /** Durum satirindaki aci ogrenme bilgisi (fight() her tik gunceller; status() sadece okur). */
    volatile String learnInfo = "";

    void updateLearnInfo() {
        String learn = "";
        if (style() == BotPlusConfig.CombatStyle.SAFE && cp.context.config.COMBAT.SAFE.SIDE == BotPlusConfig.SafeSide.AUTO
                && cp.learned(npcName) == null)
            learn = " · açı öğreniliyor " + Math.min(CombatLogic.ANGLE_MIN_SECTORS, cp.angleProgress(npcName)) + "/" + CombatLogic.ANGLE_MIN_SECTORS + " bölge";
        learnInfo = learn;
    }

    String roamStatus = "NPC aranıyor";

    /** Varis suresi (sn, yukari yuvarlanmis). */
    long eta(eu.darkbot.api.game.other.Locatable l) {
        return (long) Math.ceil(Math.max(0, cp.context.hero.distanceTo(l) - 200) / Math.max(1, cp.context.hero.getSpeed()));
    }

    /** Durumun ikinci satiri: "Lazer: UCB-100 · Roket: PLT-2026" (ates yoksa belirtilir). */
    String weapons(Npc n) {
        try {
            eu.darkbot.api.game.items.SelectableItem.Laser l = cp.context.hero.getLaser();
            eu.darkbot.api.game.items.SelectableItem.Rocket r = cp.context.hero.getRocket();
            String laser = l == null ? "-" : l.name().replace('_', '-');
            String rocket = r == null ? "-" : r.name().replace('_', '-');
            boolean firing = n != null && cp.context.hero.isAttacking(n);
            return "\nLazer: " + laser + (n != null && !firing ? " (ateş yok)" : "") + " · Roket: " + rocket;
        } catch (RuntimeException e) {
            return "";
        }
    }

    String styleName() {
        switch (style()) {
            case CIRCLE: return "Tam Tur";
            case HOLD: return "Sabit";
            case SAFE: return "Güvenli Açı";
            case OUTRANGE: return "Menzil Dışından";
            default: return "Yay";
        }
    }

    String phaseName() {
        switch (phase) {
            case APPROACH: return "yaklaşılıyor";
            case FIRST_HIT: return "ilk vuruş";
            case ARC: return slipping ? "çoklu saldırıdan sıyrılıyor" : inward ? "yay (içeri)" : "yay";
            case HOLD: return "sabit (dönme yok)";
            default: return "kutu toplanıyor";
        }
    }

    /** DarkBot son 5 sn icinde yenileme / mola istedi. */
    boolean refreshDue() {
        return Context.now() - cp.context.refreshDueAt < 5000;
    }

    /** Hiz (kacis) konfigurasyonu istensin mi (BotPlus mod secicisi). */
    public boolean wantsRun() {
        return run;
    }

    /** DarkBot yenileme / mola istedi: dovus yokken izin verilir (Yenileme ayarina gore). */
    @Override
    public boolean canRefresh() {
        long now = Context.now();
        // DarkBot sadece zaman gelince sorar: istek kaydedilir, NPC olunce siradakine gecilmez (bosluk acilir).
        cp.context.refreshDueAt = now;
        if (npcId >= 0 || phase == CombatLogic.Phase.BOX || now < boxUntil) return false;
        if (cp.context.fightThreat() != null) return false;
        if (cp.context.config.RENEW.own()) {
            // Cikis Yapip Yenile: DarkBot dogrudan yenilemez, Yenileme isi uzaklasip cikis yapar.
            if (cp.context.config.RENEW.MODE == BotPlusConfig.RefreshMode.LOGOUT) {
                cp.context.refreshWantedAt = now;
                return false;
            }
            Location here = Location.of(cp.context.hero.getX(), cp.context.hero.getY());
            eu.darkbot.api.game.other.Locatable dn = cp.context.nearestDanger(here);
            if (dn == null || dn.distanceTo(here) > 1500) return true;
            cp.context.refreshWantedAt = now; // Yenileme isi uzaklasip sonra izin verir
            return false;
        }
        // DarkBot gibi: kacis sistemi portala gider, orada beklerken yenilenir.
        cp.context.refreshingUntil = now + 10_000;
        return cp.context.safetyWaiting();
    }

    void phase(CombatLogic.Phase p, String why) {
        if (p == phase) return;
        if (p == CombatLogic.Phase.APPROACH) approachStart = Context.now();
        Log.d("COMBAT", npcName + " #" + npcId + " " + phase + " -> " + p + ": " + why);
        phase = p;
    }

    @Override
    public boolean tick() {
        long now = Context.now();
        if (!cp.enabled()) {
            Log.i("COMBAT", "ayar kapatildi, DarkBot'a birakiliyor");
            return false;
        }
        cp.context.pet.setEnabled(true);
        cp.markScanned(now);
        if (phase == CombatLogic.Phase.BOX) return boxTick(now);

        Npc n = npcId >= 0 ? cp.context.findNpc(npcId) : null;
        if (npcId >= 0 && n == null) return onGone(now);
        if (n != null) {
            String bad = invalid(n, now);
            if (bad != null) {
                dropTarget(bad);
                n = null;
            }
        }
        if (n == null) {
            n = boxFirst() ? null : select(now);
            if (n == null) return idle(now);
            String wait = cp.targeting.waitReasonPublic(n);
            if (wait != null && !cp.targeting.fought(n)) {
                // HB / YB: bekleme isi halleder (ayni tik baslar), bitince tam kontrol devam eder.
                if (!Objects.equals(cp.context.attack.getTarget(), n)) cp.context.attack.setTarget(n);
                Log.i("COMBAT", Log.npc(n) + " beklenmeli (" + wait + "), bekleme isine birakiliyor");
                return false;
            }
            setTarget(n, "secildi");
        }
        return fight(n, now);
    }

    // ------------------------------------------------------------ secim

    /** Kutu / NPC Onceligi (BotPlus ayari): yeni hedef secmeden once kutu toplansin mi. */
    boolean boxFirst() {
        if (cp.context.botMode() != BotPlusConfig.BotMode.BOX_NPC) return false;
        // Bize saldiran varken kutu tercih edilmez (once saldirana karsilik verilir).
        if (cp.context.attackerOfHero() != null) return false;
        for (Box x : cp.context.ents.getBoxes())
            if (cp.boxOk(x) && cp.context.hero.distanceTo(x) < 1500) return true;
        return false;
    }

    /** Hedef secimi: once yarim kalan NPC, sonra tablo onceligi, sonra varis suresi (hasarli NPC biraz once). */
    Npc select(long now) {
        if (!cp.npcs() || cp.context.fightThreat() != null) return null; // Sadece Kutu modunda NPC secilmez
        if (refreshDue() && !cp.context.config.RENEW.own()) {
            // Yenileme / mola: DarkBot gibi kacis sistemi portala goturur; bu sirada yeni hedef secilmez.
            Log.every("refreshidle", 5000, "COMBAT", "yenileme / mola zamani: yeni hedef secilmiyor");
            return null;
        }
        if (hpGate()) return null;
        // Bulucunun buldugu NPC (son 5 sn) secilebiliyorsa once o.
        if (cp.context.locatorFoundId >= 0 && now - cp.context.locatorFoundAt < 5000) {
            Npc f = cp.context.findNpc(cp.context.locatorFoundId);
            if (f != null && cp.selectable(f)) {
                Log.i("COMBAT", "bulucunun buldugu NPC seciliyor: " + Log.npc(f) + " " + (int) cp.context.hero.distanceTo(f) + " uzakta");
                return f;
            }
        }
        int skipHalf = -1; // tehlikedeki yarim NPC genel aramada da secilmez (dusuk cani yuzunden yine seciliyordu)
        if (cp.halfId >= 0) {
            Npc h = cp.context.findNpc(cp.halfId);
            if (now > cp.halfUntil || h == null) {
                Log.d("COMBAT", "yarim NPC #" + cp.halfId + " artik yok / sure doldu");
                cp.halfId = -1;
            } else if (cp.selectable(h)) {
                // Bizi birakmaya zorlayan dusman (son 30 sn ates etmis) NPC'nin yanindaysa ya da NPC tehlikeli
                // bolgedeyse donulmez (eskiden dusman hala oradayken donuluyordu); sure dolana kadar beklenir.
                String danger = halfDanger(h);
                if (danger == null) {
                    cp.halfId = -1;
                    Log.i("COMBAT", "yarim kalan NPC'ye donuluyor: " + Log.npc(h) + " " + (int) cp.context.hero.distanceTo(h) + " uzakta");
                    return h;
                }
                Log.every("halfdanger", 10_000, "COMBAT", "yarim kalan NPC'ye donulmuyor: " + Log.npc(h) + " " + danger);
                skipHalf = h.getId();
            }
        }
        Npc best = null, bestWait = null;
        double bestScore = Double.MAX_VALUE, bestWaitScore = Double.MAX_VALUE;
        int bestPri = Integer.MAX_VALUE, bestWaitPri = Integer.MAX_VALUE;
        for (Npc o : cp.context.ents.getNpcs()) {
            if (!cp.selectable(o) || o.getId() == skipHalf) continue;
            // Dusman yuzunden kacilan / birakilan bolgedeki NPC'ye yeni dovus baslatilmaz (bolge suresi bitene kadar).
            if (cp.context.inDangerZone(o) != null) continue;
            int pri = o.getInfo().getPriority();
            double score = reachTime(o) - (1 - Context.hp(o)) * 5;
            boolean wait = cp.targeting.waitReasonPublic(o) != null && !cp.targeting.fought(o);
            if (wait) {
                if (pri < bestWaitPri || (pri == bestWaitPri && score < bestWaitScore)) {
                    bestWait = o;
                    bestWaitPri = pri;
                    bestWaitScore = score;
                }
            } else if (pri < bestPri || (pri == bestPri && score < bestScore)) {
                best = o;
                bestPri = pri;
                bestScore = score;
            }
        }
        return best != null ? best : bestWait;
    }

    /** NPC'nin menziline varis suresi (sn): NPC bize geliyorsa daha kisa. */
    double reachTime(Npc o) {
        int heroSpeed = Math.max(1, cp.context.hero.getSpeed());
        double gap = Math.max(0, cp.context.hero.distanceTo(o) - o.getInfo().getRadius());
        double closing = 0;
        if (o.isMoving() && o.getDestination().isPresent()) {
            double toHero = o.angleTo(cp.context.hero), heading = o.angleTo(o.getDestination().get());
            closing = o.getSpeed() * Math.cos(CombatLogic.norm(heading - toHero));
        }
        return gap / Math.max(50, heroSpeed + closing);
    }

    /** DarkBot'un LootModule.ignoreInvalidTarget kurallari + BotPlus kurallari. null: gecerli. */
    String invalid(Npc n, long now) {
        if (!Context.killable(n)) return "artik kesilmiyor (tablo)";
        // Dusman oyuncu bize ates ediyor: NPC dovusu birakilir (yarim NPC olarak hatirlanir), bos is dusmandan
        // uzaklasir; tehdit gecince yarim NPC'ye donulur. (Eskiden dovus surup gemi oyuncuya patliyordu.)
        Player threat = cp.context.config.PROTECT.ON_PLAYER == BotPlusConfig.OnPlayer.CONTINUE ? null : cp.context.enemyThreat();
        if (threat != null && threat.isAttacking(cp.context.hero)) {
            cp.halfId = n.getId();
            cp.halfUntil = now + CombatLogic.HALF_MS;
            return "dusman oyuncu ates ediyor: " + Log.ship(threat) + " " + (int) threat.distanceTo(cp.context.hero)
                    + ", dovus birakildi, uzaklasiliyor";
        }
        // On kilitte uzaktan kilit + ates "vuruyor" gorunur: yaklasirken sadece son 3 sn'de gercek isabet varsa sayilir.
        boolean shooting = cp.context.shooting(n) && (phase != CombatLogic.Phase.APPROACH || now - lastHitAt < 3000);
        if (n.isBlacklisted() && !cp.targeting.heldByUs(n) && !shooting) return "yok sayildi (baska ozellik / DarkBot)";
        // Cal acik ve NPC calinabiliyorsa birakilmaz, yok sayilmaz: Cal'a devredilir (hemen baslar).
        // (Zaten biz vuruyorsak NPC bizim: birakilmaz, Cal'a da devredilmez.)
        if (!shooting && cp.context.attackedByOthers(n) && !Context.ignoresAttacked(n) && cp.context.stealCan.test(n))
            return "baskasi vuruyor, Cal'a devredildi";
        if (!Context.ignoresAttacked(n) && !shooting && cp.context.attackedByOthers(n)) {
            cp.context.bans.ban(n, Bans.Owner.COMBAT, 20_000); // DarkBot gibi
            Player p = cp.context.otherAttacker(n);
            return "baskasi vuruyor (" + (p == null ? "PET" : Log.ship(p)) + "), 20 sn yok sayiliyor";
        }
        double closest = cp.context.closestDistance(n);
        boolean locked = Objects.equals(cp.context.hero.getTarget(), n);
        if (!locked) {
            if (closest > 600) {
                cp.context.bans.ban(n, Bans.Owner.COMBAT, 1000);
                cp.context.hero.setLocalTarget(null);
                return "kilitlenmeden engelin icine girdi (" + (int) closest + ")";
            }
        } else {
            if (!cp.context.attack.isBugged() && !(Context.ignoresOwnership(n) || n.isOwned()) && cp.context.stealCan.test(n)) {
                // Kisa yok sayma: her tik yeniden secilip devredilmesin (Cal kendi isinde ele alir).
                cp.context.bans.ban(n, Bans.Owner.COMBAT, 2000);
                return "baskasinin sahipliginde, Cal'a devredildi";
            }
            // Kilit bozuk ama sebep menzil: NPC kaciyor ya da gemi lazer menzili disinda. NPC birakilmaz, hedef
            // yeniden verilir (DarkBot'un 20 sn sayaci sifirlanir) ve yaklasilir; en fazla 3 kez.
            if (cp.context.attack.isBugged() && (Context.ignoresOwnership(n) || n.isOwned()) && buggedResets < CombatLogic.BUGGED_RESETS
                    && (cp.targeting.fleeing() || cp.context.hero.distanceTo(n) > effectiveRadius(n) * 0.95 || Context.hp(n) < 0.25)) {
                buggedResets++;
                cp.context.attack.setTarget(null);
                cp.context.attack.setTarget(n);
                Log.i("COMBAT", Log.npc(n) + " kilit bozuk sayildi ama sebep menzil (uzaklik " + (int) cp.context.hero.distanceTo(n)
                        + ", " + cp.targeting.fleeInfo() + "): birakilmiyor, kilit yenilendi (" + buggedResets + "/" + CombatLogic.BUGGED_RESETS + ")");
                return null;
            }
            if (cp.context.attack.isBugged() || !(Context.ignoresOwnership(n) || n.isOwned())) {
                cp.context.bans.ban(n, Bans.Owner.COMBAT, 5000);
                cp.context.hero.setLocalTarget(null);
                return (cp.context.attack.isBugged() ? "kilit bozuk" : "baskasinin sahipliginde") + ", 5 sn yok sayiliyor (DarkBot kurali)";
            }
            if (closest > 650 && Context.hp(n) > 0.9) {
                cp.context.bans.ban(n, Bans.Owner.COMBAT, 5000);
                cp.context.hero.setLocalTarget(null);
                return "engelin derininde ve cani dolu, 5 sn yok sayiliyor (DarkBot kurali)";
            }
            try {
                if (closest > 500 && !n.isMoving() && (n.getHealth().shieldIncreasedIn(1000) || n.getHealth().shieldPercent() > 0.99)) {
                    cp.context.bans.ban(n, Bans.Owner.COMBAT, 5000);
                    cp.context.hero.setLocalTarget(null);
                    return "engelde kalkan dolduruyor, 5 sn yok sayiliyor (DarkBot kurali)";
                }
            } catch (RuntimeException ignored) {
            }
            String user = n.getEntityInfo() == null ? "" : n.getEntityInfo().getUsername();
            if (user.contains("Invoke") && n.getInfo().hasExtraFlag(NpcFlag.PASSIVE)
                    && Objects.equals(n, cp.context.hero.getLocalTarget()) && !cp.context.attack.isCastingAbility()) {
                cp.context.bans.ban(n, Bans.Owner.COMBAT, 600_000);
                cp.context.hero.setLocalTarget(null);
                return "Invoke pasif, 10 dk yok sayiliyor (DarkBot kurali)";
            }
        }
        if (cp.context.hero.distanceTo(n) > cp.context.npcDistanceIgnore() + (shooting ? 500 : 0))
            return (int) cp.context.hero.distanceTo(n) + " uzakta (DarkBot siniri " + cp.context.npcDistanceIgnore() + ")";
        if (cp.context.config.PROTECT.KILLER.ENABLED && !shooting) {
            String k = cp.context.killerZone(n, 2500);
            if (k != null) return "hatirlanan dusman " + k + " yakininda";
        }
        // Ulasilamayan NPC: yaklasma suresi beklenenin cok ustune cikti.
        if (phase == CombatLogic.Phase.APPROACH && !shooting) {
            double expect = cp.context.hero.distanceTo(n) / Math.max(1, cp.context.hero.getSpeed()) * 2500;
            if (now - approachStart > Math.max(CombatLogic.APPROACH_MIN_MS, expect) + 10_000) {
                cp.context.bans.ban(n, Bans.Owner.COMBAT, CombatLogic.UNREACH_IGNORE_MS);
                // Bolge sadece duran NPC'de hatirlanir: hareketli NPC'nin o anki yeri engel degildir (eskiden oradan
                // gecen baska NPC'ler de 3 dk ulasilamaz sayiliyordu).
                boolean moving = n.isMoving();
                if (!moving) cp.unreachable.add(new double[]{n.getX(), n.getY(), now + CombatLogic.UNREACH_ZONE_MS});
                return (now - approachStart) / 1000 + " sn'de menzile girilemedi (ulasilamiyor), 60 sn yok sayiliyor"
                        + (moving ? " (NPC hareketli, bolge hatirlanmadi)" : ", bolge 3 dk hatirlanacak");
            }
        }
        return null;
    }

    // ------------------------------------------------------------ dovus

    boolean fight(Npc n, long now) {
        heroRate.sample(CombatLogic.total(cp.context.hero), now);
        crowd(n, now);
        // Kalabalik kontrolu hedefi degistirmediyse ayni NPC kullanilir (tekrar aranmaz).
        Npc swapped = n.getId() == npcId && n.isValid() ? n : cp.context.findNpc(npcId);
        if (swapped == null) return onGone(now);
        n = swapped;
        npcRate.sample(CombatLogic.total(n), now);

        double tableR = effectiveRadius(n);
        // Saldiri mesafesi: tablo x "menzil disi" orani ile ogrenilmis lazer siniri, kucuk olan (carpilmaz: eskiden
        // ikisi ust uste binip mesafe ~530'a iniyordu).
        double radius = attackRadius(n);
        // Kacan NPC (Combat Plus'ta da): menzil ayardaki oran kadar dusurulur, en fazla X sn.
        boolean chase = chaseActive(n, now);
        if (chase) radius = Math.min(radius, tableR * (1 - cp.context.config.CHASE.DROP / 100.0));
        // DarkBot kurali (ayardan bagimsiz): NPC %25 altindaysa ya da kaciyorsa en fazla 600 (DarkBot'ta
        // modifyRadius). Yoksa kacan dusuk canli NPC lazer menzili disinda kalip 20 sn sonra "kilit bozuk" oluyordu.
        boolean fleeing = cp.targeting.fleeing();
        if (Context.hp(n) < 0.25 || fleeing) radius = Math.min(radius, CombatLogic.LOW_HP_RADIUS);
        double d = cp.context.hero.distanceTo(n);
        // Isabet sayimi: gemi tablo menzilinde (normal gidis %97), iceri gidiste ve kucultulmus menzilde sayilmaz.
        boolean count = (phase == CombatLogic.Phase.ARC || phase == CombatLogic.Phase.HOLD) && localScale >= 0.999 && d >= tableR * 0.95
                && now >= outUntil; // bilerek menzilden cikarken menzil ogrenilmez
        cp.targeting.observe(n, now, count);
        observeAngle(n, d, now);
        checkSafeExit(n, now);
        updateLearnInfo();
        lastNpcHp = Context.hp(n);
        lastNpcDist = d;
        lastNpcLoc = Location.of(n.getX(), n.getY());
        curRadius = radius;
        oorRecover(n, now);

        if (!noDamage(n, now, d, radius)) {
            dropTarget("hasar alamiyor");
            return true;
        }
        // Ates "var" gorunmesi yetmez (on kilitte uzaktan da gorunur): son 1,5 sn'de gercek isabet olmali.
        if (cp.context.shooting(n) && now - lastHitAt < 1500) lastShotAt = now;
        predictDeath(n, now);

        if (!Objects.equals(cp.context.attack.getTarget(), n)) cp.context.attack.setTarget(n);
        // On kilit: menzil disinda sadece kilitlenir; ates menzile girerken baslar. (Menzil disinda ates tusu
        // tekrar tekrar basilinca oyun atesi kapatip acabiliyordu: ilk vurusta lazer iptal oluyordu.)
        // Yaklasirken ve ilk vurusta ates sadece tablo menzili icinde acilir (eskiden menzil + 60, ilk vurusta mesafeye
        // hic bakilmiyordu: 613 menzilde 650-669'dan atis, "menzil disi"). Yayda / sabitte ates surer (kisa cikislar).
        boolean established = phase == CombatLogic.Phase.ARC || phase == CombatLogic.Phase.HOLD;
        boolean inReach = established || d <= Math.max(radius, firstHitRadius(n, false));
        if (inReach) cp.context.attack.tryLockAndAttack();
        else if ((cp.context.config.COMBAT.PRE_LOCK || d < radius * 1.5) && !cp.context.attack.isLocked()) cp.context.attack.tryLockTarget();
        fireWatchdog(n, d, radius, fleeing, now);
        if (!firstHitLogged && cp.context.shooting(n)) {
            if (firstTotal < 0) firstTotal = CombatLogic.total(n);
            else if (CombatLogic.total(n) < firstTotal - 1) {
                firstHitLogged = true;
                Log.d("COMBAT", npcName + " ilk isabet " + (now - npcStart) + " ms, uzaklik " + (int) d);
            }
        }

        Location predicted = predict(n);
        Location p;
        if (phase == CombatLogic.Phase.APPROACH ? d > Math.max(radius, firstHitLogged ? 0 : firstHitRadius(n, false)) * CombatLogic.APPROACH_OUT
                : d > Math.max(radius, phase == CombatLogic.Phase.FIRST_HIT ? firstHitRadius(n, false) : 0) * 1.3 && !stepOutActive(now)) {
            // Histerezis: yaya 1,15R icinde girilir, 1,3R disinda cikilir (sinirda her tik gidip gelmez).
            if (phase != CombatLogic.Phase.APPROACH) phase(CombatLogic.Phase.APPROACH, "menzil disi " + (int) d + " > " + (int) radius);
            p = chase || fleeing ? flankPoint(n, predicted, radius) : approachPoint(n, predicted, radius);
            if (d > radius * 1.3) { // yaklasirken menzilin 1,3 katina kadar yoldaki liste disi NPC'lerden kacinilir (eskiden 1500'de kapaniyordu)
                eu.darkbot.api.game.other.Locatable w = cp.context.avoidOnPath(p);
                p = Location.of(w.getX(), w.getY());
            }
            cp.context.speedAbility(d - radius, "NPC'ye yaklasma");
        } else {
            if (phase == CombatLogic.Phase.APPROACH) {
                // Once dogrudan vur: menzile girince NPC'ye dogru (yon degistirmeden) gidilir, ilk isabet gelince yay.
                // Kisa cikista (2 sn) ilk vurus tekrarlanmaz, yay ortasi korunur.
                boolean again = now - approachStart <= 2000 && firstHitLogged;
                if (!again) arcCenter = Double.NaN;
                phase(noCircle(n, radius) || style() == BotPlusConfig.CombatStyle.HOLD ? CombatLogic.Phase.HOLD
                        : again ? CombatLogic.Phase.ARC : CombatLogic.Phase.FIRST_HIT, "menzile girildi " + (int) d);
                firstHitAt = now;
                // Ates bekcisi payi menzile giristen baslar (yaklasma suresi "hasar yok" sayilmaz).
                lastDropAt = Math.max(lastDropAt, now);
                wdAt = now;
            }
            if (phase == CombatLogic.Phase.FIRST_HIT) {
                boolean hit = firstHitLogged;
                // Isabet gelmeden yaya gecilmez (yaninda donup vuramiyordu): 2 sn'de gelmezse biraz iceri, 4 sn'de yay.
                if (hit || now - firstHitAt > CombatLogic.FIRST_HIT_GIVEUP_MS) {
                    if (hit) Log.d("COMBAT", npcName + " ilk vurus isabet etti (" + (now - firstHitAt) + " ms), yaya geciliyor");
                        // Isabetsiz gecis sessiz kalmaz: ates bekcisi yayda da kilit / atesi yeniler.
                    else Log.i("COMBAT", npcName + " ilk vurus " + (now - firstHitAt) + " ms'de isabet etmedi (uzaklik " + (int) d
                            + ", kilit " + (cp.context.attack.isLocked() ? "var" : "yok") + ", ates " + (cp.context.shooting(n) ? "var" : "yok")
                            + "): yaya geciliyor, ates bekcisi izliyor");
                    phase(CombatLogic.Phase.ARC, hit ? "ilk isabet" : "ilk vurus suresi");
                }
            }
            // Ilk isabetten once guvenli tarafa dolanilmaz (1 sn donup NPC kaptiriliyordu): once dumduz vur, sonra aci.
            double safe = cp.context.config.COMBAT.SAFE.APPROACH && firstHitLogged ? safeCenter(n, now) : Double.NaN;
            if (phase == CombatLogic.Phase.FIRST_HIT) {
                // Dogrudan NPC'ye: lazerin yetistigi ilk mesafe (gecikirse biraz iceri).
                p = Location.of(predicted, Double.isNaN(safe) ? predicted.angleTo(cp.context.hero) : safe,
                        firstHitRadius(n, now - firstHitAt > CombatLogic.FIRST_HIT_CLOSER_MS));
            } else if (phase == CombatLogic.Phase.HOLD || noCircle(n, radius)) {
                if (phase != CombatLogic.Phase.HOLD) phase(CombatLogic.Phase.HOLD, "NO_CIRCLE");
                p = holdPoint(n, predicted, d, radius);
            } else if (style() == BotPlusConfig.CombatStyle.OUTRANGE && (p = outrangePoint(n, predicted, radius)) != null) {
                // Menzil Disindan Vur
            } else {
                p = arcPoint(n, predicted, d, radius, now);
            }
        }
        // Hasar Alirken Menzilden Cik: NPC'nin vurdugu mesafenin disina kisa cikis, sonra geri.
        if ((phase == CombatLogic.Phase.ARC || phase == CombatLogic.Phase.HOLD) && !chase && !fleeing) {
            Location o = outPoint(n, predicted, radius, now);
            if (o != null) p = o;
        }
        if (!cp.context.move.canMove(p)) p = searchValid(predicted, p, radius);
        p = sideBox(n, p, now);
        mode(n, p, d, radius, now);
        cp.context.moveTo(p);

        if (Log.due("kesimstat" + npcId, 3000))
            Log.i("COMBAT", Log.npc(n) + " " + phaseName() + " | uzaklik " + (int) d
                    + " menzil " + (int) tableR + (localScale < 1 ? " (saldiri mesafesi " + (int) radius + ")" : "")
                    + " | " + cp.targeting.fleeInfo() + (n.getDestination().isPresent() ? " hedefi " + Log.loc(n.getDestination().get()) : "")
                    + " | NPC hasar alimi " + (int) npcRate.perSec() + "/sn, gemi " + (int) heroRate.perSec() + "/sn"
                    + " | gemi " + Log.pct(cp.context.heroHp()) + " | " + (run ? "hiz" : "saldiri") + " konfig");
        return true;
    }

    /**
     * Dovus menzili: tablodaki menzil; oyunun "menzil disi" dedigi lazer siniri ogrenildiyse onu asmaz.
     * DarkBot'un bayrak / ekipman eklemeleri korunur: AGGRESSIVE_FOLLOW (%25 alti x0,75), Zephyr / Hecate bonusu.
     * (DarkBot'un 560 / 600 tavani uygulanmaz: mesafe tablodan.)
     */
    double effectiveRadius(Npc n) {
        return Math.min(tableRadius(n), capRadius(n));
    }

    /** Tablodaki menzil (DarkBot eklemeleriyle: AGGRESSIVE_FOLLOW, Zephyr / Hecate). */
    double tableRadius(Npc n) {
        double r = n.getInfo().getRadius();
        if (Context.hp(n) < 0.25 && n.getInfo().hasExtraFlag(NpcFlag.AGGRESSIVE_FOLLOW)) r *= 0.75;
        return r + abilityBonus();
    }

    /** Ogrenilmis lazer siniri (oyunun "menzil disi" dedigi mesafe) icerisi: olcumdur, ayardan bagimsiz. Yoksa sinirsiz. */
    double capRadius(Npc n) {
        int cap = Targeting.laserCap(n);
        return cap > 0 ? cap - CombatLogic.LASER_MARGIN + abilityBonus() : Double.MAX_VALUE;
    }

    /**
     * Yay / saldiri mesafesi: tablo menzili x calisan oran, ogrenilmis lazer siniri (capRadius) asilmaz.
     */
    double attackRadius(Npc n) {
        double table = tableRadius(n);
        return Math.min(table * localScale, capRadius(n));
    }

    /** Hasar Alirken Menzilden Cik: kisa cikis suruyor ya da yeni bitti (geri donus): yaklasmaya gecilmez. */
    boolean stepOutActive(long now) {
        return outUntil > 0 && now < outUntil + 2500;
    }

    /**
     * Ates bekcisi: menzilde (yay / sabit / ilk vurus), menzilden cikilmiyor, NPC kacmiyor, NPC'de ISH yok ama NPC'nin
     * can / kalkani max(1 sn, 2 x ping) suredir dusmuyor: (1 sn + ping)'de bir kilit -> ates -> 3. denemede hedef yenileme.
     */
    /**
     * Ates bekcisi esigi: lazer saniyede bir atar, ping ve RSB beklemesiyle iki isabet arasi 1-1,3 sn surer (eskiden
     * esik 1 sn'ydi: 514 uyarinin 392'si 1000-1249 ms arasindaydi, hepsi sahte alarm).
     */
    static final long WATCHDOG_MS = 1300;

    void fireWatchdog(Npc n, double d, double radius, boolean fleeing, long now) {
        long ping = ping();
        if (phase == CombatLogic.Phase.FIRST_HIT) radius = Math.max(radius, firstHitRadius(n, false));
        boolean watch = (phase == CombatLogic.Phase.ARC || phase == CombatLogic.Phase.HOLD || phase == CombatLogic.Phase.FIRST_HIT) && d <= radius
                && now >= outUntil && !fleeing && !ish(n) && now - lastDropAt > WATCHDOG_MS + ping;
        if (!watch) {
            if (now - lastDropAt <= WATCHDOG_MS + ping) wdTries = 0;
            return;
        }
        if (now - wdAt < 1000 + ping) return;
        wdAt = now;
        wdTries++;
        boolean locked = cp.context.attack.isLocked();
        boolean attacking = cp.context.hero.isAttacking(n);
        String act;
        if (!locked) {
            cp.context.attack.tryLockAndAttack();
            act = "kilit yok, yeniden kilit";
        } else if (!attacking) {
            // triggerLaserAttack dogrudan basilmaz (tus ac / kapa: AttackAPI'nin az once actigi atesi kapatabilir).
            cp.context.attack.tryLockAndAttack();
            act = "kilit var ates yok, yeniden ates";
        } else if (wdTries % 3 == 0 && phase != CombatLogic.Phase.FIRST_HIT && lastHitAt > 0 && now - cp.context.lastOutOfRangeAt >= 1500
                && now - lastKeyAt >= 600) {
            // Lazeri kapat / ac: tek basis kapatir, AttackAPI 500 ms sonra yeniden acar (hedefi yeniden vermek
            // oyunda bir sey degistirmiyordu). Menzil disi mesaji yeniyse basilmaz (sorun mesafe).
            lastKeyAt = now;
            cp.context.hero.triggerLaserAttack();
            act = "ates var gorunuyor ama hasar yok, lazer kapatilip acildi";
        } else act = "ates var gorunuyor ama hasar yok, bekleniyor";
        Log.i("COMBAT", Log.npc(n) + " menzilde ama ates yok (" + (now - lastDropAt) + " ms hasar yok, uzaklik " + (int) d
                + " / " + (int) radius + ", ping " + ping + "): " + act + " (" + wdTries + ". deneme)");
    }

    /** NPC'de ISH (hasar almaz) etkisi var mi. */
    boolean ish(Npc n) {
        try {
            return n.hasEffect(eu.darkbot.api.game.enums.EntityEffect.NPC_ISH) || n.hasEffect(eu.darkbot.api.game.enums.EntityEffect.ISH);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * Menzil disi mesaji olmadan 20 sn ates edildiyse kucultulmus saldiri mesafesi %5 geri buyutulur (en fazla %100):
     * tek seferlik yanlis mesajlar mesafeyi kalici dusurmesin.
     */
    void oorRecover(Npc n, long now) {
        long dt = oorTickAt == 0 ? 0 : Math.min(1000, now - oorTickAt);
        oorTickAt = now;
        if (localScale >= 0.999 || !(phase == CombatLogic.Phase.ARC || phase == CombatLogic.Phase.HOLD) || !cp.context.shooting(n)) return;
        oorQuietMs += dt;
        if (oorQuietMs < 20_000) return;
        oorQuietMs = 0;
        localScale = Math.min(1.0, localScale + 0.05);
        if (localScale >= 0.999) cp.scale.remove(npcName);
        else if (scaleEvidence) cp.scale.put(npcName, localScale); // kanitsiz kucultme NPC turune yazilmaz
        Log.i("COMBAT", Log.npc(n) + " 20 sn menzil disi mesaji yok: saldiri mesafesi %" + Math.round(localScale * 100)
                + " (" + (int) attackRadius(n) + ")" + (scaleEvidence || localScale >= 0.999 ? ", " + npcName + " icin hatirlandi" : ", sadece bu hedefte"));
    }

    /** RSB: hedefe kararli ates ediliyor mu. */
    public boolean rsbStable(Npc n) {
        if (n == null || n.getId() != npcId || !firstHitLogged) return false;
        if (phase != CombatLogic.Phase.ARC && phase != CombatLogic.Phase.HOLD) return false;
        long now = Context.now();
        if (now < outUntil || cp.targeting.fleeing()) return false;
        if (cp.context.hero.distanceTo(n) > curRadius) return false;
        // Ates bekcisiyle ayni esik (1,3 sn + ping): isabetler arasi 1-1,25 sn oldugundan 1 sn esik saglikli
        // atista da RSB'yi sik sik reddediyordu.
        return now - lastDropAt <= WATCHDOG_MS + ping();
    }

    /** RSB: ates sorunu RSB disi bir nedenden mi; nedeni ya da null. */
    public String rsbExcuse(Npc n) {
        if (n == null || n.getId() != npcId) return null;
        if (phase == CombatLogic.Phase.APPROACH) return "yaklasiliyor";
        if (phase == CombatLogic.Phase.FIRST_HIT) return "ilk vurus";
        if (phase == CombatLogic.Phase.BOX) return "kutu";
        if (Context.now() < outUntil) return "menzilden cikiliyor";
        if (cp.targeting.fleeing()) return "NPC kaciyor";
        if (curRadius > 0 && cp.context.hero.distanceTo(n) > curRadius) return "menzil disi (" + (int) cp.context.hero.distanceTo(n) + " > " + (int) curRadius + ")";
        return null;
    }

    /**
     * Ilk vurus mesafesi: lazerin yetistigi ilk yer (lazer siniri - 50, bilinmiyorsa tablonun %90'i); daha icerisi
     * gerekmez. Dusuk canli / kacan NPC'de en fazla 600. Isabet gecikirse biraz daha iceri (%85).
     */
    double firstHitRadius(Npc n, boolean closer) {
        int cap = Targeting.laserCap(n);
        double r = cap > 0 ? Math.min(cap - CombatLogic.FIRST_HIT_MARGIN + abilityBonus(), tableRadius(n)) : tableRadius(n) * 0.9;
        // Kucultulmus saldiri mesafesinin disinda ilk vurus yapilmaz (attackRadius bunu cagirmaz: dongu yok).
        r = Math.min(r, attackRadius(n));
        if (Context.hp(n) < 0.25 || cp.targeting.fleeing()) r = Math.min(r, CombatLogic.LOW_HP_RADIUS);
        return closer ? r * 0.85 : r;
    }

    long abilityAt = -1;
    double abilityVal;

    /** Zephyr/Hecate menzil bonusu: tick basina bir kez okunur (her menzil hesabinda item aranmaz). */
    double abilityBonus() {
        long t = Context.now();
        if (t - abilityAt < 100) return abilityVal;
        abilityAt = t;
        return abilityVal = readAbilityBonus();
    }

    double readAbilityBonus() {
        try {
            Optional<eu.darkbot.api.game.items.Item> it = cp.context.items.getItem(
                    eu.darkbot.api.game.items.SelectableItem.Ability.ZEPHYR_MMT, eu.darkbot.api.game.items.ItemFlag.AVAILABLE);
            if (!it.isPresent()) it = cp.context.items.getItem(eu.darkbot.api.game.items.SelectableItem.Ability.HECATE_PLUS_STOCKPILE,
                    eu.darkbot.api.game.items.ItemFlag.AVAILABLE);
            // Yigin basina +5 (en fazla 100): okunan miktar bozuksa menzil sacmalamasin.
            return it.map(i -> Math.max(0d, Math.min(100d, i.getQuantity() * 5))).orElse(0d);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Konfigurasyon: uzun yaklasmada hiz (5,5 sn degisim beklemesine gore), uzakta dolasma, menzilde saldiri. */
    void mode(Npc n, Location p, double d, double radius, long now) {
        double sec = Math.max(0, d - radius) / Math.max(1, cp.context.hero.getSpeed());
        boolean want = run;
        if (phase == CombatLogic.Phase.APPROACH && sec > CombatLogic.RUN_ON_SEC) want = true;
        else if (phase != CombatLogic.Phase.APPROACH || sec < CombatLogic.RUN_OFF_SEC) want = false;
        // DarkBot kurali: "dairede kacis konfigurasyonu" acik, NPC %25 alti ve hedef nokta uzaksa kacis.
        if (Boolean.TRUE.equals(Context.value(cp.context.runConfigInCircle, Boolean.FALSE)) && Context.hp(n) < 0.25
                && cp.context.hero.distanceTo(p) > n.getInfo().getRadius() * 2) want = true;
        // Hiz konfigurasyonu kapatma hemen (menzile girerken bekletilmez); acma 5,5 sn'de en fazla bir kez.
        if (want != run && (!want || now - runSwitchAt >= CombatLogic.RUN_SWITCH_MS)) {
            run = want;
            runSwitchAt = now;
            Log.d("COMBAT", npcName + (run ? " hiz konfigurasyonu (yaklasma ~" + (int) sec + " sn)" : " saldiri konfigurasyonu"));
        }
        if (run) return;
        // Saldiri formasyonu menzile 1,5 sn kala (formasyon degisimi varistan once biter); "Hedef Yakinsa NPC'den
        // Kacinma" acikken NPC'ye 1500 kala da saldiri formasyonuna gecilir.
        // DarkBot konfig degisimini 5,5 sn kilitler: dolasma konfigurasyonu sadece varis bu sureden (+1,5 sn) uzunsa,
        // yoksa yaklasma boyunca saldiri konfigurasyonu (gidip gelme olmasin).
        boolean near = sec < CombatLogic.RUN_SWITCH_MS / 1000.0 + 1.5
                || (cp.context.config.AVOID_NEAR_OFF && cp.context.hero.distanceTo(n) <= Context.AVOID_NEAR);
        // Bu hedefte saldiri konfigurasyonuna gecildiyse bir daha dolasmaya donulmez (NPC uzaklasip yaklasirken).
        boolean far = phase == CombatLogic.Phase.APPROACH && !near && !attackCfg;
        if (!far) attackCfg = true;
        if (far) cp.context.hero.setRoamMode();
        else cp.context.hero.setAttackMode(n);
    }

    // ------------------------------------------------------------ hareket

    /** NPC'nin tahmini yeri: oyundaki gercek hedefine dogru, ping + tik kadar ileri. */
    Location predict(Npc n) {
        long horizon = 250 + Math.min(350, ping());
        Optional<Location> dest = n.getDestination();
        if (n.isMoving() && dest.isPresent()) {
            Location from = Location.of(n.getX(), n.getY());
            double dist = from.distanceTo(dest.get());
            double move = n.getSpeed() * horizon / 1000.0;
            if (dist <= move) return Location.of(dest.get().getX(), dest.get().getY());
            return Location.of(from, from.angleTo(dest.get()), move);
        }
        return n.getLocationInfo().destinationInTime(horizon);
    }

    long ping() {
        try {
            return Math.max(0, cp.context.stats.getPing());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** Kacisi onceden karsilama: NPC kacacagi cana yaklastiysa (ogrenilmis) biraz daha yakin dur. */
    double fleePrep(Npc n, double r, double radius) {
        double fh = Targeting.fleeHp(n);
        if (fh > 0 && Context.hp(n) < fh + 0.05 && !cp.targeting.fleeing()) return Math.min(r, radius * CombatLogic.RANGE_BUFFER_IN);
        return r;
    }

    /**
     * Yaklasma: NPC gercek hedefine gidiyorsa once yolu kesilir (gemi NPC'den once yetisebilecegi ilk nokta);
     * yetisilemiyorsa NPC'nin varacagi yere onceden yerlesilir. Yakinlasinca yayin yonune teget girilir.
     */
    Location approachPoint(Npc n, Location predicted, double radius) {
        Optional<Location> dest = n.getDestination();
        int heroSpeed = Math.max(1, cp.context.hero.getSpeed());
        // NPC'nin uzerine duz gidilir; yol kesme / varis noktasina yerlesme sadece NPC bizden hizla uzaklasiyorsa
        // (eskiden her hareketli NPC'de yan noktaya gidilip ilk vurus gecikiyordu).
        boolean away = false;
        if (n.isMoving() && dest.isPresent() && n.getSpeed() >= heroSpeed * 0.5) {
            double heading = Math.atan2(dest.get().getY() - n.getY(), dest.get().getX() - n.getX());
            double toHero = Math.atan2(cp.context.hero.getY() - n.getY(), cp.context.hero.getX() - n.getX());
            away = Math.cos(heading - toHero) < 0;
        }
        if (away && n.getSpeed() > 0) {
            Location from = Location.of(n.getX(), n.getY());
            Location to = dest.get();
            double len = from.distanceTo(to);
            double npcArrive = len / n.getSpeed();
            double step = Math.max(0.1, npcArrive / 20);
            for (double t = step; t <= npcArrive; t += step) {
                Location at = Location.of(from, from.angleTo(to), n.getSpeed() * t);
                if ((cp.context.hero.distanceTo(at) - radius) / heroSpeed <= t) {
                    if (Log.dDue("intercept" + npcId, 3000)) Log.i("COMBAT", npcName + " yolu kesiliyor: " + Log.loc(at)
                            + " (" + String.format(java.util.Locale.ROOT, "%.1f", t) + " sn)");
                    return Location.of(at, at.angleTo(cp.context.hero), radius * 0.95);
                }
            }
            if (len > radius) {
                if (Log.dDue("arrive" + npcId, 3000)) Log.i("COMBAT", npcName + " " + Log.loc(to) + " noktasina gidiyor ("
                        + String.format(java.util.Locale.ROOT, "%.1f", npcArrive) + " sn), oraya onceden yerlesiliyor");
                return Location.of(to, to.angleTo(cp.context.hero), radius * 0.95);
            }
        }
        double r = fleePrep(n, radius * 0.95, radius);
        double a = predicted.angleTo(cp.context.hero);
        // Yaklasirken Guvenli Acidan Gir: sadece ilk isabetten sonra (menzilden cikip geri girerken); ilk giris dumduz.
        double safe = cp.context.config.COMBAT.SAFE.APPROACH && firstHitLogged ? safeCenter(n, Context.now()) : Double.NaN;
        if (!Double.isNaN(safe)) return Location.of(predicted, safe, r);
        if (cp.context.hero.distanceTo(predicted) < radius * 2.5) a += arcDir * 0.25; // teget giris
        return Location.of(predicted, a, r);
    }

    /** Guvenli mesafe penceresi: [lazer siniri - 120 .. - 30], lazer siniri bilinmiyorsa [%85 .. %100]. */
    double bandMin(Npc n, double radius) {
        int cap = Targeting.laserCap(n);
        return cap > 0 ? cap - 120 : radius * CombatLogic.RANGE_BUFFER_IN;
    }

    double bandMax(Npc n, double radius) {
        int cap = Targeting.laserCap(n);
        return cap > 0 ? cap - 30 : radius;
    }

    /** DarkBot kurali: NO_CIRCLE isaretli NPC'de donulmez, menzil 750'yi asinca isaret yok sayilir. */
    boolean noCircle(Npc n, double radius) {
        return n.getInfo().hasExtraFlag(NpcFlag.NO_CIRCLE) && radius <= 750;
    }

    /**
     * Menzil Disindan Vur: NPC'nin bize hasar verdigi en uzak mesafenin "Pay" kadar disinda, lazerin yettigi yerde durulur.
     * Bilinmiyorsa lazerin en ucunda (%98) durulup ogrenilir. NPC'nin menzili bizimkine yetisiyorsa null (normal yay).
     */
    Location outrangePoint(Npc n, Location predicted, double radius) {
        CombatLogic.AngleStats a = cp.angles.get(npcName);
        double reach = a == null ? 0 : a.reach;
        double max = radius * 0.98;
        double r = reach > 0 ? reach + cp.context.config.COMBAT.OUT.MARGIN : max;
        if (r > max) {
            if (!outLogged) {
                outLogged = true;
                Log.i("COMBAT", npcName + " " + (int) reach + " mesafeden de vuruyor, lazer menzili " + (int) radius
                        + ": menzil disindan vurulamiyor, normal yay");
            }
            return null;
        }
        if (!outLogged) {
            outLogged = true;
            Log.i("COMBAT", npcName + " menzil disindan vuruluyor: " + (int) r + (reach > 0 ? " (NPC'nin vurdugu en uzak " + (int) reach
                    + " + pay " + cp.context.config.COMBAT.OUT.MARGIN + ")" : " (lazer ucu, NPC'nin menzili ogreniliyor)"));
        }
        return Location.of(predicted, predicted.angleTo(cp.context.hero), fleePrep(n, r, radius));
    }

    Location holdPoint(Npc n, Location predicted, double d, double radius) {
        double r = d <= radius && d >= radius * 0.6 ? d : radius * 0.8;
        return Location.of(predicted, predicted.angleTo(cp.context.hero), fleePrep(n, r, radius));
    }

    /**
     * Yay: ortasi sabit (sadece tehlike degisince kayar), gemi aci kadar ileri geri gider.
     * Yon sadece yayin ucunda, NPC'nin onune gelince ya da engelde degisir (1 sn kilit).
     * Her ikinci gidiste tablo menzilinin %10'u kadar iceri (tampon icinde) gidilir.
     */
    BotPlusConfig.CombatStyle style() {
        return cp.context.config.COMBAT.STYLE;
    }

    Location arcPoint(Npc n, Location predicted, double d, double radius, long now) {
        BotPlusConfig.CombatStyle st = style();
        boolean arc = st != BotPlusConfig.CombatStyle.CIRCLE; // Yay, Guvenli Aci ve yedek olarak diger tarzlar
        // Coklu saldiridan siyrilirken (en az 3 sn) guvenli taraf kullanilmaz: saldiranlardan uzak orta.
        boolean slip = now < slipUntil;
        double safe = slip ? Double.NaN : safeCenter(n, now);
        double a = predicted.angleTo(cp.context.hero);
        if (!Double.isNaN(safe) && Double.isNaN(arcCenter)) {
            arcCenter = safe;
            centerAt = now;
            initDir(predicted, a);
        } else if (Double.isNaN(arcCenter)) {
            arcCenter = pickCenter(n, predicted, radius, true);
            centerAt = now;
            initDir(predicted, a);
        } else if (Double.isNaN(safe) && now - centerAt > CombatLogic.CENTER_REPICK_MS && (now - centerChangedAt > CombatLogic.CENTER_CHANGE_MIN_MS || slip)) {
            centerAt = now;
            double best = pickCenter(n, predicted, radius, false);
            if (!Double.isNaN(best) && Math.abs(CombatLogic.norm(best - arcCenter)) > Math.PI / 8) {
                Log.d("COMBAT", npcName + " yay ortasi tehlike yuzunden kaydi " + Math.round(Math.toDegrees(CombatLogic.norm(arcCenter)))
                        + " -> " + Math.round(Math.toDegrees(CombatLogic.norm(best))) + " derece");
                arcCenter = best;
                centerChangedAt = now;
            }
        }
        if (!Double.isNaN(safe)) arcCenter = safe; // Guvenli Aci: orta ogrenilen / secilen taraf
        // Olmek uzere (siradaki hazir): yay siradaki NPC'nin tarafina kayar (DarkBot Siradaki Hedef gibi), sadece yayda.
        Npc nx = phase == CombatLogic.Phase.ARC && nextId >= 0 ? cp.context.findNpc(nextId) : null;
        if (nx != null) arcCenter = predicted.angleTo(nx);
        double half = Double.isNaN(safe) ? Math.toRadians(arc ? cp.context.config.COMBAT.ARC.ANGLE : 360) / 2 : CombatLogic.SAFE_HALF;
        int npcSpeed = n.isMoving() ? Math.max(0, n.getSpeed()) : 0;
        int heroSpeed = Math.max(1, cp.context.hero.getSpeed());
        if (arc && Double.isNaN(safe) && heroSpeed < npcSpeed * 1.1) half *= 0.5;
        // Acisal adim: DarkBot'un formuluyle (gemi + NPC hizi), yaricapa bolunur; dar yayda yayin icinde kalir.
        double step = Math.max(0.05, Math.min(0.6, (heroSpeed * 0.625 + Math.max(200, npcSpeed) * 0.625) / Math.max(1, radius) * 0.5));
        boolean limited = arc && half < Math.PI;
        // Dar yayda bir gidis en az 1,2 sn surer (yoksa gemi yayin ucuna hemen varip duruyor, dur-kalk gidiyordu).
        if (limited) half = Math.max(half, Math.min(Math.PI * 0.45, heroSpeed * 1.2 / (2 * Math.max(1, radius))));
        if (limited) step = Math.min(step, Math.max(0.05, half * 0.6));
        if (limited && CombatLogic.norm(a - arcCenter) * arcDir > half * 0.85 && now - flipAt > CombatLogic.FLIP_MIN_MS) flip(now, "yay ucu");
        // NPC'nin onune gecme: hareketli NPC'nin gittigi yone girilecekse yon cevrilir.
        Optional<Location> dest = n.getDestination();
        if (n.isMoving() && dest.isPresent() && now - flipAt > CombatLogic.FLIP_MIN_MS) {
            double heading = predicted.angleTo(dest.get());
            double next = Math.abs(CombatLogic.norm(a + arcDir * step - heading));
            if (next < CombatLogic.FRONT_AVOID && next < Math.abs(CombatLogic.norm(a - heading))) flip(now, "NPC'nin onune gecmemek icin");
        }
        // Gidis basina hedef yaricap (normal %95, iceri gidiste %95 - %10). Kiris kisalmasi telafi edilir:
        // gemi ileri noktaya duz gidince yayin ortasinda r*cos(adim/2)'ye iner, o yuzden hedef bolu cos.
        double rTarget = inward ? Math.max(radius * CombatLogic.RANGE_BUFFER_IN, radius * (0.97 - CombatLogic.INWARD)) : radius * 0.97;
        if (!Double.isNaN(safe)) {
            // Guvenli mesafe: ogrenilen en az hasarli bant (tampon icinde), yoksa %97.
            double band = cp.safeBand(npcName, bandMin(n, radius), Math.min(radius, bandMax(n, radius)));
            rTarget = band > 0 ? band : radius * 0.97;
        }
        rTarget = fleePrep(n, rTarget, radius);
        // Yaricap duzeltmesi (DarkBot'un LootModule'u gibi, kapali dongu): gemi hedef yaricapin icindeyse nokta disari,
        // disindaysa iceri itilir; gemi yay boyunca rTarget cizgisinde kalir. Adim en fazla 0,3 rad: eskiden dar yayda
        // yayin bir ucundan obur ucuna duz gidiliyor, kirisin ortasi NPC'ye ~440'a iniyordu (menzil 590 iken).
        double dNow = cp.context.hero.distanceTo(predicted);
        double fix = Math.max(-radius / 2, Math.min(radius / 2, rTarget - dNow));
        double r = Math.max(100, rTarget + fix);
        double stepUse = Math.min(step, 0.3);
        Location p = Location.of(predicted, a + arcDir * stepUse, r);
        if (!cp.context.move.canMove(p)) {
            Location q = Location.of(predicted, a - arcDir * stepUse, r);
            boolean inside = !limited || Math.abs(CombatLogic.norm(a - arcDir * stepUse - arcCenter)) <= half;
            if (cp.context.move.canMove(q) && inside && now - flipAt > CombatLogic.FLIP_MIN_MS) {
                flip(now, "engel");
                p = q;
            } else p = searchValid(predicted, p, radius);
        }
        return p;
    }

    void flip(long now, String why) {
        arcDir = -arcDir;
        flipAt = now;
        // Iceri adim sadece yayin ucunda degisir (engel / NPC onu yon degisimleri saymaz).
        if (why.equals("yay ucu")) {
            sweeps++;
            inward = cp.context.config.COMBAT.INWARD && sweeps % 2 == 1; // 2., 4., ... gidis iceri
        }
        if (Log.dDue("flip" + npcId, 1500))
            Log.i("COMBAT", npcName + " yay yon degisti (" + why + ")" + (inward ? ", bu gidiste biraz iceri" : ""));
    }

    /** Ilk yon: gemi zaten hareket ediyorsa ayni donus yonu (yon degistirmeden ilk vurus), yoksa yay ortasina dogru. */
    void initDir(Location predicted, double a) {
        Location dest = cp.context.move.getDestination();
        if (cp.context.move.isMoving() && dest != null && cp.context.hero.distanceTo(dest) > 30) {
            double da = CombatLogic.norm(predicted.angleTo(dest) - a);
            if (Math.abs(da) > 0.02) {
                arcDir = da > 0 ? 1 : -1;
                return;
            }
        }
        arcDir = CombatLogic.norm(arcCenter - a) >= 0 ? 1 : -1;
    }

    /**
     * Yayin ortasi: diger NPC'lerin menzili, dusmanlar, harita kenari, engel ve NPC'nin onu disinda en iyi aci.
     * Ilk secimde gemiye yakin taraf tercih edilir; sonraki kontrollerde gemi konumu hesaba katilmaz (orta kaymaz),
     * mevcut orta sadece digeri CENTER_MARGIN kadar iyiyse degisir.
     */
    double pickCenter(Npc n, Location predicted, double radius, boolean initial) {
        double best = Double.NaN, bestScore = -Double.MAX_VALUE;
        double heading = Double.NaN;
        if (n.isMoving() && n.getDestination().isPresent()) heading = predicted.angleTo(n.getDestination().get());
        List<Npc> others = new ArrayList<>();
        for (Npc o : cp.context.ents.getNpcs()) if (o != n && o.isValid() && !Context.isFake(o)) others.add(o);
        double current = predicted.angleTo(cp.context.hero);
        double base = initial || Double.isNaN(arcCenter) ? current : arcCenter;
        double currentScore = Double.NaN;
        for (int k = 0; k < 16; k++) {
            double ang = base + k * Math.PI / 8;
            Location p = Location.of(predicted, ang, radius);
            double score = cp.context.move.canMove(p) ? 0 : -5000;
            for (Npc o : others) score -= Math.max(0, o.getInfo().getRadius() + 200 - o.distanceTo(p)) * 3;
            Player e = cp.context.nearestEnemy(p);
            if (e != null) score -= Math.max(0, 3000 - e.distanceTo(p));
            double edge = cp.context.edgeDist(p);
            if (edge < 1500) score -= (1500 - edge) * 2;
            if (!Double.isNaN(heading) && Math.abs(CombatLogic.norm(ang - heading)) < CombatLogic.FRONT_AVOID) score -= 800; // onune gecme
            if (initial) score -= Math.abs(CombatLogic.norm(ang - current)) * 100; // yakin taraf (gereksiz yol yok)
            if (slipping) score += secondaryAway(n, p);
            if (k == 0) currentScore = score;
            if (score > bestScore) {
                bestScore = score;
                best = ang;
            }
        }
        if (!initial && bestScore < currentScore + CombatLogic.CENTER_MARGIN) return arcCenter;
        if (initial)
            Log.d("COMBAT", npcName + " yay ortasi " + Math.round(Math.toDegrees(CombatLogic.norm(best))) + " derece (puan " + (int) bestScore + ")");
        return best;
    }

    Location searchValid(Location center, Location p, double radius) {
        double a = center.angleTo(p);
        for (int i = 1; i <= 12; i++) {
            Location q = Location.of(center, a + arcDir * i * 0.3, radius * 0.9);
            if (cp.context.move.canMove(q)) return q;
        }
        return p;
    }

    // ------------------------------------------------------------ kalabalik / coklu saldiri

    /** Kalabalikta sira: birden fazla NPC vuruyorsa en hizli olecek olan once. Coklu saldiridan siyrilma. */
    void crowd(Npc n, long now) {
        if (now - crowdAt < CombatLogic.CROWD_CHECK_MS) return;
        crowdAt = now;
        List<Npc> attackers = new ArrayList<>();
        for (Npc o : cp.context.ents.getNpcs())
            if (o.isValid() && !Context.isFake(o) && o.isAttacking(cp.context.hero)) attackers.add(o);
        double hp = cp.context.heroHp();
        if (hpAt2s < 0 || now - hpAt2sTime > 2000) {
            boolean drop = hpAt2s >= 0 && hpAt2s - hp > CombatLogic.MULTI_HP_DROP;
            boolean wasSlipping = slipping;
            slipping = attackers.size() >= 2 && drop;
            if (slipping && !wasSlipping) {
                Log.i("COMBAT", attackers.size() + " NPC vuruyor, can 2 sn'de " + Log.pct(hpAt2s - hp)
                        + " dustu: digerlerinin menzilinden siyriliyor (en az 3 sn guvenli taraf yerine uzak taraf)");
                arcCenter = Double.NaN; // yeni orta saldiranlardan uzak taraf puaniyla secilir
            }
            if (slipping) slipUntil = Math.max(slipUntil, now + 3000);
            hpAt2s = hp;
            hpAt2sTime = now;
        }
        if (phase == CombatLogic.Phase.APPROACH) return;
        // Vurulan hedef bırakılmaz (eskiden "kalabalikta sira" ile yandaki NPC'ye geciliyordu). Sadece:
        // - lazer menzilindeki, tablo onceligi daha yuksek (sayi kucuk) listedeki NPC'ye,
        // - "Zayif NPC Once" aciksa: canı vurulan hedefin su anki caninin yarisindan az listedeki NPC'ye (birden
        //   fazlaysa tablo onceligi) gecilir; asil hedef hatirlanir, o olunce ona donulur.
        if (cp.targeting.fleeing() || Context.hp(n) < 0.25 || weakSwitches >= CombatLogic.WEAK_MAX) return;
        // Araya alinan NPC bitirilmeden asil hedefe (ya da baskasina) geri gecilmez (gidip gelme olmasin).
        if (n.getId() == weakPick) return;
        int pri = n.getInfo().getPriority();
        // DarkBot LootModule kurali: vurulan hedefe can oranina gore ek oncelik (20 - can x 10).
        int extra = 20 - (int) (Context.hp(n) * 10);
        boolean weak = cp.context.config.PLUS.WEAK_FIRST();
        double limit = CombatLogic.total(n) * CombatLogic.WEAK_RATIO;
        Npc best = null;
        String why = null;
        for (Npc o : cp.context.ents.getNpcs()) {
            // Ucuz mesafe kontrolu once: sadece lazer menzilinde (yol yapilmaz).
            if (o == n || cp.context.hero.distanceTo(o) > effectiveRadius(o) || !cp.selectable(o)) continue;
            if (cp.targeting.waitReasonPublic(o) != null && !cp.targeting.fought(o)) continue;
            int op = o.getInfo().getPriority();
            boolean better = pri - op > extra;
            // Zayif NPC'ye ancak hedefle savas basladiysa (isabet geldiyse) gecilir; hedef vurulmadan birakilmaz.
            boolean isWeak = weak && firstHitLogged && CombatLogic.total(o) < limit;
            if (!better && !isWeak) continue;
            if (best == null || op < best.getInfo().getPriority()
                    || (op == best.getInfo().getPriority() && CombatLogic.total(o) < CombatLogic.total(best))) {
                best = o;
                why = better ? "daha oncelikli (" + op + " < " + pri + " - " + extra + ")" : "zayif NPC once (can " + (int) CombatLogic.total(o)
                        + " < hedefin %" + Math.round(CombatLogic.WEAK_RATIO * 100) + "'i)";
            }
        }
        if (best == null) return;
        weakSwitches++;
        weakMain = n.getId();
        weakPick = best.getId();
        cp.halfId = n.getId();
        cp.halfUntil = Context.now() + CombatLogic.HALF_MS;
        Log.i("COMBAT", Log.npc(n) + " yerine once " + Log.npc(best) + ": " + why + ", sonra " + npcName + "'a donulecek");
        setTarget(best, "araya alindi");
    }

    /** Coklu saldirida: diger saldiranlardan uzak taraf puani. */
    double secondaryAway(Npc target, Location p) {
        double s = 0;
        for (Npc o : cp.context.ents.getNpcs())
            if (o != target && o.isValid() && !Context.isFake(o) && o.isAttacking(cp.context.hero))
                s += Math.min(o.distanceTo(p), o.getInfo().getRadius() + 500);
        return s;
    }

    /**
     * Yayda / sabitte 4 sn icinde 2 "Menzil disi": saldiri mesafesi %5 kucultulur (en az %80, NPC turu icin
     * hatirlanir). NPC'nin cani PET yuzunden de dustugu icin "hasar alamiyor" kontrolu bunu yakalamiyordu.
     */
    public void outOfRange() {
        long now = Context.now();
        Npc n = npcId >= 0 ? cp.context.findNpc(npcId) : null;
        if (n == null || !(phase == CombatLogic.Phase.ARC || phase == CombatLogic.Phase.HOLD) || cp.targeting.fleeing()) return;
        // Bilerek menzilden cikarken / donerken gelen mesaj ve gemi o an zaten saldiri mesafesinin disindaysa (oyun
        // hakli, lazer sinirinin kaniti degil) sayilmaz.
        if (now < outUntil + 1500) return;
        double d = cp.context.hero.distanceTo(n);
        if (curRadius > 0 && d > curRadius) {
            Log.d("COMBAT", Log.npc(n) + " menzil disi mesaji sayilmadi: gemi saldiri mesafesinin disinda (" + (int) d + " > " + (int) curRadius + ")");
            return;
        }
        // Bu mesafeden daha uzaktan hasar verildigi kanitliysa mesaj mesafeyle ilgili degil (ping / roket / kilit):
        // saldiri mesafesi kucultulmez (eskiden 514 ve 539'daki mesajlarla 660 -> 560'a inip oturum boyu kaliyordu).
        double proven = cp.targeting.damageRange(n);
        if (proven > 0 && d < proven - 50) {
            Log.d("COMBAT", Log.npc(n) + " menzil disi mesaji sayilmadi: uzaklik " + (int) d + ", bu NPC'ye "
                    + (int) proven + "'den hasar verildi");
            return;
        }
        oorQuietMs = 0;
        if (now - oorFirstAt > 4000) {
            oorFirstAt = now;
            oorCount = 0;
        }
        if (++oorCount < 2) return;
        oorCount = 0;
        oorFirstAt = now;
        if (localScale <= CombatLogic.OOR_MIN_SCALE + 0.001) return;
        localScale = Math.max(CombatLogic.OOR_MIN_SCALE, localScale - 0.05);
        cp.scale.put(npcName, localScale);
        scaleEvidence = true;
        Log.i("COMBAT", Log.npc(n) + " menzil disi (uzaklik " + (int) d + "): saldiri mesafesi %"
                + Math.round(localScale * 100) + " (" + (int) attackRadius(n) + "), " + npcName + " icin hatirlandi");
    }

    // ------------------------------------------------------------ hasar almayan hedef

    /**
     * Menzildeyken ates edildigi halde NPC'nin cani dusmuyorsa: saldiri mesafesi %10'luk adimlarla %50'ye iner
     * (tablo degismez, calisan oran NPC turu icin hatirlanir), sonunda NPC 20 sn yok sayilir. false: birak.
     */
    boolean noDamage(Npc n, long now, double d, double radius) {
        double t = CombatLogic.total(n);
        if (lastNpcTotal >= 0 && t < lastNpcTotal - 1) {
            lastDropAt = lastHitAt = now;
            wdTries = 0;
        }
        // ISH (hasar almaz) etkisi: NPC vuruluyor sayilir, mesafe kucultulmez.
        if (ish(n)) {
            lastNpcTotal = t;
            lastDamageAt = now;
            return true;
        }
        if (lastNpcTotal < 0 || t < lastNpcTotal - 1) {
            // Sadece oran degistiyse ve kucultme kanitliysa ("menzil disi" mesaji) yazilir.
            if (localScale < 1 && scaleEvidence && lastNpcTotal >= 0 && now - lastDamageAt > 2000
                    && !Objects.equals(cp.scale.get(npcName), localScale)) {
                cp.scale.put(npcName, localScale);
                Log.i("COMBAT", npcName + " tekrar hasar aliyor, saldiri mesafesi %" + Math.round(localScale * 100) + " hatirlandi");
            }
            lastDamageAt = now;
        }
        lastNpcTotal = t;
        boolean inRange = d <= radius * CombatLogic.RANGE_BUFFER_OUT && (phase == CombatLogic.Phase.ARC || phase == CombatLogic.Phase.HOLD || phase == CombatLogic.Phase.FIRST_HIT);
        if (!cp.context.shooting(n) || cp.targeting.fleeing() || !inRange) {
            lastDamageAt = Math.max(lastDamageAt, now - CombatLogic.NO_DAMAGE_MS / 2);
            return true;
        }
        if (now - lastDamageAt < CombatLogic.NO_DAMAGE_MS || now - lastStepAt < CombatLogic.NO_DAMAGE_STEP_MS) return true;
        // Once ates bekcisi denesin (kilit / ates kaybi mesafe sorunu degildir): en az 2 deneme.
        if (wdTries < 2) return true;
        lastStepAt = now;
        if (localScale > CombatLogic.LOCAL_MIN_SCALE + 0.001) {
            localScale = Math.max(CombatLogic.LOCAL_MIN_SCALE, localScale - 0.1);
            // Sadece oyun son 3 sn icinde "menzil disi" dediyse NPC turu icin hatirlanir.
            double proven = cp.targeting.damageRange(n);
            boolean evidence = now - cp.context.lastOutOfRangeAt < 3000 && (proven <= 0 || d >= proven - 50);
            if (evidence) {
                scaleEvidence = true;
                cp.scale.put(npcName, localScale);
            } else scaleEvidence = false; // kanitsiz kucultme sonra "tekrar hasar aliyor" ile NPC turune yazilmasin
            Log.i("COMBAT", Log.npc(n) + " " + (now - lastDamageAt) / 1000 + " sn hasar almadi (ates bekcisi " + wdTries
                    + " deneme), saldiri mesafesi %" + Math.round(localScale * 100) + " (" + (int) attackRadius(n) + ", tablo degismez"
                    + (evidence ? ", menzil disi mesaji var: " + npcName + " icin hatirlandi)" : ", sadece bu hedefte)"));
            return true;
        }
        Log.i("COMBAT", Log.npc(n) + " menzil en altta ve hala vurulamiyor, " + CombatLogic.DEAD_TARGET_IGNORE_MS / 1000 + " sn yok sayiliyor");
        cp.scale.remove(npcName);
        cp.context.bans.ban(n, Bans.Owner.COMBAT, CombatLogic.DEAD_TARGET_IGNORE_MS);
        cp.context.hero.setLocalTarget(null);
        return false;
    }

    // ------------------------------------------------------------ olum / siradaki / kutu

    /** Olumu onceden gorme: NPC'nin can dusus hizindan kalan sure; azsa siradaki hedef hazirlanir. */
    void predictDeath(Npc n, long now) {
        double rate = npcRate.perSec();
        if (rate <= 0 || nextId >= 0) return;
        double left = CombatLogic.total(n) / rate * 1000;
        if (left > CombatLogic.DEATH_PREDICT_MS) return;
        // Siradaki bulunamadiysa en sik 500 ms'de bir yeniden aranir (her tik tum NPC'ler taranmaz).
        if (now - nextSearchAt < 500) return;
        nextSearchAt = now;
        Npc next = chooseNext(n);
        if (next == null) return;
        nextId = next.getId();
        if (phase == CombatLogic.Phase.ARC) {
            arcCenter = predict(n).angleTo(next);
            centerAt = now;
        }
        Log.d("COMBAT", npcName + " ~" + (int) left + " ms icinde olecek, siradaki hazir: " + Log.npc(next));
    }

    /** Siradaki hedef: once tablo onceligi, sonra varis suresi (sana gelen uzak NPC, kacan yakin NPC'den once). */
    Npc chooseNext(Npc except) {
        if (cp.context.fightThreat() != null || hpGate()) return null;
        Npc best = null;
        double bestTime = Double.MAX_VALUE;
        int bestPri = Integer.MAX_VALUE;
        for (Npc o : cp.context.ents.getNpcs()) {
            if (o == except || !cp.selectable(o) || cp.context.hero.distanceTo(o) > CombatLogic.NEXT_SEARCH) continue;
            // Tehlikeli bolgedeki NPC ve dusman yanindaki yarim NPC siradaki hedef olmaz.
            if (cp.context.inDangerZone(o) != null || (o.getId() == cp.halfId && halfDanger(o) != null)) continue;
            if (cp.targeting.waitReasonPublic(o) != null && !cp.targeting.fought(o)) continue;
            int pri = o.getInfo().getPriority();
            double t = reachTime(o);
            if (pri < bestPri || (pri == bestPri && t < bestTime)) {
                bestPri = pri;
                bestTime = t;
                best = o;
            }
        }
        return best;
    }

    /** Hedef kayboldu: olduyse kutu / siradaki, gorusten ciktiysa (kacti) birakilir. */
    boolean onGone(long now) {
        // Hizli kesim / tek salvoda olen NPC: son 1,5 sn icinde ates ediliyordu ve yakindaydi.
        boolean shotRecently = lastShotAt > 0 && now - lastShotAt < 1500;
        // Olum tahmini (siradaki hazir, nextId) tek basina yetmez: son 1,5 sn'de gercekten vuruluyor olmali
        // (shotRecently zaten tek basina olum sayilir, tahmin ayrica sayilmaz).
        boolean died = lastNpcDist < 1500 && (lastNpcHp <= 0.25 || shotRecently
                || (lastNpcHp <= 0.5 && lastNpcDist < 1200));
        Location diedAt = lastNpcLoc; // olen NPC'nin son gorulen yeri
        if (!died) {
            // Oyun NPC'yi bazen yeni numarayla yeniden olusturuyor: ayni yerde ayni turden NPC varsa onunla devam.
            Npc same = null;
            if (lastNpcLoc != null) for (Npc o : cp.context.ents.getNpcs())
                if (o.isValid() && !Context.isFake(o) && o.getInfo() != null && npcName.equals(o.getInfo().getName())
                        && o.distanceTo(lastNpcLoc) < 400 && cp.selectable(o)) same = o;
            if (same != null) {
                Log.i("COMBAT", npcName + " #" + npcId + " yeniden olustu (#" + same.getId() + "), devam ediliyor");
                setTarget(same, "ayni NPC yeni numarayla");
                return true;
            }
            dropTarget("gorusten cikti (son can " + Log.pct(lastNpcHp) + ", " + (int) lastNpcDist + " uzakta), olu sayilmadi");
            return true;
        }
        kills++;
        cp.totalKills++;
        cp.lastKillAt = now;
        cp.lastKillLoc = diedAt;
        cp.targeting.forgetFlee();
        Stats.count("kesim");
        CombatLogic.AngleStats as = cp.angles.get(npcName);
        if (as != null) {
            as.kills++;
            if (style() == BotPlusConfig.CombatStyle.SAFE) as.safeKills++;
            cp.logAngles(npcName);
        }
        Log.i("COMBAT", "OLDU " + npcName + " #" + npcId + " " + (now - npcStart) / 100 / 10.0 + " sn"
                + " | gemi " + Log.pct(cp.context.heroHp()) + " | oturumda " + cp.totalKills + ". kesim");
        cp.localUntil = 0; // olduruldu: birakilan NPC bolgesi beklemesi biter
        if (diedAt != null) cp.context.npcDied.accept(diedAt); // bulucunun eski isaretine gidilmesin
        deathLoc = diedAt;
        deathAt = now;
        box = null;
        boxUntil = 0;
        boxTries = 0;
        boxStart = now;
        run = false;
        int next = nextId;
        npcId = -1;
        npcName = "-";
        nextId = next;
        if (cp.collectBoxes()) {
            phase = CombatLogic.Phase.BOX;
            return boxTick(now);
        }
        phase = CombatLogic.Phase.APPROACH;
        return continueWith(nextNpc());
    }

    Npc nextNpc() {
        if (cp.context.fightThreat() != null || refreshDue() || lowForNext() || hpGate()) {
            nextId = -1;
            return null;
        }
        // Araya alinan NPC olunce asil hedefe donulur.
        if (cp.halfId >= 0 && Context.now() <= cp.halfUntil) {
            Npc h = cp.context.findNpc(cp.halfId);
            if (h != null && cp.selectable(h) && halfDanger(h) == null) {
                cp.halfId = -1;
                nextId = -1;
                Log.i("COMBAT", "asil hedefe donuluyor: " + Log.npc(h));
                return h;
            }
        }
        Npc next = nextId >= 0 ? cp.context.findNpc(nextId) : null;
        nextId = -1;
        if (next != null && (cp.context.inDangerZone(next) != null || (next.getId() == cp.halfId && halfDanger(next) != null)))
            next = null;
        if (next == null || !cp.selectable(next)) next = chooseNext(null);
        return next;
    }

    /** Dovuse Baslama Cani: can dusukse siradaki NPC'ye dogrudan gecilmez (once tamir), bize vuran yoksa. */
    boolean lowForNext() {
        BotPlusConfig.HpWait w = cp.context.config.PLUS.TARGET.HP_WAIT;
        int min = w.HP;
        if (!w.ENABLED || w.WHICH != BotPlusConfig.HpWaitWhich.ALL || cp.context.heroHp() >= min / 100.0
                || cp.context.attackerOfHero() != null) return false;
        Log.i("COMBAT", "can " + Log.pct(cp.context.heroHp()) + " < %" + min + " (HP Bekle, tum NPC'ler): siradaki NPC'ye gecilmiyor");
        return true;
    }

    /** Yeni Dovuse Baslama Cani: can bunun altindaysa ve bize vuran yoksa yeni hedefe baslanmaz (suren dovus birakilmaz). */
    boolean lowForFight() {
        int min = cp.context.config.COMBAT.FIGHT_MIN_HP;
        long now = Context.now();
        double hp = cp.context.heroHp();
        if (min <= 0 || hp >= min / 100.0 || cp.context.attackerOfHero() != null) {
            lowSince = 0;
            return false;
        }
        // Can artmiyorsa (tamir robotu yok / can tamir sinirinin ustunde) sonsuza kadar beklenmez: 60 sn sonra dovusulur.
        if (lowSince == 0 || hp > lowBestHp + 0.005) {
            lowSince = now;
            lowBestHp = hp;
        } else if (now - lowSince > 60_000) {
            if (Log.due("fightminhpto", 30_000)) Log.i("COMBAT", "can " + Log.pct(hp) + " 60 sn'dir artmiyor, HP Bekle beklemesi birakildi");
            return false;
        }
        if (Log.due("fightminhp", 10_000)) Log.i("COMBAT", "can " + Log.pct(hp) + " < %" + min + ", yeni dovuse girilmiyor (HP Bekle)");
        return true;
    }

    /**
     * Secim oncesi HP Bekle kapisi: true -> hic NPC secilmez ("Tum NPC'ler"). "HB isaretli" seciliyse sadece isaretli
     * NPC'ler secilmez (eskiden dolas modunda hepsi engelleniyordu).
     */
    boolean hpGate() {
        boolean low = lowForFight();
        boolean flagged = cp.context.config.PLUS.TARGET.HP_WAIT.WHICH == BotPlusConfig.HpWaitWhich.FLAGGED;
        cp.skipHpFlagged = low && flagged;
        return low && !flagged;
    }

    private long lowSince;
    private double lowBestHp;

    boolean continueWith(Npc next) {
        phase = CombatLogic.Phase.APPROACH;
        if (next == null) {
            dropTarget(refreshDue() ? "yenileme / mola zamani" : "siradaki yok");
            Log.d("COMBAT", "yakinda siradaki NPC yok, NPC araniyor (" + kills + " kesim)");
            return true;
        }
        String wait = cp.targeting.waitReasonPublic(next);
        if (wait != null && !cp.targeting.fought(next)) {
            cp.context.attack.setTarget(next);
            Log.i("COMBAT", Log.npc(next) + " beklenmeli (" + wait + "), bekleme isine birakiliyor");
            return false;
        }
        setTarget(next, "siradaki, durmadan gecis");
        return true;
    }

    /**
     * Olumden sonra kutu: once olen NPC'nin kutusu (dusmesi ~1 sn beklenir, bu sirada siradakine on kilit),
     * sonra yol ustu kutu (300 sapma), en fazla 3 deneme.
     */
    boolean boxTick(long now) {
        run = false;
        // tryCollect sonrasi kutu bekleme suresince "toplandi" gorunur: bu sure beklenir, ancak kaybolunca alindi sayilir.
        if (box != null && boxUntil > 0 && now < boxUntil && box.isValid()) return true;
        if (box != null && !box.isValid()) {
            Log.d("COMBAT", box.getTypeName() + " alindi (" + (now - boxStart) + " ms)");
            box = null;
            boxUntil = 0;
            boxTries = 0;
        }
        if (box == null) {
            Npc next = nextId >= 0 ? cp.context.findNpc(nextId) : null;
            box = dropBox();
            if (box == null) box = nearBox(next);
            if (box == null) {
                // Kutu dusmesi sadece "Kutuyu Bekle" aciksa beklenir (kapaliyken gemi olum yerinde durmaz).
                if (deathLoc != null && now - deathAt < CombatLogic.DROP_WAIT_MS && cp.context.config.PLUS.BOX_WAIT()) {
                    // Kutu birkac yuz ms sonra duser: beklerken siradakine kilit atilir, gemi durmaz.
                    if (next != null && cp.selectable(next)) {
                        cp.targeting.release(next); // selectable artik tutmayi kaldirmiyor: on kilitten once burada
                        if (!Objects.equals(cp.context.attack.getTarget(), next)) cp.context.attack.setTarget(next);
                        cp.context.attack.tryLockTarget();
                    }
                    return true;
                }
                deathLoc = null;
                return continueWith(nextNpc());
            }
            boxStart = now;
            Log.d("COMBAT", "kutu: " + box.getTypeName() + " " + (int) cp.context.hero.distanceTo(box));
        }
        if (now - boxStart > CombatLogic.BOX_MAX_MS || boxTries >= CombatLogic.BOX_TRIES) {
            Log.d("COMBAT", box.getTypeName() + " alinamadi (" + boxTries + " deneme)");
            box = null;
            deathLoc = null;
            return continueWith(nextNpc());
        }
        if (boxUntil > 0) {
            if (now < boxUntil) return true;
            boxUntil = 0; // hala gecerliyse tekrar denenir
        }
        // Yakinda (3000) secilebilir NPC varsa dolasma konfigurasyonuna gecilmez: kutudan hemen sonra dovus
        // (DarkBot konfig degisimini 5,5 sn kilitler, dovuse dolasma konfigurasyonuyla girilmesin).
        if (!cp.npcs() || !cp.selectableNear(3000)) cp.context.hero.setRoamMode();
        collect(box, now);
        return true;
    }

    void collect(Box b, long now) {
        double d = cp.context.hero.distanceTo(b);
        if (d < 250) {
            cp.context.stop();
            if (cp.context.collect(b)) {
                boxTries++;
                boxUntil = now + cp.context.collectWaitLeft();
            }
        } else if (!cp.context.collectBusy()) cp.context.travelTo(b); // toplama suruyorken yurunmez (toplama kesilmesin)
    }

    /** Olen NPC'nin dusurdugu kutu. */
    Box dropBox() {
        if (deathLoc == null) return null;
        Box best = null;
        double bd = 500;
        for (Box b : cp.context.ents.getBoxes()) {
            if (!cp.boxOk(b)) continue;
            double d = b.distanceTo(deathLoc);
            if (d < bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }

    /** Kutu yoldan az sapmayla aliniyorsa (ya da 600 icindeyse) kutu. */
    Box nearBox(Npc next) {
        Box best = null;
        double bestCost = Double.MAX_VALUE;
        for (Box b : cp.context.ents.getBoxes()) {
            if (!cp.boxOk(b)) continue;
            double d = cp.context.hero.distanceTo(b);
            double detour = next == null ? d : d + b.distanceTo(next) - cp.context.hero.distanceTo(next);
            if (d > 600 && detour > CombatLogic.BOX_DETOUR) continue;
            if (detour < bestCost) {
                bestCost = detour;
                best = b;
            }
        }
        return best;
    }

    /**
     * Dovus sirasinda kutu (DarkBot toplayici kurali): NPC %25 ustunde, kutu toplama mesafesinde,
     * NPC'nin IGNORE_BOXES isareti yoksa kutuya gidilir; ates ve kilit surer.
     */
    Location sideBox(Npc n, Location p, long now) {
        if (now < boxUntil && box != null && box.isValid()) return Location.of(cp.context.hero.getX(), cp.context.hero.getY());
        // Dovus sirasinda kutuya sadece "Once Kutu" modunda gidilir (NPC varken kutuya gidip NPC unutulmaz).
        // "NPC'ye giderken yoldaki kutulari al": NPC'ye yaklasirken rotanin 300 yakinindaki kutu alinir.
        boolean boxFirstMode = cp.context.botMode() == BotPlusConfig.BotMode.BOX_NPC;
        BotPlusConfig.BoxWay way = cp.context.config.BOX_WAY;
        boolean onWay = way != BotPlusConfig.BoxWay.IGNORE && phase == CombatLogic.Phase.APPROACH && cp.collectBoxes();
        if (!(boxFirstMode || onWay) || Context.hp(n) < 0.25 || now < boxUntil) return p;
        boolean route = !boxFirstMode && way == BotPlusConfig.BoxWay.ON_ROUTE;
        Box best = null;
        double bd = route ? 1200 : cp.context.collectRadius();
        double toNpc = cp.context.hero.distanceTo(n);
        for (Box b : cp.context.ents.getBoxes()) {
            if (!cp.boxOk(b)) continue;
            double d = cp.context.hero.distanceTo(b);
            if (d >= bd) continue;
            if (route && (d > toNpc || segDist(cp.context.hero, n, b) > 300)) continue;
            if (n.getInfo().hasExtraFlag(NpcFlag.IGNORE_BOXES) && n.distanceTo(b) > Math.min(800, n.getInfo().getRadius() * 2)) continue;
            bd = d;
            best = b;
        }
        if (best == null) return p;
        if (Log.dDue("sidebox", 3000)) Log.i("COMBAT", "dovus sirasinda kutu: " + best.getTypeName() + " " + (int) bd);
        cp.boxFocus(best, now);
        if (bd < 250) {
            if (cp.context.collect(best)) {
                box = best;
                boxUntil = now + cp.context.collectWaitLeft();
            }
            return Location.of(cp.context.hero.getX(), cp.context.hero.getY());
        }
        return Location.of(best.getX(), best.getY());
    }

    /** Gorunen, DarkBot'un kesecegi ama mesafe yuzunden henuz secilemeyen NPC (en fazla 4000). */
    Npc pendingNpc() {
        Npc best = null;
        double bd = 4000;
        for (Npc o : cp.context.ents.getNpcs()) {
            if (cp.context.hero.distanceTo(o) >= 4000 || !cp.context.darkBotWouldKill(o) || cp.context.closestDistance(o) >= 500
                    || cp.inUnreachable(o)) continue;
            if (!cp.usable(o)) continue;
            if (cp.context.config.PROTECT.KILLER.ENABLED && cp.context.killerZone(o, 2500) != null) continue;
            double d = cp.context.hero.distanceTo(o);
            if (d < bd) {
                bd = d;
                best = o;
            }
        }
        return best;
    }

    // ------------------------------------------------------------ kovalama / menzilden cik

    long chaseSince, outUntil, lastOutAt;
    boolean chaseLogged, chaseOverLogged;

    /** Kacan NPC'yi Kovala (Combat Plus): NPC kaciyorsa ve en fazla kovalama suresi dolmadiysa. */
    boolean chaseActive(Npc n, long now) {
        BotPlusConfig.Chase c = cp.context.config.CHASE;
        if (!c.ENABLED || !cp.targeting.fleeing()) {
            chaseSince = 0;
            chaseLogged = chaseOverLogged = false;
            return false;
        }
        if (chaseSince == 0) chaseSince = now;
        if (now - chaseSince > c.MAX_SEC * 1000L) {
            if (!chaseOverLogged) {
                chaseOverLogged = true;
                Log.i("KOVALA", npcName + " " + c.MAX_SEC + " sn kovalandi, menzil normale dondu");
            }
            return false;
        }
        if (!chaseLogged) {
            chaseLogged = true;
            Stats.count("kovala");
            Log.i("KOVALA", npcName + " kaciyor: menzil %" + c.DROP + " dusuruldu, yandan yaklasiliyor (en fazla "
                    + c.MAX_SEC + " sn)");
        }
        return true;
    }

    /** Kacan NPC'ye yandan yaklasma: gittigi yonun yanina ve biraz onune (arkasindan kovalanmaz). */
    Location flankPoint(Npc n, Location predicted, double r) {
        Optional<Location> dest = n.getDestination();
        if (!n.isMoving() || !dest.isPresent()) return approachPoint(n, predicted, r);
        double mx = dest.get().getX() - predicted.getX(), my = dest.get().getY() - predicted.getY();
        double len = Math.hypot(mx, my);
        if (len < 1) return approachPoint(n, predicted, r);
        double ux = mx / len, uy = my / len;
        double px = -uy, py = ux;
        double hx = cp.context.hero.getX() - predicted.getX(), hy = cp.context.hero.getY() - predicted.getY();
        if (px * hx + py * hy < 0) {
            px = -px;
            py = -py;
        }
        double ax = px * 0.8 + ux * 0.6, ay = py * 0.8 + uy * 0.6;
        double al = Math.hypot(ax, ay);
        return Location.of(predicted.getX() + ax / al * r * 0.95, predicted.getY() + ay / al * r * 0.95);
    }

    /** Hasar Alirken Menzilden Cik: 7 sn'de bir, 1,5 sn NPC'nin vurdugu mesafenin disina. */
    Location outPoint(Npc n, Location predicted, double radius, long now) {
        if (!cp.context.config.COMBAT.OUT_ON_DAMAGE) return null;
        CombatLogic.AngleStats a = cp.angles.get(npcName);
        // Cikis mesafesi: NPC'nin vurdugu en uzak + 80 (bilinmiyorsa tablo menzili + 100). Cikis 1,5 sn: lazer
        // menzilinin disina dusse de yapilir (bu sirada menzil ogrenme ve "menzil disi" sayimi duraklatilir).
        // Eskiden cikis lazer menzili disina dusunce ozellik kalici kapaniyordu (NPC menzili 581 ogrenilince hic cikilmadi).
        double reach = a != null && a.reach > 0 ? a.reach + 80 : tableRadius(n) + 100;
        Location out = Location.of(predicted, predicted.angleTo(cp.context.hero), reach);
        if (now >= outUntil) {
            // Hafif hasarda cikilmaz: can+kalkan %90 altinda ve saniyede en az %1,5 dusmeli. Kalkan da sayilir
            // (eskiden sadece can bakiliyordu: kalkan hasari karsilarken can %100 kaldigi icin hic cikilmiyordu).
            if (!n.isAttacking(cp.context.hero) || !heroRate.known()
                    || CombatLogic.total(cp.context.hero) / CombatLogic.maxTotal(cp.context.hero) >= 0.9
                    || heroRate.perSec() / CombatLogic.maxTotal(cp.context.hero) * 100 < 1.5 || now - lastOutAt < 7000) return null;
            if (!cp.context.move.canMove(out)) {
                if (Log.dDue("outblocked" + npcId, 7000))
                    Log.i("COMBAT", npcName + " menzilden cikis noktasi " + Log.loc(out) + " gidilemez (engel), cikis atlandi");
                lastOutAt = now;
                return null;
            }
            outUntil = now + 1500;
            lastOutAt = now;
            cp.targeting.pauseLearn(now + 3500); // bilerek cikista gelen "menzil disi" mesaji menzili dusurmesin
            Log.i("COMBAT", npcName + " hasar aliniyor (" + Math.round(heroRate.perSec()) + "/sn), menzilden kisa cikis");
        }
        return cp.context.move.canMove(out) ? out : null;
    }

    /** b noktasinin a-c dogru parcasina uzakligi. */
    double segDist(eu.darkbot.api.game.other.Locatable a, eu.darkbot.api.game.other.Locatable c,
                   eu.darkbot.api.game.other.Locatable b) {
        double dx = c.getX() - a.getX(), dy = c.getY() - a.getY();
        double len = dx * dx + dy * dy;
        double t = len == 0 ? 0 : Math.max(0, Math.min(1, ((b.getX() - a.getX()) * dx + (b.getY() - a.getY()) * dy) / len));
        return Math.hypot(a.getX() + t * dx - b.getX(), a.getY() + t * dy - b.getY());
    }

    // ------------------------------------------------------------ hedef yokken

    /** NPC yok: dusmandan uzaklas, kutu topla ya da dolas (bulucu sinyali varsa bulucu isi keser). */
    boolean idle(long now) {
        run = false;
        cp.context.hero.setRoamMode();
        if (cp.context.attack.hasTarget() && cp.context.targetNpc() == null) cp.context.attack.setTarget(null);
        Player threat = cp.context.fightThreat();
        // Portala kacis 2.1.0'dan beri Escape isinde (BotPlus menusu -> Dusman Saldirinca); burada sadece uzaklasilir.
        if (threat != null) {
            roamStatus = "düşmandan uzaklaşılıyor";
            if (roamTo == null || cp.context.hero.distanceTo(roamTo) < 150 || roamTo.distanceTo(threat) < 1500) {
                roamTo = cp.context.safePoint(1500, threat);
                if (Log.due("idlethreat", 3000)) Log.i("COMBAT", "dusman " + Log.ship(threat) + " " + (int) threat.distanceTo(cp.context.hero)
                        + " uzakta saldiriyor / yaklasiyor: yeni dovus yok, uzaklasiliyor " + Log.loc(roamTo));
            }
            cp.context.moveTo(roamTo);
            return true;
        }
        if (now < boxUntil && box != null && box.isValid()) return true; // kutu toplaniyor: bekle
        // Gorunen kesilecek NPC var ama henuz secilemiyor (DarkBot mesafe siniri disinda): "NPC yokken kutu"
        // calismaz, NPC'ye dogru gidilir (yoksa NPC'ye giderken kutuya donuyordu). Once kutu modu haric.
        Npc pend = cp.context.botMode() == BotPlusConfig.BotMode.BOX_NPC || !cp.npcs() || refreshDue() ? null : pendingNpc();
        if (pend != null) {
            Box nb = cp.context.config.BOX_WAY == BotPlusConfig.BoxWay.NEARBY && cp.collectBoxes() ? bestBox() : null;
            if (nb == null || cp.context.hero.distanceTo(nb) > cp.context.collectRadius()) {
                roamStatus = "NPC'ye gidiliyor: " + pend.getInfo().getName() + " (" + eta(pend) + " sn)";
                roamTo = null;
                cp.context.travelTo(pend);
                cp.context.speedAbility(cp.context.hero.distanceTo(pend), "NPC'ye gitme");
                if (Log.due("pend" + pend.getId(), 5000)) Log.i("COMBAT", Log.npc(pend) + " " + (int) cp.context.hero.distanceTo(pend)
                        + " uzakta, henuz secilemiyor (DarkBot siniri " + cp.context.npcDistanceIgnore() + "), ona gidiliyor");
                return true;
            }
        }
        if (cp.collectBoxes()) {
            Box b = bestBox();
            if (b != null) {
                cp.boxFocus(b, now);
                roamStatus = "kutuya gidiliyor: " + b.getTypeName() + " (" + eta(b) + " sn)";
                // Bir kutu toplanirken (baska is / yeni dovus isi de olsa) yerinde beklenir, tiklanmaz, yurunmez.
                if (!cp.context.collectBusy()) {
                    double d = cp.context.hero.distanceTo(b);
                    if (d < 250) {
                        cp.context.stop();
                        if (cp.context.collect(b)) boxUntil = now + cp.context.collectWaitLeft();
                    } else cp.context.travelTo(b);
                }
                return true;
            }
        }
        // Bosta tamir (dolasirken tamir): liste disi NPC'lerden kacilmaz, gidilen yere giderken etrafindan dolanilir
        // (Context.travelTo); listedekilere zaten saldirilir. (Eskiden kacis noktasina gidip hedef degisiyordu.)
        roamStatus = searchInfo();
        // Bulucu sinyali varken (bulucu isi bir an icin baslamadiysa da) sinyale dogru gidilir, ters yone dolasilmaz.
        // Bos / olu sinyaller context.pingOk ile elenir. Yakin sinyal sadece su anki ya da az once olen hedefin
        // ustundeyse (300) yok sayilir (eskiden 1000'den yakin her sinyal yok sayilip uzak koseye gidiliyordu).
        java.util.Optional<Location> ping = cp.context.config.LOCATOR.GO ? cp.context.ping() : java.util.Optional.empty();
        boolean pingBad = ping.isPresent() && !cp.context.pingOk.test(ping.get());
        if (ping.isPresent() && !pingBad && cp.context.config.PROTECT.KILLER.ENABLED && cp.context.killerZone(ping.get(), 2500) != null)
            pingBad = true;
        if (ping.isPresent() && !pingBad && onOldTarget(ping.get(), now)) pingBad = true;
        if (ping.isPresent() && !pingBad) {
            roamStatus = "düşman bulucu sinyal verdi: NPC'ye gidiliyor (" + eta(ping.get()) + " sn)";
            cp.context.travelTo(ping.get());
            return true;
        }
        // Kesimden hemen sonra (2,5 sn) ya da haritaya yeni girilince (5 sn, PET / bulucu hazir degilken) yeni bulucu
        // sinyali beklenir: uzak "taranmamis bolge" secilmez, harita ortasina dogru yavasca kayilir.
        if (cp.context.config.LOCATOR.GO && cp.npcs()) {
            boolean afterKill = now - cp.lastKillAt < 2500;
            boolean pickNull = cp.context.locatorPick == null;
            boolean petOff;
            try {
                petOff = !cp.context.pet.isActive();
            } catch (RuntimeException e) {
                petOff = true;
            }
            boolean entering = now - Math.max(cp.mapEnteredAt, start) < 5000 && (petOff || pickNull);
            if (afterKill || entering) {
                roamStatus = "düşman bulucu yeni sinyal bekleniyor, haritanın ortasına kayılıyor";
                driftToCenter(now);
                return true;
            }
        }
        driftTo = null;
        driftAt = 0;
        // Birakilan NPC'nin bolgesinde kisa turlar (20 sn).
        if (cp.localCenter != null && now < cp.localUntil) {
            roamStatus = searchInfo() + "\nSon NPC'nin bölgesinde (" + (cp.localUntil - now + 999) / 1000 + " sn)";
            if (roamTo == null || cp.context.hero.distanceTo(roamTo) < 150 || roamTo.distanceTo(cp.localCenter) > 1600)
                roamTo = cp.context.ringPoint(cp.localCenter, 800 + ThreadLocalRandom.current().nextDouble() * 700,
                        ThreadLocalRandom.current().nextDouble() * Math.PI * 2, Math.PI);
            cp.context.moveTo(roamTo);
            return true;
        }
        roamTo = null;
        // Bos Harita Modu: bulucu sinyal vermiyor (aranan tur haritada yok): kenarlar yerine harita ortasinda dolasilir.
        if (cp.context.emptyMap != null && cp.context.emptyMap.empty()) {
            emptyGoal = cp.context.emptyMap.goal(); // gidilebilir nokta, varilamazsa 60 sn'de yenisi
            if (emptyGoal != null) {
                roamStatus = "boş harita: düşman bulucu " + cp.context.emptyMap.emptyMs() / 1000 + " sn'dir sinyal vermiyor, haritanın ortasında NPC bekleniyor";
                cp.roamDirty = true; // bos mod bitince yeni dolasma hedefi secilir
                cp.context.travelTo(emptyGoal);
                return true;
            }
        } else emptyGoal = null;
        // Dolasma hedefi: varilinca (radar zaten gordu) ya da kare yeni tarandiysa / 2 dk'da varilamadiysa yenisi.
        double cell = cp.radar();
        // Sure sadece dolasirken sayilir (dovus / baska is arasinda durur).
        if (cp.roamIdleTick > 0 && now - cp.roamIdleTick < 1500) cp.roamIdleMs += now - cp.roamIdleTick;
        cp.roamIdleTick = now;
        boolean done = cp.roamGoal == null || cp.context.hero.distanceTo(cp.roamGoal) < cell * 0.5
                || cp.roamIdleMs > 120_000 || cp.context.move.isOutOfMap() || cp.roamDirty
                || (cp.context.config.PLUS.SMART_ROAM() && cp.recentlyScanned(cp.roamGoal, now));
        if (done) {
            Location next = cp.pickRoamGoal(now);
            cp.roamFrom = Location.of(cp.context.hero.getX(), cp.context.hero.getY());
            cp.roamGoal = next;
            cp.roamIdleMs = 0;
            cp.roamDirty = false;
            cp.roamStep = null;
            if (cp.roamGoal == null) return true;
            Log.i("DOLASMA", (cp.context.config.PLUS.SMART_ROAM() ? "taranmamis bolgeye gidiliyor " : "dolasma hedefi ")
                    + Log.loc(cp.roamGoal) + " (" + (int) cp.context.hero.distanceTo(cp.roamGoal) + ", radar " + (int) cell + ")");
        }
        // Kisa Dolasma: hedefe 1000-2000'lik adimlarla (adim bitince ayni hedefe yeni adim, hedef degismez).
        Location go = cp.roamGoal;
        double far = cp.context.hero.distanceTo(cp.roamGoal);
        if (cp.context.config.PLUS.SHORT_ROAM() && far > 2200) {
            if (cp.roamStep == null || cp.context.hero.distanceTo(cp.roamStep) < 150 || cp.roamStep.distanceTo(cp.roamGoal) > far)
                cp.roamStep = cp.context.ringPoint(cp.context.hero, 1000 + ThreadLocalRandom.current().nextDouble() * 1000,
                        cp.context.hero.angleTo(cp.roamGoal), Math.PI / 8);
            go = cp.roamStep;
        } else cp.roamStep = null;
        roamStatus = searchInfo() + "\n" + (cp.context.config.PLUS.SMART_ROAM() ? "Taranmamış bölgeye gidiliyor (" : "Dolaşılıyor (") + eta(cp.roamGoal) + " sn)";
        cp.context.travelTo(go);
        cp.context.speedAbility(far, "dolasma");
        return true;
    }

    /** Bulucu beklenirken gidilen kisa nokta (harita ortasina dogru) ve secildigi an. */
    Location driftTo;
    long driftAt;

    /** Sinyal su anki hedefin ya da az once (10 sn) olen NPC'nin ustunde mi (300): eski NPC'nin sinyali. */
    boolean onOldTarget(Location l, long now) {
        if (cp.lastKillLoc != null && now - cp.lastKillAt < 10_000 && cp.lastKillLoc.distanceTo(l) < 300) return true;
        Npc t = cp.context.targetNpc();
        return t != null && t.distanceTo(l) < 300;
    }

    /** Bulucu beklenirken: harita ortasina dogru kisa (300-500) bir adim, ortadaysa durulur. */
    void driftToCenter(long now) {
        // Gemi durmaz: hedef noktaya yaklasinca (300) yenisi verilir, bekleme boyunca ortaya dogru tam hizla gidilir.
        if (driftTo != null && cp.context.hero.distanceTo(driftTo) > 300 && now - driftAt < 3000) {
            cp.context.moveTo(driftTo);
            return;
        }
        driftAt = now;
        driftTo = null;
        eu.darkbot.api.game.other.Area.Rectangle b = null;
        try {
            b = cp.context.star.getCurrentMapBounds();
        } catch (RuntimeException ignored) {
        }
        if (b == null || b.getWidth() <= 0 || b.getHeight() <= 0) {
            cp.context.stop();
            return;
        }
        double cx = (b.getX() + b.getX2()) / 2 - cp.context.hero.getX(), cy = (b.getY() + b.getY2()) / 2 - cp.context.hero.getY();
        double len = Math.hypot(cx, cy);
        if (len < 500) {
            cp.context.stop();
            return;
        }
        double step = Math.min(len - 300, 1500 + ThreadLocalRandom.current().nextDouble() * 300);
        Location p = Location.of(cp.context.hero.getX() + cx / len * step, cp.context.hero.getY() + cy / len * step);
        if (!cp.context.move.canMove(p)) {
            cp.context.stop();
            return;
        }
        driftTo = p;
        if (Log.due("locwait", 5000)) Log.i("DOLASMA", "bulucu sinyali bekleniyor, harita ortasina dogru gidiliyor " + Log.loc(p));
        cp.context.moveTo(p);
    }

    /** Bos harita modunda harita ortasindaki dolasma noktasi. */
    Location emptyGoal;

    /** DarkBot toplayicisi gibi: kutu tablosu onceligi, sonra yakinlik; mevcut kutu belirgin daha iyisi yoksa korunur. */
    Box bestBox() {
        Box best = null;
        int bp = Integer.MAX_VALUE;
        double bd = Double.MAX_VALUE;
        for (Box b : cp.context.ents.getBoxes()) {
            if (!cp.boxOk(b)) continue;
            int p = b.getInfo().getPriority();
            double d = cp.context.hero.distanceTo(b);
            if (p < bp || (p == bp && d < bd)) {
                bp = p;
                bd = d;
                best = b;
            }
        }
        // Kutu kutu arasi gidip gelme yok: secilen kutu alinana kadar korunur.
        if (cp.committed(box) && box.getInfo() != null && box.getInfo().shouldCollect()) return box;
        box = best;
        return best;
    }

    // ------------------------------------------------------------ bitir sonra tamir

    /** NPC gemiden cok once olecekse tamir ertelenir (kesin alt can ve yakin dusman haric). */
    private int finishDecidedId = Integer.MIN_VALUE;
    private boolean finishDecided;

    public boolean finishFirst() {
        Npc n = npcId >= 0 ? cp.context.findNpc(npcId) : null;
        if (n == null || phase == CombatLogic.Phase.BOX) return false;
        double hp = cp.context.heroHp();
        // Kesin alt can: %25 (eskiden %10: gemi bu cana kadar dovusup olebiliyordu).
        double floor = Math.max(0.25, BotPlusConfig.FINISH_MIN_HP / 100.0);
        if (hp < floor) return false;
        // Hedef disinda bir NPC de ates ediyorsa tahmin tutmaz: ertelenmez.
        for (Npc o : cp.context.ents.getNpcs())
            if (o != n && o.isValid() && o.isAttacking(cp.context.hero)) return false;
        Player e = cp.context.nearestEnemy(cp.context.hero);
        if (e != null && e.distanceTo(cp.context.hero) < 3000) return false;
        if (!npcRate.known() || !heroRate.known()) return false; // tahmin icin veri yok: guvenli degil
        double npcRateV = npcRate.perSec(), heroRateV = heroRate.perSec();
        if (npcRateV <= 0) return false;
        double ttk = CombatLogic.total(n) / npcRateV;
        double survive = heroRateV <= 0 ? Double.MAX_VALUE : CombatLogic.total(cp.context.hero) / heroRateV;
        // Genis pay (hasar ani artarsa da yetisir) ve histerezis: karar saliseler icinde gidip gelmesin (eskiden ani
        // hasar olcumuyle 0,1-0,3 sn icinde tersine donuyordu). Bu hedefte "tamir oncelikli" denildiyse oyle kalir;
        // "bitir" denildiyse ancak tahmin belirgin kotulesirse (0,6) tamire donulur.
        boolean was = finishDecidedId == npcId && finishDecided;
        boolean ok = finishDecidedId == npcId && !finishDecided ? false : ttk < survive * (was ? 0.6 : 0.35);
        finishDecidedId = npcId;
        finishDecided = ok;
        // Hedef basina bir kez (karar degisirse en sik 10 sn'de bir).
        if (npcId != finishLogId || (ok != finishLogOk && Log.due("finishflip", 10_000))) {
            finishLogId = npcId;
            finishLogOk = ok;
            Log.i("COMBAT", (ok ? "BITIR SONRA TAMIR: " : "tamir oncelikli: ") + Log.npc(n)
                    + " ~" + String.format(java.util.Locale.ROOT, "%.1f", ttk) + " sn'de olur, gemi "
                    + (survive == Double.MAX_VALUE ? "hasar almiyor" : "~" + String.format(java.util.Locale.ROOT, "%.1f", survive) + " sn dayanir")
                    + " (can " + Log.pct(hp) + ")");
        }
        return ok;
    }

    /** Yarim NPC'ye donmek tehlikeli mi: yaninda son 30 sn icinde bize ates etmis dusman ya da tehlikeli bolge. */
    String halfDanger(Npc h) {
        for (Player p : cp.context.ents.getPlayers())
            if (cp.context.attackedBy(p, Context.THREAT_MEMORY_MS) && p.distanceTo(h) < 2500)
                return "(bize ates eden " + Log.name(p) + " " + (int) p.distanceTo(h) + " yakininda)";
        String z = cp.context.inDangerZone(h);
        return z == null ? null : "(tehlikeli bolge: " + z + ")";
    }

    @Override
    public void end(String why) {
        if (cp.active == this) cp.active = null;
        run = false;
        Npc n = npcId >= 0 ? cp.context.findNpc(npcId) : null;
        // Daha onemli is (tamir / dusman) ya da araya giren modul yuzunden yarim kaldiysa NPC hatirlanir.
        // Araya alinan NPC'de kesilirse asil hedef (halfId) korunur, uzerine yazilmaz.
        boolean keepMain = cp.halfId >= 0 && npcId == weakPick;
        // Gemi patladiysa NPC hatirlanmaz (canlaninca dogrudan olunen yere, patlatanin yanina gidiliyordu).
        boolean died = why != null && why.contains("patladi");
        if (died) cp.halfId = -1; // dusman saldirisinda once kaydedilmis olabilir: olunen yere donulmez
        if (n != null && !keepMain && !died && !"bitti".equals(why) && Context.killable(n) && Context.hp(n) < 0.95) {
            cp.halfId = npcId;
            cp.halfUntil = Context.now() + CombatLogic.HALF_MS;
            Log.i("COMBAT", "yarim kaldi (" + why + "): " + Log.npc(n) + ", sonra geri donulecek");
        }
        Log.i("COMBAT", "tam kontrol bitti (" + why + ") " + (Context.now() - start) / 1000 + " sn, " + kills + " kesim");
    }
}