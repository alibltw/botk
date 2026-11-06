package com.botplus.core;

import com.botplus.*;
import com.botplus.combat.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.shared.modules.TemporalModule;

/**
 * Sadece BotPlus ana modul olarak secili DEGILKEN kullanilir (DarkBot'un Loot / Collector modulu secili):
 * BotPlus isi (dovus, tamir, bulucu...) DarkBot'un modulunu gecici olarak devralir, is bitince geri verir.
 * BotPlus ana modul olarak seciliyse isler BotPlus'in icinde dogrudan tiklenir, bu sinif kullanilmaz.
 */
public final class PlusTemporal extends TemporalModule {

    private final Context context;
    private final PlusModule run;

    public PlusTemporal(Context context, PlusModule run) {
        super(context.bot);
        this.context = context;
        this.run = run;
    }

    public PlusModule run() {
        return run;
    }

    @Override
    public void onTickModule() {
        try {
            context.beginTick();
            run.tick();
        } catch (Throwable t) {
            // DarkBot'un hata yakalayicisi gecici modulde patlar: hata burada kalir.
            try {
                run.finish("hata: " + t.getClass().getSimpleName());
            } catch (Throwable ignored) {
            }
        }
        if (run.ended()) back();
    }

    /** DarkBot'un moduluna geri donulur (zaten kontrol bizde degilse bir sey yapmaz). */
    public void back() {
        try {
            goBack();
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public String getStatus() {
        return run.getStatus();
    }

    @Override
    public boolean canRefresh() {
        return run.canRefresh();
    }
}