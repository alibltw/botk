package com.botplus.core;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.config.types.NpcInfo;
import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.entities.Player;
import eu.darkbot.api.game.items.Item;
import eu.darkbot.api.game.other.Location;

import java.util.HashMap;
import java.util.Map;

/**
 * NPC secimini yonlendirir: En Yakin NPC, Yetenek Bekle / HP Bekle, Siradaki Hedefe Hazirlan,
 * Kacan NPC'yi Kovala, Otomatik Menzil, Vuramiyorsa Menzili Dusur.
 * Kovalama ve bekleme gecici is (PlusModule) olarak yapilir: is surerken DarkBot araya girmez, gemi durup kalkmaz.
 * Kacan NPC'den menzil ogrenilmez: kacis baslayinca isabet sayaci sifirlanir, kacis bitince birkac saniye veri alinmaz.
 */
public final class Targeting {

    private static final long HOLD_MS = 1000;
    private static final long HOLD_RENEW_MS = 500;
    private static final int KEEP_TARGET_EXTRA = 300;
    private static final double NEXT_TARGET_HP = 0.10;
    private static final int NEXT_TARGET_SEARCH = 3000;

    // Kacis tespiti: 500 ms'de bir NPC'nin kendi hareketinin gemiden uzaklasan bileseni olculur
    private static final long FLEE_SAMPLE_MS = 500;
    private static final double FLEE_AWAY_PER_SEC = 80;
    private static final int FLEE_CALM_SAMPLES = 2;
    private static final long NO_LEARN_AFTER_MS = 3000;
    private static final long CHASE_COOLDOWN_MS = 1500;
    private static final long CHASE_STOP_STILL_MS = 1500;
    private static final long CHASE_IN_RANGE_MS = 3000;

    private static final int RANGE_UP = 10;
    private static final int RANGE_DOWN = 10;
    private static final int RANGE_MIN = 150;
    private static final int UP_HITS = 5;
    private static final long UP_TIME_MS = 5000;
    private static final long HIT_GAP_MS = 2500;
    private static final long RANGE_COOLDOWN_MS = 5000;
    private static final long NO_DAMAGE_MS = 60_000;
    private static final long DOWN_STEP_MS = 5000;

    /** HP Bekle siniri (menude, varsayilan %70). */
    private double waitHp() {
        // Tamirin bitis caninin ustu beklenmez (tamir orada durur, sonra her NPC'de bos yere 60 sn beklenirdi).
        BotPlusConfig.Repair r = context.config.REPAIR;
        double end = Math.max(r.START_HP, r.END_HP) / 100.0;
        double w = context.config.PLUS.TARGET.HP_WAIT.HP / 100.0;
        return end > 0 ? Math.min(w, end) : w;
    }

    private static final int WAIT_MARGIN = 400;
    private static final double WAIT_SPOT_MAX = 1200;
    /** Beklerken diger NPC'lerden uzaklik: menzilleri + bu (yakininda can dolmaz, saldiri gelir). */
    private static final int WAIT_OTHER = 900;
    private static final long WAIT_MAX_MS = 60_000;
    private static final long WAIT_HIT_MS = 4000;
    private static final long WAIT_OVERRIDE_MS = 60_000;

    private final Context context;
    /** Bizim tuttugumuz (Bans HOLD) NPC'ler: id -> bizim bitis (yenileme zamani ve birakmak icin). */
    private final Map<Integer, Long> held = new HashMap<>();
    private static final int KEEP_HOLD_DIST = 2000;
    /** Beklemeden vazgecilen NPC'ler (bize saldirdi / zaman doldu): id -> bitis. */
    private final Map<Integer, Long> waitOverride = new HashMap<>();
    /** Ates ettigimiz NPC'ler (dovus basladi): id -> son ates. Bekleme ve kacis tespiti icin. */
    private final Map<Integer, Long> fought = new HashMap<>();
    /** Kovalama zaman asimina ugrayan NPC'ler: id -> bitis (tekrar kovalanmaz). */
    private final Map<Integer, Long> chaseBlock = new HashMap<>();
    /** Lazer menzili tavani (NPC turu -> {sinir, zaman}): oyun bu mesafede "menzil disi" dedi, otomatik menzil bunu asmaz. */
    private static final Map<String, long[]> LASER_CAP = new HashMap<>();
    /** NPC turu -> kullanicinin NPC tablosundaki ilk menzil (BotPlus degistirmeden once). */
    private static final Map<String, Integer> ORIGINAL = new HashMap<>();
    /** Ogrenme tablodaki asil menzilin bu oraninin altina inemez (yanlis "menzil disi" mesajlari 660'i 500'e dusurmesin). */
    private static final double MIN_OF_ORIGINAL = 0.9;
    /** NPC turu -> BotPlus'in tabloya en son yazdigi menzil (kullanici elle degistirdiyse geri yuklenmez). */
    private static final Map<String, Integer> LAST_SET = new HashMap<>();

    /**
     * Asil tablo menzillerinin kalici kaydi (BotPlus ayar dosyasi, Settings baglar). Eskiden sadece bellekteydi:
     * DarkBot ayari kaydedince dusurulen menzil tabloya kalici yaziliyor, sonraki acilista dusmus deger "asil"
     * saniliyor ve menzil her oturumda biraz daha iniyordu (660 -> 613 -> ...).
     */
    private static Map<String, Integer> store = new HashMap<>();
    /** Kalici: NPC turu -> BotPlus'in tabloya en son yazdigi deger (tablo buna esitse BotPlus'in degeri, degilse elle). */
    private static Map<String, Integer> written = new HashMap<>();
    private static Runnable storeSaver = () -> { };
    /** "Asil Menzillere Don" sayaci: degisince ornekler (lazer siniri, menzil disi, hasar mesafesi) unutulur. */
    private static volatile int restoreGen;
    private int seenRestoreGen;

    public static void useStore(Map<String, Integer> s, Map<String, Integer> w, Runnable saver) {
        // Profil degisti (farkli ayar dosyasi): onceki profilin asil / yazilan degerleri karismasin.
        if ((s != null && s != store) || (w != null && w != written)) {
            ORIGINAL.clear();
            LAST_SET.clear();
            LASER_CAP.clear();
            restoreGen++;
        }
        if (s != null) store = s;
        if (w != null) written = w;
        if (saver != null) storeSaver = saver;
    }

    /** BotPlus tabloya menzil yazdi: oturum ve kalici kayda islenir. */
    private static void wrote(NpcInfo info) {
        int v = (int) info.getRadius();
        LAST_SET.put(info.getName(), v);
        Integer old = written.put(info.getName(), v);
        if (old == null || old != v) persist();
    }

    private static void persist() {
        try {
            storeSaver.run();
        } catch (RuntimeException ignored) {
        }
    }

    /** Ogrenme tablodaki asil menzilin bu oraninin ustune cikamaz. */
    private static final double MAX_OF_ORIGINAL = 1.10;

    /** Tablodaki menzil degismeden once asil degeri saklanir; donus: asil deger. */
    private static int original(NpcInfo info) {
        String name = info.getName();
        int cur = (int) info.getRadius();
        Integer o = ORIGINAL.get(name);
        if (o != null) {
            // Tablo ne BotPlus'in son yazdigi ne de asil deger: kullanici elle degistirdi (ornek 613 -> 660),
            // yeni deger asil deger olur.
            Integer last = LAST_SET.get(name);
            if ((last != null ? cur != last : cur != o) && cur != o && siblingWrite(name, cur)) {
                // Gruplu tablo kardesin degerini kopyaladi: BotPlus'in degeri sayilir (geri yuklenebilsin).
                LAST_SET.put(name, cur);
                written.put(name, cur);
                persist();
                return o;
            }
            if (last != null ? cur != last : cur != o) {
                handEdit(name, cur, last != null ? last : o);
                return cur;
            }
            return o;
        }
        // Oturumun ilk okumasi. Tablo BotPlus'in en son yazdigi degerse (onceki oturumdan kalan) asil deger
        // kayittakidir; tablo kayittaki asil degerse o; ikisi de degilse kullanici elle girmistir, tablo asil olur.
        Integer saved = store.get(name), w = written.get(name);
        int orig;
        if (saved != null && w != null && cur == w) {
            orig = saved;
            if (cur != saved) LAST_SET.put(name, cur); // kapanista / "Asil Menzillere Don" ile geri yuklenebilsin
        } else if (saved != null && cur == saved) {
            orig = saved;
        } else if (saved != null && siblingWrite(name, cur)) {
            orig = saved; // gruplu tablo kardesin dusurulmus degerini kopyalamis
            LAST_SET.put(name, cur);
            written.put(name, cur);
        } else {
            orig = cur;
            if (saved != null) Log.i("MENZIL", name + " tablo menzili elle degistirilmis (" + saved + " -> " + cur + "), yeni asil deger " + cur);
        }
        ORIGINAL.put(name, orig);
        boolean dirty = saved == null || saved != orig;
        if (dirty) store.put(name, orig);
        if (w != null && !LAST_SET.containsKey(name)) {
            written.remove(name);
            dirty = true;
        }
        if (dirty) persist();
        return orig;
    }

    private static void handEdit(String name, int cur, int was) {
        ORIGINAL.put(name, cur);
        LAST_SET.remove(name);
        store.put(name, cur);
        written.remove(name);
        LASER_CAP.remove(name);
        restoreGen++;
        Log.i("MENZIL", name + " tablo menzili elle degistirildi (" + was + " -> " + cur + "), yeni asil deger " + cur);
        persist();
    }

    /** Ogrenmenin cikabilecegi en ust menzil: asil tablo menzilinin %110'u. */
    public static int learnCeil(NpcInfo info) {
        return (int) Math.round(original(info) * MAX_OF_ORIGINAL);
    }

    /**
     * "Asil Menzillere Don": kalici kayittaki tum NPC'lerin menzili asil degerine geri yuklenir (tablo degeri asilin
     * %90-%110 bandindaysa; band disindaysa kullanici elle degistirmistir, dokunulmaz). Donus: geri yuklenen sayisi.
     */
    public static int restoreAll(java.util.function.Function<String, NpcInfo> lookup) {
        int n = 0;
        for (Map.Entry<String, Integer> e : new HashMap<>(store).entrySet()) {
            try {
                NpcInfo info = lookup.apply(e.getKey());
                if (info == null) continue;
                int cur = (int) info.getRadius(), orig = e.getValue();
                LASER_CAP.remove(e.getKey());
                // Sadece BotPlus'in yazdigi deger geri alinir; elle girilen deger korunur.
                Integer w = written.get(e.getKey());
                if (cur == orig || w == null || cur != w) continue;
                info.setRadius(orig);
                LAST_SET.remove(e.getKey());
                written.remove(e.getKey());
                ORIGINAL.put(e.getKey(), orig);
                Log.i("MENZIL", "GERI " + e.getKey() + " " + cur + " -> " + orig + " (asil tablo degeri)");
                n++;
            } catch (RuntimeException ignored) {
            }
        }
        restoreGen++;
        persist();
        return n;
    }

    /** Ogrenmenin inebilecegi en alt menzil: asil tablo menzilinin %90'i. */
    public static int learnFloor(NpcInfo info) {
        return (int) Math.round(original(info) * MIN_OF_ORIGINAL);
    }

    /** BotPlus'in degistirdigi tum NPC menzilleri kullanicinin asil degerlerine geri doner (BotPlus kapanirken). */
    public static void restoreRadii(java.util.function.Function<String, NpcInfo> lookup) {
        // Kalici "yazilan" kaydindaki tum NPC'ler (bu oturumda hic gorulmeyenler de: onceki oturumda dusurulup
        // kalmis olabilir). Tablo hala BotPlus'in yazdigi degerse asil degere doner; elle degistirildiyse dokunulmaz.
        for (Map.Entry<String, Integer> e : new HashMap<>(written).entrySet()) {
            try {
                String name = e.getKey();
                NpcInfo info = lookup.apply(name);
                Integer orig = ORIGINAL.containsKey(name) ? ORIGINAL.get(name) : store.get(name);
                if (info == null || orig == null || (int) info.getRadius() != e.getValue() || orig.equals(e.getValue())) continue;
                Log.i("MENZIL", "GERI " + name + " " + (int) info.getRadius() + " -> " + orig + " (asil tablo degeri)");
                info.setRadius(orig);
                written.remove(name);
                LAST_SET.remove(name);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * DarkBot'un gruplu NPC tablosu ("X 1", "X 2") bir satirin menzilini kardeslerine kopyalar: kardese BotPlus'in
     * yazdigi deger elle degisiklik sayilmaz (yoksa dusurulmus deger yeni asil olup menzil kayardi).
     */
    private static boolean siblingWrite(String name, int cur) {
        String base = baseName(name);
        for (Map.Entry<String, Integer> e : written.entrySet())
            if (!e.getKey().equals(name) && e.getValue() == cur && baseName(e.getKey()).equals(base)) return true;
        return false;
    }

    private static String baseName(String n) {
        return n == null ? "" : n.replaceAll("\\s*\\d+$", "").trim();
    }
    /** Lazer siniri 30 dk sonra unutulur (gemi / cephane degisirse yeniden ogrenilir). */
    private static final long LASER_CAP_MS = 30 * 60_000L;
    /** "Menzil disi" mesaji bu sure icinde ates edilen NPC icin de sayilir (mesaj ates kesildikten sonra gelir). */
    private static final long OOR_SHOT_MS = 1500;
    private long nextTargetAt;
    /** Son tikteki hedef (degisince bizim tutmalarimiz kaldirilir). */
    private int lastCurId = -1;
    private int awaySamples;
    private double fleeStartHp;

    // Kacis durumu (hedef NPC)
    private int fleeNpc = -1;
    private long fleeAt;
    private Location fleePos;
    private boolean fleeingNow;
    private int calmSamples;
    private long fleeStartedAt, fleeEndedAt;
    private double fleeSpeed;
    private long chaseCooldown;
    private boolean chaseActive;

    // Otomatik Menzil
    private int upNpc = -1;
    private int hits;
    private long hitStart, lastHitAt, rangeCooldown;
    private double lastHeroTotal = -1;

    // Vuramiyorsa Menzili Dusur
    private int downNpc = -1;
    private long shootStart, lastDealt;
    private double lastNpcTotal = -1;
    private boolean downStarted;

    public Targeting(Context context) {
        this.context = context;
    }

    public boolean chasing() {
        return chaseActive;
    }

    public void reset() {
        releaseHolds();
        resetUp();
        downNpc = -1;
        lastNpcTotal = -1;
        fleeNpc = -1;
        fleeingNow = false;
        calmSamples = 0;
        awaySamples = 0;
        chaseActive = false;
    }

    /** Log icin hedefin kacis durumu. */
    public String fleeInfo() {
        return fleeingNow ? "kaciyor (" + (int) fleeSpeed + "/sn uzaklasiyor)" : "kacmiyor";
    }

    /** Her tik (BotPlus isi yokken): NPC tutma, kacis tespiti, menzil. */
    public void tick(boolean steer) {
        long now = Context.now();
        cleanHeld(now);
        markFought(now);

        BotPlusConfig.Plus plus = context.config.PLUS;
        Npc cur = context.targetNpc();
        boolean shooting = context.shooting(cur);
        // Hedef degisti (yok olduysa da): onceki hedef icin konan tutmalar kaldirilir, asagida yeni hedefe gore konur.
        int curId = cur == null ? -1 : cur.getId();
        if (curId != lastCurId) {
            lastCurId = curId;
            releaseHolds();
        }

        holdWaitFlags(cur, now);
        // Hedef secildiyse ayni oncelikteki baska NPC'ye donulmez (daha oncelikli NPC cikarsa DarkBot ona gecer).
        // Hedef beklenecekse (YB / HB) tutulmaz: beklerken baska serbest NPC secilebilsin. Dovus baslamissa
        // (bu NPC'ye ates edildi) gemi cani HP Bekle'nin altina inse de hedef birakilmaz.
        boolean engaged = cur != null && Context.killable(cur) && (fought(cur) || waitReason(cur) == null);
        if (engaged && cur.isBlacklisted() && heldByUs(cur)) release(cur); // sadece bizim tutmamiz: hedef serbest
        if (engaged && !cur.isBlacklisted()) keepTarget(cur, now);
        else if (plus.NEAREST()) nearest(cur, now);

        updateFlee(cur, now);
        if (steer && plus.NEXT_TARGET() && !context.config.COMBAT.ENABLED) nextTarget(cur, shooting);
        learn(cur, shooting, true, now);
    }

    /**
     * Combat Plus her tik cagirir: kacis tespiti ve menzil ogrenme (is surerken de).
     * count: gemi tablo menzilinde duruyor (yay / sabit, dovus menzili kucultulmemis); degilse isabet sayilmaz.
     */
    public void observe(Npc cur, long now, boolean count) {
        cleanHeld(now);
        markFought(now);
        updateFlee(cur, now);
        learn(cur, context.shooting(cur), count, now);
    }

    private void markFought(long now) {
        Npc cur = context.targetNpc();
        if (cur != null && context.shooting(cur)) fought.put(cur.getId(), now);
        if (fought.size() > 200) fought.values().removeIf(t -> now - t > 120_000);
    }

    /** Bu NPC'ye ates edildi mi (son 2 dk). */
    public boolean fought(Npc n) {
        Long t = n == null ? null : fought.get(n.getId());
        return t != null && Context.now() - t < 120_000;
    }

    /** Hedef kaciyor mu: kacis durumu sadece onu baslatan NPC hedefteyken gecerli (sonraki NPC'ye tasinmaz). */
    public boolean fleeing() {
        if (!fleeingNow) return false;
        Npc cur = context.targetNpc();
        return cur != null && cur.getId() == fleeNpc;
    }

    /** Hedef degisti / oldu / birakildi: kacis durumu silinir (Combat Plus her tik observe cagirmadigi icin). */
    public void forgetFlee() {
        if (fleeingNow) endFlee(Context.now(), "hedef degisti");
        fleeNpc = -1;
        awaySamples = 0;
        calmSamples = 0;
        fleeStartHp = 0;
    }

    /** NPC'lerin kacmaya basladigi can (oyunda ~%20): ogrenildiyse o, yoksa %25. */
    private static double fleeThreshold(Npc n) {
        double fh = fleeHp(n);
        return fh > 0 ? Math.min(0.30, fh + 0.05) : 0.25;
    }

    /** NPC turune gore kacmaya basladigi ortalama can (ogrenilmis), bilinmiyorsa -1. */
    public static double fleeHp(Npc n) {
        if (n == null || n.getInfo() == null) return -1;
        Double v = FLEE_HP.get(n.getInfo().getName());
        return v == null ? -1 : v;
    }

    private static final Map<String, Double> FLEE_HP = new HashMap<>();

    /**
     * Menzil ogrenme. "Kacan NPC'den Menzil Ogrenme" aciksa NPC kacarken ve sonraki 3 sn ogrenilmez;
     * NPC hareket ederken de isabet sayilmaz (NPC durunca vurur, olcum o zaman dogru).
     */
    /** count: isabet sayilabilir an (Combat Plus: gemi tablo menzilinde); false iken sayac silinmez, sadece sayilmaz. */
    private void learn(Npc cur, boolean shooting, boolean count, long now) {
        BotPlusConfig.Plus plus = context.config.PLUS;
        // Menzil ayarlari menuden: acikken kesilecek tum NPC'lerde (eski MB bayragi kaldirildi).
        boolean findRange = cur != null && cur.getInfo() != null;
        boolean noLearn = noLearn(now);
        if (cur != null) recordDist(cur, now);
        if (cur != null && shooting) noteDamage(cur, now);
        // Hareketli NPC'nin isabeti hicbir zaman sayilmaz (NPC durunca vurur, olcum o zaman dogru).
        boolean moving = cur != null && cur.isMoving();
        if (plus.AUTO_RANGE() && findRange && !noLearn && moving) {
            // Hareketli NPC: isabet sayilmaz, durunca temiz olcum baslar.
            lastHeroTotal = -1;
            lastDealt = now;
            Log.dEvery("movingnl", 5000, "MENZIL", () -> Log.npc(cur) + " hareketli, isabet sayilmiyor (" + hits + " isabet bekliyor)");
            return;
        }
        if (plus.AUTO_RANGE() && findRange && !noLearn) {
            if (count) autoRange(cur, shooting, now);
            else lastHeroTotal = -1; // bu an sayilmaz, sayac korunur
        } else {
            if (plus.AUTO_RANGE() && findRange && noLearn && hits > 0)
                Log.i("MENZIL", Log.npc(cur) + " " + fleeInfo() + ", " + hits + " isabet sayilmadi");
            resetUp();
        }
        if (plus.RANGE_DOWN() && findRange) {
            if (noLearn) {
                // Kacan NPC'ye hasar verilemez: "hasar yok" suresi isletilmez.
                lastDealt = now;
                if (cur != null && cur.getHealth() != null) lastNpcTotal = cur.getHealth().getHp() + cur.getHealth().getShield();
            } else rangeDown(cur, shooting, now);
        } else downNpc = -1;
        if (plus.AUTO_RANGE() && findRange && noLearn)
            Log.dEvery("noLearn", 5000, "MENZIL", () -> Log.npc(cur) + " " + fleeInfo() + ", menzil ogrenilmiyor (menzil "
                    + (int) cur.getInfo().getRadius() + ")");
    }

    /** Kacan NPC'den menzil ogrenilmesin mi. */
    private boolean noLearn(long now) {
        // Combat Plus'in durdurmasi (pauseLearn) ayardan bagimsiz her zaman gecerli.
        if (!context.config.PLUS.NO_LEARN_FLEE()) return chaseActive || fleeingNow || now < pausedUntil; // eski davranis: sadece kacarken
        return fleeingNow || chaseActive || now - fleeEndedAt < NO_LEARN_AFTER_MS || now < pausedUntil;
    }

    /**
     * NPC kaciyor mu: NPC hareket ediyor ve kendi hareketinin gemiden uzaklasan bileseni saniyede 80'den fazla.
     * Menzil icinde de olsa kacis sayilir. 2 olcum ust uste uzaklasmazsa kacis biter.
     * Kacis baslayinca otomatik menzilin isabet sayaci sifirlanir.
     */
    /** Kacis baslamasi icin ardisik uzaklasma olcumu. */
    private static final int FLEE_START_SAMPLES = 3;
    /** Bu canin altindaki NPC kacis sayilmaz (olmek uzere). */
    private static final double FLEE_MIN_HP = 0.05;

    private void updateFlee(Npc cur, long now) {
        if (cur == null || cur.getInfo() == null) {
            if (fleeingNow) endFlee(now, cur == null ? "hedef yok" : "NPC bilgisi yok");
            fleeNpc = -1;
            return;
        }
        if (cur.getId() != fleeNpc || fleePos == null) {
            if (fleeingNow) endFlee(now, "hedef degisti");
            fleeNpc = cur.getId();
            fleeAt = now;
            fleePos = Location.of(cur);
            calmSamples = 0;
            awaySamples = 0;
            if (!fleeingNow) fleeStartHp = 0;
            fleeEndedAt = 0; // onceki hedefin kacisi yeni hedefe tasinmaz
            return;
        }
        if (now - fleeAt < FLEE_SAMPLE_MS) return;
        double dt = (now - fleeAt) / 1000.0;
        double dx = cur.getX() - fleePos.getX(), dy = cur.getY() - fleePos.getY();
        double ux = cur.getX() - context.hero.getX(), uy = cur.getY() - context.hero.getY();
        double len = Math.max(1, Math.hypot(ux, uy));
        double away = (dx * ux + dy * uy) / len / dt;
        fleeAt = now;
        fleePos = Location.of(cur);
        fleeSpeed = away;

        // Sadece dovusulen NPC (ates edildi ya da menzile girildi) kacabilir: dolasan pasif NPC kacis sayilmaz.
        boolean engaged = fought(cur) || context.hero.distanceTo(cur) <= cur.getInfo().getRadius() * 1.2;
        // Gercek kacis sadece dusuk canda (oyunda ~%20): tam canli NPC'nin normal hareketi kacis sayilmaz.
        // Olmek uzere olan NPC (can < %5) kacis sayilmaz: yay sirasindaki normal hareketi "kacis" sanilip son atislarda
        // kovalamaya (menzil %30 dusuk) geciliyordu.
        boolean awayNow = engaged && cur.isMoving() && away > FLEE_AWAY_PER_SEC && Context.hp(cur) < fleeThreshold(cur)
                && Context.hp(cur) >= FLEE_MIN_HP;
        if (awayNow) {
            calmSamples = 0;
            // Baslamak icin 3 ardisik olcum (~1,5 sn surekli uzaklasma; eskiden 2: yay hareketi kacis sayiliyordu).
            // Kacis cani ilk olcumde alinir.
            if (!fleeingNow) {
                if (++awaySamples == 1) fleeStartHp = Context.hp(cur);
                if (awaySamples >= FLEE_START_SAMPLES) startFlee(cur, now);
            }
        } else {
            awaySamples = 0;
            if (!fleeingNow) fleeStartHp = 0;
            if (fleeingNow && ++calmSamples >= FLEE_CALM_SAMPLES) endFlee(now, cur.isMoving() ? "artik uzaklasmiyor" : "durdu");
        }
    }

    private String fleeName;

    private void startFlee(Npc cur, long now) {
        fleeingNow = true;
        if (fleeStartHp <= 0) fleeStartHp = Context.hp(cur); // ilk olcumde alinmadiysa
        NpcInfo info = cur.getInfo();
        fleeName = info == null ? null : info.getName();
        fleeStartedAt = now;
        String lost = hits > 0 ? ", " + hits + " isabet silindi (menzil ogrenilmiyor)" : "";
        Log.i("KACIS", "BASLADI " + Log.npc(cur) + " " + (int) fleeSpeed + "/sn uzaklasiyor, uzaklik "
                + (int) context.hero.distanceTo(cur) + (info == null ? "" : " menzil " + (int) info.getRadius())
                + " | kacis cani " + Log.pct(fleeStartHp) + lost);
        if (context.config.PLUS.NO_LEARN_FLEE()) {
            resetUp();
            lastDealt = now;
        }
    }

    private void endFlee(long now, String why) {
        fleeingNow = false;
        calmSamples = 0;
        fleeEndedAt = now;
        // Kacis cani: sadece gercek kacis (en az 2 sn, can 0 - %50 arasi) ogrenilir.
        if (fleeName != null && fleeStartHp > 0 && fleeStartHp <= 0.5 && now - fleeStartedAt >= 2000)
            FLEE_HP.merge(fleeName, fleeStartHp, (a, b) -> a * 0.7 + b * 0.3);
        fleeName = null;
        fleeStartHp = 0;
        Log.i("KACIS", "BITTI (" + why + ") " + (now - fleeStartedAt) / 100 / 10.0 + " sn"
                + (context.config.PLUS.NO_LEARN_FLEE() ? ", " + NO_LEARN_AFTER_MS / 1000 + " sn menzil ogrenilmeyecek" : ""));
    }

    // ---------------------------------------------------------------- yok sayma

    private void cleanHeld(long now) {
        held.values().removeIf(until -> until < now);
        waitOverride.values().removeIf(until -> until < now);
        chaseBlock.values().removeIf(until -> until < now);
    }

    /**
     * NPC'yi kisa sure yok sayar. Baskasinin koydugu uzun yok saymayi kisaltmaz: DarkBot'un "baskasi vuruyor" (20 sn)
     * yok saymasi bizimkinin uzerine gelmis olabilir, o yuzden baskasi vuruyorsa yenilenmez (bizim kayit silinir).
     */
    private boolean hold(Npc n, long now) {
        boolean ours = context.bans.has(n, Bans.Owner.HOLD);
        if (!ours) held.remove(n.getId());
        if (!ours && n.isBlacklisted()) return false; // baskasinin (ya da baska ozelligin) yok saymasi
        if (ours && context.attackedByOthers(n)) {
            release(n);
            return false;
        }
        Long until = held.get(n.getId());
        if (ours && until != null && until - now > HOLD_RENEW_MS) return true;
        context.bans.ban(n, Bans.Owner.HOLD, HOLD_MS);
        held.put(n.getId(), now + HOLD_MS);
        return true;
    }

    /** Bu NPC sadece bizim kisa tutmamiz yuzunden yok sayiliyor mu. */
    public boolean heldByUs(Npc n) {
        return context.bans.onlyBy(n, Bans.Owner.HOLD);
    }

    /** Tek NPC'nin bizim tutmasi kaldirilir (baskasinin yok saymasina dokunulmaz, Bans karar verir). */
    public void release(Npc n) {
        if (n == null) return;
        held.remove(n.getId());
        context.bans.release(n, Bans.Owner.HOLD);
    }

    // ---------------------------------------------------------------- YB / HB

    public String waitReasonPublic(Npc n) {
        return waitReason(n);
    }

    /** NPC'nin beklenmesi gerekiyorsa nedeni, yoksa null. */
    private String waitReason(Npc n) {
        if (!Context.killable(n) || waitOverride.containsKey(n.getId())) return null;
        NpcInfo i = n.getInfo();
        if (i.hasExtraFlag(BotPlusFlag.WAIT_ABILITY) && !abilityReady()) return "yetenek hazir degil";
        if (context.config.PLUS.TARGET.HP_WAIT.appliesTo(i) && context.heroHp() < waitHp())
            return "gemi cani " + Log.pct(context.heroHp()) + " < %" + context.config.PLUS.TARGET.HP_WAIT.HP;
        return null;
    }

    /** Hedef disindaki bekleme isaretli NPC'ler kisa sure tutulur (DarkBot onlari secmesin). Hedef bekleme isiyle beklenir. */
    private void holdWaitFlags(Npc cur, long now) {
        boolean abilityReady = abilityReady();
        boolean hpOk = context.heroHp() >= waitHp();
        if (abilityReady && hpOk) return;
        // Beklenmesi gerekmeyen baska NPC varsa beklenecekler tutulur (DarkBot onu secsin); yoksa tutulmaz,
        // DarkBot beklenecek NPC'yi hedef alir ve bekleme isi baslar.
        boolean other = false;
        for (Npc n : context.ents.getNpcs())
            if (Context.killable(n) && !n.isBlacklisted() && context.hero.distanceTo(n) < 3000 && waitReason(n) == null
                    && context.darkBotWouldKill(n) && context.move.getClosestDistance(n) < 500) {
                other = true;
                break;
            }
        if (!other) return;
        for (Npc n : context.ents.getNpcs()) {
            if (n == cur || !Context.killable(n)) continue;
            String why = waitReason(n);
            if (why != null && hold(n, now))
                Log.every("hold" + n.getId(), 10_000, "BEKLE", Log.npc(n) + " su an secilmiyor: " + why
                        + " (yakinda beklemesiz NPC var)");
        }
    }

    /** Hedef NPC bekleme isaretliyse ve henuz ates edilmiyorsa bekleme isi doner. */
    public PlusModule.Job checkWait() {
        Npc cur = context.targetNpc();
        // Dovus baslamissa (bu NPC'ye ates edildiyse, kalkan fazinda da) beklenmez.
        if (cur == null || fought(cur)) return null;
        String why = waitReason(cur);
        if (why == null) return null;
        Log.i("BEKLE", "BASLADI " + Log.npc(cur) + ": " + why + " | uzaklik " + (int) context.hero.distanceTo(cur)
                + " | " + context.dangerInfo(context.hero));
        Stats.count("bekle");
        return new Wait(cur);
    }

    /**
     * Bekleme: NPC hedefte kalir (birakilmaz, bulucu araya girmez), gemi NPC'nin menzili disinda bekler.
     * NPC bize saldirirsa, baskasi vurmaya baslarsa ya da 60 sn dolarsa beklemeden saldirilir.
     */
    public final class Wait implements PlusModule.Job {
        private final int npcId;
        private final String npcName;
        private final long start = Context.now();
        private long hitSince;
        private Location spot;
        private long spotAt;

        public Wait(Npc n) {
            this.npcId = n.getId();
            this.npcName = n.getInfo().getName();
        }

        @Override
        public String name() {
            return "BEKLE";
        }

        @Override
        public int priority() {
            return PlusModule.P_WAIT;
        }

        @Override
        public String status() {
            Npc n = context.findNpc(npcId);
            String why = n == null ? "" : waitReason(n);
            return npcName + " bekleniyor" + (why == null || why.isEmpty() ? "" : " (" + why + ")");
        }

        /** HP icin mi bekleniyor (tamir konfigurasyonu ve tamirci PET icin). */
        public boolean hpWait() {
            Npc n = context.findNpc(npcId);
            return n != null && context.config.PLUS.TARGET.HP_WAIT.appliesTo(n.getInfo()) && context.heroHp() < waitHp();
        }

        /** Bekleme yeri: diger NPC'lerin menzili + 400 ve dusmanlardan uzak (yoksa can dolmaz, saldiri gelir). */
        private boolean safeSpot(Location p, Npc target) {
            for (Npc o : context.ents.getNpcs())
                if (o.getId() != target.getId() && o.isValid() && !Context.isFake(o) && o.getInfo() != null
                        && o.distanceTo(p) < o.getInfo().getRadius() + WAIT_OTHER) return false;
            Player e = context.nearestEnemy(p);
            return e == null || e.distanceTo(p) > 2500;
        }

        private Location waitSpot(Npc n, double keep) {
            Location best = null;
            double bestScore = -Double.MAX_VALUE;
            for (int k = 0; k < 24; k++) {
                // NPC'yi gorecek kadar yakin (en fazla 1200): uzaklasinca NPC haritadan kaybolup elden gidiyordu.
                double r = Math.max(keep, Math.min(WAIT_SPOT_MAX, keep + (k % 4) * 70));
                Location p = Location.of(n, n.angleTo(context.hero) + k * Math.PI / 12, r);
                if (!context.move.canMove(p)) continue;
                double score = -Math.abs(r - keep) * 0.5 - context.hero.distanceTo(p) * 0.2;
                for (Npc o : context.ents.getNpcs()) {
                    if (o.getId() == n.getId() || !o.isValid() || Context.isFake(o) || o.getInfo() == null) continue;
                    double gap = o.distanceTo(p) - (o.getInfo().getRadius() + WAIT_OTHER);
                    if (gap < 0) score += gap * 5;
                }
                Player e = context.nearestEnemy(p);
                if (e != null) score -= Math.max(0, 3000 - e.distanceTo(p)) * 3;
                double edge = context.edgeDist(p);
                if (edge < 1000) score -= (1000 - edge) * 3;
                if (score > bestScore) {
                    bestScore = score;
                    best = p;
                }
            }
            if (best == null) best = context.ringPoint(n, keep, n.angleTo(context.hero), Math.PI / 6);
            if (Log.dDue("waitspot", 3000)) Log.i("BEKLE", "bekleme yeri " + Log.loc(best) + " (" + context.dangerInfo(best) + ")");
            return best;
        }

        private boolean override(Npc n, String why) {
            waitOverride.put(npcId, Context.now() + WAIT_OVERRIDE_MS);
            Log.i("BEKLE", Log.npc(n) + " " + why + ", beklemeden saldiriliyor (" + (Context.now() - start) / 1000 + " sn)");
            return false;
        }

        @Override
        public boolean tick() {
            long now = Context.now();
            Npc n = context.findNpc(npcId);
            if (n == null) {
                Log.i("BEKLE", npcName + " #" + npcId + " kayboldu");
                return false;
            }
            String why = waitReason(n);
            if (why == null) {
                Log.i("BEKLE", "TAMAM " + Log.npc(n) + ", gemi " + Log.pct(context.heroHp()) + " yetenek "
                        + (abilityReady() ? "hazir" : "hazir degil") + ", saldiriliyor (" + (now - start) / 1000 + " sn)");
                return false;
            }
            if (n.isAttacking(context.hero)) {
                if (hitSince == 0) hitSince = now;
                if (now - hitSince > WAIT_HIT_MS) return override(n, "bize saldiriyor (" + WAIT_HIT_MS / 1000 + " sn)");
            } else hitSince = 0;
            if (context.attackedByOthers(n)) {
                Player other = context.otherAttacker(n);
                String by = other == null ? "PET" : Log.ship(other);
                // "Saldirilani yok say" / "Ikinci saldir": baskasi vurunca beklemeden saldirilir (NPC elden gitmesin).
                if (Context.ignoresAttacked(n)) return override(n, "baskasi vuruyor (" + by + "), bayrak: saldirilani yok say / ikinci saldir");
                // Cal acikken ve NPC calinabiliyorsa yok sayilmaz: Cal hemen devralir.
                if (context.stealCan.test(n)) {
                    Log.i("BEKLE", Log.npc(n) + " baskasi vuruyor (" + by + "), Cal'a devredildi");
                    return false;
                }
                // Bayrak yoksa DarkBot kurali: baskasinin vurdugu NPC birakilir (Combat da almaz, ters yone gidilmez).
                context.bans.ban(n, Bans.Owner.WAIT, 20_000);
                if (java.util.Objects.equals(context.attack.getTarget(), n)) context.attack.setTarget(null);
                context.hero.setLocalTarget(null);
                Log.i("BEKLE", Log.npc(n) + " baskasi vuruyor (" + by + "), birakildi, 20 sn yok sayiliyor (DarkBot kurali)");
                return false;
            }
            if (now - start > WAIT_MAX_MS) return override(n, WAIT_MAX_MS / 1000 + " sn doldu");

            if (!java.util.Objects.equals(context.attack.getTarget(), n)) context.attack.setTarget(n);
            // Once kilit: NPC haritada secili gorunur (kaybolmaz), ama ates edilmez.
            if (!java.util.Objects.equals(context.hero.getTarget(), n)) context.attack.tryLockTarget();
            context.attack.stopAttack();
            double keep = n.getInfo().getRadius() + WAIT_MARGIN;
            double d = context.hero.distanceTo(n);
            // Yer en fazla 2 sn'de bir yeniden secilir (her tik degismesin); tehlike yoksa 8 sn korunur.
            boolean bad = spot == null || spot.distanceTo(n) < keep - 100 || !safeSpot(spot, n);
            if (spot == null || (bad && now - spotAt > 2000) || now - spotAt > 8000) {
                spot = waitSpot(n, keep);
                spotAt = now;
            }
            if (context.hero.distanceTo(spot) < 80) context.stop(); // yerinde bekle (can dolsun)
            if (!hpWait()) context.hero.setRoamMode(); // HP beklerken konfigurasyonu tamir konfigurasyonu (secici)
            if (context.hero.distanceTo(spot) >= 80) context.moveTo(spot);
            Log.every("waitstat" + npcId, 3000, "BEKLE", Log.npc(n) + " " + why + " | uzaklik " + (int) d + " (bekleme "
                    + (int) keep + ") | gemi " + Log.pct(context.heroHp()) + " | " + (now - start) / 1000 + " sn");
            return true;
        }
    }

    @SuppressWarnings("deprecation")
    private boolean abilityReady() {
        Character key = context.abilityKey.getValue();
        if (key == null) return true;
        try {
            Item item = context.items.getItem(key);
            return item == null || item.isReady();
        } catch (RuntimeException e) {
            return true;
        }
    }

    // ---------------------------------------------------------------- En Yakin NPC

    /** DarkBot'un sececegi NPC (bayrak kurallari) ya da sadece bizim tuttugumuz NPC. */
    private boolean candidate(Npc n) {
        return context.darkBotWouldKill(n) || (Context.killable(n) && heldByUs(n));
    }

    private void nearest(Npc cur, long now) {
        // Tek gecis: en iyi (kucuk) oncelik, o oncelikteki sayi ve en yakin NPC (yol hesabi NPC basina bir kez).
        int best = Integer.MAX_VALUE;
        Npc near = null;
        double nearDist = Double.MAX_VALUE;
        int count = 0;
        for (Npc n : context.ents.getNpcs()) {
            if (!candidate(n)) continue;
            int pri = n.getInfo().getPriority();
            if (pri > best || context.move.getClosestDistance(n) >= 500) continue;
            if (pri < best) {
                best = pri;
                near = null;
                nearDist = Double.MAX_VALUE;
                count = 0;
            }
            count++;
            double d = context.hero.distanceTo(n);
            if (d < nearDist) {
                nearDist = d;
                near = n;
            }
        }
        if (near == null || count < 2) return;

        Npc keep = near;
        if (cur != null && cur != near && candidate(cur) && cur.getInfo().getPriority() == best
                && (fought(cur) || waitReason(cur) == null) && context.hero.distanceTo(cur) < nearDist + KEEP_TARGET_EXTRA) keep = cur;
        if (heldByUs(keep)) release(keep); // secilen NPC bizim tuttugumuz olabilir: serbest birakilir

        for (Npc n : context.ents.getNpcs()) {
            if (n == keep || !Context.killable(n) || n.getInfo().getPriority() != best) continue;
            if (n.isAttacking(context.hero)) { // bize saldiran NPC tutulmaz
                if (held.containsKey(n.getId())) release(n);
                continue;
            }
            hold(n, now); // baskasinin yok saydigi NPC'yi hold atlar
        }
        Log.dEvery("near", 5000, "EN YAKIN", Log.npc(keep) + " secildi (" + (int) context.hero.distanceTo(keep)
                + " uzakta), ayni oncelikte " + (count - 1) + " NPC tutuldu");
    }

    // ---------------------------------------------------------------- Hedefte Kal

    /** Hedef olunce bizim kisa yok saymalarimiz kaldirilir (siradaki NPC hemen secilebilsin). */
    public void releaseHolds() {
        for (Integer id : new java.util.ArrayList<>(held.keySet())) {
            Npc n = context.findNpc(id);
            if (n != null) context.bans.release(n, Bans.Owner.HOLD); // suresi bitmisse / baskasininsa Bans dokunmaz
        }
        held.clear();
    }

    /**
     * Hedefte kal: ayni oncelikteki, gemiye 2000'den yakin NPC'ler kisa tutulur. Bize saldiran NPC tutulmaz;
     * hedef beklenecekse (YB / HB) hic tutulmaz (beklerken serbest NPC'ler secilebilsin).
     */
    private void keepTarget(Npc cur, long now) {
        if (cur.getInfo() == null || (!fought(cur) && waitReason(cur) != null)) return;
        int pri = cur.getInfo().getPriority();
        int heldCount = 0;
        for (Npc n : context.ents.getNpcs()) {
            if (n == cur || !Context.killable(n) || n.getInfo().getPriority() != pri) continue;
            if (n.isAttacking(context.hero) || context.hero.distanceTo(n) > KEEP_HOLD_DIST) {
                if (held.containsKey(n.getId())) release(n);
                continue;
            }
            if (hold(n, now)) heldCount++;
        }
        if (heldCount > 0)
            Log.every("keep" + cur.getId(), 10_000, "HEDEF", Log.npc(cur) + " hedefte kaliniyor, ayni oncelikte "
                    + heldCount + " NPC'ye donulmuyor");
    }

    // ---------------------------------------------------------------- Kacan NPC

    /** Hedef kaciyor ve tablodaki menzilin disina ciktiysa kovalama isi doner. */
    public PlusModule.Job checkChase() {
        BotPlusConfig.Chase c = context.config.CHASE;
        if (!c.ENABLED || !fleeingNow || Context.now() < chaseCooldown) return null;
        Npc cur = context.targetNpc();
        if (!Context.killable(cur) || cur.getId() != fleeNpc || chaseBlock.containsKey(cur.getId())) return null;
        if (waitReason(cur) != null) return null; // YB / HB: once bekleme
        double radius = cur.getInfo().getRadius();
        double d = context.hero.distanceTo(cur);
        if (d > context.npcDistanceIgnore()) return null;
        if (d <= radius) {
            Log.dEvery("chasein", 3000, "KOVALA", Log.npc(cur) + " kaciyor ama menzil icinde (" + (int) d + " <= "
                    + (int) radius + "), DarkBot takip ediyor");
            return null;
        }
        double range = radius * (1 - c.DROP / 100.0);
        Log.i("KOVALA", "BASLADI " + Log.npc(cur) + " uzaklik " + (int) d + " | tablo menzili " + (int) radius
                + " -> kovalama " + (int) range + " (-%" + c.DROP + ") | " + (int) fleeSpeed + "/sn uzaklasiyor");
        Stats.count("kovala");
        chaseActive = true;
        return new Chase(cur);
    }

    /**
     * Kovalama: tablodaki menzilin "Menzil Dususu" kadar altina, NPC'nin 0,4 sn sonraki tahmini yerine gidilir.
     * Menzil disindayken kacis konfigurasyonu (secici), menzile girince saldiri konfigurasyonu kullanilir.
     * NPC durup menzile girince ya da sure dolunca biter.
     */
    public final class Chase implements PlusModule.Job {
        private final int npcId;
        private final String npcName;
        private final long start = Context.now();
        private long stillSince, inRangeSince;
        private boolean run = true;
        private double maxDist;

        public Chase(Npc n) {
            this.npcId = n.getId();
            this.npcName = n.getInfo().getName();
        }

        /** Kacis konfigurasyonu istensin mi (BotPlus mod secicisi). */
        public boolean wantsRun() {
            return run;
        }

        @Override
        public String name() {
            return "KOVALA";
        }

        @Override
        public int priority() {
            return PlusModule.P_CHASE;
        }

        @Override
        public String status() {
            Npc n = context.findNpc(npcId);
            return npcName + " kovalanıyor" + (n == null ? "" : " (" + (int) context.hero.distanceTo(n) + ")");
        }

        @Override
        public boolean tick() {
            long now = Context.now();
            Npc n = context.findNpc(npcId);
            if (n == null) {
                Log.i("KOVALA", npcName + " #" + npcId + " kayboldu / oldu");
                return false;
            }
            if (!Context.killable(n) || n.isBlacklisted()) {
                Log.i("KOVALA", Log.npc(n) + " artik kesilmiyor / yok sayildi");
                return false;
            }
            if (context.attackedByOthers(n) && !Context.ignoresAttacked(n)) {
                // DarkBot kurali (baskasinin vurdugu NPC birakilir): kovalama biter, gerisini DarkBot / Cal halleder.
                Log.i("KOVALA", Log.npc(n) + " baskasi vuruyor, kovalama birakildi (DarkBot kurali)");
                return false;
            }
            if (now - start > context.config.CHASE.MAX_SEC * 1000L) {
                chaseBlock.put(npcId, now + 20_000);
                Log.i("KOVALA", context.config.CHASE.MAX_SEC + " sn doldu, " + Log.npc(n) + " uzaklik "
                        + (int) context.hero.distanceTo(n) + ", 20 sn tekrar kovalanmayacak");
                return false;
            }
            if (context.hero.distanceTo(n) > context.npcDistanceIgnore()) {
                chaseBlock.put(npcId, now + 20_000);
                Log.i("KOVALA", Log.npc(n) + " " + (int) context.hero.distanceTo(n) + " uzakta (DarkBot siniri "
                        + context.npcDistanceIgnore() + "), birakildi");
                return false;
            }
            double radius = n.getInfo().getRadius();
            double range = radius * (1 - context.config.CHASE.DROP / 100.0);
            double d = context.hero.distanceTo(n);
            maxDist = Math.max(maxDist, d);

            if (!n.isMoving() && d <= radius) {
                if (stillSince == 0) stillSince = now;
                if (now - stillSince > CHASE_STOP_STILL_MS) {
                    Log.i("KOVALA", "TAMAM " + Log.npc(n) + " durdu ve menzilde (" + (int) d + "), DarkBot devam ediyor");
                    return false;
                }
            } else stillSince = 0;
            // NPC menzil icinde dolasiyorsa (durmasa da) 3 sn sonra DarkBot'a birakilir: o etrafinda doner.
            if (d <= radius * 0.95) {
                if (inRangeSince == 0) inRangeSince = now;
                if (now - inRangeSince > CHASE_IN_RANGE_MS) {
                    Log.i("KOVALA", "TAMAM " + Log.npc(n) + " " + CHASE_IN_RANGE_MS / 1000 + " sn'dir menzilde (" + (int) d
                            + " <= " + (int) radius + "), DarkBot devam ediyor");
                    return false;
                }
            } else inRangeSince = 0;

            // Konfigurasyon gidip gelmesin: menzilin %15 disinda kacis, %10 icinde saldiri.
            if (run && d < radius * 0.9) run = false;
            else if (!run && d > radius * 1.15) run = true;

            if (!java.util.Objects.equals(context.attack.getTarget(), n)) context.attack.setTarget(n);
            context.attack.tryLockAndAttack();
            if (!run) context.hero.setAttackMode(n);

            Location predicted = n.getLocationInfo().destinationInTime(400);
            Location p = Location.of(predicted, predicted.angleTo(context.hero), range);
            if (!context.move.canMove(p)) p = Location.of(predicted, predicted.angleTo(context.hero), Math.max(100, range * 0.6));
            context.moveTo(p);
            Log.every("chase" + npcId, 2000, "KOVALA", Log.npc(n) + " uzaklik " + (int) d + " | menzil " + (int) radius
                    + " -> " + (int) range + " | NPC hizi " + n.getSpeed() + " | " + (run ? "kacis" : "saldiri")
                    + " konfigurasyonu | " + (now - start) / 1000 + " sn");
            return true;
        }

        @Override
        public void end(String why) {
            chaseActive = false;
            chaseCooldown = Context.now() + CHASE_COOLDOWN_MS;
            awaySamples = 0;
            if (fleeingNow) endFlee(Context.now(), "kovalama bitti");
            fleeEndedAt = Context.now();
            Log.i("KOVALA", "bitti (" + why + "), en buyuk uzaklik " + (int) maxDist
                    + (context.config.PLUS.NO_LEARN_FLEE() ? ", " + NO_LEARN_AFTER_MS / 1000 + " sn menzil ogrenilmeyecek" : ""));
        }
    }

    // ---------------------------------------------------------------- Siradaki Hedef

    private void nextTarget(Npc cur, boolean shooting) {
        if (!shooting || Context.hp(cur) >= NEXT_TARGET_HP) return;
        long now = Context.now();
        if (now < nextTargetAt) return; // DarkBot'un hareketiyle her tik cakisip gemiyi takirdatmasin
        nextTargetAt = now + 1000;
        Npc next = null;
        double best = NEXT_TARGET_SEARCH;
        for (Npc n : context.ents.getNpcs()) {
            if (n == cur) continue;
            double d = cur.distanceTo(n);
            if (d >= best || !context.darkBotWouldKill(n) || context.move.getClosestDistance(n) >= 500) continue;
            best = d;
            next = n;
        }
        if (next == null) return;
        double r = Math.min(cur.getInfo().getRadius(), 600);
        Location p = Location.of(cur, cur.angleTo(next), r);
        if (!context.move.canMove(p)) return;
        context.moveTo(p);
        Log.every("next", 3000, "SIRADAKI", Log.npc(cur) + " bitmek uzere, " + Log.npc(next) + " tarafina kayiliyor");
    }

    // ---------------------------------------------------------------- Otomatik Menzil

    private void resetUp() {
        hits = 0;
        hitStart = 0;
        upNpc = -1;
        lastHeroTotal = -1;
    }

    private void autoRange(Npc cur, boolean shooting, long now) {
        if (cur == null || !shooting) {
            resetUp();
            return;
        }
        if (cur.getId() != upNpc) {
            resetUp();
            upNpc = cur.getId();
        }
        double total = context.hero.getHealth().getHp() + context.hero.getHealth().getShield();
        boolean dropped = lastHeroTotal >= 0 && total < lastHeroTotal - 1;
        lastHeroTotal = total;

        if (now < rangeCooldown) return;
        double dist = context.hero.distanceTo(cur);
        boolean atRadius = dist >= cur.getInfo().getRadius() - 30;
        if (!cur.isAttacking(context.hero) || othersOnHero(cur) || !atRadius) {
            if (dropped && !atRadius && cur.isAttacking(context.hero))
                Log.dEvery("hitnear" + cur.getId(), 5000, "MENZIL", Log.npc(cur) + " vurdu ama gemi menzilin icinde ("
                        + (int) dist + " < " + (int) cur.getInfo().getRadius() + "), isabet sayilmadi");
            if (now - lastHitAt > HIT_GAP_MS) {
                hits = 0;
                hitStart = 0;
            }
            return;
        }
        if (dropped) {
            if (hitStart == 0) hitStart = now;
            hits++;
            lastHitAt = now;
            Log.d("MENZIL", Log.npc(cur) + " vurdu (" + hits + ". isabet) uzaklik " + (int) context.hero.distanceTo(cur)
                    + " menzil " + (int) cur.getInfo().getRadius() + " | gemi " + Log.pct(context.heroHp()));
        } else if (now - lastHitAt > HIT_GAP_MS) {
            hits = 0;
            hitStart = 0;
        }
        boolean longHit = hitStart > 0 && hits >= 2 && now - hitStart >= UP_TIME_MS;
        if (hits < UP_HITS && !longHit) return;

        NpcInfo info = cur.getInfo();
        int r = (int) info.getRadius();
        int capV = laserCap(cur);
        Integer cap = capV < 0 ? null : capV;
        if (cur.isMoving()) {
            Log.d("MENZIL", Log.npc(cur) + " hareket etti, artis yapilmadi (" + hits + " isabet silindi)");
        } else if (cap != null && r < cap && r + RANGE_UP > cap && cap <= Math.min(Context.ATTACK_RANGE, learnCeil(info))) {
            // Tam adim siniri asar: sinira kadar artirilir.
            original(info);
            info.setRadius(cap);
            wrote(info);
            save();
            Log.i("MENZIL", "ARTTI " + info.getName() + " " + r + " -> " + cap + " (lazer siniri, " + hits + " isabet, uzaklik "
                    + (int) context.hero.distanceTo(cur) + ")");
        } else if (cap != null && r + RANGE_UP > cap) {
            Log.every("upcap" + info.getName(), 30_000, "MENZIL", info.getName() + " menzil " + r + ", lazer siniri " + cap
                    + " (oyun bu mesafede menzil disi dedi), artirilmiyor");
        } else if (r >= Math.min(Context.ATTACK_RANGE, learnCeil(info))) {
            Log.every("upmax" + info.getName(), 30_000, "MENZIL", info.getName() + " menzil " + r
                    + " sinirda (asil " + original(info) + ", en fazla %110), NPC yine vuruyor (artirilmiyor)");
        } else {
            int nr = Math.min(Math.min(Context.ATTACK_RANGE, learnCeil(info)), r + RANGE_UP);
            original(info);
            info.setRadius(nr);
            wrote(info);
            save();
            Log.i("MENZIL", "ARTTI " + info.getName() + " " + r + " -> " + nr + " (" + hits + " isabet, "
                    + (now - hitStart) / 1000 + " sn, uzaklik " + (int) context.hero.distanceTo(cur) + ", NPC "
                    + (cur.isMoving() ? "hareketli" : "duruyor") + ", gemi cani " + Log.pct(context.heroHp()) + ")");
        }
        hits = 0;
        hitStart = 0;
        rangeCooldown = now + RANGE_COOLDOWN_MS;
    }

    /** Bize hedef disinda bir NPC ya da oyuncu da vuruyor mu. */
    private boolean othersOnHero(Npc cur) {
        for (Npc n : context.ents.getNpcs())
            if (n != cur && n.isValid() && !Context.isFake(n) && n.isAttacking(context.hero)) return true;
        for (Player p : context.ents.getPlayers())
            if (p.getId() != context.hero.getId() && p.isAttacking(context.hero)) return true;
        return false;
    }

    // ---------------------------------------------------------------- Menzili Dusur

    private void rangeDown(Npc cur, boolean shooting, long now) {
        if (cur == null || !shooting || cur.getHealth() == null) {
            downNpc = -1;
            return;
        }
        double total = cur.getHealth().getHp() + cur.getHealth().getShield();
        if (cur.getId() != downNpc) {
            downNpc = cur.getId();
            shootStart = now;
            lastDealt = now;
            lastNpcTotal = total;
            downStarted = false;
            return;
        }
        if (total < lastNpcTotal - 1) {
            if (downStarted) Log.i("MENZIL", cur.getInfo().getName() + " tekrar vuruluyor, menzil "
                    + (int) cur.getInfo().getRadius() + " kaydedildi");
            lastDealt = now;
            downStarted = false;
        }
        lastNpcTotal = total;

        long wait = downStarted ? DOWN_STEP_MS : NO_DAMAGE_MS;
        if (now - Math.max(shootStart, lastDealt) < wait) return;

        NpcInfo info = cur.getInfo();
        int r = (int) info.getRadius();
        // Ogrenilen mesafelerin altina inilmez: hasar verilen en uzak mesafe ve lazer siniri (lazer oraya yetisiyor,
        // hasar yoksa sebep menzil degil).
        int floor = Math.max(RANGE_MIN, learnFloor(info));
        Double dmg = damageDist.get(info.getName());
        if (dmg != null) floor = Math.max(floor, dmg.intValue());
        floor = Math.max(floor, laserCap(cur));
        if (r <= floor) {
            Log.every("downmin" + info.getName(), 30_000, "MENZIL", info.getName() + " menzil " + r
                    + (floor > RANGE_MIN ? " ogrenilen sinirda (" + floor + "), daha dusurulmuyor" : " en altta")
                    + ", NPC hala vurulamiyor");
            lastDealt = now;
            return;
        }
        int nr = Math.max(floor, r - RANGE_DOWN);
        original(info);
        info.setRadius(nr);
        wrote(info);
        save();
        Log.i("MENZIL", "DUSTU " + info.getName() + " " + r + " -> " + nr + " (" + (now - lastDealt) / 1000
                + " sn hasar verilemedi, uzaklik " + (int) context.hero.distanceTo(cur) + ")");
        downStarted = true;
        lastDealt = now;
    }

    private boolean petAttacking(Npc n) {
        try {
            return context.pet.isValid() && context.pet.isAttacking(n);
        } catch (RuntimeException e) {
            return true; // bilinmiyorsa sayilmaz
        }
    }

    /** NPC turu -> hasar verdigimiz en buyuk mesafe (lazer en az bu kadar uzaga yetisir). */
    private final Map<String, Double> damageDist = new HashMap<>();

    /** Bu NPC turunde sadece bizim lazerimizle hasar verilen en uzak mesafe (lazerin yetistigi kanitli), bilinmiyorsa -1. */
    public double damageRange(Npc n) {
        if (n == null || n.getInfo() == null) return -1;
        Double d = damageDist.get(n.getInfo().getName());
        return d == null ? -1 : d;
    }
    private final Map<String, java.util.List<double[]>> oorSamples = new HashMap<>();
    private long oorAt;
    private double lastDmgTotal = -1;
    private int lastDmgNpc = -1;

    /** Ates ederken NPC'nin cani dustuyse o mesafe "lazer yetisiyor" kaniti olarak tutulur. */
    /** Son 3 sn'nin gemi-NPC mesafeleri (zaman, npc id, mesafe): hasar ve "menzil disi" gecikmeli gorulur. */
    private final java.util.ArrayDeque<double[]> distHist = new java.util.ArrayDeque<>();

    private void recordDist(Npc cur, long now) {
        distHist.addLast(new double[]{now, cur.getId(), context.hero.distanceTo(cur)});
        while (!distHist.isEmpty() && now - distHist.peekFirst()[0] > 3000) distHist.pollFirst();
    }

    /**
     * Olayin (hasar / "menzil disi") gercekten oldugu andaki mesafe: ping + 300 ms onceki kayit. Eskiden olayin
     * goruldugu anki mesafe kullaniliyordu; gemi bu arada yayda uzaklastigi icin "hasar verilen en uzak mesafe"
     * 790'a sisiyor, lazer siniri hic ogrenilemiyordu. Kayit yoksa su anki mesafe.
     */
    private double distAtEvent(Npc cur, long now) {
        long back = 300;
        try {
            back += Math.max(0, Math.min(1000, context.stats.getPing()));
        } catch (RuntimeException ignored) {
        }
        double best = context.hero.distanceTo(cur);
        long target = now - back;
        double bestDt = Double.MAX_VALUE;
        for (double[] h : distHist) {
            // Eski kayitlar (duraklama sonrasi listede kalanlar) kullanilmaz.
            if ((int) h[1] != cur.getId() || now - h[0] > 3000) continue;
            double dt = Math.abs(h[0] - target);
            if (dt < bestDt) {
                bestDt = dt;
                best = h[2];
            }
        }
        return best;
    }

    /** "Asil Menzillere Don" / elle degisiklik sonrasi eski ornekler unutulur (eski menzile gore toplanmislardi). */
    private void syncRestore() {
        int g = restoreGen;
        if (g == seenRestoreGen) return;
        seenRestoreGen = g;
        oorSamples.clear();
        damageDist.clear();
    }

    private void noteDamage(Npc cur, long now) {
        if (cur.getInfo() == null || cur.getHealth() == null) return;
        original(cur.getInfo()); // elle degisiklik once fark edilsin (eski ornekler yeni degeri dusurmesin)
        syncRestore();
        double total = cur.getHealth().getHp() + cur.getHealth().getShield();
        double max = cur.getHealth().getMaxHp() + cur.getHealth().getMaxShield();
        // Sadece bizim lazerimiz vuruyorken: PET ya da baska oyuncu da vuruyorsa can dususu lazerin yetistigini gostermez
        // (eskiden PET hasari yuzunden "hasar verilen en uzak" 925'e cikip lazer sinirini bozuyordu).
        // Lazer menzilinde (800) ates ederken; tek olcumde %15'ten buyuk dusus roket / patlama sayilir.
        double d = distAtEvent(cur, now);
        boolean onlyUs = !context.attackedByOthers(cur) && !petAttacking(cur)
                && context.hero.isAttacking(cur) && d <= Context.ATTACK_RANGE;
        double drop = lastDmgTotal - total;
        if (onlyUs && cur.getId() == lastDmgNpc && lastDmgTotal >= 0 && drop > 1 && (max <= 0 || drop <= max * 0.15))
            damageDist.merge(cur.getInfo().getName(), d, Math::max);
        lastDmgNpc = cur.getId();
        lastDmgTotal = total;
    }

    /**
     * Oyun "menzil disi" dedi. Guvenilir olcum sartlari: gemi ve NPC duruyor, bu NPC'ye gercekten ates ediliyor,
     * NPC kacmiyor / kovalanmiyor, roket ayari kapali (mesaj roketten de gelebilir), en fazla 2 sn'de bir ornek.
     * En az 5 ornegin ortancasi - 15 lazer siniri olur (30 dk gecerli); son 1,5 sn icinde ates edilen NPC de sayilir.
     * Otomatik Menzil + Menzili Dusur acikken tablodaki menzil bu sinira indirilir.
     */
    private long pausedUntil;

    /** Menzil ogrenmeyi bir sure durdur (Combat Plus bilerek menzilden cikarken). */
    public void pauseLearn(long until) {
        pausedUntil = Math.max(pausedUntil, until);
    }

    public void outOfRange(Npc cur) {
        if (cur == null || cur.getInfo() == null) return;
        original(cur.getInfo()); // elle degisiklik once fark edilsin (eski ornekler yeni degeri dusurmesin)
        syncRestore();
        long now = Context.now();
        // Otomatik Menzil ve Menzili Dusur kapaliyken tek menzil referansi NPC tablosudur: lazer siniri ogrenilmez,
        // tabloya / dovus mesafesine dokunulmaz.
        if (!context.config.PLUS.AUTO_RANGE() && !context.config.PLUS.RANGE_DOWN()) return;
        double d = distAtEvent(cur, now);
        NpcInfo info = cur.getInfo();
        String name = info.getName();
        int r = (int) info.getRadius();
        // Mesaj ates kesildikten hemen sonra da gelir: son 1,5 sn icinde bu NPC'ye ates edildiyse sayilir.
        Long shot = fought.get(cur.getId());
        boolean shotRecently = context.shooting(cur) || (shot != null && now - shot <= OOR_SHOT_MS);
        String why = null;
        // NPC ve gemi hareket ederken de ogrenilir (Uber NPC'ler hic durmuyor; ping hatasi ~20-30 birim, ortanca ile duzelir).
        if (fleeingNow || chaseActive) why = "NPC kaciyor / kovalaniyor";
        else if (noLearn(now)) why = now < pausedUntil ? "bilerek menzilden cikis (ogrenme durduruldu)" : "kacis yeni bitti (3 sn)";
        else if (!shotRecently) why = "bu NPC'ye ates edilmiyor";
        else if (shot == null) why = "ilk vurus oncesi";
        else if (d >= Math.max(r, laserCap(cur)) + 100 || d < 300) why = "mesafe tablo menziline / lazer sinirina uymuyor";
        else if (damageDist.containsKey(name) && d < damageDist.get(name) - 50)
            why = "hasar verilen mesafenin altinda (" + damageDist.get(name).intValue() + ")";
        else if (now - oorAt < 2000) why = "2 sn dolmadi";
        else if (context.hero.isMoving() && d > r * 0.95) why = "gemi hala yaklasiyor";
        if (why != null) {
            Log.d("MENZIL", "menzil disi mesaji ogrenilmedi (" + why + "), uzaklik " + (int) d + " menzil " + r);
            return;
        }
        oorAt = now;
        java.util.List<double[]> list = oorSamples.computeIfAbsent(name, k -> new java.util.ArrayList<>());
        list.removeIf(x -> now - x[1] > LASER_CAP_MS); // eski ornekler (gemi / cephane degismis olabilir)
        list.add(new double[]{d, now});
        if (list.size() > 9) list.remove(0);
        if (list.size() < 5) {
            Log.i("MENZIL", name + " menzil disi ornegi " + list.size() + "/5: uzaklik " + (int) d + " (tablo " + r + ")");
            return;
        }
        java.util.List<Double> sorted = new java.util.ArrayList<>();
        for (double[] x : list) sorted.add(x[0]);
        java.util.Collections.sort(sorted);
        double median = sorted.get(sorted.size() / 2);
        double floor = damageDist.getOrDefault(name, 0.0);
        // Sinir oyunun "menzil disi" dedigi mesafenin ALTINDA olmali: ortanca - 15; hasar verilen mesafe taban olur.
        int cap = (int) Math.max(RANGE_MIN, median - 15);
        int lf = learnFloor(info);
        if (cap < lf) {
            Log.i("MENZIL", name + " menzil disi ortancasi " + (int) median + " asil tablo menzilinin (" + original(info)
                    + ") cok altinda, sinir " + lf + " alindi (yanlis mesaj olabilir)");
            cap = lf;
        }
        // Lazer en az hasar verdigimiz mesafeye yetisir: sinir onun altina inmez (ortancadan kucukse).
        if (floor > 0 && floor < median) cap = Math.max(cap, (int) floor);
        long[] old = LASER_CAP.get(name);
        LASER_CAP.put(name, new long[]{cap, now});
        if (old == null || old[0] != cap)
            Log.i("MENZIL", "LAZER SINIRI " + name + ": " + list.size() + " ornegin ortancasi " + (int) median
                    + (floor > 0 ? ", hasar verilen en uzak " + (int) floor : "") + " -> sinir " + cap + " (tablo " + r + ")");
        BotPlusConfig.Plus plus = context.config.PLUS;
        if (plus.RANGE_DOWN() && r > cap) {
            int nr = Math.max(cap, r - 60); // bir adimda en fazla 60
            original(info);
            info.setRadius(nr);
            wrote(info);
            save();
            rangeCooldown = now + RANGE_COOLDOWN_MS;
            Log.i("MENZIL", "DUSTU " + name + " " + r + " -> " + nr + " (oyun menzil disi dedi, sinir " + cap + ")");
        } else if (r > cap) {
            Log.every("capwarn" + name, 60_000, "MENZIL", name + " tablo menzili " + r + " lazer sinirindan ("
                    + cap + ") buyuk; dovus lazer sinirina gore yapiliyor. Tabloyu da dusurmek icin Menzili Dusur'u ac");
        }
    }

    /** Ogrenilmis lazer siniri (yoksa ya da 30 dk'dan eskiyse -1). */
    public static int laserCap(Npc n) {
        if (n == null || n.getInfo() == null) return -1;
        String name = n.getInfo().getName();
        long[] c = LASER_CAP.get(name);
        if (c == null) return -1;
        if (Context.now() - c[1] > LASER_CAP_MS) {
            LASER_CAP.remove(name);
            return -1;
        }
        return (int) c[0];
    }

    /** Ogrenilen menzil diske yazilmaz: sadece bu oturumda gecerli, BotPlus kapanirken asil degerler geri yuklenir. */
    private static void save() {
    }
}