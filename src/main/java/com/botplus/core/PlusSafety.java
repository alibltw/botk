package com.botplus.core;

import eu.darkbot.api.utils.Inject;
import eu.darkbot.api.game.entities.Ship;
import eu.darkbot.api.managers.AttackAPI;
import eu.darkbot.api.managers.ConfigAPI;
import eu.darkbot.api.managers.EntitiesAPI;
import eu.darkbot.api.managers.HeroAPI;
import eu.darkbot.api.managers.HeroItemsAPI;
import eu.darkbot.api.managers.MovementAPI;
import eu.darkbot.api.managers.StarSystemAPI;
import eu.darkbot.shared.utils.MapTraveler;
import eu.darkbot.shared.utils.PortalJumper;
import eu.darkbot.shared.utils.SafetyFinder;

import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * BotPlus kacis sistemi: DarkBot'un SafetyFinder'i (portal secimi, portala gitme, atlama, portalda bekleme) aynen
 * kullanilir; sadece DUSMANDAN kacma karari DarkBot'un "Dusmandan Kac" ayarlarindan degil BotPlus'in
 * "Dusman Saldirinca" ayarindan gelir. Tamir icin portala gitme (REPAIR) ve yenileme (REFRESH) DarkBot'taki gibidir.
 * Tek ornek: BotPlus isleri ve BotPlus'in DarkBot yedekleri (Loot / Collector) ayni nesneyi kullanir.
 */
public class PlusSafety extends SafetyFinder {

    /** BotPlus portala kacmak istiyor mu (Context kurar). */
    public volatile BooleanSupplier escapeWanted = () -> false;
    /** Kacilan gemi mi (portal secimi: dusmanin bizden once varacagi portal secilmez). */
    public volatile Predicate<Ship> threat = s -> false;
    /**
     * BotPlus su an gemiyi yonetiyor mu (acik, calisma haritasinda, captcha yok). Yonetmiyorsa (BotPlus kapali,
     * harita disi yolculuk, captcha) DarkBot'un kendi "Dusmandan Kac" ayarlari gecerli: yedek modul kacabilsin.
     */
    public volatile BooleanSupplier inCharge = () -> true;

    @Inject
    public PlusSafety(HeroAPI hero, AttackAPI attacker, HeroItemsAPI items, MovementAPI movement, StarSystemAPI starSystem,
                      ConfigAPI config, EntitiesAPI entities, MapTraveler mapTraveler, PortalJumper portalJumper) {
        super(hero, attacker, items, movement, starSystem, config, entities, mapTraveler, portalJumper);
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

    @Override
    protected boolean runFrom(Ship ship) {
        if (!charge()) return super.runFrom(ship);
        try {
            return threat.test(ship);
        } catch (RuntimeException e) {
            return false;
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