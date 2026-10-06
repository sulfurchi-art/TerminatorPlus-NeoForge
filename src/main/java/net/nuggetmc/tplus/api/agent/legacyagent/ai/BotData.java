package net.nuggetmc.tplus.api.agent.legacyagent.ai;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.utils.MathUtils;

import java.util.*;

// If this is laggy, try only instantiating this once and update it instead of creating a new instance every tick
public class BotData {

    private final Map<BotDataType, Double> values;

    private BotData(Terminator bot, LivingEntity target) {
        this.values = new HashMap<>();

        Vec3 a = bot.getLocation();
        Vec3 b = target.position();

        float health = bot.getBotHealth();

        values.put(BotDataType.CRITICAL_HEALTH, health >= 5 ? 0 : 5D - health);
        values.put(BotDataType.DISTANCE_XZ, Math.sqrt(MathUtils.square(a.x - b.x) + MathUtils.square(a.z - b.z)));
        values.put(BotDataType.DISTANCE_Y, b.y - a.y);
        values.put(BotDataType.ENEMY_BLOCKING, target instanceof Player player && player.isBlocking() ? 1D : 0);
    }

    public static BotData generate(Terminator bot, LivingEntity target) {
        return new BotData(bot, target);
    }

    public Map<BotDataType, Double> getValues() {
        return values;
    }

    public double getValue(BotDataType dataType) {
        return values.get(dataType);
    }

    @Override
    public String toString() {
        List<String> strings = new ArrayList<>();

        values.forEach((type, value) -> strings.add(type.name() + "=" + MathUtils.round2Dec(value)));

        Collections.sort(strings);

        return "BotData{" + NeuralNetwork.join(strings) + "}";
    }
}
