package net.nuggetmc.tplus.command.commands;

import net.nuggetmc.tplus.api.agent.legacyagent.skill.Hardness;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.legacyagent.EnumTargetGoal;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.skill.SkillSettings;
import net.nuggetmc.tplus.api.utils.ChatUtils;
import net.nuggetmc.tplus.api.utils.Location;
import net.nuggetmc.tplus.api.utils.MathUtils;
import net.nuggetmc.tplus.bot.BotManagerImpl;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.bot.EquipmentPresets;
import net.nuggetmc.tplus.command.CommandHandler;
import net.nuggetmc.tplus.command.CommandInstance;
import net.nuggetmc.tplus.command.CommandUtils;
import net.nuggetmc.tplus.command.annotation.*;
import net.nuggetmc.tplus.utils.Debugger;

import java.text.DecimalFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.stream.Collectors;

public class BotCommand extends CommandInstance {

    private final CommandHandler handler;
    private final DecimalFormat formatter;
    private final Map<String, ItemStack[]> armorTiers;
    private AICommand aiManager;

    public BotCommand(CommandHandler handler, String name, String description, String... aliases) {
        super(handler, name, description, aliases);

        this.handler = commandHandler;
        this.formatter = new DecimalFormat("0.##");
        this.armorTiers = new LinkedHashMap<>();
    }

    private static LegacyAgent agent() {
        return (LegacyAgent) manager().getAgent();
    }

    @Command
    public void root(CommandSourceStack sender) {
        commandHandler.sendRootInfo(this, sender);
    }

    @Command(
            name = "create",
            desc = "Create a bot."
    )
    public void create(CommandSourceStack sender, @Arg("name") String name, @OptArg("skin") String skin, @TextArg @OptArg("loc") String loc) {
        Location location = CommandUtils.parseSpawnLocation(sender, loc);

        if (location != null) {
            manager().createBots(sender, name, skin, 1, location);
        }
    }

    @Command(
            name = "multi",
            desc = "Create multiple bots at once."
    )
    public void multi(CommandSourceStack sender, @Arg("amount") int amount, @Arg("name") String name, @OptArg("skin") String skin, @TextArg @OptArg("loc") String loc) {
        Location location = CommandUtils.parseSpawnLocation(sender, loc);

        if (location != null) {
            manager().createBots(sender, name, skin, amount, location);
        }
    }

    @Command(name = "preset", desc = "Save and reuse your inventory and equipment.", autofill = "presetAutofill")
    public void preset(CommandSourceStack sender, List<String> args) {
        EquipmentPresets presets = manager().presets();
        String action = args.isEmpty() ? "list" : args.getFirst().toLowerCase(Locale.ROOT);
        String name = args.size() > 1 ? args.get(1) : null;
        try {
            switch (action) {
                case "list" -> send(sender, "装备预设：" + String.join(", ", presets.names()) + "；默认：" + presets.selectedName());
                case "save" -> {
                    if (name == null || sender.getPlayer() == null) { send(sender, "玩家用法：/bot preset save <预设名>（保存当前背包与装备）"); return; }
                    presets.save(name, sender.getPlayer());
                    send(sender, "已保存装备预设 " + name + "（含附魔、耐久、数量和副手）。");
                }
                case "use" -> {
                    if (name == null) { send(sender, "用法：/bot preset use <预设名|none>"); return; }
                    presets.select(name.equalsIgnoreCase("none") ? null : name);
                    send(sender, "新生成的机器人默认使用装备预设：" + presets.selectedName());
                }
                case "delete" -> {
                    if (name == null) { send(sender, "用法：/bot preset delete <预设名>"); return; }
                    presets.delete(name);
                    send(sender, "已删除装备预设 " + name);
                }
                case "apply" -> {
                    EquipmentPresets.Preset preset = name == null ? null : presets.get(name);
                    if (preset == null) { send(sender, "找不到装备预设。用法：/bot preset apply <预设名> [机器人名|all]"); return; }
                    List<Terminator> bots = selectedBots(sender, args.size() > 2 ? args.get(2) : null);
                    for (Terminator bot : bots) {
                        agent().getSkills().forget(bot);
                        preset.apply((Bot) bot);
                    }
                    send(sender, "已为 " + bots.size() + " 个机器人替换背包与装备。");
                }
                default -> send(sender, "用法：/bot preset <save|list|use|apply|delete> [预设名] [机器人名|all]");
            }
        } catch (java.io.IOException | IllegalArgumentException e) { send(sender, ChatFormatting.RED + e.getMessage()); }
    }

    private static List<Terminator> selectedBots(CommandSourceStack sender, String name) {
        if (name == null || name.equalsIgnoreCase("all")) return new ArrayList<>(manager().fetch());
        Terminator bot = manager().getFirst(name, sender.getPlayer() == null ? null : Location.of(sender.getPlayer()));
        if (bot == null) throw new IllegalArgumentException("找不到机器人：" + name);
        return List.of(bot);
    }

    @Autofill
    public List<String> presetAutofill(CommandSourceStack sender, String[] args) {
        if (args.length == 2) return List.of("save", "list", "use", "apply", "delete");
        if (args.length == 3) {
            List<String> names = new ArrayList<>(manager().presets().names());
            if (args[1].equalsIgnoreCase("use")) names.add("none");
            return names;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("apply")) return manager().fetchNames();
        return List.of();
    }

    @Command(name = "createpreset", desc = "Spawn with a saved equipment preset and AI hardness.", autofill = "createPresetAutofill")
    public void createPreset(CommandSourceStack sender, @Arg("preset") String presetName, @Arg("name") String name,
                             @Arg("hardness") int hardness, @OptArg("team") String team, @OptArg("skin") String skin,
                             @TextArg @OptArg("loc") String loc) {
        EquipmentPresets.Preset preset = manager().presets().get(presetName);
        if (preset == null || hardness < 1 || hardness > 10) { send(sender, "预设必须存在，AI hardness 必须为 1–10。"); return; }
        Location location = CommandUtils.parseSpawnLocation(sender, loc);
        if (location == null) return;
        try { manager().createConfiguredBots(sender, name, skin, location, preset, hardness, team == null || team.equalsIgnoreCase("none") ? null : team); }
        catch (IllegalArgumentException e) { send(sender, ChatFormatting.RED + e.getMessage()); }
    }

    @Autofill
    public List<String> createPresetAutofill(CommandSourceStack sender, String[] args) {
        if (args.length == 2) return new ArrayList<>(manager().presets().names());
        if (args.length == 4) return List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        if (args.length == 5) {
            List<String> teams = new ArrayList<>(sender.getServer().getScoreboard().getTeamNames()); teams.add("none"); return teams;
        }
        return List.of();
    }

    @Command(name = "team", desc = "Assign bots to an existing vanilla scoreboard team.")
    public void team(CommandSourceStack sender, @Arg("team") String name, @OptArg("bot-name") String botName) {
        var scoreboard = sender.getServer().getScoreboard();
        var team = scoreboard.getPlayerTeam(name);
        if (!name.equalsIgnoreCase("none") && team == null) { send(sender, "找不到原版队伍：" + name + "；先 /team add " + name); return; }
        try {
            List<Terminator> bots = selectedBots(sender, botName);
            for (Terminator bot : bots) {
                if (team == null) scoreboard.removePlayerFromTeam(bot.getEntity().getScoreboardName());
                else scoreboard.addPlayerToTeam(bot.getEntity().getScoreboardName(), team);
            }
            send(sender, "已为 " + bots.size() + " 个机器人设置队伍：" + name);
        } catch (IllegalArgumentException e) { send(sender, ChatFormatting.RED + e.getMessage()); }
    }

    @Command(
            name = "give",
            desc = "Gives a specified item to all bots."
    )
    public void give(CommandSourceStack sender, @Arg("item-name") String itemName) {
        ResourceLocation id = ResourceLocation.tryParse(itemName.toLowerCase(Locale.ROOT));
        Optional<Item> type = id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);

        if (type.isEmpty()) {
            send(sender, "The item " + ChatFormatting.YELLOW + itemName + ChatFormatting.RESET + " is not valid!");
            return;
        }

        ItemStack item = new ItemStack(type.get());

        manager().fetch().forEach(bot -> bot.setDefaultItem(item.copy()));

        send(sender, "Successfully set the default item to " + ChatFormatting.YELLOW + BuiltInRegistries.ITEM.getKey(type.get()) + ChatFormatting.RESET + " for all current bots.");
    }

    private Map<String, ItemStack[]> armorTiers() {
        if (armorTiers.isEmpty()) {
            armorTierSetup();
        }
        return armorTiers;
    }

    private static ItemStack[] armor(Item boots, Item leggings, Item chestplate, Item helmet) {
        return new ItemStack[]{new ItemStack(boots), new ItemStack(leggings), new ItemStack(chestplate), new ItemStack(helmet)};
    }

    private void armorTierSetup() {
        armorTiers.put("none", armor(Items.AIR, Items.AIR, Items.AIR, Items.AIR));
        armorTiers.put("leather", armor(Items.LEATHER_BOOTS, Items.LEATHER_LEGGINGS, Items.LEATHER_CHESTPLATE, Items.LEATHER_HELMET));
        armorTiers.put("chain", armor(Items.CHAINMAIL_BOOTS, Items.CHAINMAIL_LEGGINGS, Items.CHAINMAIL_CHESTPLATE, Items.CHAINMAIL_HELMET));
        armorTiers.put("gold", armor(Items.GOLDEN_BOOTS, Items.GOLDEN_LEGGINGS, Items.GOLDEN_CHESTPLATE, Items.GOLDEN_HELMET));
        armorTiers.put("iron", armor(Items.IRON_BOOTS, Items.IRON_LEGGINGS, Items.IRON_CHESTPLATE, Items.IRON_HELMET));
        armorTiers.put("diamond", armor(Items.DIAMOND_BOOTS, Items.DIAMOND_LEGGINGS, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_HELMET));
        armorTiers.put("netherite", armor(Items.NETHERITE_BOOTS, Items.NETHERITE_LEGGINGS, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_HELMET));
    }

    @Command(
            name = "armor",
            desc = "Gives all bots an armor set.",
            autofill = "armorAutofill"
    )
    public void armor(CommandSourceStack sender, @Arg("armor-tier") String armorTier) {
        String tier = armorTier.toLowerCase();

        if (!armorTiers().containsKey(tier)) {
            send(sender, ChatFormatting.YELLOW + tier + ChatFormatting.RESET + " is not a valid tier!");
            send(sender, "Available tiers: " + ChatFormatting.YELLOW + String.join(ChatFormatting.RESET + ", " + ChatFormatting.YELLOW, armorTiers().keySet()));
            return;
        }

        ItemStack[] armor = armorTiers().get(tier);

        manager().fetch().forEach(bot -> {
            bot.setItem(armor[0], EquipmentSlot.FEET);
            bot.setItem(armor[1], EquipmentSlot.LEGS);
            bot.setItem(armor[2], EquipmentSlot.CHEST);
            bot.setItem(armor[3], EquipmentSlot.HEAD);
        });

        send(sender, "Successfully set the armor tier to " + ChatFormatting.YELLOW + tier + ChatFormatting.RESET + " for all current bots.");
    }

    @Autofill
    public List<String> armorAutofill(CommandSourceStack sender, String[] args) {
        return args.length == 2 ? new ArrayList<>(armorTiers().keySet()) : new ArrayList<>();
    }

    @Command(
            name = "info",
            desc = "Information about loaded bots.",
            autofill = "infoAutofill"
    )
    public void info(CommandSourceStack sender, @Arg("bot-name") String name) {
        if (name == null) {
            send(sender, ChatFormatting.YELLOW + "Bot GUI coming soon!");
            return;
        }

        send(sender, "Processing request...");

        try {
            Terminator bot = manager().getFirst(name, sender.getPlayer() != null ? Location.of(sender.getPlayer()) : null);

            if (bot == null) {
                send(sender, "Could not find bot " + ChatFormatting.GREEN + name + ChatFormatting.RESET + "!");
                return;
            }

            /*
             * time created
             * current life (how long it has lived for)
             * health
             * inventory
             * current target
             * current kills
             * skin
             * neural network values (network name if loaded, otherwise RANDOM)
             */

            String botName = bot.getBotName();
            String world = ChatFormatting.YELLOW + bot.getBotLevel().dimension().location().toString();
            Vec3 loc = bot.getLocation();
            String strLoc = ChatFormatting.YELLOW + formatter.format(loc.x) + ", " + formatter.format(loc.y) + ", " + formatter.format(loc.z);
            Vec3 vel = bot.getVelocity();
            String strVel = ChatFormatting.AQUA + formatter.format(vel.x) + ", " + formatter.format(vel.y) + ", " + formatter.format(vel.z);

            send(sender, ChatUtils.LINE);
            send(sender, ChatFormatting.GREEN + botName);
            send(sender, ChatUtils.BULLET_FORMATTED + "World: " + world);
            send(sender, ChatUtils.BULLET_FORMATTED + "Position: " + strLoc);
            send(sender, ChatUtils.BULLET_FORMATTED + "Velocity: " + strVel);
            send(sender, ChatUtils.BULLET_FORMATTED + "AI hardness: " + agent().getSkills().hardness(bot).level()
                    + " / 10; tactic: " + agent().getSkills().memory(bot).getTactic());
            send(sender, ChatUtils.LINE);
        } catch (Exception e) {
            send(sender, ChatUtils.EXCEPTION_MESSAGE);
        }
    }

    @Autofill
    public List<String> infoAutofill(CommandSourceStack sender, String[] args) {
        return args.length == 2 ? manager().fetchNames() : new ArrayList<>();
    }

    @Command(
            name = "count",
            desc = "Counts the amount of bots on screen by name.",
            aliases = {
                    "list"
            }
    )
    public void count(CommandSourceStack sender) {
        List<String> names = manager().fetchNames();
        Map<String, Integer> freqMap = names.stream().collect(Collectors.toMap(s -> s, s -> 1, Integer::sum));
        List<Entry<String, Integer>> entries = freqMap.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder())).toList();

        send(sender, ChatUtils.LINE);
        entries.forEach(en -> send(sender, ChatFormatting.GREEN + en.getKey()
                + ChatFormatting.RESET + " - " + ChatFormatting.BLUE + en.getValue().toString() + ChatFormatting.RESET));
        send(sender, "Total bots: " + ChatFormatting.BLUE + freqMap.values().stream().reduce(0, Integer::sum) + ChatFormatting.RESET);
        send(sender, ChatUtils.LINE);
    }

    @Command(
            name = "reset",
            desc = "Remove all loaded bots."
    )
    public void reset(CommandSourceStack sender) {
        send(sender, "Removing every bot...");
        BotManagerImpl manager = manager();
        int size = manager.fetch().size();
        manager.reset();
        send(sender, "Removed " + ChatFormatting.RED + ChatUtils.NUMBER_FORMAT.format(size) + ChatFormatting.RESET + " entit" + (size == 1 ? "y" : "ies") + ".");

        if (aiManager == null) {
            this.aiManager = (AICommand) handler.getCommand("ai");
        }

        if (aiManager != null && aiManager.hasActiveSession()) {
            aiManager.stop(sender);
        }
    }

    /*
     * EVENTUALLY, we should make a command parent hierarchy system soon too! (so we don't have to do this crap)
     * basically, in the @Command annotation, you can include a "parent" for the command, so it will be a subcommand under the specified parent
     */
    @Command(
            name = "settings",
            desc = "Make changes to the global configuration file and bot-specific settings.",
            aliases = "options",
            autofill = "settingsAutofill"
    )
    public void settings(CommandSourceStack sender, List<String> args) {
        BotManagerImpl manager = manager();
        LegacyAgent agent = agent();

        String arg1 = args.isEmpty() ? null : args.get(0);
        String arg2 = args.size() < 2 ? null : args.get(1);

        String extra = ChatFormatting.GRAY + " [" + ChatFormatting.YELLOW + "/bot settings" + ChatFormatting.GRAY + "]";

        if (arg1 == null || (!arg1.equalsIgnoreCase("setgoal") && !arg1.equalsIgnoreCase("mobtarget") && !arg1.equalsIgnoreCase("playertarget")
                && !arg1.equalsIgnoreCase("addplayerlist") && !arg1.equalsIgnoreCase("region")
                && !arg1.equalsIgnoreCase("range") && !arg1.equalsIgnoreCase("ability") && !arg1.equalsIgnoreCase("buildblock")
                && !arg1.equalsIgnoreCase("hardness"))) {
            send(sender, ChatUtils.LINE);
            send(sender, ChatFormatting.GOLD + "Bot Settings" + extra);
            send(sender, "/bot settings hardness <1–10> [机器人名]；7 为原有水平，无名字时设置全体与默认值。");
            send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "setgoal" + ChatUtils.BULLET_FORMATTED + "Set the global bot target selection method.");
            send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "mobtarget" + ChatUtils.BULLET_FORMATTED + "Allow all bots to be targeted by hostile mobs.");
            send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "playertarget" + ChatUtils.BULLET_FORMATTED + "Sets a player name for spawned bots to focus on if the goal is PLAYER.");
            send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "addplayerlist" + ChatUtils.BULLET_FORMATTED + "Adds newly spawned bots to the player list. This allows the bots to be affected by player selectors like @a and @p.");
            send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "region" + ChatUtils.BULLET_FORMATTED + "Sets a region for the bots to prioritize entities inside.");
            send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "range" + ChatUtils.BULLET_FORMATTED + "Sets how far away (in blocks) bots look for targets, or unlimited.");
            send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "ability" + ChatUtils.BULLET_FORMATTED + "Turns abilities (pathfinding, climbing, elytra, mace, pearls, windcharges, bow, totems, retaliate) on or off.");
            send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "buildblock" + ChatUtils.BULLET_FORMATTED + "Sets the block bots build pillars, bridges and clutches with.");
            send(sender, ChatUtils.LINE);
            return;
        } else if (arg1.equalsIgnoreCase("hardness")) {
            if (arg2 == null) { send(sender, "默认 AI hardness：" + agent.getSkillSettings().hardness + " / 10"); return; }
            try {
                int value = Integer.parseInt(arg2);
                new Hardness(value);
                String botName = args.size() > 2 ? args.get(2) : null;
                List<Terminator> bots = selectedBots(sender, botName);
                if (botName == null || botName.equalsIgnoreCase("all")) agent.getSkillSettings().hardness = value;
                for (Terminator bot : bots) {
                    ((Bot) bot).cancelRecoveryItem();
                    ((Bot) bot).setHardnessOverride(botName == null || botName.equalsIgnoreCase("all") ? 0 : value);
                    agent.getSkills().forget(bot);
                }
                send(sender, "已设置 AI hardness=" + value + "，影响 " + bots.size() + " 个机器人。");
            } catch (IllegalArgumentException e) { send(sender, ChatFormatting.RED + e.getMessage()); }
        } else if (arg1.equalsIgnoreCase("range")) {
            SkillSettings skills = agent.getSkillSettings();
            if (arg2 == null) {
                send(sender, "The target range is " + ChatFormatting.BLUE + describeRange(skills.targetRange) + ChatFormatting.RESET + ".");
                return;
            }
            double range;
            if (arg2.equalsIgnoreCase("unlimited") || arg2.equalsIgnoreCase("off")) {
                range = 0;
            } else {
                try {
                    range = Double.parseDouble(arg2);
                } catch (NumberFormatException e) {
                    range = -1;
                }
                if (range < 0) {
                    send(sender, ChatFormatting.RED + "The range must be a positive number of blocks or \"unlimited\"!");
                    return;
                }
            }
            skills.targetRange = range;
            send(sender, "The target range is now " + ChatFormatting.BLUE + describeRange(range) + ChatFormatting.RESET + ".");
        } else if (arg1.equalsIgnoreCase("buildblock")) {
            SkillSettings skills = agent.getSkillSettings();
            if (arg2 == null) {
                send(sender, "Bots build with " + ChatFormatting.YELLOW + BuiltInRegistries.BLOCK.getKey(skills.buildBlock) + ChatFormatting.RESET + ".");
                return;
            }
            ResourceLocation id = ResourceLocation.tryParse(arg2.toLowerCase(Locale.ROOT));
            Optional<Block> block = id == null ? Optional.empty() : BuiltInRegistries.BLOCK.getOptional(id);
            // a full block that exists as an item (it's shown in the bot's hand)
            if (block.isEmpty() || block.get().asItem() == Items.AIR
                    || !block.get().defaultBlockState().isCollisionShapeFullBlock(EmptyBlockGetter.INSTANCE, BlockPos.ZERO)) {
                send(sender, "The block " + ChatFormatting.YELLOW + arg2 + ChatFormatting.RESET + " is not a full block bots can build with!");
                return;
            }
            skills.buildBlock = block.get();
            send(sender, "Bots now build with " + ChatFormatting.YELLOW + BuiltInRegistries.BLOCK.getKey(block.get()) + ChatFormatting.RESET + ".");
        } else if (arg1.equalsIgnoreCase("ability")) {
            SkillSettings skills = agent.getSkillSettings();
            if (arg2 == null || !skills.has(arg2)) {
                send(sender, ChatUtils.LINE);
                send(sender, ChatFormatting.GOLD + "Bot Abilities" + extra);
                skills.all().forEach((name, enabled) -> send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + name
                        + ChatUtils.BULLET_FORMATTED + (enabled ? ChatFormatting.GREEN + "enabled" : ChatFormatting.RED + "disabled")));
                send(sender, "Usage: " + ChatFormatting.YELLOW + "/bot settings ability <name> <true|false>");
                send(sender, ChatUtils.LINE);
                return;
            }
            String arg3 = args.size() < 3 ? null : args.get(2);
            if (arg3 == null) {
                send(sender, "The ability " + ChatFormatting.YELLOW + arg2.toLowerCase(Locale.ROOT) + ChatFormatting.RESET + " is "
                        + (skills.get(arg2) ? ChatFormatting.GREEN + "enabled" : ChatFormatting.RED + "disabled") + ChatFormatting.RESET + ".");
                return;
            }
            if (!arg3.equals("true") && !arg3.equals("false")) {
                send(sender, ChatFormatting.RED + "You must specify true or false!");
                return;
            }
            skills.set(arg2, Boolean.parseBoolean(arg3));
            send(sender, "The ability " + ChatFormatting.YELLOW + arg2.toLowerCase(Locale.ROOT) + ChatFormatting.RESET + " is now "
                    + (skills.get(arg2) ? ChatFormatting.GREEN + "enabled" : ChatFormatting.RED + "disabled") + ChatFormatting.RESET + ".");
        } else if (arg1.equalsIgnoreCase("setgoal")) {
            if (arg2 == null) {
                send(sender, "The global bot goal is currently " + ChatFormatting.BLUE + agent.getTargetType() + ChatFormatting.RESET + ".");
                return;
            }
            EnumTargetGoal goal = EnumTargetGoal.from(arg2);

            if (goal == null) {
                send(sender, ChatUtils.LINE);
                send(sender, ChatFormatting.GOLD + "Goal Selection Types" + extra);
                Arrays.stream(EnumTargetGoal.values()).forEach(g -> send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + g.name().replace("_", "").toLowerCase()
                        + ChatUtils.BULLET_FORMATTED + g.description()));
                send(sender, ChatUtils.LINE);
                return;
            }
            agent.setTargetType(goal);
            send(sender, "The global bot goal has been set to " + ChatFormatting.BLUE + goal.name() + ChatFormatting.RESET + ".");
        } else if (arg1.equalsIgnoreCase("mobtarget")) {
            if (arg2 == null) {
                send(sender, "Mob targeting is currently " + (manager.isMobTarget() ? ChatFormatting.GREEN + "enabled" : ChatFormatting.RED + "disabled") + ChatFormatting.RESET + ".");
                return;
            }
            if (!arg2.equals("true") && !arg2.equals("false")) {
                send(sender, ChatFormatting.RED + "You must specify true or false!");
                return;
            }
            manager.setMobTarget(Boolean.parseBoolean(arg2));
            send(sender, "Mob targeting is now " + (manager.isMobTarget() ? ChatFormatting.GREEN + "enabled" : ChatFormatting.RED + "disabled") + ChatFormatting.RESET + ".");
        } else if (arg1.equalsIgnoreCase("playertarget")) {
            if (args.size() < 2) {
                send(sender, ChatFormatting.RED + "You must specify a player name!");
                return;
            }
            String playerName = arg2;
            ServerPlayer player = sender.getServer().getPlayerList().getPlayerByName(playerName);
            if (player == null || player instanceof Terminator) {
                send(sender, ChatFormatting.RED + "Could not find player " + ChatFormatting.YELLOW + playerName + ChatFormatting.RED + "!");
                return;
            }
            for (Terminator fetch : manager.fetch()) {
                fetch.setTargetPlayer(player.getUUID());
            }
            send(sender, "All spawned bots are now set to target " + ChatFormatting.BLUE + player.getName().getString() + ChatFormatting.RESET + ". They will target the closest player if they can't be found.\nYou may need to set the goal to PLAYER.");
        } else if (arg1.equalsIgnoreCase("addplayerlist")) {
            if (arg2 == null) {
                send(sender, "Adding bots to the player list is currently " + (manager.addToPlayerList() ? ChatFormatting.GREEN + "enabled" : ChatFormatting.RED + "disabled") + ChatFormatting.RESET + ".");
                return;
            }
            if (!arg2.equals("true") && !arg2.equals("false")) {
                send(sender, ChatFormatting.RED + "You must specify true or false!");
                return;
            }
            manager.setAddToPlayerList(Boolean.parseBoolean(arg2));
            send(sender, "Adding bots to the player list is now " + (manager.addToPlayerList() ? ChatFormatting.GREEN + "enabled" : ChatFormatting.RED + "disabled") + ChatFormatting.RESET + ".");
        } else if (arg1.equalsIgnoreCase("region")) {
            if (arg2 == null) {
                if (agent.getRegion() == null) {
                    send(sender, "No region has been set.");
                    return;
                }
                send(sender, "The current region is " + ChatFormatting.BLUE + agent.getRegion() + ChatFormatting.RESET + ".");
                if (agent.getRegionWeightX() == 0 && agent.getRegionWeightY() == 0 && agent.getRegionWeightZ() == 0)
                    send(sender, "Entities out of range will not be targeted.");
                else {
                    send(sender, "The region X weight is " + ChatFormatting.BLUE + agent.getRegionWeightX() + ChatFormatting.RESET + ".");
                    send(sender, "The region Y weight is " + ChatFormatting.BLUE + agent.getRegionWeightY() + ChatFormatting.RESET + ".");
                    send(sender, "The region Z weight is " + ChatFormatting.BLUE + agent.getRegionWeightZ() + ChatFormatting.RESET + ".");
                }
                return;
            }
            if (arg2.equalsIgnoreCase("clear")) {
                agent.setRegion(null, 0, 0, 0);
                send(sender, "The region has been cleared.");
                return;
            }
            boolean strict = args.size() == 8 && args.get(7).equalsIgnoreCase("strict");
            if (args.size() != 10 && !strict) {
                send(sender, ChatUtils.LINE);
                send(sender, ChatFormatting.GOLD + "Bot Region Settings" + extra);
                send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "<x1> <y1> <z1> <x2> <y2> <z2> <wX> <wY> <wZ>" + ChatUtils.BULLET_FORMATTED
                        + "Sets a region for bots to prioritize entities within.");
                send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "<x1> <y1> <z1> <x2> <y2> <z2> strict" + ChatUtils.BULLET_FORMATTED
                        + "Sets a region so that the bots only target entities within the region.");
                send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "clear" + ChatUtils.BULLET_FORMATTED
                        + "Clears the region.");
                send(sender, "Without strict mode, the entity distance from the region is multiplied by the weight values if outside the region.");
                send(sender, "The resulting value is added to the entity distance when selecting an entity.");
                send(sender, ChatUtils.LINE);
                return;
            }
            double x1, y1, z1, x2, y2, z2, wX, wY, wZ;
            try {
                Location loc = sender.getPlayer() != null ? Location.of(sender.getPlayer()) : null;
                x1 = CommandUtils.parseDoubleOrRelative(args.get(1), loc, 0);
                y1 = CommandUtils.parseDoubleOrRelative(args.get(2), loc, 1);
                z1 = CommandUtils.parseDoubleOrRelative(args.get(3), loc, 2);
                x2 = CommandUtils.parseDoubleOrRelative(args.get(4), loc, 0);
                y2 = CommandUtils.parseDoubleOrRelative(args.get(5), loc, 1);
                z2 = CommandUtils.parseDoubleOrRelative(args.get(6), loc, 2);
                if (strict)
                    wX = wY = wZ = 0;
                else {
                    wX = Double.parseDouble(args.get(7));
                    wY = Double.parseDouble(args.get(8));
                    wZ = Double.parseDouble(args.get(9));
                    if (wX <= 0 || wY <= 0 || wZ <= 0) {
                        send(sender, "The region weights must be positive values!");
                        return;
                    }
                }
            } catch (NumberFormatException e) {
                send(sender, "The region bounds and weights must be valid numbers!");
                send(sender, "Correct syntax: " + ChatFormatting.YELLOW + "/bot settings region <x1> <y1> <z1> <x2> <y2> <z2> <wX> <wY> <wZ>"
                        + ChatFormatting.RESET);
                return;
            }
            agent.setRegion(new AABB(x1, y1, z1, x2, y2, z2), wX, wY, wZ);
            send(sender, "The region has been set to " + ChatFormatting.BLUE + agent.getRegion() + ChatFormatting.RESET + ".");
            if (wX == 0 && wY == 0 && wZ == 0)
                send(sender, "Entities out of range will not be targeted.");
            else {
                send(sender, "The region X weight is " + ChatFormatting.BLUE + agent.getRegionWeightX() + ChatFormatting.RESET + ".");
                send(sender, "The region Y weight is " + ChatFormatting.BLUE + agent.getRegionWeightY() + ChatFormatting.RESET + ".");
                send(sender, "The region Z weight is " + ChatFormatting.BLUE + agent.getRegionWeightZ() + ChatFormatting.RESET + ".");
            }
        }
    }

    @Autofill
    public List<String> settingsAutofill(CommandSourceStack sender, String[] args) {
        List<String> output = new ArrayList<>();

        // More settings:
        // setitem
        // tpall
        // tprandom
        // hidenametags or nametags <show/hide>
        // sitall
        // lookall

        if (args.length == 2) {
            output.add("setgoal");
            output.add("mobtarget");
            output.add("playertarget");
            output.add("addplayerlist");
            output.add("region");
            output.add("range");
            output.add("ability");
            output.add("buildblock");
            output.add("hardness");
        } else if (args.length == 4 && args[1].equalsIgnoreCase("hardness")) {
            output.addAll(manager().fetchNames());
            output.add("all");
        } else if (args.length == 4 && args[1].equalsIgnoreCase("ability")) {
            output.add("true");
            output.add("false");
        } else if (args.length == 3) {
            if (args[1].equalsIgnoreCase("hardness")) output.addAll(List.of("1", "2", "3", "4", "5", "6", "7", "8", "9", "10"));
            if (args[1].equalsIgnoreCase("range")) {
                output.addAll(List.of("unlimited", "32", "64", "128", "256"));
            }
            if (args[1].equalsIgnoreCase("buildblock")) {
                output.addAll(List.of("minecraft:cobblestone", "minecraft:obsidian", "minecraft:crying_obsidian", "minecraft:netherrack", "minecraft:end_stone"));
            }
            if (args[1].equalsIgnoreCase("ability")) {
                output.addAll(agent().getSkillSettings().all().keySet());
            }
            if (args[1].equalsIgnoreCase("setgoal")) {
                Arrays.stream(EnumTargetGoal.values()).forEach(goal -> output.add(goal.name().replace("_", "").toLowerCase()));
            }
            if (args[1].equalsIgnoreCase("mobtarget")) {
                output.add("true");
                output.add("false");
            }
            if (args[1].equalsIgnoreCase("playertarget")) {
                for (ServerPlayer player : sender.getServer().getPlayerList().getPlayers()) {
                    if (!(player instanceof Terminator)) {
                        output.add(player.getGameProfile().getName());
                    }
                }
            }
            if (args[1].equalsIgnoreCase("addplayerlist")) {
                output.add("true");
                output.add("false");
            }
        }

        return output;
    }

    private static String describeRange(double range) {
        return range > 0 ? MathUtils.round1Dec(range) + " blocks" : "unlimited";
    }

    private static Map<String, List<ItemStack>> kits() {
        Map<String, List<ItemStack>> kits = new LinkedHashMap<>();
        kits.put("elytra", List.of(new ItemStack(Items.ELYTRA), new ItemStack(Items.FIREWORK_ROCKET, 64)));
        kits.put("mace", List.of(new ItemStack(Items.MACE), new ItemStack(Items.ELYTRA), new ItemStack(Items.FIREWORK_ROCKET, 64),
                new ItemStack(Items.WIND_CHARGE, 32)));
        kits.put("pearl", List.of(new ItemStack(Items.ENDER_PEARL, 16)));
        kits.put("windcharge", List.of(new ItemStack(Items.WIND_CHARGE, 32)));
        kits.put("bow", List.of(new ItemStack(Items.BOW), new ItemStack(Items.ARROW, 64)));
        kits.put("full", List.of(new ItemStack(Items.MACE), new ItemStack(Items.ELYTRA), new ItemStack(Items.FIREWORK_ROCKET, 64),
                new ItemStack(Items.ENDER_PEARL, 16), new ItemStack(Items.WIND_CHARGE, 32), new ItemStack(Items.BOW),
                new ItemStack(Items.ARROW, 64)));
        return kits;
    }

    @Command(
            name = "inventory",
            desc = "Gives items to every bot (elytra, rockets, mace, pearls...).",
            aliases = "inv",
            autofill = "inventoryAutofill"
    )
    public void inventory(CommandSourceStack sender, List<String> args) {
        String sub = args.isEmpty() ? "" : args.get(0).toLowerCase(Locale.ROOT);
        BotManagerImpl manager = manager();

        switch (sub) {
            case "give" -> {
                if (args.size() < 2) {
                    send(sender, "Usage: " + ChatFormatting.YELLOW + "/bot inventory give <item> [count]");
                    return;
                }
                ResourceLocation id = ResourceLocation.tryParse(args.get(1).toLowerCase(Locale.ROOT));
                Optional<Item> item = id == null ? Optional.empty() : BuiltInRegistries.ITEM.getOptional(id);
                if (item.isEmpty() || item.get() == Items.AIR) {
                    send(sender, "The item " + ChatFormatting.YELLOW + args.get(1) + ChatFormatting.RESET + " is not valid!");
                    return;
                }
                int count = 1;
                if (args.size() >= 3) {
                    try {
                        count = Math.max(1, Math.min(Integer.parseInt(args.get(2)), 64 * 35));
                    } catch (NumberFormatException e) {
                        send(sender, ChatFormatting.RED + "The count must be a number!");
                        return;
                    }
                }
                ItemStack stack = new ItemStack(item.get(), count);
                manager.fetch().forEach(bot -> bot.giveItem(stack));
                send(sender, "Gave " + ChatFormatting.BLUE + count + "x " + BuiltInRegistries.ITEM.getKey(item.get()) + ChatFormatting.RESET
                        + " to " + ChatFormatting.BLUE + manager.fetch().size() + ChatFormatting.RESET + " bot(s).");
            }
            case "kit" -> {
                Map<String, List<ItemStack>> kits = kits();
                String kit = args.size() < 2 ? "" : args.get(1).toLowerCase(Locale.ROOT);
                if (!kits.containsKey(kit)) {
                    send(sender, "Available kits: " + ChatFormatting.YELLOW + String.join(ChatFormatting.RESET + ", " + ChatFormatting.YELLOW, kits.keySet()));
                    return;
                }
                manager.fetch().forEach(bot -> kits.get(kit).forEach(bot::giveItem));
                send(sender, "Gave the " + ChatFormatting.YELLOW + kit + ChatFormatting.RESET + " kit to "
                        + ChatFormatting.BLUE + manager.fetch().size() + ChatFormatting.RESET + " bot(s).");
            }
            case "clear" -> {
                manager.fetch().forEach(Terminator::clearInventory);
                send(sender, "Cleared the inventory of " + ChatFormatting.BLUE + manager.fetch().size() + ChatFormatting.RESET + " bot(s).");
            }
            case "show" -> {
                if (args.size() < 2) {
                    send(sender, "Usage: " + ChatFormatting.YELLOW + "/bot inventory show <bot-name>");
                    return;
                }
                Terminator bot = manager.getFirst(args.get(1), sender.getPlayer() != null ? Location.of(sender.getPlayer()) : null);
                if (bot == null) {
                    send(sender, "Could not find bot " + ChatFormatting.GREEN + args.get(1) + ChatFormatting.RESET + "!");
                    return;
                }
                send(sender, ChatUtils.LINE);
                send(sender, ChatFormatting.GREEN + bot.getBotName() + ChatFormatting.GRAY + " (weapon: " + ChatFormatting.YELLOW
                        + BuiltInRegistries.ITEM.getKey(bot.getWeapon().getItem()) + ChatFormatting.GRAY + ")");
                ServerPlayer entity = bot.getEntity();
                for (EquipmentSlot slot : new EquipmentSlot[]{EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND}) {
                    ItemStack stack = entity.getItemBySlot(slot);
                    if (!stack.isEmpty()) {
                        send(sender, ChatUtils.BULLET_FORMATTED + slot.getName() + ": " + ChatFormatting.YELLOW + describe(stack));
                    }
                }
                List<ItemStack> items = entity.getInventory().items;
                for (int i = 1; i < items.size(); i++) {
                    if (!items.get(i).isEmpty()) {
                        send(sender, ChatUtils.BULLET_FORMATTED + "slot " + i + ": " + ChatFormatting.YELLOW + describe(items.get(i)));
                    }
                }
                send(sender, ChatUtils.LINE);
            }
            default -> {
                send(sender, ChatUtils.LINE);
                send(sender, ChatFormatting.GOLD + "Bot Inventory" + ChatFormatting.GRAY + " [" + ChatFormatting.YELLOW + "/bot inventory" + ChatFormatting.GRAY + "]");
                send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "give <item> [count]" + ChatUtils.BULLET_FORMATTED + "Gives an item to every bot.");
                send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "kit <" + String.join("|", kits().keySet()) + ">" + ChatUtils.BULLET_FORMATTED + "Gives a set of items to every bot.");
                send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "clear" + ChatUtils.BULLET_FORMATTED + "Empties every bot's inventory.");
                send(sender, ChatUtils.BULLET_FORMATTED + ChatFormatting.YELLOW + "show <bot-name>" + ChatUtils.BULLET_FORMATTED + "Lists the items of a bot.");
                send(sender, "Bots use elytras with firework rockets, maces, ender pearls, wind charges, bows and totems when they carry them.");
                send(sender, ChatUtils.LINE);
            }
        }
    }

    private static String describe(ItemStack stack) {
        String name = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        return stack.getCount() > 1 ? stack.getCount() + "x " + name : name;
    }

    @Autofill
    public List<String> inventoryAutofill(CommandSourceStack sender, String[] args) {
        List<String> output = new ArrayList<>();
        if (args.length == 2) {
            output.addAll(List.of("give", "kit", "clear", "show"));
        } else if (args.length == 3) {
            switch (args[1].toLowerCase(Locale.ROOT)) {
                case "give" -> BuiltInRegistries.ITEM.keySet().forEach(id -> output.add(id.toString()));
                case "kit" -> output.addAll(kits().keySet());
                case "show" -> output.addAll(manager().fetchNames());
                default -> {
                }
            }
        }
        return output;
    }

    @Command(
            name = "debug",
            desc = "Debug plugin code.",
            visible = false,
            autofill = "debugAutofill"
    )
    public void debug(CommandSourceStack sender, @Arg("expression") String expression) {
        new Debugger(sender).execute(expression);
    }

    @Autofill
    public List<String> debugAutofill(CommandSourceStack sender, String[] args) {
        return args.length == 2 ? new ArrayList<>(Debugger.AUTOFILL_METHODS) : new ArrayList<>();
    }
}
