package com.botplus.core;

import eu.darkbot.api.config.types.SafetyInfo;
import eu.darkbot.api.events.EventHandler;
import eu.darkbot.api.game.entities.BattleStation;
import eu.darkbot.api.game.entities.Entity;
import eu.darkbot.api.game.entities.Ship;
import eu.darkbot.api.game.other.GameMap;
import eu.darkbot.api.managers.AttackAPI;
import eu.darkbot.api.managers.ConfigAPI;
import eu.darkbot.api.managers.EntitiesAPI;
import eu.darkbot.api.managers.HeroAPI;
import eu.darkbot.api.managers.HeroItemsAPI;
import eu.darkbot.api.managers.MovementAPI;
import eu.darkbot.api.managers.StarSystemAPI;
import eu.darkbot.api.utils.Inject;
import eu.darkbot.shared.utils.MapTraveler;
import eu.darkbot.shared.utils.PortalJumper;
import eu.darkbot.shared.utils.SafetyFinder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * BotPlus kacis sistemi: DarkBot'un SafetyFinder'i (portala gitme, atlama, portalda bekleme) kullanilir; DUSMANDAN
 * kacma karari ve dusman kacisinda portal secimi BotPlus'in "Dusman Saldirinca" ayarlarindan gelir. Tamir icin
 * portala gitme (REPAIR) ve yenileme (REFRESH) DarkBot'taki gibidir.
 * Tek ornek: BotPlus isleri ve BotPlus'in DarkBot yedekleri (Loot / Collector) ayni nesneyi kullanir.
 */
public class PlusSafety extends SafetyFinder {

    /**
     * Portalin dibinde bu kadar sure atlanamazsa (oyun mesaji gelmese de) atlanamiyor sayilir. Atlama tusu, animasyon
     * ve harita yuklemesi suruyor olabilir: kisa tutulmaz (asil hizli tespit oyunun "kacamazsin" mesajiyla).
     */
    private static final long JUMP_STUCK_MS = 9000;
    /** Atlanamayan portal bu kadar sure secilmez. */
    private static final long BLOCK_MS = 60_000;
    /** Kacis Konisi: dusmanin tam tersinden en fazla bu aci (derece). */
    private static final double CONE_DEG = 60;
    /** Kacarken Portaldan Atla: portal kacis cizgisine en fazla bu kadar yanal uzaklikta olmali. */
    private static final double HOP_LATERAL = 700;
    /** Varis Yarisi payi: gemi portala dusmandan en az bu oranda once varmali. */
    private static final double RACE_MARGIN = 1.1;

    /** BotPlus portala kacmak istiyor mu (Context kurar). */
    public volatile BooleanSupplier escapeWanted = () -> false;
    /** Kacilan gemi mi (portal secimi: dusmanin bizden once varacagi portal secilmez). */
    public volatile Predicate<Ship> threat = s -> false;
    /**
     * BotPlus su an gemiyi yonetiyor mu (acik, calisma haritasinda, captcha yok). Yonetmiyorsa (BotPlus kapali,
     * harita disi yolculuk, captcha) DarkBot'un kendi "Dusmandan Kac" ayarlari gecerli: yedek modul kacabilsin.
     */
    public volatile BooleanSupplier inCharge = () -> true;
    /** Gelismis Kacis ayarlari (Context kurar). */
    public volatile Supplier<CoreConfig.EscapePlusSet> plus = CoreConfig.EscapePlusSet::new;
    /** Can Kacis Cani'nin altinda mi (Context kurar). */
    public volatile BooleanSupplier critical = () -> false;
    /** Kacilan dusman pesimizde mi (Takip Edilirse Atla; Context kurar). */
    public volatile BooleanSupplier following = () -> true;
    /** Portal Zinciri: diger haritada bekleme suresi. */
    private static final long CHAIN_WAIT_MS = 10_000;
    private long jumpedAt;
    private boolean chainLogged;

    private CoreConfig.EscapePlusSet plusCfg() {
        try {
            CoreConfig.EscapePlusSet p = plus.get();
            return p == null ? new CoreConfig.EscapePlusSet() : p;
        } catch (RuntimeException e) {
            return new CoreConfig.EscapePlusSet();
        }
    }

    /** Kac Secenekleri (Context kurar). */
    public volatile Supplier<Set<BotPlusConfig.EscapeOpt>> options = () -> EnumSet.allOf(BotPlusConfig.EscapeOpt.class);

    /** Atlanamayan portallar: anahtar (harita:x:y) -> bu zamana kadar secilmez. */
    private final Map<String, Long> blocked = new HashMap<>();
    /** Tum portallar bu zamana kadar kapali (ornegin oyuna girdikten sonraki atlama yasagi). */
    private long allBlockedUntil;
    /** Oyun "atlanamaz" dedi (BotPlus oyun mesajindan isaretler). */
    private volatile long refusedAt;
    private volatile long refusedForMs;
    private long nearPortalSince;
    private int lastMapId = -1;
    /** Son ret "savas esnasinda" mi (Gecerken Atla bu reddi portal degistirmeden, hareket ederek asar). */
    private volatile boolean refusedCombat;

    /** Gecerken Atla: portal ustunden gecis yaricapi, atlama deneme mesafesi / sikligi, NPC menzilinden cikis. */
    private static final double PASS_R = 250;
    private static final double PASS_TRY_DIST = 250;
    private static final long PASS_TRY_MS = 1000;
    private static final double SHOOTER_DIST = 1500;
    private static final double RETREAT_R = 1000;
    private static final long RETREAT_MAX_MS = 8000;
    /** Gecerken Atla: J'den sonra portalin "atliyor" gorunmesi en fazla bu kadar beklenir. */
    private static final long JUMP_ANIM_MS = 2000;
    /** Portalin dibinde (bu yakinda) kacilan dusman varsa baska portala gecilir. */
    private static final double PORTAL_CAMP_DIST = 700;
    /** Gecerken Atla bu kadar surede atlayamazsa (dusman kacisi, Baska Portala aciksa) portal degistirilir. */
    private static final long PASS_SWITCH_MS = 2 * JUMP_STUCK_MS;
    private long passStart, passTryAt, passPickAt, passLogAt, passRetreatUntil;
    private int passTries;
    private eu.darkbot.api.game.other.Location passTo;
    private final java.util.Collection<? extends eu.darkbot.api.game.entities.Npc> npcs;

    @Inject
    public PlusSafety(HeroAPI hero, AttackAPI attacker, HeroItemsAPI items, MovementAPI movement, StarSystemAPI starSystem,
                      ConfigAPI config, EntitiesAPI entities, MapTraveler mapTraveler, PortalJumper portalJumper) {
        super(hero, attacker, items, movement, starSystem, config, entities, mapTraveler, portalJumper);
        this.npcs = entities.getNpcs();
    }

    /**
     * DarkBot'un olay sistemi dinleyici metotlarini getDeclaredMethods ile arar: ust siniftaki (SafetyFinder)
     * @EventHandler gorulmez. Bu metot olmadan harita degisimi PlusSafety'ye hic ulasmiyordu; atladiktan sonra durum
     * "jumping"te kaliyor, oyun yeniden yuklenene kadar (~5 dk) bot bekliyordu.
     */
    @Override
    @EventHandler
    public void onMapChange(StarSystemAPI.MapChangeEvent event) {
        super.onMapChange(event);
        nearPortalSince = 0;
        passReset();
        prefer = null;
    }

    /**
     * Oyun atlamayi reddetti (BotPlus oyun mesajindan cagirir). forMs: bu portal ne kadar secilmesin;
     * all: tum portallar (oyuna girdikten sonraki atlama yasagi gibi).
     */
    public void jumpRefused(long forMs, boolean all) {
        refusedCombat = false;
        refusedForMs = forMs;
        refusedAt = System.currentTimeMillis();
        if (all) allBlockedUntil = Math.max(allBlockedUntil, refusedAt + forMs);
    }

    /** Oyun "savas esnasinda atlanamaz" dedi: butun portallar ayni (savas bitene kadar). */
    public void jumpRefusedCombat() {
        jumpRefused(60_000, false);
        refusedCombat = true;
    }

    @Override
    public boolean tick() {
        checkMapChange();
        if (chainTick()) return false;
        if (charge() && opt(BotPlusConfig.EscapeOpt.PASS_JUMP)) {
            Boolean r = passTick();
            if (r != null) return r;
        } else passReset();
        checkRefused();
        return super.tick();
    }

    /**
     * Gecerken Atla: DarkBot portalda durup J'ye basiyor; oyun reddedince 20 sn yeniden denemiyor ve gemi portalda
     * hareketsiz vuruluyordu (savasta hep "savas esnasinda kacamazsin"). Bunun yerine gemi portalin ustunden gidip
     * gelerek gecer, her geciste (~1 sn) atlama denenir; savas durumu dustugu an atlanir. Ret gelirken ates eden NPC
     * varsa once onun menzilinden cikilir, hasar kesilince portala donulur. Dusman ve tamir kacisinda calisir.
     * null: Gecerken Atla bu tikte devrede degil (normal kacis sistemi).
     */
    private Boolean passTick() {
        if (jumpState != JumpState.JUMPING || safety == null || safety.getType() != SafetyInfo.Type.PORTAL) {
            passReset();
            return null;
        }
        eu.darkbot.api.game.entities.Portal portal = safety.getEntity()
                .filter(e -> e instanceof eu.darkbot.api.game.entities.Portal)
                .map(e -> (eu.darkbot.api.game.entities.Portal) e).orElse(null);
        if (portal == null || !portal.isValid()) {
            passReset();
            return null;
        }
        long now = System.currentTimeMillis();
        // Savas disi ret (ornegin portal kapali / bekleme): Gecerken Atla bunu asamaz; dusman kacisinda portal
        // degistirilir (Portaldan Atlayamazsa Baska Portala), degilse normal kacis sistemine birakilir.
        if (refusedAt > 0 && now - refusedAt < 5000 && !refusedCombat) {
            if (escape == Escaping.ENEMY && opt(BotPlusConfig.EscapeOpt.SWITCH_PORTAL)) {
                switchPortal("oyun reddetti", Math.max(refusedForMs, 5000));
                passReset();
                return false;
            }
            passReset();
            return null;
        }
        if (shouldTimeout()) return false;
        if (jumpState != JumpState.JUMPING) {
            passReset();
            return null;
        }
        activeTick();
        if (escape == Escaping.NONE || safety == null) {
            passReset();
            return true;
        }
        prevMap = starSystem.getCurrentMap();
        if (passStart == 0) {
            passStart = now;
            Log.i("GUVENLIK", "Gecerken Atla: " + safety + " (" + escape + "), portalda durulmadan ustunden gecilerek atlama deneniyor");
        }
        // Atlama animasyonu: en fazla JUMP_ANIM_MS beklenir (J'den sonra ret gelse de portal bir sure "atliyor"
        // gorunuyordu; gemi 10 sn kipirdamadan bekledi). Ret geldiyse hic beklenmez.
        boolean refusedNow = refusedAt > 0 && now - refusedAt < 5000;
        if (portal.isJumping() && !refusedNow && now - passTryAt < JUMP_ANIM_MS) return false;
        // Portalin dibinde dusman oyuncu (bizi takip eden / ates eden) varsa beklenmez: baska portal (Baska Portala Gec).
        if (escape == Escaping.ENEMY && opt(BotPlusConfig.EscapeOpt.SWITCH_PORTAL) && now - passStart > 1500) {
            Ship camper = portalCamper(portal);
            if (camper != null) {
                switchPortal("portalin dibinde dusman " + Log.name(camper) + " (" + (int) camper.getLocationInfo().distanceTo(portal) + ")", BLOCK_MS / 2);
                passReset();
                return false;
            }
        }
        // Uzun suredir atlanamiyor (ornegin NPC portalin dibinde bekliyor): dusman kacisinda baska portal denenir.
        if (now - passStart > PASS_SWITCH_MS && escape == Escaping.ENEMY && opt(BotPlusConfig.EscapeOpt.SWITCH_PORTAL)) {
            switchPortal(PASS_SWITCH_MS / 1000 + " sn gecerek denendi", BLOCK_MS);
            passReset();
            return false;
        }
        hero.setRunMode();
        eu.darkbot.api.game.entities.Npc shooter = shooter();
        boolean refused = refusedAt > 0 && now - refusedAt < 5000;
        if (refused && refusedCombat && shooter != null && passRetreatUntil < now) {
            passRetreatUntil = now + RETREAT_MAX_MS;
            refusedAt = 0;
            Log.i("GUVENLIK", "Gecerken Atla: oyun savas yuzunden reddetti, " + Log.npc(shooter) + " ates ediyor: menzilinden cikiliyor, hasar kesilince portala donulecek");
        }
        if (passRetreatUntil > now) {
            if (shooter == null && !hero.getHealth().hpDecreasedIn(2500)) {
                passRetreatUntil = 0;
                Log.i("GUVENLIK", "Gecerken Atla: ates kesildi, portala donuluyor");
            } else {
                eu.darkbot.api.game.entities.Npc from = shooter;
                if (from != null) {
                    // NPC'nin gemi tarafinda, gemiden daha uzak nokta (NPC'ye dogru gidilmez).
                    double r = Math.max(RETREAT_R, hero.distanceTo(from) + 500);
                    movement.moveTo(eu.darkbot.api.game.other.Location.of(from, from.angleTo(hero), r));
                    return false;
                }
            }
        } else if (passRetreatUntil != 0) {
            passRetreatUntil = 0;
            Log.i("GUVENLIK", "Gecerken Atla: " + RETREAT_MAX_MS / 1000 + " sn uzaklasildi, portala donuluyor");
        }
        // Portalin obur tarafindaki nokta: gemi portalin ustunden gecer, varinca / 2,5 sn'de yenilenir.
        if (passTo == null || hero.distanceTo(passTo) < 80 || now - passPickAt > 2500) {
            double a = portal.angleTo(hero) + (Math.random() - 0.5) * 0.6;
            passTo = eu.darkbot.api.game.other.Location.of(portal, a, -PASS_R);
            passPickAt = now;
        }
        movement.moveTo(passTo);
        if (hero.distanceTo(portal) <= PASS_TRY_DIST && now - passTryAt >= PASS_TRY_MS) {
            passTryAt = now;
            passTries++;
            movement.jumpPortal(portal);
            // DarkBot'un J tusu reddedilince 20 sn beklemesi: her ikinci denemede portala tiklanir (beklemesiz).
            if (passTries % 2 == 0 && portal.isSelectable()) portal.trySelect(false);
            if (now - passLogAt > 5000) {
                passLogAt = now;
                Log.i("GUVENLIK", "Gecerken Atla: atlama denendi (" + passTries + ". deneme, " + (now - passStart) / 1000
                        + " sn) | gemi " + Log.pct(hero.getHealth().hpPercent()));
            }
        }
        return false;
    }

    /** Kacilan dusmanlardan portala PORTAL_CAMP_DIST'ten yakin olan (portalda bekleyen / bize yetismis), yoksa null. */
    private Ship portalCamper(eu.darkbot.api.game.entities.Portal portal) {
        for (Ship sh : ships) {
            if (!sh.isValid() || !runFrom(sh)) continue;
            double dp = sh.getLocationInfo().distanceTo(portal);
            // Sadece portalin dibinde ve bizden portala yakin (onumuzde / portalda bekliyor); arkamizdan gelen sayilmaz
            // (yoksa portala varirken her seferinde portal degisiyordu).
            double hd = hero.getLocationInfo().distanceTo(portal);
            if (dp < PORTAL_CAMP_DIST && dp < hd - 200 && hd > PASS_TRY_DIST) return sh;
        }
        return null;
    }

    /** Gemiye ates eden en yakin NPC (SHOOTER_DIST icinde), yoksa null. */
    private eu.darkbot.api.game.entities.Npc shooter() {
        eu.darkbot.api.game.entities.Npc best = null;
        double bd = SHOOTER_DIST;
        for (eu.darkbot.api.game.entities.Npc n : npcs) {
            if (!n.isValid() || !n.isAttacking(hero)) continue;
            double d = hero.distanceTo(n);
            if (d < bd) {
                bd = d;
                best = n;
            }
        }
        return best;
    }

    private void passReset() {
        passStart = passTryAt = passPickAt = passRetreatUntil = 0;
        passTries = 0;
        passTo = null;
    }

    /**
     * Portal Zinciri: dusman kacisinda atladiktan sonra diger haritada dusman da gecerse (yakinda ve bize ates etmis /
     * ediyor) hemen geri atlanir; gecmezse CHAIN_WAIT_MS beklenip donulur (dusman eski haritada portaldan uzaklassin).
     * true: bu tik Portal Zinciri yonetti (bekleniyor).
     */
    private boolean chainTick() {
        if (jumpState != JumpState.JUMPED) {
            jumpedAt = 0;
            chainLogged = false;
            return false;
        }
        if (!charge() || escape != Escaping.ENEMY || !plusCfg().PORTAL_CHAIN || prevMap == null) return false;
        long now = System.currentTimeMillis();
        if (jumpedAt == 0) jumpedAt = now;
        Ship follower = null;
        for (Ship s : ships) {
            if (!s.isValid()) continue;
            if (!(s instanceof eu.darkbot.api.game.entities.Player)) continue; // NPC'ler sayilmaz
            boolean t;
            try {
                t = threat.test(s) || s.isAttacking(hero);
            } catch (RuntimeException e) {
                t = false;
            }
            if (t && hero.getLocationInfo().distanceTo(s) < 2500) {
                follower = s;
                break;
            }
        }
        if (follower != null) {
            jumpState = JumpState.RETURNING;
            mapTraveler.setTarget(prevMap);
            Log.i("GUVENLIK", "Portal Zinciri: " + Log.name(follower) + " de gecti, hemen geri atlaniyor (" + prevMap + ")");
            return false;
        }
        boolean hurt;
        try {
            hurt = hero.getHealth().hpDecreasedIn(500);
        } catch (RuntimeException e) {
            hurt = false;
        }
        if (now - jumpedAt < CHAIN_WAIT_MS && !hurt) {
            // DarkBot'un "tik gelmedi" sifirlamasi (2,5 sn) beklerken tetiklenmesin: kacis durumu kaybolurdu.
            lastTick = now;
            if (!chainLogged) {
                chainLogged = true;
                Log.i("GUVENLIK", "Portal Zinciri: diger haritada " + CHAIN_WAIT_MS / 1000 + " sn bekleniyor, dusman gecerse hemen geri atlanacak");
            }
            return true;
        }
        return false;
    }

    /** Portalin ustundeyken (beklemeden atlamaya gecis) gemi yetenegi yeniden basilmaz. */
    @Override
    protected void castDefensiveAbility() {
        if (safety != null && hero.getLocationInfo().distanceTo(safety) <= safety.getRadius() * 1.5) return;
        super.castDefensiveAbility();
    }

    /**
     * Takip Edilirse Atla: dusman kacisinda portala varinca dusman takip etmiyorsa atlanmaz, portalda beklenir
     * (17:31: KINIK 11 sn'dir ates etmiyordu, yine de atlandi). Takip eder ya da hasar alinirsa atlanir.
     */
    @Override
    protected Escaping getEscape() {
        Escaping e = super.getEscape();
        if (e != Escaping.ENEMY || !charge() || !plusCfg().FOLLOW_JUMP) return e;
        if (jumpState != JumpState.CURRENT_MAP || safety == null || safety.getType() != SafetyInfo.Type.PORTAL) return e;
        if (hero.getLocationInfo().distanceTo(safety) > safety.getRadius()) return e;
        boolean f;
        try {
            f = following.getAsBoolean();
        } catch (RuntimeException ex) {
            f = true;
        }
        if (f) return e;
        Log.every("followjump", 5000, "GUVENLIK", "Takip Edilirse Atla: portaldayiz, dusman takip etmiyor, atlanmadan bekleniyor");
        return Escaping.WAITING;
    }

    /**
     * Yedek: harita degisim olayi bir sekilde gelmezse (dinleyici kaydi) atlama burada da yakalanir; yoksa kacis
     * "jumping"te kalip yeni haritada eski portala atlamaya calisiyordu.
     */
    private void checkMapChange() {
        GameMap m = starSystem.getCurrentMap();
        int id = m == null ? -1 : m.getId();
        if (lastMapId != -1 && id != -1 && id != lastMapId) prefer = null;
        if (lastMapId != -1 && id != -1 && id != lastMapId && jumpState == JumpState.JUMPING
                && safety != null && safety.getType() == SafetyInfo.Type.PORTAL) {
            jumpState = m.equals(prevMap) ? JumpState.RETURNED : JumpState.JUMPED;
            nearPortalSince = 0;
            Log.d("GUVENLIK", "harita degisti, atlama tamam (" + jumpState + ")");
        }
        lastMapId = id;
    }

    /**
     * Portaldan Atlayamazsa Baska Portala: portalin yanindayken oyun atlamayi reddederse (ya da JUMP_STUCK_MS boyunca
     * atlanamazsa) o portal bir sure secilmez, kacis hemen baska bir portal arar; kalmadiysa Hizla Atlat'a gecilir.
     */
    private void checkRefused() {
        if (jumpState != JumpState.JUMPING || safety == null || escape != Escaping.ENEMY) {
            nearPortalSince = 0;
            return;
        }
        long now = System.currentTimeMillis();
        boolean portalJumping = safety.getEntity().map(e -> e instanceof eu.darkbot.api.game.entities.Portal
                && ((eu.darkbot.api.game.entities.Portal) e).isJumping()).orElse(false);
        boolean near = !portalJumping && hero.getLocationInfo().distanceTo(safety) <= Math.max(150, safety.getRadius());
        if (!near) {
            nearPortalSince = 0;
            return;
        }
        if (nearPortalSince == 0) nearPortalSince = now;
        boolean byGame = refusedAt >= nearPortalSince && now - refusedAt < 5000;
        boolean byTime = now - nearPortalSince > JUMP_STUCK_MS;
        if (!byGame && !byTime) return;
        if (!charge() || !opt(BotPlusConfig.EscapeOpt.SWITCH_PORTAL)) return;
        long ms = byGame ? Math.max(refusedForMs, 5000) : BLOCK_MS;
        switchPortal(byGame ? "oyun reddetti" : (now - nearPortalSince) / 1000 + " sn bekledi", ms);
    }

    /** Portal ms boyunca secilmez, hemen yeni portal secilir (uygun yoksa null: kacis isi Hizla Atlat'a gecer). */
    private void switchPortal(String why, long ms) {
        long now = System.currentTimeMillis();
        blocked.put(key(safety), now + ms);
        Log.i("GUVENLIK", "portaldan atlanamadi (" + why + "): " + safety + " " + ms / 1000 + " sn secilmeyecek, baska portal araniyor");
        refusedAt = 0;
        nearPortalSince = 0;
        jumpState = JumpState.CURRENT_MAP;
        // Hemen yeni portal secilir (eskiden null birakiliyordu: kacis bir tik kapanip yetenek tekrar basiliyordu).
        safety = getSafety();
    }

    /** DarkBot'un "saldiri altinda" karari yerine BotPlus'in karari. */
    @Override
    protected boolean isUnderAttack() {
        return charge() ? want() : super.isUnderAttack();
    }

    /** Portalda beklerken: BotPlus hala kacmak istiyorsa beklemeye devam edilir. */
    @Override
    protected boolean hasEnemy() {
        return charge() ? want() : super.hasEnemy();
    }

    /**
     * BotPlus dusmandan kacarken portal secimi. Her zaman: sadece atlanabilecek portal (Atlama Kosulu "Asla" ya da
     * "Tamir Esnasinda" olan portal hic secilmez) ve atlanamadigi gorulen portal secilmez. Secenege gore:
     * Kacis Konisi (dusmanin tersindeki portal) ve Varis Yarisi (dusmanin once varacagi portal secilmez).
     * Uygun yer yoksa: "Portal Yoksa Hizla Atlat" acikken null (kacis isi haritada uzaklasir), kapaliyken koni /
     * yaris kurali olmadan en yakin atlanabilir portal.
     * BotPlus yonetmiyorsa ya da kacis dusman kacisi degilse (tamir / yenileme) DarkBot'un secimi aynen kullanilir.
     */
    @Override
    protected SafetyInfo getSafety() {
        if (!charge() || escape != Escaping.ENEMY) return super.getSafety();
        long now = System.currentTimeMillis();
        blocked.values().removeIf(t -> t < now);
        List<SafetyInfo> usable = new ArrayList<>();
        GameMap map = starSystem.getCurrentMap();
        for (SafetyInfo s : config.getLegacy().getSafeties(map)) {
            if (!s.getEntity().map(Entity::isValid).orElse(false) || !usableForEnemyEscape(s)) continue;
            if (s.getType() == SafetyInfo.Type.PORTAL && (now < allBlockedUntil || blocked.containsKey(key(s)))) continue;
            s.setDistance(Math.max(0, movement.getDistanceBetween(hero, s) - s.getRadius()));
            usable.add(s);
        }
        usable.sort(Comparator.comparingDouble(SafetyInfo::getDistance));
        List<Ship> enemies = new ArrayList<>();
        for (Ship sh : ships) if (runFrom(sh)) enemies.add(sh);

        Set<BotPlusConfig.EscapeOpt> o = opts();
        // Portalda (Takip Edilirse Atla ile beklerken) dusman gelince ayni portal korunur: koni kurali portalin
        // ustundeyken rastgele sonuc verip baska portala / haritaya kaciliyordu.
        if (safety != null && safety.getType() == SafetyInfo.Type.PORTAL && hero.getLocationInfo().distanceTo(safety) <= safety.getRadius() * 1.5) {
            for (SafetyInfo s : usable)
                if (s.getType() == SafetyInfo.Type.PORTAL && Math.hypot(s.getX() - safety.getX(), s.getY() - safety.getY()) < 50) return s;
        }
        // Kacarken Portaldan Atla: kacisin sectigi portal (30 sn icinde) once denenir.
        if (preferAt > 0 && now - preferAt < 30_000 && prefer != null && map != null && map.getId() == preferMap) {
            for (SafetyInfo s : usable)
                if (s.getType() == SafetyInfo.Type.PORTAL && Math.hypot(s.getX() - prefer.getX(), s.getY() - prefer.getY()) < 50) return s;
        }
        CoreConfig.EscapePlusSet pc = plusCfg();
        // Can Kritikse En Yakin Portal: koni / yaris kurali yok (yaris kazanilan varsa o, yoksa en yakin).
        boolean crit;
        try {
            crit = pc.CRIT_PORTAL && critical.getAsBoolean();
        } catch (RuntimeException e) {
            crit = false;
        }
        if (crit && !usable.isEmpty()) {
            for (SafetyInfo s : usable)
                if (winsRace(s, enemies)) {
                    Log.every("safetycrit", 5000, "GUVENLIK", "can kritik: koni kuralsiz, dusmandan once varilan en yakin portal " + s);
                    return s;
                }
            Log.every("safetycrit", 5000, "GUVENLIK", "can kritik: koni / yaris kuralsiz en yakin portal " + usable.get(0));
            return usable.get(0);
        }
        // Genisleyen Koni: uygun portal yoksa koni 60 -> 90 -> 120 derece (eskiden 3 atlanabilir portal varken 11 kez
        // "uygun portal yok" deyip haritada kaciliyordu).
        boolean cone = o.contains(BotPlusConfig.EscapeOpt.CONE);
        double[] cones = cone && pc.WIDEN_CONE ? new double[]{CONE_DEG, 90, 120} : new double[]{CONE_DEG};
        for (double deg : cones) {
            for (SafetyInfo s : usable) {
                if (cone && !inCone(s, enemies, deg)) continue;
                if (o.contains(BotPlusConfig.EscapeOpt.RACE) && !winsRace(s, enemies)) continue;
                if (deg > CONE_DEG) Log.every("safetywide", 5000, "GUVENLIK", "kacis konisi " + (int) deg + " dereceye genisletildi: " + s);
                return s;
            }
        }
        if (o.contains(BotPlusConfig.EscapeOpt.ELSE_OUTRUN) || usable.isEmpty()) {
            Log.every("safetynone", 5000, "GUVENLIK", "kacis icin uygun portal yok (" + usable.size()
                    + " atlanabilir, koni / yaris disinda): Hizla Atlat");
            return null;
        }
        return usable.get(0);
    }

    /** Kacarken Portaldan Atla: kacisin sectigi portal. */
    private volatile eu.darkbot.api.game.other.Location prefer;
    private volatile long preferAt;

    private volatile int preferMap = -1;

    public void preferPortal(eu.darkbot.api.game.other.Location l) {
        prefer = l;
        preferAt = System.currentTimeMillis();
        GameMap m = starSystem.getCurrentMap();
        preferMap = m == null ? -1 : m.getId();
    }

    /**
     * Kacarken Portaldan Atla: hero'dan maxDist icinde, kacis yonune (away: gemiden dusmana bakan aci; portal bunun
     * tersinde olmali) en fazla maxDeg derece, dusmandan once varilan, kacista atlanabilir ve atlanamadigi
     * gorulmemis en yakin portal; yoksa null.
     */
    public eu.darkbot.api.game.other.Location hopPortal(eu.darkbot.api.game.other.Locatable heroLoc, double away,
                                                        double maxDist, double maxDeg, Ship enemy) {
        try {
            long now = System.currentTimeMillis();
            if (now < allBlockedUntil) return null;
            SafetyInfo best = null;
            double bd = maxDist;
            for (SafetyInfo s : config.getLegacy().getSafeties(starSystem.getCurrentMap())) {
                if (s.getType() != SafetyInfo.Type.PORTAL || !s.getEntity().map(Entity::isValid).orElse(false)) continue;
                if (!usableForEnemyEscape(s)) continue;
                Long b = blocked.get(key(s));
                if (b != null && b > now) continue;
                double d = Math.hypot(s.getX() - heroLoc.getX(), s.getY() - heroLoc.getY());
                if (d >= bd) continue;
                // Portala yon: kacis yonu (away'in tersi) ile en fazla maxDeg fark.
                double to = Math.atan2(s.getY() - heroLoc.getY(), s.getX() - heroLoc.getX());
                double run = away + Math.PI;
                double diff = Math.abs(Math.atan2(Math.sin(to - run), Math.cos(to - run)));
                if (Math.toDegrees(diff) > maxDeg) continue;
                // Sadece kacis yolunun ustundeki portal: kacis cizgisine yanal uzakligi HOP_LATERAL'dan fazla olmamali
                // (eskiden 75 derece sapma serbestti, yolda olmayan portala dogrudan gidiliyordu).
                if (d * Math.sin(diff) > HOP_LATERAL) continue;
                if (enemy != null) {
                    double ours = d / Math.max(1, hero.getSpeed());
                    double theirs = enemy.getLocationInfo().distanceTo(s) / Math.max(1, enemy.getSpeed());
                    if (ours * RACE_MARGIN >= theirs) continue;
                }
                bd = d;
                best = s;
            }
            return best == null ? null : eu.darkbot.api.game.other.Location.of(best.getX(), best.getY());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Liman (saldiri tarzi): from'a max'tan yakin, kacista atlanabilir (ve atlanamadigi gorulmemis) en yakin portal.
     */
    public eu.darkbot.api.game.other.Location harborPortal(eu.darkbot.api.game.other.Locatable from, double max) {
        try {
            long now = System.currentTimeMillis();
            SafetyInfo best = null;
            double bd = max;
            for (SafetyInfo s : config.getLegacy().getSafeties(starSystem.getCurrentMap())) {
                if (s.getType() != SafetyInfo.Type.PORTAL || !s.getEntity().map(Entity::isValid).orElse(false)) continue;
                if (!usableForEnemyEscape(s) || now < allBlockedUntil) continue;
                Long b = blocked.get(key(s));
                if (b != null && b > now) continue;
                double d = Math.hypot(s.getX() - from.getX(), s.getY() - from.getY());
                if (d < bd) {
                    bd = d;
                    best = s;
                }
            }
            return best == null ? null : eu.darkbot.api.game.other.Location.of(best.getX(), best.getY());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Kullanim Kosulu "Her Zaman" ya da "Kacarken"; portalda Atlama Kosulu kacista atlamaya izin vermeli. */
    private static boolean usableForEnemyEscape(SafetyInfo s) {
        SafetyInfo.RunMode rm = s.getRunMode();
        if (rm != SafetyInfo.RunMode.ALWAYS && rm != SafetyInfo.RunMode.ENEMY_FLEE_ONLY) return false;
        switch (s.getType()) {
            case PORTAL:
                SafetyInfo.JumpMode jm = s.getJumpMode();
                return jm == SafetyInfo.JumpMode.ESCAPING || jm == SafetyInfo.JumpMode.FLEEING
                        || jm == SafetyInfo.JumpMode.ALWAYS || jm == SafetyInfo.JumpMode.ALWAYS_OTHER_SIDE;
            case CBS:
                BattleStation cbs = (BattleStation) s.getEntity().orElse(null);
                return cbs != null && !cbs.getEntityInfo().isEnemy()
                        && !(cbs.isAsteroid() && s.getCbsMode() == SafetyInfo.CbsMode.ALLY);
            default:
                return true;
        }
    }

    /** Kacis Konisi: gemiden portala yon, en yakin dusmandan gemiye yonle en fazla CONE_DEG derece fark. */
    private boolean inCone(SafetyInfo s, List<Ship> enemies) {
        return inCone(s, enemies, CONE_DEG);
    }

    private boolean inCone(SafetyInfo s, List<Ship> enemies, double coneDeg) {
        Ship e = nearest(enemies);
        if (e == null) return true;
        double away = Math.atan2(hero.getY() - e.getY(), hero.getX() - e.getX());
        double to = Math.atan2(s.getY() - hero.getY(), s.getX() - hero.getX());
        double diff = Math.abs(Math.atan2(Math.sin(to - away), Math.cos(to - away)));
        return Math.toDegrees(diff) <= coneDeg;
    }

    /** Varis Yarisi: gemi portala her dusmandan (hizlarina gore) belirgin sekilde once varmali. */
    private boolean winsRace(SafetyInfo s, List<Ship> enemies) {
        double ours = hero.getLocationInfo().distanceTo(s) / Math.max(1, hero.getSpeed());
        for (Ship e : enemies) {
            double theirs = e.getLocationInfo().distanceTo(s) / Math.max(1, e.getSpeed());
            if (ours * RACE_MARGIN >= theirs) return false;
        }
        return true;
    }

    private Ship nearest(List<Ship> list) {
        Ship best = null;
        double bd = Double.MAX_VALUE;
        for (Ship s : list) {
            double d = hero.distanceTo(s);
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    private String key(SafetyInfo s) {
        GameMap m = starSystem.getCurrentMap();
        return (m == null ? -1 : m.getId()) + ":" + (int) s.getX() + ":" + (int) s.getY();
    }

    @Override
    protected boolean runFrom(Ship ship) {
        if (!charge()) return super.runFrom(ship);
        try {
            return threat.test(ship);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean opt(BotPlusConfig.EscapeOpt o) {
        return opts().contains(o);
    }

    private Set<BotPlusConfig.EscapeOpt> opts() {
        try {
            Set<BotPlusConfig.EscapeOpt> s = options.get();
            return s == null ? EnumSet.noneOf(BotPlusConfig.EscapeOpt.class) : s;
        } catch (RuntimeException e) {
            return EnumSet.allOf(BotPlusConfig.EscapeOpt.class);
        }
    }

    private boolean charge() {
        try {
            return inCharge.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean want() {
        try {
            return escapeWanted.getAsBoolean();
        } catch (RuntimeException e) {
            return false;
        }
    }
}