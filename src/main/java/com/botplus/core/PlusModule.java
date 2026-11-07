package com.botplus.core;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

/**
 * BotPlus isi yurutucusu. Eskiden DarkBot'un gecici modulu (TemporalModule) idi ve her iste DarkBot'un modulu
 * degistiriliyordu; artik BotPlus kendisi ana modul oldugu icin is, BotPlus modulunun icinde dogrudan tiklenir
 * (modul degisimi yok, "ana module donuluyor" gecisi yok, DarkBot ile hareket cekismesi yok).
 * Isin her tikinden once (is izin veriyorsa) DarkBot'un kacis sistemi (SafetyFinder) calistirilir:
 * dusman gorulur ya da vurulursa portala kacilir, is o sirada bekler ve sonra kaldigi yerden surer.
 */
public final class PlusModule {

    /** Is oncelikleri: buyuk olan kucugu keser. */
    public static final int P_ESCAPE = 95, P_REFRESH = 90, P_REPAIR = 80, P_KILLER = 70, P_STEAL = 50, P_IGNORE = 45, P_CHASE = 40, P_COMBAT = 38,
            P_BOX = 35, P_WAIT = 30, P_ORDER = 25, P_LOCATOR = 20, P_IDLE_REPAIR = 15, P_UNSTUCK = 10, P_IDLE = 5;

    /** Modulun yaptigi is. */
    public interface Job {
        String name();

        /** Her tik cagrilir. false donerse is biter. */
        boolean tick();

        default String status() {
            return name();
        }

        default void end(String why) {
        }

        /** DarkBot yenilemek istediginde izin veriliyor mu. */
        default boolean canRefresh() {
            return false;
        }

        /** Oncelik: daha yuksek oncelikli is bu isi keser. */
        default int priority() {
            return 0;
        }

        /** Is sirasinda DarkBot'un dusmandan kacisi (SafetyFinder) calissin mi. */
        default boolean safety() {
            return true;
        }

        /** Her tikte kacis sisteminden once (kacis portala giderken de) calisir. */
        default void beforeSafety() {
        }
    }

    private final Context context;
    private final Job job;
    /** Isin gercekten tiklendigi sure (2 sn'den uzun bosluklar - bot durdu / baska modul araya girdi - sayilmaz). */
    private long activeMs;
    private long lastTickAt = System.currentTimeMillis();
    private boolean ended;
    private boolean escaping;
    private final int startMap;
    private final int startDeaths;

    public PlusModule(Context context, Job job) {
        this(context, job, -1);
    }

    /** startMap: kesilen isin haritasi (portal kacisi sirasinda kesilince yeni is diger haritada baslamasin). */
    public PlusModule(Context context, Job job, int startMap) {
        this.context = context;
        this.job = job;
        this.startMap = startMap != -1 ? startMap : mapId();
        this.startDeaths = deaths();
        context.clearMove();
    }

    public int startMap() {
        return startMap;
    }

    private int deaths() {
        try {
            return context.repair.getDeathAmount();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    private int mapId() {
        try {
            return context.star.getCurrentMap() == null ? -1 : context.star.getCurrentMap().getId();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    public Job job() {
        return job;
    }

    /** Isin yasi: sadece tiklendigi sure (bot dururken / araya baska modul girdiginde gecen sure sayilmaz). */
    public long age() {
        long gap = System.currentTimeMillis() - lastTickAt;
        return activeMs + (gap > PAUSE_MS ? 0 : Math.max(0, gap));
    }

    private static final long PAUSE_MS = 2000;

    /** DarkBot'un kacisi su an isi bekletiyor mu. */
    public boolean escaping() {
        return escaping;
    }

    /** BotPlus modulu her tik cagirir. Hata asla yukari atilmaz: is birakilir. */
    public void tick() {
        if (ended) return;
        try {
            tick0();
        } catch (Throwable t) {
            try {
                Log.every("hatamodul", 10_000, "HATA", job.name() + ": " + t);
            } catch (Throwable ignored) {
            }
            try {
                finish("hata: " + t.getClass().getSimpleName());
            } catch (Throwable ignored) {
                ended = true;
            }
        }
    }

    private void tick0() {
        long now = System.currentTimeMillis();
        long gap = now - lastTickAt;
        lastTickAt = now;
        // Bot kisa sure durdurulup baslatildiysa da (bosluk 2 sn'den kisa olabilir) eski hedef sayilmaz.
        if (gap > PAUSE_MS || now - context.lastStoppedAt < 3000) {
            // Bot durdu / baska modul araya girdi: eski hareket hedefi "hareket degistirildi" sanilmasin.
            context.clearMoveAfterPause();
        }
        if (gap > 0 && gap <= PAUSE_MS) activeMs += gap;
        // Gemi patladiysa ya da harita degistiyse (kacisin portal atlamasi haric) is anlamsizdir.
        int d = deaths();
        if (context.repair.isDestroyed() || (startDeaths >= 0 && d > startDeaths)) {
            finish("gemi patladi");
            return;
        }
        if (!escaping && !context.safetyEscaping() && startMap != -1 && mapId() != startMap) {
            finish("harita degisti (" + (context.star.getCurrentMap() == null ? "?" : context.star.getCurrentMap().getName()) + ")");
            return;
        }
        try {
            job.beforeSafety();
        } catch (RuntimeException e) {
            Log.every("beforesafety" + job.name(), 60_000, job.name(), "hata: " + e);
        }
        if (job.safety() && !context.safetyTick()) {
            if (!escaping) {
                escaping = true;
                context.clearMove();
                Log.i("GUVENLIK", "kacis devrede (" + context.safetyStatus() + "), " + job.name()
                        + " bekliyor | " + context.dangerInfo(context.hero));
                Stats.count("kacis");
            }
            Log.dEvery("safetystat", 3000, "GUVENLIK", () -> context.safetyStatus() + " | gemi " + Log.pct(context.heroHp())
                    + " | " + context.dangerInfo(context.hero));
            return;
        }
        if (escaping) {
            escaping = false;
            context.clearMove();
            Log.i("GUVENLIK", "kacis bitti, " + job.name() + " devam ediyor | " + context.dangerInfo(context.hero));
        }
        // DarkBot'un kacisi / harita gecisi PET'i kapatir; calisma haritasinda tekrar acilir (LootModule gibi).
        // Cikis geri sayiminda dokunulmaz (PET'e basmak cikisi iptal eder).
        if (context.config.PET.ENABLED && context.onWorkingMap()
                && !(job instanceof Refresh.Job && ((Refresh.Job) job).loggingOut())) {
            try {
                context.pet.setEnabled(true);
            } catch (RuntimeException ignored) {
            }
        }
        String over = context.moveOverridden();
        if (over != null) Log.every("moveover" + job.name(), 5000, job.name(), "hareket degistirildi: " + over
                + " (baska eklenti ya da DarkBot)");
        boolean go;
        try {
            go = job.tick();
        } catch (RuntimeException e) {
            Log.i(job.name(), "HATA, is birakildi: " + e);
            go = false;
        }
        if (!go) finish("bitti");
    }

    /** Is biter; BotPlus bir sonraki isi ya da DarkBot yedegini calistirir. */
    public void finish(String why) {
        if (ended) return;
        ended = true;
        context.lastEndAt = System.currentTimeMillis();
        try {
            job.end(why);
        } catch (RuntimeException e) {
            Log.i(job.name(), "bitiste hata: " + e);
        }
        context.clearMove();
        Log.i(job.name(), "BITTI (" + why + ") " + (age() / 100) / 10.0 + " sn");
    }

    public boolean ended() {
        return ended;
    }

    public String getStatus() {
        // Ikinci satir: verim (DarkBot sol ustte modul durumunu satir satir yazar).
        String stats = context.config.PLUS.STATS() ? "\n" + Stats.screen() : "";
        return "BotPlus: " + (escaping ? "kaçış (" + context.safetyStatus() + "), " : "") + job.status() + stats;
    }

    public boolean canRefresh() {
        // Kacis surerken de is karar verir (yenileme icin portalda bekleme = DarkBot'un LootModule davranisi).
        // Portala kacis surerken (bekleme haric) yenileme / mola yapilmaz.
        if (ended || (escaping && !context.safetyWaiting())) return false;
        return job.canRefresh();
    }
}