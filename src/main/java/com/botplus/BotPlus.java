package com.botplus;

import com.botplus.combat.*;
import com.botplus.core.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.config.types.ShipMode;
import eu.darkbot.api.events.EventHandler;
import eu.darkbot.api.events.Listener;
import eu.darkbot.api.extensions.Behavior;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Draw;
import eu.darkbot.api.extensions.Drawable;
import eu.darkbot.api.extensions.Feature;
import eu.darkbot.api.extensions.Installable;
import eu.darkbot.api.extensions.MapGraphics;
import eu.darkbot.api.extensions.Module;
import eu.darkbot.api.extensions.NpcFlags;
import eu.darkbot.api.extensions.TemporalModule;
import eu.darkbot.api.extensions.selectors.GearSelector;
import eu.darkbot.api.extensions.selectors.LaserSelector;
import eu.darkbot.api.extensions.selectors.PetGearSupplier;
import eu.darkbot.api.extensions.selectors.PrioritizedSupplier;
import eu.darkbot.api.extensions.selectors.ShipModeSelector;
import eu.darkbot.api.game.entities.Box;
import eu.darkbot.api.game.entities.Npc;
import eu.darkbot.api.game.enums.PetGear;
import eu.darkbot.api.game.items.SelectableItem;
import eu.darkbot.api.game.other.Location;
import eu.darkbot.api.managers.GameLogAPI;
import eu.darkbot.api.managers.HeroAPI;
import eu.darkbot.api.managers.PetAPI;

import eu.darkbot.shared.modules.CollectorModule;
import eu.darkbot.shared.modules.LootCollectorModule;
import eu.darkbot.shared.modules.LootModule;

import java.util.Arrays;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * BotPlus: iki sekilde calisir.
 * - DarkBot'ta ana modul olarak secilirse: her sey BotPlus'in icinde calisir (gecici modul yok). Is yokken gemiyi
 *   BotPlus'in icindeki DarkBot yedegi (Loot / Collector, calisma sekline gore) yonetir.
 * - DarkBot'un Loot / Collector modulu seciliyse: eskisi gibi arka planda calisir, gerekince gecici modulle
 *   (PlusTemporal) gemiyi devralir. Baska eklentinin modulu seciliyse sadece gemiyi surmeyen RSB / Defense calisir.
 * Alt moduller (Combat, RSB, NPC Steal, Repair, Enemy Locator, Defense) ayri ozelliklerdir, DarkBot'un eklentiler
 * sekmesinden acilip kapanir; ayarlari Settings ile tek gorunume (Context.config) toplanir.
 * Combat Plus acikken calisma haritasinda tam kontrol: gemi DarkBot'a birakilmaz (hedef secimi, dovus, kutu, dolasma).
 * Gecici is surerken her tik daha onemli is var mi bakilir; varsa mevcut is kesilir.
 * NPC tablosuna NC / YB / HB / MB / HE sutunlarini da ekler.
 */
@Feature(name = "BotPlus", description = "NPC ve toplama modüllerine ek özellikler.")
@Draw(value = Draw.Stage.INFO_AND_HEALTH, attach = Draw.Attach.AFTER)
public class BotPlus implements Module, Behavior, Configurable<CoreConfig>, NpcFlags<BotPlusFlag>, GearSelector,
        LaserSelector, ShipModeSelector, Drawable, Listener, Installable {

    public static final String VERSION = "2.4.0";
    private static final int COMBAT_PET_DIST = 1500;
    private static final long BOX_WAIT_MS = 1200;
    private static final long BOX_COLLECT_MS = 6000;
    private static final int FAST_LOCK_DIST = 1500;
    private static final long CAPTCHA_PAUSE_MS = 45_000;
    private static final long STATE_MS = 5000;
    /** Combat Plus kapaliyken: is bitince DarkBot'un modulu en az bu kadar calisir (yenileme / kacis kontrolu). */
    private static final long HANDBACK_MS = 300;

    private final Context context;
    private final Targeting targeting;
    private final Roam roam;
    private final Boxes boxes;
    private final Locator locator;
    private final EmptyMap emptyMap;
    private final LocatorRefresh locRefresh;
    private final Steal steal;
    private final RepairLogic repair;
    private final Stats stats;
    private final TargetPriority order;
    private final DefenseLogic protect;
    private final Refresh refresh;
    private final Escape escape;
    private final CombatLogic combat;
    private final RsbLogic rsb;
    private final Settings settings;
    /** Ana mod disinda (DarkBot modulu secili) isin calistigi gecici modul. */
    private PlusTemporal temporal;
    /** Ana modda isin disinda gemiyi yoneten DarkBot yedekleri (calisma sekline gore). */
    private final Map<Class<?>, Module> fallbacks = new HashMap<>();

    private boolean wasActive;
    private String lastModule = "";
    private long captchaUntil;
    private int outOfRange;
    /** BotPlus'in baslattigi son gecici modul (araya giren modulu yakalamak icin). */
    private PlusModule current;
    private Location pendingDied;
    private long pendingDiedAt;
    private String lastSettings;
    private long settingsAt;
    private boolean logFileWas = true;

    private final PrioritizedSupplier<SelectableItem.Laser> laserSupplier = new PrioritizedSupplier<SelectableItem.Laser>() {
        @Override
        public PrioritizedSupplier.Priority getPriority() {
            // get() null donerse siradaki secici gecerli olur; dolu donerse her zaman en yuksek oncelik
            // (DarkBot'un MODERATE RSB secicisi engellenir).
            return PrioritizedSupplier.Priority.HIGHEST;
        }

        @Override
        public SelectableItem.Laser get() {
            try {
                if (!context.config.ENABLED) return null;
                // Bulastirma atisi (PIB-100) ve PvP'de Kalkan Emici (SAB-50): secici vermezse DarkBot geri degistirir.
                SelectableItem.Laser forced = context.strikeLaser.get();
                if (forced == null) forced = context.pvpLaser.get();
                if (forced != null) return forced;
                // PvP Plus -> RSB Kullan: oyuncuya saldirirken RSB alt modulu aciksa ve RSB hazirsa RSB (yoksa DarkBot).
                if (context.pvpTarget.get() != null)
                    return context.config.PROTECT.RSB_PLAYERS && context.config.RSB.ENABLED && rsbReady() ? rsb.rsbLaser() : null;
                if (steal.firingAtEnemy(plusModule())) {
                    // PvP Plus -> RSB Kullan: RSB alt modulu aciksa ve RSB hazirsa oyuncuya RSB.
                    if (context.config.PROTECT.RSB_PLAYERS && context.config.RSB.ENABLED && rsbReady())
                        return rsb.rsbLaser();
                    return context.config.STEAL.AMMO.laser;
                }
                if (!context.config.RSB.ENABLED) return null;
                Npc n = context.hero.getLocalTargetAs(Npc.class);
                if (n == null || !n.isValid() || Context.isFake(n)) n = context.targetNpc();
                if (n == null) return null;
                // RSB atisi: RSB cephanesi. Degilse sadece DarkBot'un kendi RSB'si aciksa normal cephane verilir
                // (onu engellemek icin); yoksa null: alt seviye seciciler (DarkBot SAB vb.) calisir.
                // DarkBot'un RSB'si sadece NPC tablosunda "RSB kullan" (USE_RSB) isaretli NPC'de atar: sadece o zaman
                // engellenir (eskiden her NPC'de engelleniyor, DarkBot'un SAB'i ve diger eklentilerin cephanesi de duruyordu).
                boolean block = darkBotRsb() && n.getInfo() != null
                        && n.getInfo().hasExtraFlag(eu.darkbot.api.config.types.NpcFlag.USE_RSB);
                if (!rsb.active() && !block) return null;
                SelectableItem.Laser l = rsb.laser(n);
                if (l != null && rsb.active() && l == rsb.rsbLaser()) return l;
                return block ? l : null;
            } catch (RuntimeException e) {
                return null;
            }
        }
    };

    /** RSB cephanesi var ve atisa hazir mi (oyunculara RSB). */
    private boolean rsbReady() {
        try {
            java.util.Optional<eu.darkbot.api.game.items.Item> o = context.items.getItem(rsb.rsbLaser());
            return o.isPresent() && o.get().isUsable() && o.get().isReady() && o.get().getQuantity() > 50;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** DarkBot'un kendi RSB secicisi (loot.rsb.enabled); okunamazsa acik sayilir (eski davranis). */
    private eu.darkbot.api.config.ConfigSetting<Boolean> dbRsb;
    private boolean dbRsbTried;

    private boolean darkBotRsb() {
        if (!dbRsbTried) {
            try {
                dbRsb = context.api.requireAPI(eu.darkbot.api.managers.ConfigAPI.class).getConfig("loot.rsb.enabled");
                dbRsbTried = dbRsb != null; // okunamadiysa sonraki tikte yine denenir
            } catch (Throwable t) {
                dbRsb = null;
            }
        }
        if (dbRsb == null) return true;
        try {
            return !Boolean.FALSE.equals(dbRsb.getValue());
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Konfigurasyon tek yerden: tamirde tamir (dusmandan kacarken kacis), kovalarken / uzun yaklasmada kacis. */
    private final PrioritizedSupplier<ShipMode> modeSupplier = new PrioritizedSupplier<ShipMode>() {
        @Override
        public PrioritizedSupplier.Priority getPriority() {
            return PrioritizedSupplier.Priority.HIGH;
        }

        @Override
        public ShipMode get() {
            PlusModule m = plusModule();
            if (m == null || m.ended() || m.escaping()) return null;
            PlusModule.Job j = m.job();
            ShipMode mode = null;
            if (j instanceof RepairLogic.Job)
                mode = ((RepairLogic.Job) j).enemyRun() ? context.runMode.getValue() : context.config.REPAIR.MODE;
            else if (j instanceof Targeting.Chase && ((Targeting.Chase) j).wantsRun()) mode = context.runMode.getValue();
            else if (j instanceof CombatJob && ((CombatJob) j).wantsRun()) mode = context.runMode.getValue();
            else if (j instanceof Targeting.Wait && ((Targeting.Wait) j).hpWait()) mode = context.config.REPAIR.MODE;
            else if (j instanceof RepairLogic.IdleJob) mode = context.config.REPAIR.MODE;
                // Bosta tamir (tam kontrol): dovus yokken dolasirken / bulucuya giderken tamir konfigurasyonu.
                // Kesimden hemen sonra (savas / yakinda NPC) gecilmez: konfig 5,5 sn kilitli kalir, siradaki dovus 2C'de baslar.
            else if (((j instanceof CombatJob && !combat.fighting()) || j instanceof Locator.Go) && repair.idleRepairing()
                    && context.outOfCombat(3000) && !combat.selectableNear(3000))
                mode = longTravel() ? context.roamMode.getValue() : context.config.REPAIR.MODE;
            // Gecersiz konfig (ornegin elle bozulmus ayar) DarkBot'un tikini durdurur: verilmez.
            if (mode == null || mode.getConfiguration() == null || mode.getConfiguration() == HeroAPI.Configuration.UNKNOWN)
                return null;
            return plainMode(mode);
        }
    };

    private ShipMode modeSrc, modeOut;
    private HeroAPI.Configuration modeCfg;
    private Character modeKey;
    private long modeAt;

    /**
     * DarkBot'un konfig sinifi (Config.ShipConfig) eski formasyon yoluna gider: son basilan tus farkliysa ya da 60 sn
     * gectiyse basar (formasyon 60 sn'ye kadar yanlis kalabiliyordu). Tus formasyona cevrilip normal ShipMode verilir.
     * Cevrilemezse asil nesne verilir.
     */
    private ShipMode plainMode(ShipMode mode) {
        Character key;
        try {
            if (!(mode instanceof com.github.manolo8.darkbot.config.Config.ShipConfig)) return mode;
            key = ((com.github.manolo8.darkbot.config.Config.ShipConfig) mode).FORMATION;
        } catch (Throwable t) {
            return mode;
        }
        if (key == null) return mode;
        HeroAPI.Configuration cfg = mode.getConfiguration();
        long now = Context.now();
        if (modeOut != null && modeSrc == mode && modeCfg == cfg && key.equals(modeKey) && now - modeAt < 2000) return modeOut;
        SelectableItem.Formation f = null;
        try {
            f = context.items.getItem(key, eu.darkbot.api.game.items.ItemCategory.DRONE_FORMATIONS,
                    SelectableItem.Formation.class).orElse(null);
        } catch (RuntimeException ignored) {
        }
        if (f == null) {
            // DarkBot cozemedigi tusa STANDARD verir: o durumda asil nesne kalir.
            try {
                SelectableItem.Formation g = mode.getFormation();
                if (g != null && g != SelectableItem.Formation.STANDARD) f = g;
            } catch (RuntimeException ignored) {
            }
        }
        if (f == null) return mode;
        if (modeOut == null || modeOut.getConfiguration() != cfg || modeOut.getFormation() != f) modeOut = ShipMode.of(cfg, f);
        modeSrc = mode;
        modeCfg = cfg;
        modeKey = key;
        modeAt = now;
        return modeOut;
    }

    /** Bosta tamirde uzun yol (3000+), tehdit yok: dolasma konfigurasyonu (hizli formasyon). */
    private boolean longTravel() {
        if (!context.config.REPAIR.TRAVEL_ROAM) return false;
        Location dest = context.move.getDestination();
        if (dest == null || context.hero.distanceTo(dest) <= 3000) return false;
        return context.enemyThreat() == null && context.attackerOfHero() == null;
    }

    // ---------------------------------------------------------------- PET

    /** Bulucu turu dondurme: sinyal vermeyen tur bir sure atlanir. */
    private final Map<String, Long> skipPick = new HashMap<>();
    private String lastPick;
    private long noPingSince;

    private final PetGearSupplier gearSupplier = new PetGearSupplier() {
        @Override
        public PrioritizedSupplier.Priority getPriority() {
            // Bir sey istemiyorsak (null) DarkBot'un kendi secimi gecerli olur. Sadece bulucu dondurme icin LOW:
            // diger eklentilerin PET secimlerini ezmez.
            try {
                if (!context.config.ENABLED) return null;
                if (wantGear() != null || logoutHold()) return PrioritizedSupplier.Priority.HIGH;
                // Sadece atlanan tur varken: aksi halde diger eklentilerin PET secimlerini ezmez.
                return rotating() && !skipPick.isEmpty() ? PrioritizedSupplier.Priority.LOW : null;
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public PetGear get() {
            try {
                PetGear g = wantGear();
                if (g != null) return g;
                if (logoutHold()) {
                    PetGear cur = currentGear();
                    if (cur != null) return cur;
                }
            } catch (RuntimeException ignored) {
            }
            PetGear def = Context.value(context.petModule, PetGear.PASSIVE);
            return def == null ? PetGear.PASSIVE : def; // asla null degil (DarkBot PET yoneticisi null'da patlar)
        }

        @Override
        public PetAPI.LocatorPick getNpcLocatorPick(Collection<? extends PetAPI.LocatorPick> picks) {
            try {
                PetAPI.LocatorPick pick = PetGearSupplier.super.getNpcLocatorPick(picks);
                // Tek bos liste (oyun bazen bos verir) atlamalari silmez; liste dolu ve hepsi atlandiysa bastan.
                if (pick == null && !skipPick.isEmpty() && picks != null && !picks.isEmpty()) {
                    skipPick.clear();
                    pick = PetGearSupplier.super.getNpcLocatorPick(picks);
                }
                String name = pick == null ? null : pick.getName();
                if (name != null) {
                    context.locatorPick = name;
                    context.locatorPickInfo = pick.getNpcInfo();
                }
                // Ayni tur tekrar tekrar yazilmasin (liste bazen bos gelir).
                if (name != null && !name.equals(lastPick)) {
                    Log.i("BULUCU", "bulucu turu: " + name);
                    lastPick = name;
                }
                return pick;
            } catch (RuntimeException e) {
                return null;
            }
        }

        @Override
        public Integer getNpcPickPriority(PetAPI.LocatorPick pick) {
            Integer p = PetGearSupplier.super.getNpcPickPriority(pick);
            if (p == null || !rotating()) return p;
            Long until = skipPick.get(pick.getName());
            return until != null && until > Context.now() ? null : p;
        }
    };

    public BotPlus(PluginAPI api) {
        this.context = new Context(api);
        this.targeting = new Targeting(context);
        this.roam = new Roam(context);
        this.boxes = new Boxes(context);
        this.locator = new Locator(context);
        this.emptyMap = new EmptyMap(context);
        this.locRefresh = new LocatorRefresh(context);
        context.emptyMap = emptyMap;
        this.steal = new Steal(context);
        this.repair = new RepairLogic(context);
        this.stats = new Stats(context);
        this.order = new TargetPriority(context);
        this.refresh = new Refresh(context);
        this.escape = new Escape(context);
        this.combat = new CombatLogic(context, targeting);
        this.protect = new DefenseLogic(context);
        this.rsb = new RsbLogic(context, () -> combat.enabled() && combat.fighting()); // Combat gercekten dovusuyorsa
        this.settings = new Settings(context);
        context.selfMode = this::self;
        context.mainModule = this::mainModule;
        context.job = this::plusModule;
        context.finishFirst = combat::finishFirst;
        context.pingOk = locator::pingWorthGoing;
        context.combatCanTake = combat::canTake;
        context.stealCan = steal::canSteal;
        context.npcDied = l -> {
            locator.npcDied(l);
            locRefresh.onKill(l);
        };
        context.idleRepair = repair::idleRepairing;
        Log.i("BASLADI", "BotPlus " + VERSION + " | DarkBot kacis sistemi: " + (context.safety == null ? "YOK" : "var"));
    }

    @Override
    public void install(PluginAPI api) {
    }

    /** Eklenti kapanirken / yeniden yuklenirken log dosyasi kapatilir. */
    @Override
    public void uninstall() {
        endJob("BotPlus kaldirildi");
        // Bu oturumda ogrenilen menziller NPC tablosuna kalici yazilmasin: asil degerler geri yuklenir.
        try {
            eu.darkbot.api.config.ConfigSetting<java.util.Map<String, ?>> infos = context.pluginApi
                    .requireAPI(eu.darkbot.api.managers.ConfigAPI.class).requireConfig("loot.npc_infos");
            java.util.Map<String, ?> m = infos.getValue();
            if (m != null) Targeting.restoreRadii(name -> {
                Object o = m.get(name);
                return o instanceof eu.darkbot.api.config.types.NpcInfo ? (eu.darkbot.api.config.types.NpcInfo) o : null;
            });
            Context.saveConfig(); // geri yuklenen menziller diske de yazilsin
        } catch (Throwable t) {
            Log.i("MENZIL", "asil menziller geri yuklenemedi: " + t);
        }
        Log.close();
    }

    // ---------------------------------------------------------------- ayarlar

    @Override
    public void setConfig(ConfigSetting<CoreConfig> setting) {
        settings.setCore(setting.getValue());
        // Alt moduller burada okunmaz (DarkBot yukleme sirasinda baska ozellik yuklemek / acip kapamak kilitlenmeye yol
        // acabilir): gorunum eldeki bilgiyle kurulur, alt moduller ilk tikte okunur.
        settings.rebuild();
        Log.configured = true;
        applyLog();
        lastSettings = settingsLine();
        Log.i("AYARLAR", lastSettings);
        warnSettings();
    }

    /** Arayuzden yapilan degisiklikler (setConfig tekrar cagrilmaz): 5 sn'de bir karsilastirilip loga yazilir. */
    private void checkSettings() {
        long now = Context.now();
        if (now - settingsAt < 5000) return;
        settingsAt = now;
        String line = settingsLine();
        if (line.equals(lastSettings)) return;
        Log.i("AYARLAR", "DEGISTI: " + diff(lastSettings, line));
        lastSettings = line;
        warnSettings();
    }

    /** Degisen alanlar (bosluk ayracli anahtar=deger). */
    private static String diff(String a, String b) {
        if (a == null) return b;
        Set<String> old = new HashSet<>(Arrays.asList(a.split(" ")));
        StringBuilder sb = new StringBuilder();
        for (String t : b.split(" ")) if (!old.contains(t) && t.contains("=")) sb.append(t).append(' ');
        return sb.length() == 0 ? b : sb.toString().trim();
    }

    private void warnSettings() {
        BotPlusConfig c = context.config;
        if (c.REPAIR.ENABLED)
            Log.i("AYARLAR", "tamir: baslama %" + c.REPAIR.START_HP + " / bitis %" + c.REPAIR.END_HP
                    + " ve konfigurasyon DarkBot'un tamir ayarlarindan");
        if (c.COMBAT.ENABLED)
            Log.i("AYARLAR", "Combat Plus acik: calisma haritasinda tam kontrol BotPlus'ta (hedef secimi, dovus, kutu, dolasma)");
    }

    /** Coklu secim: bosluksuz (ayar farki bosluga gore bulunur). */
    private static String set(java.util.Set<?> s) {
        if (s == null || s.isEmpty()) return "-";
        StringBuilder b = new StringBuilder();
        for (Object o : s) b.append(b.length() == 0 ? "" : ",").append(o);
        return b.toString();
    }

    private String settingsLine() {
        BotPlusConfig c = context.config;
        return "mod=" + (self() ? "BotPlus" : "DarkBot") + " alt=" + subsLine() + " aktif=" + c.ENABLED + " siralama=" + c.SORT + "(" + context.botMode() + ")" + " kutu_mesafe=" + c.BOX_MAX_DIST + " yoldaki_kutu=" + c.BOX_WAY + " yolda_kacin=" + c.AVOID_UNLISTED + " hedef_yakinsa_kacinma=" + c.AVOID_NEAR_OFF + " hp_bekle=" + c.PLUS.TARGET.HP_WAIT.ENABLED + "/" + c.PLUS.TARGET.HP_WAIT.WHICH + "/%" + c.PLUS.TARGET.HP_WAIT.HP + " yenileme=" + c.RENEW.ENABLED
                + " yenileme_sekli=" + c.RENEW.MODE
                + " || bulucu_git=" + c.LOCATOR.GO + " bulucu_direkt=" + c.LOCATOR.DIRECT + " birak=" + c.LOCATOR.DROP + " birak_can=%" + c.LOCATOR.DROP_HP
                + " savas_PET=" + (c.PET.COMBAT_CHANGE ? c.PET.COMBAT : "DEGISTIRME") + " PET_dusuk_tamir=" + c.PET.LOW_REPAIR + "/%" + c.PET.LOW_HP + " yol_bulucu=" + c.PET.TRAVEL_LOCATOR + " tamir_PET=" + c.PET.REPAIR + " uzaklasirken_koruma=" + c.LOCATOR.EVADE_GUARD + " yok_say=" + c.LOCATOR.IGNORE + " bulucu_tur_dondur=" + c.LOCATOR.ROTATE
                + " || cal=" + c.STEAL.ENABLED + " kimden=" + c.STEAL.FROM + " NPC>=%" + c.STEAL.NPC_HP + " yontem=" + c.STEAL.ACTION
                + " dusman=%" + c.STEAL.ENEMY_HP + " kovala=" + c.STEAL.CHASE_SEC + "sn gemi>=%" + c.STEAL.HERO_HP
                + " cephane=" + c.STEAL.AMMO + " calarken_PET=" + c.STEAL.PET + " hangi=" + c.STEAL.WHICH
                + " || dusman_saldirinca=" + c.ON_ENEMY.MODE + " kacis_can=%" + c.ON_ENEMY.HP + " atlat=" + c.ON_ENEMY.DIST + "/"
                + c.ON_ENEMY.CALM_SEC + "sn/hizliysa_portal=" + c.ON_ENEMY.FASTER_PORTAL + "/emp=" + c.ON_ENEMY.EMP + "@" + c.ON_ENEMY.EMP_DIST
                + " kac=" + c.ON_ENEMY.ESCAPE_OPTS + " atlat_sec=" + c.ON_ENEMY.OUTRUN_OPTS + " baslangic=" + c.ON_ENEMY.START_OPTS + " akilli_rsb=" + (c.RSB != null && c.RSB.SMART)
                + " || dusmani_hatirla=" + (c.PROTECT.KILLER.ENABLED ? (c.REMEMBER_KILLERS ? "patlatan" : "") + (c.REMEMBER_ATTACKERS ? "+saldiran" : "") : "kapali") + " sure=" + c.PROTECT.KILLER.MINUTES + "dk"
                + " || tamir=" + c.REPAIR.ENABLED + " baslama=%" + c.REPAIR.START_HP + " bitis=%" + c.REPAIR.END_HP
                + " tamir_yeri=" + c.REPAIR.PLACE + " tamirde_kacin=" + set(c.REPAIR.AVOID) + " kacis_konfig=" + c.REPAIR.ENEMY_RUN_MODE
                + " tamir_npc_yakini=" + c.REPAIR.NEAR_NPC + " PET_gemi_tamiri=" + c.REPAIR.PET_SHIP
                + " bosta_tamir=" + c.REPAIR.IDLE + " bosta_tamir_can=%" + c.REPAIR.IDLE_HP
                + " || koruma=" + c.PROTECT.ENABLED + " emp=" + c.PROTECT.EMP_ON + "/" + set(c.PROTECT.EMP_WHEN) + "/%" + c.PROTECT.EMP_HP
                + " ish=" + c.PROTECT.ISH_ON + "/" + set(c.PROTECT.ISH_WHEN) + "/%" + c.PROTECT.ISH_HP
                + " hp_link=" + c.PROTECT.HP_LINK.ENABLED + "/" + set(c.PROTECT.HP_LINK.WHEN) + "/%" + c.PROTECT.HP_LINK.HP
                + " || en_yakin=" + c.PLUS.NEAREST() + " kisa_dolasma=" + c.PLUS.SHORT_ROAM() + " kutu_bekle=" + c.PLUS.BOX_WAIT()
                + " rakip=" + c.PLUS.RIVAL() + " siradaki=" + c.PLUS.NEXT_TARGET() + " oto_menzil=" + c.PLUS.AUTO_RANGE()
                + " menzil_dusur=" + c.PLUS.RANGE_DOWN() + " kacandan_ogrenme_yok=" + c.PLUS.NO_LEARN_FLEE()
                + " takilma=" + c.PLUS.UNSTUCK() + " hizli_kilit=" + c.PLUS.FAST_LOCK() + " zayif_npc_once=" + c.PLUS.WEAK_FIRST()
                + " verim=" + c.PLUS.STATS()
                + " hiz_yetenegi=" + c.PLUS.SPEED_ABILITY() + " akilli_dolasma=" + c.PLUS.SMART_ROAM() + " radar=" + c.PLUS.RADAR()
                + " || combat_plus=" + c.COMBAT.ENABLED + " tarz=" + c.COMBAT.STYLE + " yay_aci=" + c.COMBAT.ARC.ANGLE
                + " guvenli_taraf=" + c.COMBAT.SAFE.SIDE + " aci_ogren=" + c.COMBAT.SAFE.LEARN + " aci_kilit=" + c.COMBAT.SAFE.LOCK
                + " guvenli_yaklas=" + c.COMBAT.SAFE.APPROACH + " hasarda_cik=" + c.COMBAT.SAFE.EXIT + " menzil_disi_pay=" + c.COMBAT.OUT.MARGIN
                + " secenekler=" + set(c.COMBAT.OPTIONS) + " bitir_sonra_tamir=" + c.COMBAT.FINISH_FIRST
                + " kovala=" + c.COMBAT.CHASE + " menzil_dususu=%" + c.COMBAT.CHASE_DROP
                + " dovus_min_can=%" + c.COMBAT.FIGHT_MIN_HP
                + " yolda_bakis=" + c.AVOID_AHEAD + " yolda_aci=" + c.AVOID_ANGLE + " sadece_saldirgan=" + c.AVOID_ONLY_AGGRESSIVE
                + " olu_yaricap=" + c.LOCATOR.DEAD_R + " olu_sn=" + c.LOCATOR.DEAD_SEC
                + " tamir_yolda_dolasma=" + c.REPAIR.TRAVEL_ROAM
                + " || rsb=" + c.RSB.ENABLED + " rsb_hangi=" + c.RSB.WHICH + " rsb_cephane=" + c.RSB.AMMO
                + " rsb_normal=NPC tablosu"
                + " rsb_ping=" + c.RSB.PING + " rsb_lag=" + c.RSB.LAG_GUARD
                + " " + emptyMap.settingsLine()
                + " || log=" + c.LOG + " seviye=" + c.LOG_LEVEL + " dosya=" + c.LOG_FILE;
    }

    private void applyLog() {
        Log.enabled = context.config.LOG;
        Log.detailed = context.config.LOG_LEVEL == BotPlusConfig.LogLevel.DETAILED;
        boolean file = context.config.LOG_FILE;
        if (file && !logFileWas) Log.retryFile();
        if (!file && logFileWas) Log.close();
        logFileWas = file;
        Log.toFile = file;
    }

    // ---------------------------------------------------------------- oyun mesajlari

    /** Oyun mesajlari dosyaya yazilir; captcha uyarisi ve "Menzil disi" yakalanir. */
    @EventHandler
    public void onLogMessage(GameLogAPI.LogMessageEvent e) {
        String msg = e.getMessage();
        if (msg == null) return;
        Log.game(msg);
        String low = msg.toLowerCase(Locale.ROOT);
        if (low.contains("kara kutu") || low.contains("üpheli davran") || low.contains("suspicious")
                || low.contains("black box") || low.contains("captcha")) {
            captchaUntil = Context.now() + CAPTCHA_PAUSE_MS;
            Log.i("CAPTCHA", "oyun captcha istiyor: \"" + msg + "\", BotPlus " + CAPTCHA_PAUSE_MS / 1000 + " sn hicbir sey yapmayacak");
        }
        // Portal atlamayi reddetti: kacis baska portala gecer (Portaldan Atlayamazsa Baska Portala). "Yakininda
        // degilsin" reddi sayilmaz (portala varmadan basildi). Oyuna girdikten sonraki bekleme tum portallari kapatir.
        if (context.safety instanceof PlusSafety && (low.contains("atlama imkans") || low.contains("atlay")
                || low.contains("jump"))) {
            PlusSafety ps = (PlusSafety) context.safety;
            if (low.contains("kaçamazsın") || low.contains("kacamazsin") || low.contains("savaş esnasında")
                    || low.contains("during combat") || low.contains("in combat")) {
                ps.jumpRefusedCombat();
            } else if (low.contains("saniye sonra") || low.contains("seconds after")) {
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(low);
                long sec = m.find() ? Long.parseLong(m.group(1)) : 15;
                ps.jumpRefused(Math.min(60, sec) * 1000L, true);
            }
        }
        if (low.contains("menzil d") || low.contains("out of range")) {
            outOfRange++;
            context.lastOutOfRangeAt = Context.now();
            Npc cur = context.targetNpc();
            Log.d("MENZIL", "oyun: menzil disi (" + outOfRange + ") | hedef " + Log.npc(cur)
                    + (cur == null ? "" : " uzaklik " + (int) context.hero.distanceTo(cur) + " tablo menzili "
                    + (int) cur.getInfo().getRadius() + " " + targeting.fleeInfo()));
            try {
                targeting.outOfRange(cur);
                if (combat.enabled()) combat.outOfRangeMessage();
            } catch (RuntimeException ignored) {
            }
        }
    }

    private boolean captcha() {
        Module m = context.bot.getModule();
        if (m != null && m.getClass().getSimpleName().toLowerCase(Locale.ROOT).contains("captcha")) {
            Log.change("captchamod", "on", "CAPTCHA", "captcha modulu calisiyor (" + m.getClass().getSimpleName() + "), BotPlus bekliyor");
            return true;
        }
        Log.forget("captchamod");
        return Context.now() < captchaUntil;
    }

    // ---------------------------------------------------------------- ana dongu

    private PlusModule plusModule() {
        return current != null && !current.ended() ? current : null;
    }

    /** BotPlus DarkBot'ta ana modul olarak secili mi. */
    private boolean self() {
        try {
            return context.bot.getNonTemporalModule() == this;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Isin disinda gemiyi yoneten modul: ana modda DarkBot yedegi, degilse DarkBot'ta secili modul. */
    private Module mainModule() {
        return self() ? fallback() : context.bot.getNonTemporalModule();
    }

    /** Ana modda DarkBot yedegi: calisma sekline gore Loot / Collector / Loot & Collect. */
    private Module fallback() {
        BotPlusConfig.WorkMode w = context.config.WORK == null ? BotPlusConfig.WorkMode.NPC_BOX : context.config.WORK;
        Class<? extends Module> cls = w == BotPlusConfig.WorkMode.NPC ? LootModule.class
                : w == BotPlusConfig.WorkMode.BOX ? CollectorModule.class : LootCollectorModule.class;
        Module m = fallbacks.get(cls);
        if (m == null) {
            try {
                m = newFallback(cls);
                if (m instanceof Installable) ((Installable) m).install(context.api);
                // Calisma sekli degisti: eski yedek birakilir (her yedek kendi kacis sistemini tasiyordu, birikiyordu).
                fallbacks.clear();
                fallbacks.put(cls, m);
                Log.i("MODUL", "DarkBot yedegi hazir: " + cls.getSimpleName());
            } catch (Throwable t) {
                Log.every("fallbackerr", 60_000, "HATA", "DarkBot yedegi " + cls.getSimpleName() + " kurulamadi: " + t);
                return null;
            }
        }
        return m;
    }

    /**
     * DarkBot yedegi BotPlus'in kacis sistemiyle (PlusSafety) kurulur: yedek de dusmandan DarkBot'un ayarlariyla degil
     * "Dusman Saldirinca" ayariyla kacar, tamir icin portala gitme DarkBot'taki gibidir. Kurulamazsa DarkBot'un kendi
     * kurulumu kullanilir.
     */
    private Module newFallback(Class<? extends Module> cls) {
        eu.darkbot.shared.utils.SafetyFinder sf = context.safety;
        PluginAPI a = context.api;
        if (sf != null) {
            try {
                if (cls == LootModule.class) return newLoot(a, sf);
                if (cls == CollectorModule.class) return newCollector(a, sf);
                return new LootCollectorModule(newLoot(a, sf), newCollector(a, sf),
                        a.requireAPI(eu.darkbot.api.managers.ConfigAPI.class), a.requireAPI(eu.darkbot.api.managers.I18nAPI.class));
            } catch (Throwable t) {
                Log.i("MODUL", "DarkBot yedegi BotPlus kacis sistemiyle kurulamadi (" + t + "), DarkBot'un kendi kurulumu kullaniliyor");
            }
        }
        return a.requireInstance(cls);
    }

    private static LootModule newLoot(PluginAPI a, eu.darkbot.shared.utils.SafetyFinder sf) {
        return new LootModule(a, a.requireAPI(eu.darkbot.api.managers.BotAPI.class), a.requireAPI(PetAPI.class),
                a.requireAPI(HeroAPI.class), a.requireAPI(eu.darkbot.api.managers.ConfigAPI.class), sf,
                a.requireAPI(eu.darkbot.api.managers.MovementAPI.class), a.requireAPI(eu.darkbot.api.managers.EntitiesAPI.class),
                a.requireAPI(eu.darkbot.api.managers.AttackAPI.class), a.requireAPI(eu.darkbot.api.managers.StarSystemAPI.class));
    }

    private static CollectorModule newCollector(PluginAPI a, eu.darkbot.shared.utils.SafetyFinder sf) {
        return new CollectorModule(a, a.requireAPI(eu.darkbot.api.managers.BotAPI.class), a.requireAPI(PetAPI.class),
                a.requireAPI(HeroAPI.class), a.requireAPI(eu.darkbot.api.managers.StatsAPI.class),
                a.requireAPI(eu.darkbot.api.managers.ConfigAPI.class), a.requireAPI(eu.darkbot.api.managers.MovementAPI.class),
                a.requireAPI(eu.darkbot.api.managers.HeroItemsAPI.class), a.requireAPI(eu.darkbot.api.managers.EntitiesAPI.class),
                a.requireAPI(eu.darkbot.api.managers.StarSystemAPI.class), sf);
    }

    /** Ana modda is yokken DarkBot yedegi tiklenir (kacis, harita yolculugu, Combat kapaliyken NPC / kutu). */
    private void tickFallback() {
        Module m = fallback();
        if (m == null) return;
        try {
            m.onTickModule();
        } catch (Throwable t) {
            Log.every("fallbacktick", 10_000, "HATA", "DarkBot yedegi: " + t);
        }
    }

    /** Calisan is biter; ana mod disinda gecici modul DarkBot'un moduluna geri verir. */
    private void endJob(String why) {
        PlusModule m = plusModule();
        if (m != null) m.finish(why);
        if (temporal != null && context.bot.getModule() == temporal) temporal.back();
        temporal = null;
    }

    /** Ayar satiri icin: acik alt moduller. */
    private String subsLine() {
        StringBuilder b = new StringBuilder();
        if (settings.combat != null) b.append(",Combat");
        if (settings.rsb != null) b.append(",RSB");
        if (settings.steal != null) b.append(",NPCSteal");
        if (settings.repair != null) b.append(",Repair");
        if (settings.locator != null) b.append(",EnemyLocator");
        if (settings.defense != null) b.append(",Defense");
        return b.length() == 0 ? "-" : b.substring(1);
    }

    // ---------------------------------------------------------------- Module (ana mod)

    @Override
    public void onTickModule() {
        try {
            Log.flushIfDue();
            context.beginTick();
            settings.refresh();
            tickCore(true);
        } catch (Throwable t) {
            crashed("tik", t);
        }
    }

    @Override
    public String getStatus() {
        PlusModule m = plusModule();
        if (m != null) return m.getStatus();
        Module f = fallbacks.isEmpty() ? null : fallback();
        String st = f == null ? null : Context.status(f);
        String stats = context.config.PLUS.STATS() ? "\n" + Stats.screen() : "";
        // Combat alt modulu kapali / tam kontrol degilse dovusu DarkBot'un modulu yapar: bu durum satirda yazar.
        String who = "";
        if (st != null && context.attack.hasTarget()) who = combat.enabled() ? "" : " · DarkBot dövüşü (Combat kapalı)";
        return "BotPlus" + who + (st == null ? "" : ": " + st) + stats;
    }

    @Override
    public boolean canRefresh() {
        PlusModule m = plusModule();
        if (m != null) return m.canRefresh();
        // Is yokken DarkBot yenileme sordu: BotPlus Yenileme aciksa (Haritada / Cikis Yapip) istek BotPlus'in yenileme
        // isine verilir; eskiden yedek modul (LootModule) sayaci kurup DarkBot'un kacisiyla portala gidiyordu.
        // Sadece BotPlus gemiyi yonetiyorsa (acik, calisma haritasinda): yoksa istek hic islenmez, DarkBot yenileyemezdi.
        if (context.config.RENEW.own() && context.config.ENABLED && Context.now() - context.plusInChargeAt < 2000) {
            context.refreshWantedAt = Context.now();
            return false;
        }
        Module f = fallback();
        return f == null || f.canRefresh();
    }

    /** Secili ana modul calisiyor mu (gecici moduller haric). */
    /** BotPlus'in calistigi DarkBot modulleri: NPC Killer, Loot & Collect, Collector (yeni ve eski adlar). */
    private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList(
            "LootModule", "LootCollectorModule", "LootNCollectorModule", "CollectorModule"));

    private boolean activeModule() {
        if (self()) return true;
        Module m = context.bot.getModule();
        if (m == null || m instanceof TemporalModule) {
            if (m != null) Log.change("tempmod", m.getClass().getSimpleName(), "MODUL", "baska gecici modul calisiyor: "
                    + m.getClass().getSimpleName() + " (" + Context.status(m) + "), BotPlus bekliyor");
            return false;
        }
        Log.forget("tempmod");
        String id = m.getClass().getCanonicalName();
        boolean ok = SUPPORTED.contains(m.getClass().getSimpleName());
        if (!id.equals(lastModule)) {
            lastModule = id;
            Log.i("MODUL", m.getClass().getSimpleName() + " -> "
                    + (ok ? "BotPlus aktif" : "BotPlus bekliyor (NPC / kutu modulu degil, baska eklentinin modulu)"));
        }
        return ok;
    }

    private int lastKesim = -1;

    /** Combat Plus acikken "npc" sayaci CombatPlus'in "kesim" sayacindan (cift sayim olmasin). */
    private void countKills() {
        int k = Stats.get("kesim");
        if (lastKesim >= 0 && k > lastKesim && context.config.COMBAT.ENABLED)
            for (int i = lastKesim; i < k; i++) Stats.count("npc");
        lastKesim = k;
    }

    private void deactivate() {
        if (!wasActive) return;
        wasActive = false;
        targeting.reset();
        roam.reset();
        boxes.reset();
    }

    @Override
    public void onTickBehavior() {
        // Ana modda her sey onTickModule'de (BotPlus'in tiki). Ama DarkBot'un gecici modulu (harita yolculugu, tamir,
        // captcha...) calisirken BotPlus'in modul tiki gelmez: gemiyi surmeyen korumalar (Defense, RSB) burada calisir
        // (eskiden olumden sonraki yolculukta EMP / Insta-Shield / HP Link / RSB tamamen kapali kaliyordu).
        if (self()) {
            if (context.bot.getModule() != this) passiveTick();
            return;
        }
        try {
            Log.flushIfDue();
            context.beginTick(); // her tik yeni tik onbellegi (gecici modul degismis olsa da)
            settings.refresh(); // alt moduller + menu -> gorunum (arayuzde girilen deger hemen gecerli)
            tickCore(false);
        } catch (Throwable t) {
            // DarkBot'un hata yakalayicisi ozelligi kaldirabilir: hata burada kalir, is guvenle birakilir.
            crashed("tik", t);
        }
    }

    /** Ana mod, DarkBot'un gecici modulu calisirken: sadece gemiyi surmeyen ozellikler (Defense, RSB, saldiran takibi). */
    private void passiveTick() {
        try {
            Log.flushIfDue();
            context.beginTick();
            settings.refresh();
            applyLog();
            if (!context.config.ENABLED) return;
            boolean alive = !context.repair.isDestroyed() && context.heroLoaded();
            context.trackAttackers();
            Module tm = context.bot.getModule();
            context.deaths.sample("gecici modul: " + (tm == null ? "-" : tm.getClass().getSimpleName()));
            // Captcha / baglanti kesme modulunde EMP, Insta-Shield ve RSB'ye basilmaz (cikis / captcha bozulmasin).
            String tn = tm == null ? "" : tm.getClass().getSimpleName().toLowerCase(Locale.ROOT);
            if (tn.contains("captcha") || tn.contains("disconnect")) return;
            protect.tick(alive);
            if (alive) context.unlock.tick(false);
            context.trackCombat();
            rsb.tick();
        } catch (Throwable t) {
            crashed("gecici modul", t);
        }
    }

    /** Hata: 10 sn'de bir loglanir, calisan BotPlus isi birakilir; hata asla yukari atilmaz. */
    private void crashed(String where, Throwable t) {
        try {
            StackTraceElement[] st = t.getStackTrace();
            Log.every("hata" + where, 10_000, "HATA", where + ": " + t
                    + (st != null && st.length > 0 ? " @ " + st[0] : ""));
        } catch (Throwable ignored) {
        }
        try {
            endJob("hata: " + t.getClass().getSimpleName());
            current = null;
        } catch (Throwable ignored) {
        }
    }

    /**
     * Ana dongu. self: BotPlus ana modul (isler burada tiklenir, is yokken DarkBot yedegi); degilse DarkBot'un
     * modulu secili (isler gecici modulle gemiyi devralir).
     */
    private void tickCore(boolean self) {
        applyLog();
        locator.trackDeath();
        context.deaths.sample(plusModule() == null ? (self ? "DarkBot yedegi" : "-") : plusModule().job().name());
        emptyMap.tick();
        locRefresh.tick();
        if (!context.config.ENABLED) {
            endJob("BotPlus kapatildi");
            deactivate();
            if (self) tickFallback();
            return;
        }
        checkSettings();
        boolean captcha = captcha();
        Stats.running(!captcha);
        stats.tick(captcha);
        countKills();
        locator.tickIgnored();
        tickRotation();
        repair.track();
        try {
            if (context.hero.isMoving()) {
                if (movingSince == 0) movingSince = Context.now();
                stillSince = 0;
            } else {
                movingSince = 0;
                if (stillSince == 0) stillSince = Context.now();
            }
        } catch (RuntimeException ignored) {
        }
        context.trackPetCooldowns();
        if (combat.enabled() && context.onWorkingMap()) combat.markScanned(Context.now());
        // Gemiyi surmeyen ozellikler (Defense, RSB) hangi modul secili olursa olsun calisir.
        boolean alive = !context.repair.isDestroyed() && context.heroLoaded();
        context.trackAttackers();
        // Captcha / baglanti kesme modulunde EMP, Insta-Shield ve RSB'ye basilmaz (davranis modunda da).
        Module curMod = context.bot.getModule();
        String curName = curMod == null ? "" : curMod.getClass().getSimpleName().toLowerCase(Locale.ROOT);
        boolean pressOk = !curName.contains("captcha") && !curName.contains("disconnect");
        if (pressOk) protect.tick(alive);
        if (pressOk && alive) context.unlock.tick(self || plusModule() != null);
        context.trackCombat();
        if (pressOk) rsb.tick();
        tickPetPresence();

        // Ana mod disinda: calisan isin gecici modulunu baska bir modul / eklenti degistirdiyse is birakilir.
        if (!self && current != null && !current.ended() && context.bot.getModule() != temporal) {
            Module m = context.bot.getModule();
            current.finish("araya girdi: " + (m == null ? "-" : m.getClass().getSimpleName() + " (" + Context.status(m) + ")"));
            context.lastEndAt = Context.now();
            temporal = null;
        }
        if (current != null && current.ended()) current = null;
        PlusModule running = plusModule();
        if (captcha) {
            endJob("captcha");
            Log.every("captchawait", 10_000, "CAPTCHA", "captcha bekleniyor, BotPlus devre disi ("
                    + Math.max(0, (captchaUntil - Context.now()) / 1000) + " sn)");
            deactivate();
            if (self) tickFallback();
            return;
        }
        boolean full = combat.enabled();
        if (running != null) {
            // Is sirasinda (kovalama, calma) olen hedef de yakalanir: sayilir, is bitince kutu beklenir.
            Location d = boxes.died();
            if (d != null && !full) {
                pendingDied = d;
                pendingDiedAt = Context.now();
                Log.i("NPC", "is sirasinda hedef oldu " + Log.loc(d) + " (" + running.job().name() + ")");
            }
            if (context.onWorkingMap() && !context.repair.isDestroyed()) wasActive = true;
            preempt(running, full);
            // Ana modda is burada tiklenir (preempt yeni is baslattiysa o zaten tiklendi).
            if (self && current == running && !running.ended()) running.tick();
            if (!self || current == null || !current.ended()) {
                state();
                return;
            }
            current = null; // is bu tikte bitti: ayni tikte siradaki is / DarkBot yedegi
        }

        if (context.repair.isDestroyed() || !activeModule() || !context.heroLoaded()) {
            // Teshis: BotPlus neden bekliyor (yeniden baglanma / yukleme sirasinda gemi bilgisi bos gelir).
            Module cm = context.bot.getModule();
            Log.every("inactive", 5000, "BEKLIYOR", context.repair.isDestroyed() ? "gemi patlamis"
                    : !context.heroLoaded() ? "gemi bilgisi yuklenmedi (baglanti / harita yukleniyor), modul " + context.mainStatus()
                    : cm instanceof TemporalModule ? "DarkBot'un gecici modulu calisiyor (" + cm.getClass().getSimpleName()
                    + ": " + Context.status(cm) + "), bitince BotPlus devam eder"
                    : "secili modul DarkBot'un NPC / kutu modulu degil (baska eklenti): sadece RSB ve Defense calisiyor, "
                    + context.mainStatus());
            deactivate();
            if (self) tickFallback();
            return;
        }
        if (!context.onWorkingMap()) {
            Log.every("map", 30_000, "HARITA", "calisma haritasinda degil ("
                    + (context.star.getCurrentMap() == null ? "?" : context.star.getCurrentMap().getName()) + "), "
                    + (self ? "DarkBot yedegi calisma haritasina goturuyor" : "BotPlus bekliyor"));
            deactivate();
            if (self) tickFallback();
            return;
        }
        if (!wasActive && full) targeting.releaseHolds();
        wasActive = true;

        Location died = boxes.died();
        if (died == null && pendingDied != null && Context.now() - pendingDiedAt < 3000) died = pendingDied;
        pendingDied = null;
        if (full) died = null;
        if (died != null) targeting.releaseHolds();
        if (died != null) context.npcDied.accept(died);
        if (died != null && context.config.PLUS.FAST_LOCK()) lockNext(died);

        // DarkBot'un modulu / yedegi kendi kacisini yapiyorsa (portala gidiyor / bekliyor) yenileme disinda is baslatilmaz.
        // Dusman Saldirinca: dusman ates ediyorsa kacis her seyden once (yenileme dahil).
        // BotPlus kacisi (Dusman Saldirinca) sadece BotPlus ana modul olarak seciliyken. DarkBot'un kendi modulu
        // seciliyken (BotPlus davranis) kacisi DarkBot'un "Dusmandan Kac" ayarlari yapar; alt moduller (Combat, RSB,
        // Defense...) calismaya devam eder. Iki kacis sistemi ayni anda gemiyi surmez.
        if (self) context.plusInChargeAt = Context.now();
        PlusModule.Job job = self ? escape.check() : null;
        if (job == null) job = refresh.check();
        if (job == null && context.mainEscaping()) {
            Log.dEvery("dbescape", 5000, "GUVENLIK", () -> "DarkBot kaciyor (" + context.mainStatus() + "), BotPlus is baslatmiyor | "
                    + context.dangerInfo(context.hero));
            if (self) tickFallback();
            state();
            return;
        }
        // Ana mod disinda, Combat kapaliyken: is bitince DarkBot'un modulu en az bir tik calissin (yenileme / kacis).
        if (job == null && !self && !full && Context.now() - context.lastEndAt < HANDBACK_MS && !locator.goNow()) {
            state();
            return;
        }
        boolean collect = boxes.collectModule();
        if (job == null) job = repair.check();
        if (job == null) job = locator.checkKiller();
        if (job == null) job = steal.check();
        if (job == null) job = locator.checkIgnore();
        if (job == null && full) {
            job = fullChain();
            if (job == null && self) tickFallback();
        } else if (job == null) {
            targeting.tick(true);
            job = targeting.checkWait();
            if (job == null && locator.goNow()) job = locator.checkGo(); // Yok Say sonrasi hemen yeni sinyal
            if (job == null) job = targeting.checkChase();
            if (job == null && died != null && context.config.PLUS.BOX_WAIT() && collect) job = boxWait(died);
            if (job == null) job = order.check(collect);
            if (job == null) job = locator.checkGo();
            if (job == null) job = repair.checkIdle();
            if (job == null) job = roam.unstuck();
            if (job == null) {
                // Ana modda once DarkBot yedegi hareket eder, Kisa Dolasma / kutu duzeltmeleri onun hedefini duzeltir.
                if (self) tickFallback();
                roam.tick();
                boxes.tick();
                emptyRoam();
            }
        }
        state();
        if (job != null) start(job, null, -1, self);
        // Tam kontrolde ilk tikte biten is (ornegin HB / YB hedef bekleme isine birakildi): DarkBot'a bir tik bile
        // kalmasin, zincir ayni tikte bir kez daha calisir.
        if (full && current != null && current.ended()) {
            current = null;
            PlusModule.Job again = fullChain();
            if (again != null) start(again, null, -1, self);
            else if (self) tickFallback();
        }
    }

    /**
     * Bos Harita Modu (Combat tam kontrol disinda): DarkBot'un modulu dolasirken hedefi harita ortasina cekilir.
     * Hedef varken, kacarken ya da DarkBot kutuya / portala giderken dokunulmaz.
     */
    private void emptyRoam() {
        Location g = emptyMap.goal();
        if (g == null || context.attack.hasTarget() || context.mainEscaping()) return;
        Location dest = context.move.getDestination();
        if (dest != null) {
            for (Box b : context.ents.getBoxes()) if (b.distanceTo(dest) < 50) return;
            for (eu.darkbot.api.game.entities.Portal p : context.ents.getPortals()) if (p.distanceTo(dest) < 300) return;
        }
        context.travelTo(g);
        Log.dEvery("emptyroamdb", 10_000, "BOS HARITA", "DarkBot dolasmasi harita ortasina cekiliyor " + Log.loc(g));
    }

    /** Tam kontrol: bekleme (HB / YB hedef) ve bulucu, yoksa dovus / kutu / dolasma isi. */
    private PlusModule.Job fullChain() {
        PlusModule.Job job = targeting.checkWait();
        if (job == null) job = repair.checkFightMin();
        if (job == null) job = locator.checkGo();
        // Bosta tamir tam kontrolde ayri is degil: gemi dolasmaya / bulucuya devam ederken tamir olur (idleRepairing).
        if (job == null) job = combat.check();
        return job;
    }

    /** Calisan isten daha onemli is varsa mevcut is kesilir. */
    private void preempt(PlusModule running, boolean full) {
        boolean self = self();
        if (self) context.plusInChargeAt = Context.now(); // davranis modunda kacis DarkBot'un ayarlariyla
        int p = running.job().priority();
        PlusModule.Job job = null;
        // Dusman Saldirinca her isi keser. Istisnalar (can sinirin ustundeyse): cikis geri sayimi (yenileme) ve
        // NPC Calma'da dusmana saldiri. Tamir / yenileme icin portala gidilirken de calisir (eskiden kacis surerken
        // hic bakilmiyor, tamir kacisi dusman kacisina donmuyordu): ayni kacis sistemi kesintisiz devam eder.
        boolean loggingOut = running.job() instanceof Refresh.Job && ((Refresh.Job) running.job()).loggingOut();
        boolean stealing = steal.firingAtEnemy(running);
        if (self && p < PlusModule.P_ESCAPE && ((!loggingOut && !stealing) || escape.hpLow())) job = escape.check();
        if (job != null) {
            String from = running.job().name();
            int map = running.startMap();
            running.finish("kesildi, daha onemli is: " + job.name());
            start(job, from, map, self());
            return;
        }
        // Kacis surerken (portal atlamasi dahil) baska is kesmez: kacis yarida kalmasin.
        if (running.escaping()) return;
        if (job == null && p < PlusModule.P_REFRESH) job = refresh.check();
        if (job == null && p < PlusModule.P_REPAIR && !context.repair.isDestroyed()) job = repair.check();
        if (job == null && p < PlusModule.P_KILLER) job = locator.checkKiller();
        if (job == null && p < PlusModule.P_STEAL) job = steal.check();
        if (job == null && p < PlusModule.P_IGNORE) job = locator.checkIgnore();
        if (job == null && full && running.job() instanceof CombatJob) {
            // Bulucu: hedef yokken her zaman; "NPC'yi Birak" aciksa dovus sirasinda da (kutu toplarken degil).
            if (p < PlusModule.P_LOCATOR) job = repair.checkFightMin(); // dusuk canla yeni dovuse girilmez
            if (job == null && (p < PlusModule.P_LOCATOR || (context.config.LOCATOR.DROP && p == PlusModule.P_COMBAT))) job = locator.checkGo();
            // Takilmayi Coz tam kontrolde de: gemi 15 sn yerinden kipirdamiyorsa (hedef / dovus yokken). Eskiden
            // "p < P_UNSTUCK" sarti dovus isinde (oncelik 38) hic saglanmiyordu, ozellik tam kontrolde olu koddu.
            if (job == null && !combat.fighting() && context.targetNpc() == null) job = roam.unstuck();
        }
        if (job == null && full && running.job() instanceof Locator.Go) job = repair.checkFightMin();
        if (job == null && full && running.job() instanceof Targeting.Wait && combat.freeTarget()) {
            // Beklerken beklemesiz NPC geldi: bekleme birakilir, tam kontrol onu alir.
            int map0 = running.startMap();
            endJob("beklemesiz NPC var");
            context.lastEndAt = Context.now();
            PlusModule.Job k = combat.check();
            if (k != null) start(k, "BEKLE", map0, self());
            return;
        }
        if (job == null) return;
        String from = running.job().name();
        int map = running.startMap();
        running.finish("kesildi, daha onemli is: " + job.name());
        start(job, from, map, self());
    }

    @Override
    public void onStoppedBehavior() {
        try {
            Log.flushIfDue();
            stoppedBehavior();
        } catch (Throwable t) {
            crashed("durdu", t);
        }
    }

    private void stoppedBehavior() {
        stoppedAt = Context.now();
        context.lastStoppedAt = stoppedAt;
        boolean destroyed = context.repair.isDestroyed();
        boolean running;
        try {
            running = context.bot.isRunning();
        } catch (RuntimeException e) {
            running = false;
        }
        if (!running) {
            context.lastHaltedAt = stoppedAt;
            // Bot durduruldu: is bitirilir (ana modda da; yeniden baslayinca sifirlanmis durumla eski ise devam edilmesin).
            endJob("bot durduruldu");
        }
        String why = destroyed ? "gemi patlamis" : !running ? "bot durduruldu"
                : "oyun yukleniyor / baglanti penceresi (DarkBot modulu tiklemiyor)";
        Log.every("stopped", 10_000, "BEKLIYOR", "DarkBot BotPlus'i calistirmiyor: " + why);
        // Gemi oluyken DarkBot modulu tiklemez: calisan is burada bitirilir (canlaninca eski ise devam edilmesin).
        if (destroyed) endJob("gemi patladi");
        Stats.running(false);
        locator.trackDeath();
        // Arayuz / baglanti duraklamasinda durum korunur; sadece bot gercekten durunca ya da gemi patlayinca sifirlanir.
        if (destroyed || !running) deactivate();
    }

    /** Son "bot durdu / bekliyor" zamani (yeniden baslatmada isin kesilmesini ayirt etmek icin). */
    private long stoppedAt;

    /**
     * Is baslar. Ana modda BotPlus'in icinde ayni tikte tiklenir; degilse gecici modulle DarkBot'un modulu devralinir.
     */
    private void start(PlusModule.Job job, String from, int map, boolean self) {
        Module back = self ? fallback() : context.bot.getNonTemporalModule();
        Log.i(job.name(), "BotPlus aldi" + (from == null ? "" : " (" + from + " yerine)") + ": " + job.status()
                + " | " + (self ? "ana mod, yedek " : "DarkBot modulu ") + (back == null ? "-" : back.getClass().getSimpleName()
                + " (" + Context.status(back) + ")"));
        PlusModule run = new PlusModule(context, job, map);
        current = run;
        if (self) {
            temporal = null;
            run.tick(); // ayni tikte ilk hareket
            return;
        }
        temporal = context.bot.setModule(new PlusTemporal(context, run));
        // Ayni tikte ilk hareket: bir tik gecikme olmasin.
        if (context.bot.getModule() == temporal) temporal.onTickModule();
    }

    /** Ayrintili log: 5 sn'de bir durum satiri (sadece yazilacaksa kurulur). */
    private void state() {
        if (Log.dDue("state", STATE_MS)) Log.i("DURUM", stateLine());
    }

    private String stateLine() {
        StringBuilder sb = new StringBuilder();
        Module m = context.bot.getModule();
        PlusModule pm = plusModule();
        sb.append("modul ").append(m == null ? "-" : m.getClass().getSimpleName()).append(": ")
                .append(m == null ? "-" : Context.status(m));
        if (pm != null) sb.append(" (is ").append(pm.age() / 1000).append(" sn)");
        try {
            sb.append(" | gemi can ").append(Log.pct(context.heroHp())).append(" kalkan ")
                    .append(Log.pct(context.hero.getHealth().shieldPercent()))
                    .append(" konfig ").append(context.hero.getConfiguration())
                    .append(" formasyon ").append(context.hero.getFormation())
                    .append(" cephane ").append(context.hero.getLaser())
                    .append(" hiz ").append(context.hero.getSpeed())
                    .append(" ping ").append(context.stats.getPing());
        } catch (RuntimeException ignored) {
        }
        Npc cur = context.targetNpc();
        if (cur != null) {
            sb.append(" | hedef ").append(Log.npc(cur)).append(" uzaklik ").append((int) context.hero.distanceTo(cur))
                    .append(" menzil ").append((int) cur.getInfo().getRadius())
                    .append(context.shooting(cur) ? " ates ediliyor" : " ates yok")
                    .append(cur.isAttacking(context.hero) ? ", bize vuruyor" : "")
                    .append(", ").append(targeting.fleeInfo());
        } else sb.append(" | hedef yok");
        sb.append(" | ").append(context.dangerInfo(context.hero));
        Box b = context.nearestBox(Math.max(2000, context.collectRadius()));
        if (b != null) sb.append(" | kutu ").append(b.getTypeName()).append(" ").append((int) context.hero.distanceTo(b));
        Optional<Location> ping = context.ping();
        if (ping.isPresent()) sb.append(" | sinyal ").append((int) context.hero.distanceTo(ping.get()));
        try {
            PetGear pg = context.pet.isActive() ? context.pet.getGear() : null;
            sb.append(" | PET ").append(pg == null ? "yok" : pg.toString());
        } catch (RuntimeException ignored) {
        }
        if (context.config.RSB.ENABLED) sb.append(" | RSB ").append(rsb.status());
        sb.append(" | kargo ").append(context.cargo());
        sb.append(" | kacis ").append(context.safetyStatus());
        if (context.mainEscaping()) sb.append(" | DarkBot ").append(context.mainStatus());
        return sb.toString();
    }

    /** Hizli Kilit: NPC olur olmaz siradaki NPC'ye kilit atilir. */
    private void lockNext(Location at) {
        Npc best = null;
        int bestPri = Integer.MAX_VALUE;
        double bestDist = Double.MAX_VALUE;
        for (Npc n : context.ents.getNpcs()) {
            if (!context.darkBotWouldKill(n) || context.move.getClosestDistance(n) >= 500) continue;
            double d = context.hero.distanceTo(n);
            if (d > FAST_LOCK_DIST) continue;
            int pri = n.getInfo().getPriority();
            if (pri < bestPri || (pri == bestPri && d < bestDist)) {
                best = n;
                bestPri = pri;
                bestDist = d;
            }
        }
        if (best == null) {
            Log.dEvery("fastlocknone", 5000, "HIZLI KILIT", "NPC oldu " + Log.loc(at) + ", " + FAST_LOCK_DIST
                    + " icinde baska NPC yok | " + context.dangerInfo(at));
            return;
        }
        context.attack.setTarget(best);
        context.attack.tryLockTarget();
        Stats.count("hizli kilit");
        Log.i("HIZLI KILIT", "NPC oldu " + Log.loc(at) + ", kilit: " + Log.npc(best) + " " + (int) bestDist + " uzakta");
    }

    /**
     * Kutuyu Bekle: NPC olunce kutusu beklenir, dusunce toplanir (bulucu araya girmez).
     * Kutu tablosunda toplama kapaliysa ya da kargo doluysa (FROM_SHIP) loga yazilir ve birakilir.
     * Toplama ancak kutu kaybolunca "alindi" sayilir; alinamazsa tekrar denenir.
     */
    private PlusModule.Job boxWait(Location at) {
        final long start = Context.now();
        Log.i("KUTU", "NPC oldu " + Log.loc(at) + ", kutusu icin " + BOX_WAIT_MS + " ms bekleniyor");
        return new PlusModule.Job() {
            private Box box;
            private long collectUntil;
            private int tries;

            @Override
            public String name() {
                return "KUTU";
            }

            @Override
            public int priority() {
                return PlusModule.P_BOX;
            }

            @Override
            public String status() {
                return box == null ? "kutu bekleniyor" : box.getTypeName() + " toplanıyor";
            }

            @Override
            public boolean tick() {
                long now = Context.now();
                if (box == null) {
                    // Olum yerine en yakin, toplanacak kutu (eskiden listedeki son kutu aliniyordu).
                    double bestD = Double.MAX_VALUE;
                    for (Box b : context.ents.getBoxes()) {
                        if (!b.isValid() || b.distanceTo(at) >= 500 || Boxes.isPublic(b)) continue;
                        if (b.getInfo() == null || !b.getInfo().shouldCollect()) continue;
                        double bd = b.distanceTo(at);
                        if (bd < bestD) {
                            bestD = bd;
                            box = b;
                        }
                    }
                    if (box == null) {
                        if (now - start >= BOX_WAIT_MS) {
                            Log.i("KUTU", BOX_WAIT_MS + " ms doldu, kutu gorulmedi");
                            return false;
                        }
                        context.stop();
                        if (context.config.PLUS.FAST_LOCK() && context.attack.hasTarget()) context.attack.tryLockTarget();
                        return true;
                    }
                    Log.i("KUTU", box.getTypeName() + " dustu (" + (now - start) + " ms) " + (int) context.hero.distanceTo(box) + " uzakta");
                    if (box.getInfo() == null || !box.getInfo().shouldCollect()) {
                        Log.i("KUTU", box.getTypeName() + " kutu tablosunda toplama kapali, toplanmiyor");
                        return false;
                    }
                    if (Context.isResourceBox(box) && context.cargoFull()) {
                        Log.i("KUTU", box.getTypeName() + " toplanamaz: kargo dolu (" + context.cargo() + ")");
                        return false;
                    }
                }
                // Sadece kutu kaybolunca alindi sayilir (DarkBot ilk denemeden sonra kutuyu 50 ms "toplandi" gosterir).
                if (!box.isValid()) {
                    Log.i("KUTU", box.getTypeName() + " alindi (" + (now - start) + " ms, " + tries + " deneme)");
                    return false;
                }
                // Once suren toplama beklenir (yarida birakilirsa DarkBot ayni kutuya tekrar tiklar).
                if (collectUntil > 0) {
                    if (now < collectUntil) return true;
                    collectUntil = 0; // kutu hala duruyor: tekrar denenir
                }
                if (now - start > BOX_COLLECT_MS || tries >= 3) {
                    Log.i("KUTU", box.getTypeName() + " alinamadi (" + (now - start) / 1000 + " sn, " + tries + " deneme), uzaklik "
                            + (int) context.hero.distanceTo(box));
                    return false;
                }
                double d = context.hero.distanceTo(box);
                if (d < 250) {
                    context.stop();
                    if (context.collect(box)) {
                        tries++;
                        collectUntil = now + context.collectWaitLeft();
                    }
                } else if (!context.collectBusy()) context.travelTo(box);
                return true;
            }
        };
    }

    // ---------------------------------------------------------------- ekran

    @Override
    public void onDraw(MapGraphics mg) {
        // Verim sadece BotPlus kontrolu aldiginda modul durumunun ikinci satirinda yazar (PlusModule.getStatus);
        // BotPlus calismiyorken ekrana bir sey yazilmaz.
    }

    // ---------------------------------------------------------------- secimler

    @Override
    public PetGearSupplier getGearSupplier() {
        return gearSupplier;
    }

    /**
     * PET: yok sayma / calma / dusman sirasinda koruma, tamirde tamirci, savasta secilen modul.
     * null: BotPlus bir sey istemiyor (DarkBot'un ayari gecerli).
     */
    /** Tik basina bir kez hesaplanir (getPriority ve get ayni sonucu kullanir; bekci bir kez calisir). */
    private long gearTickNo = -1;
    private PetGear gearCached;

    private PetGear wantGear() {
        long t = context.tickNo();
        if (t == gearTickNo) return gearCached;
        PetGear g = wantGear0();
        if (g != null && g != currentGear() && blocked(g)) g = fallbackGear(g);
        watchRefusal(g);
        gearTickNo = t;
        gearCached = g;
        return g;
    }

    /** Cikis geri sayimi suruyor: PET'e dokunulmaz (DarkBot'un varsayilan secicisi de basmasin). */
    private boolean logoutHold() {
        if (!context.config.ENABLED || !context.config.PET.ENABLED) return false;
        PlusModule m = plusModule();
        return m != null && !m.ended() && m.job() instanceof Refresh.Job && ((Refresh.Job) m.job()).loggingOut();
    }

    /**
     * Istenen PET modulu gecmiyor (oyun savasta kombo tamiri baslatmiyor, modul bekleme suresinde vb.):
     * 2,5 sn + 2 x ping icinde gecmezse o modulden bir sure vazgecilir (10 / 20 / 40 / 60 sn; gecince sifirlanir).
     */
    private final Map<PetGear, Long> refusedUntil = new EnumMap<>(PetGear.class);
    private final Map<PetGear, Integer> refusedFails = new EnumMap<>(PetGear.class);
    private PetGear refuseWant;
    private long refuseSince;
    private static final long COMBO_SWITCH_MS = 2500, COMBO_GIVE_UP_MS = 10_000;

    private void watchRefusal(PetGear want) {
        long now = Context.now();
        PetGear cur = currentGear();
        if (cur != null) refusedFails.remove(cur); // modul gecti: art arda basarisizlik sifirlanir
        boolean comboActive = now - context.comboRepairUsedAt < Context.COMBO_REPAIR_ACTIVE_MS;
        // PET yokken (olu / kapali) bekci calismaz. Bekleme suresindeki modul (kombo tamir, HP Link vb.) basarisizlik
        // sayilmaz: DarkBot beklemedeki module basmaz (eskiden HP Link'ten sonra 10-60 sn ceza veriliyordu).
        // DarkBot, hedef dusman sayilmayan bir oyuncuyken PET'i pasife zorlar: bu da basarisizlik sayilmaz.
        // DarkBot hedef dusman olmayan oyuncuyken PET'i PASIF'e zorlar (PetManager): bu ret sayilmaz.
        Object ht = context.hero.getTarget();
        boolean forcedPassive = cur == PetGear.PASSIVE && ht instanceof eu.darkbot.api.game.entities.Player
                && ((eu.darkbot.api.game.entities.Player) ht).getEntityInfo() != null
                && !((eu.darkbot.api.game.entities.Player) ht).getEntityInfo().isEnemy();
        if (want == null || cur == null || want == cur || cooldown(want) || forcedPassive
                || (want == PetGear.COMBO_REPAIR && comboActive)) {
            refuseWant = null;
            refuseSince = 0;
            return;
        }
        if (want != refuseWant) {
            refuseWant = want;
            refuseSince = now;
            return;
        }
        long limit = COMBO_SWITCH_MS + 2L * ping();
        if (now - refuseSince <= limit) return;
        int fails = refusedFails.getOrDefault(want, 0);
        long wait = Math.min(60_000, COMBO_GIVE_UP_MS << Math.min(fails, 3));
        refusedFails.put(want, fails + 1);
        refusedUntil.put(want, now + wait);
        refuseWant = null;
        refuseSince = 0;
        if (want == PetGear.COMBO_REPAIR)
            Log.i("PET", "Kombo Gemi Tamiri " + limit / 1000.0 + " sn'de olmadi (PET " + cur + ", "
                    + (context.outOfCombat(1000) ? "savas yok" : context.combatWhy()) + "): " + wait / 1000
                    + " sn vazgecildi, bulucuya donuluyor");
        else
            Log.i("PET", want + " " + limit / 1000.0 + " sn'de olmadi (PET " + cur + ", "
                    + (context.outOfCombat(1000) ? "savas yok" : context.combatWhy()) + "): " + wait / 1000 + " sn vazgecildi");
    }

    private int ping() {
        try {
            return Math.max(0, Math.min(1000, context.stats.getPing()));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private boolean refused(PetGear g) {
        Long until = refusedUntil.get(g);
        return until != null && Context.now() < until;
    }

    private boolean cooldown(PetGear g) {
        try {
            return context.pet.hasCooldown(g);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Yetenek modulunun etkisi suruyor mu (bekleme suresinde olsa da tutulur). */
    private boolean abilityActive(PetGear g, long now) {
        if (g == PetGear.COMBO_REPAIR) return now - context.comboRepairUsedAt < Context.COMBO_REPAIR_ACTIVE_MS;
        if (g == PetGear.HP_LINK) return now < context.hpLinkUntil || context.hpLinkActive(now);
        return false;
    }

    /** Istenemez: vazgecilmis ya da bekleme suresinde (etkisi surmuyorsa). */
    private boolean blocked(PetGear g) {
        return refused(g) || (cooldown(g) && !abilityActive(g, Context.now()));
    }

    /** Vazgecilen / beklemedeki modul yerine: savasta (ya da tamirde) mevcut modul, yoksa bulucu. */
    private PetGear fallbackGear(PetGear g) {
        long now = Context.now();
        PetGear cur = currentGear();
        boolean curStale = cur == null || blocked(cur)
                || ((cur == PetGear.COMBO_REPAIR || cur == PetGear.HP_LINK) && !abilityActive(cur, now));
        PlusModule m = plusModule();
        PlusModule.Job j = m == null || m.ended() ? null : m.job();
        boolean repairJob = j instanceof RepairLogic.Job || j instanceof RepairLogic.IdleJob
                || (j instanceof Targeting.Wait && ((Targeting.Wait) j).hpWait());
        if (!curStale && (repairJob || !context.outOfCombat(Context.PET_QUIET_MS))) return cur;
        if (repairJob && g != PetGear.GUARD && has(PetGear.GUARD) && !blocked(PetGear.GUARD)) return PetGear.GUARD;
        if (g != PetGear.ENEMY_LOCATOR && has(PetGear.ENEMY_LOCATOR) && !blocked(PetGear.ENEMY_LOCATOR)) return PetGear.ENEMY_LOCATOR;
        return cur;
    }

    private boolean petLowRepairing;
    private double petLowBest;
    private long petLowGainAt, petLowBlockUntil;
    /** PET kendi tamirini bu cana kadar yapar (eskiden %95: cok uzun suruyordu). */
    private static final double PET_LOW_END = 0.70;

    private double petHp() {
        try {
            eu.darkbot.api.managers.PetAPI.PetStat st = context.pet.getStat(eu.darkbot.api.managers.PetAPI.Stat.HP);
            return st == null || st.getTotal() <= 0 ? 1 : st.getCurrent() / st.getTotal();
        } catch (RuntimeException e) {
            return 1;
        }
    }

    /** PET'in cani "PET Cani Dusunce Tamir Et" sinirinin altinda mi. */
    private boolean petLow(BotPlusConfig.Pet pc) {
        return pc.LOW_REPAIR && has(PetGear.REPAIR) && petHp() < pc.LOW_HP / 100.0;
    }

    /**
     * PET Cani Dusunce Tamir Et (gemi tamiri disinda): sadece savas yokken (4 sn) ve bulucu kullanilmiyorken. Bulucu
     * aciksa PET tamiri gemi tamiri sirasinda yapilir (bulucu kapaninca sinyal kayboluyor, gemi 80 sn kor dolasiyordu).
     * %70'e kadar surer; 30 sn'de can artmazsa 60 sn denenmez.
     */
    private PetGear petLowRepair(BotPlusConfig.Pet pc, long now) {
        if (!pc.LOW_REPAIR || !has(PetGear.REPAIR) || now < petLowBlockUntil) {
            petLowRepairing = false;
            return null;
        }
        boolean fight = context.attack.hasTarget() || context.attackerOfHero() != null || combat.fighting()
                || !context.outOfCombat(Context.PET_QUIET_MS);
        // Bulucu sadece gercekten kullaniliyorsa (sinyale gidiliyor / gidilecek sinyal var) PET tamiri ertelenir;
        // sinyal yokken PET tamir edilir (eskiden bulucu acikken PET hic tamir edilmiyor, olunce bulucu da gidiyordu).
        PlusModule pm = plusModule();
        boolean locatorBusy = context.config.LOCATOR.GO && ((pm != null && pm.job() instanceof Locator.Go)
                || context.ping().filter(context.pingOk::test).isPresent());
        double hp = petHp();
        if (fight || locatorBusy || hp >= PET_LOW_END) {
            if (petLowRepairing) Log.i("PET", fight ? "dovus basladi, PET tamiri birakildi (PET cani " + Log.pct(hp) + ")"
                    : locatorBusy ? "bulucu kullaniliyor, PET tamiri gemi tamirine birakildi (PET cani " + Log.pct(hp) + ")"
                    : "PET tamiri bitti (PET cani " + Log.pct(hp) + ")");
            petLowRepairing = false;
            return null;
        }
        if (!petLowRepairing && hp < pc.LOW_HP / 100.0) {
            petLowRepairing = true;
            petLowBest = hp;
            petLowGainAt = now;
            Log.i("PET", "PET cani " + Log.pct(hp) + " < %" + pc.LOW_HP + ": dovus disinda PET kendini tamir ediyor (%"
                    + Math.round(PET_LOW_END * 100) + "'e kadar)");
        }
        if (petLowRepairing) {
            if (hp > petLowBest + 0.01) {
                petLowBest = hp;
                petLowGainAt = now;
            } else if (now - petLowGainAt > 30_000) {
                Log.i("PET", "PET tamiri 30 sn'de ilerlemedi (PET cani " + Log.pct(hp) + "), 60 sn denenmeyecek");
                petLowRepairing = false;
                petLowBlockUntil = now + 60_000;
                return null;
            }
        }
        return petLowRepairing ? PetGear.REPAIR : null;
    }

    /** Kombo Gemi Tamiri istenebilir mi: savas disinda (4 sn) ve yakin zamanda basarisiz olmadi. Etkisi suruyorsa hep. */
    private boolean comboOk(long now) {
        if (now - context.comboRepairUsedAt < Context.COMBO_REPAIR_ACTIVE_MS) return true;
        return !refused(PetGear.COMBO_REPAIR) && context.outOfCombat(Context.PET_QUIET_MS);
    }

    private PetGear wantGear0() {
        BotPlusConfig.Pet pc = context.config.PET;
        if (!context.config.ENABLED || !wasActive) return null;
        if (!pc.ENABLED) return null; // PET'i BotPlus yonetmiyor (ana anahtar: HP Link dahil hicbir sey degismez)
        PlusModule m = plusModule();
        // Cikis yapip yenilerken PET degismez (her eylem oyunun cikis geri sayimini iptal eder). HP Link'ten once.
        // null degil mevcut modul: null'da DarkBot'un varsayilan secicisi PET'e basar, geri sayim iptal olur.
        if (m != null && !m.ended() && m.job() instanceof Refresh.Job && ((Refresh.Job) m.job()).loggingOut()) return currentGear();
        long nowG = Context.now();
        // HP Link: istendiginden kullanilana kadar ve etkisi surdukce (Context.HP_LINK_ACTIVE_MS) PET HP Link'te kalir;
        // bu surede bulucu, dovus ya da tamir PET'i degistirmez (degisirse hasar yonlendirme kesilir).
        // Sadece saldiri altindayken: saldiri bitince PET bulucuya / isine doner.
        // Etkisi surerken bekleme suresi baslar: PET HP Link'te degilse artik istenmez (DarkBot beklemedeki module basmaz,
        // PET bosta kaliyordu); PET zaten HP Link'teyse etkisi bitene kadar tutulur.
        if ((nowG < context.hpLinkUntil || context.hpLinkActive(nowG)) && context.underAttack() && has(PetGear.HP_LINK)
                && (!cooldown(PetGear.HP_LINK) || currentGear() == PetGear.HP_LINK))
            return PetGear.HP_LINK;
        if (m != null && !m.ended()) {
            PlusModule.Job j = m.job();
            if (j instanceof RepairLogic.Job || j instanceof RepairLogic.IdleJob || (j instanceof Targeting.Wait && ((Targeting.Wait) j).hpWait())) {
                // Tamirde PET Gemi Tamiri: tamir boyunca PET Kombo Gemi Tamiri'nde kalir. Bekleme suresindeyken de
                // birakilmaz: DarkBot beklemedeki module basmaz, modul degisip gemi durmaz, sure bitince yine basilir.
                // Savasta (bize ates eden / hasar) oyun kombo tamiri baslatmaz: PET'e dokunulmaz.
                if (context.config.REPAIR.PET_SHIP && has(PetGear.COMBO_REPAIR) && !comboOk(nowG)) {
                    PetGear curG = currentGear();
                    return curG; // mevcut modulde kalinir (savasta modul degisimi yok)
                }
                if (context.config.REPAIR.PET_SHIP && has(PetGear.COMBO_REPAIR)) {
                    // Once gemi: Kombo Gemi Tamiri (hazirken ve etkisi surerken). Bekleme suresindeyken tamir suruyorsa
                    // "Tamirde PET'i Tamir Et" aciksa PET kendi tamirine gecer; tamir bitince bulucuya donulur.
                    boolean cd;
                    try {
                        cd = context.pet.hasCooldown(PetGear.COMBO_REPAIR);
                    } catch (RuntimeException e) {
                        cd = false;
                    }
                    boolean active = nowG - context.comboRepairUsedAt < Context.COMBO_REPAIR_ACTIVE_MS;
                    if (!cd || active) return PetGear.COMBO_REPAIR;
                    // Bekleme suresinde kombo istenmez (gecmez, basarisizlik sayilirdi): asagidaki PET tamiri / mevcut modul.
                }
                // Tamirde PET'i Tamir Et: PET'in kendi canini tamir eden modul. "PET Cani Dusunce Tamir Et" aciksa ve
                // PET'in cani dusukse de gemi tamiri sirasinda PET kendini tamir eder (bulucu bu sirada zaten kullanilmaz).
                if ((pc.REPAIR || petLow(pc)) && has(PetGear.REPAIR)) return PetGear.REPAIR;
                if (pc.REPAIR && has(PetGear.GUARD)) return PetGear.GUARD;
                // Tamirde PET dusman bulucuya (DarkBot varsayilani) gecmesin: mevcut modulde kalir.
                PetGear curG = currentGear();
                return curG == null || curG == PetGear.ENEMY_LOCATOR || curG == PetGear.HP_LINK
                        ? (has(PetGear.GUARD) ? PetGear.GUARD : null) : curG;
            }
            if (j instanceof Locator.Evade) return context.config.LOCATOR.EVADE_GUARD && has(PetGear.GUARD) ? PetGear.GUARD : null;
            if (j instanceof Steal.Job) {
                PetGear sg = context.config.STEAL.PET.gear;
                return sg != null && has(sg) ? sg : null;
            }
            if (m.escaping()) return null;
            // Diger isler (bekleme vb.) DarkBot'un PET ayarinda; bulucu ve tam kontrol asagida.
            if (!(j instanceof CombatJob) && !(j instanceof Locator.Go)) return null;
        }
        // PET Cani Dusunce Tamir Et: dovus disinda (hedef yok, kimse ates etmiyor) PET'in cani sinirin altindaysa PET kendi
        // tamirine gecer, %70'e kadar surer. Dovus baslarsa birakilir (dovus modulu oncelikli).
        PetGear low = petLowRepair(pc, nowG);
        if (low != null) return low;
        // Bosta tamir (dolasirken): Kombo Gemi Tamiri hazirsa (bekleme yok) ve gemi cani %90'in altindaysa basilir,
        // etkisi (5 sn) boyunca tutulur; sonra bulucu ("Yolda Dusman Bulucu" acik olsa da).
        if (m != null && !m.ended() && context.config.REPAIR.PET_SHIP && repair.idleRepairing() && !combat.fighting()
                && (m.job() instanceof CombatJob || m.job() instanceof Locator.Go) && has(PetGear.COMBO_REPAIR)) {
            boolean ready;
            try {
                ready = !context.pet.hasCooldown(PetGear.COMBO_REPAIR);
            } catch (RuntimeException e) {
                ready = false;
            }
            if (nowG - context.comboRepairUsedAt < Context.COMBO_REPAIR_ACTIVE_MS) return PetGear.COMBO_REPAIR;
            // Sadece yakinda NPC yokken ve ya uzak bir sinyale gidiliyorsa (3000+) ya da can dovus alt sinirina yakinsa:
            // aksi halde PET bulucudan bosuna cikar.
            if (ready && context.heroHp() < 0.9 && comboOk(nowG) && !combat.selectableNear(3000)) {
                boolean far = false;
                if (m.job() instanceof Locator.Go) {
                    Location p = context.ping().orElse(null);
                    far = p != null && context.hero.distanceTo(p) > 3000;
                }
                if (far || context.heroHp() < (context.config.COMBAT.FIGHT_MIN_HP + 10) / 100.0) return PetGear.COMBO_REPAIR;
            }
        }
        // Kesim sonrasi bulucu tazeleme (deneme): PET kisa sure bulucudan cikarilir, sonra normal secim bulucuya dondurur.
        PetGear refresh = locRefresh.gear();
        if (refresh != null && has(refresh)) return refresh;
        PetGear g = pc.COMBAT_CHANGE ? pc.COMBAT.gear : null; // Savasta PET'i Degistir kapaliysa savasta dokunulmaz
        Npc cur = context.targetNpc();
        long now = Context.now();
        PetGear curGear = currentGear();
        // Sinirda git-gel olmasin: zaten savas modulundeyse +500.
        int petDist = COMBAT_PET_DIST + (g != null && curGear == g ? 500 : 0);
        if (g != null && cur != null && context.hero.distanceTo(cur) <= petDist && has(g)) {
            combatGearUntil = now + 5000;
            return g;
        }
        // Dovus bitti: yakinda siradaki NPC varsa 5 sn savas modulunde kalinir (PET sik degisince gemi duruyor);
        // yoksa hemen bulucuya gecilir (yeni sinyal ~1 sn'de gelir, 5 sn bosta dolasilmaz).
        if (g != null && now < combatGearUntil && has(g) && combat.selectableNear(COMBAT_PET_DIST)) return g;
        // Yolda (bulucu / dolasma): PET dusman bulucuda; sinyal ararken zaten bu modul.
        boolean travel = m != null && !m.ended() && (m.job() instanceof Locator.Go
                || (m.job() instanceof CombatJob && !combat.fighting() && cur == null));
        if (travel && pc.TRAVEL_LOCATOR && context.config.LOCATOR.GO && has(PetGear.ENEMY_LOCATOR)) {
            // Once git, sonra degistir: PET modulu degisirken gemi ~1 sn duruyor; gemi 1 sn hareket ettikten
            // sonra degistirilir (hareket komutu once gider).
            PetGear curG = currentGear();
            // Gemi 3 sn'dir hic hareket etmiyorsa yine de degistirilir (sinyal olmadan yola cikilamayabilir).
            boolean waitMove = movingSince == 0 ? now - stillSince < 3000 : now - movingSince < 1000;
            if (curG != null && curG != PetGear.ENEMY_LOCATOR && waitMove) return curG;
            return PetGear.ENEMY_LOCATOR;
        }
        // Dovus / bulucu isinde (ornegin 1500'den uzak NPC'ye yaklasirken) bulucu ya da savas modulu tutulur:
        // null'da DarkBot'un varsayilan modulu basilir, PET gereksiz degisir.
        if (m != null && !m.ended() && (m.job() instanceof CombatJob || m.job() instanceof Locator.Go)
                && curGear != null && (curGear == PetGear.ENEMY_LOCATOR || curGear == g))
            return curGear;
        return null;
    }

    private long combatGearUntil;

    private long movingSince, stillSince;

    private PetGear currentGear() {
        try {
            return context.pet.isActive() ? context.pet.getGear() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean rotating() {
        return context.config.PET.ENABLED && context.config.LOCATOR.ROTATE && wasActive
                && Context.value(context.petModule, PetGear.PASSIVE) == PetGear.ENEMY_LOCATOR;
    }

    /** Bulucu turu sinyal vermiyorsa (20 sn) 90 sn atlanir, siradaki tur denenir. */
    private void tickRotation() {
        if (!rotating()) {
            noPingSince = 0;
            return;
        }
        long now = Context.now();
        skipPick.values().removeIf(t -> t < now);
        boolean ping = context.livePing() != null;
        PetGear g;
        try {
            g = context.pet.getGear();
        } catch (RuntimeException e) {
            g = null;
        }
        if (ping || g != PetGear.ENEMY_LOCATOR || lastPick == null || context.targetNpc() != null) {
            noPingSince = 0;
            return;
        }
        if (noPingSince == 0) noPingSince = now;
        if (now - noPingSince > 20_000) {
            skipPick.put(lastPick, now + 90_000);
            Log.i("BULUCU", lastPick + " 20 sn'dir sinyal vermiyor, 90 sn atlaniyor, siradaki tur deneniyor");
            Stats.count("bulucu tur degisti");
            noPingSince = 0;
        }
    }

    private long petMissingSince, petMissingLogAt;

    /** PET 10 sn'dir yoksa (bot calisirken, calisma haritasinda) 30 sn'de bir loglanir ve PET tekrar acilir. */
    private void tickPetPresence() {
        long now = Context.now();
        boolean active;
        try {
            active = !context.config.PET.ENABLED || !context.bot.isRunning() || !context.onWorkingMap()
                    || context.repair.isDestroyed() || context.pet.isActive();
        } catch (RuntimeException e) {
            active = true;
        }
        if (active) {
            petMissingSince = 0;
            return;
        }
        if (petMissingSince == 0) petMissingSince = now;
        long sec = (now - petMissingSince) / 1000;
        if (sec < 10 || now - petMissingLogAt < 30_000) return;
        petMissingLogAt = now;
        Log.i("PET", "PET yok " + sec + " sn, PET aciliyor");
        try {
            context.pet.setEnabled(true);
        } catch (RuntimeException ignored) {
        }
    }

    private boolean has(PetGear g) {
        try {
            return context.pet.hasGear(g);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    public PrioritizedSupplier<SelectableItem.Laser> getLaserSupplier() {
        return laserSupplier;
    }

    @Override
    public PrioritizedSupplier<ShipMode> getShipModeSupplier() {
        return modeSupplier;
    }
}