package net.nuggetmc.tplus.api.agent.botagent;

import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.Terminator;

public class BotSituation {

    private final VerticalDisplacement disp;

    /*
     * aboveGround
     */

    public BotSituation(Terminator bot, Vec3 target) {
        Vec3 loc = bot.getLocation();

        this.disp = VerticalDisplacement.fetch(Mth.floor(loc.y), Mth.floor(target.y));
    }

    public VerticalDisplacement getVerticalDisplacement() {
        return disp;
    }
}
