package com.botplus;

import com.botplus.combat.*;
import com.botplus.core.*;
import com.botplus.defense.*;
import com.botplus.locator.*;
import com.botplus.repair.*;
import com.botplus.rsb.*;
import com.botplus.steal.*;

import eu.darkbot.api.config.annotations.Configuration;

@Configuration("botplus.flag")
public enum BotPlusFlag {
    STEAL, WAIT_ABILITY, WAIT_HP, RSB
}