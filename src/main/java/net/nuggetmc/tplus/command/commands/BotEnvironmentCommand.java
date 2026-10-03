package net.nuggetmc.tplus.command.commands;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.nuggetmc.tplus.api.agent.legacyagent.CustomListMode;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyMats;
import net.nuggetmc.tplus.api.utils.ChatUtils;
import net.nuggetmc.tplus.api.utils.Location;
import net.nuggetmc.tplus.command.CommandHandler;
import net.nuggetmc.tplus.command.CommandInstance;
import net.nuggetmc.tplus.command.CommandUtils;
import net.nuggetmc.tplus.command.annotation.Arg;
import net.nuggetmc.tplus.command.annotation.Autofill;
import net.nuggetmc.tplus.command.annotation.Command;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public class BotEnvironmentCommand extends CommandInstance {

    public BotEnvironmentCommand(CommandHandler handler, String name, String description, String... aliases) {
        super(handler, name, description, aliases);
    }

    @Command
    public void root(CommandSourceStack sender, List<String> args) {
        commandHandler.sendRootInfo(this, sender);
    }

    @Command(
            name = "help",
            desc = "Help for /botenvironment.",
            autofill = "autofill"
    )
    public void help(CommandSourceStack sender, List<String> args) {
        if (!args.isEmpty() && args.get(0).equals("blocks")) {
            send(sender, ChatUtils.LINE);
            send(sender, "Blocks added by mods are only considered solid if they block movement like vanilla solid blocks do.");
            send(sender, "You can manually add other solid blocks with " + ChatFormatting.YELLOW + "/botenvironment addSolid <block>" + ChatFormatting.RESET + ".");
            send(sender, ChatUtils.LINE);
        } else if (!args.isEmpty() && args.get(0).equals("mobs")) {
            send(sender, ChatUtils.LINE);
            send(sender, "The custom mob list is a user-defined list of mobs.");
            send(sender, "Use " + ChatFormatting.YELLOW + "/botenvironment addCustomMob <name>" + ChatFormatting.RESET + " to add mob types to the list.");
            send(sender, "The list can be used in the CUSTOM_LIST targeting option, and can also be appended to the hostile, raider, or mob targeting options.");
            send(sender, "When appending, the mobs predefined as well as the mobs in the custom list will be considered.");
            send(sender, "Use " + ChatFormatting.YELLOW + "/botenvironment mobListType" + ChatFormatting.RESET + " to change the behavior of the custom mob list.");
            send(sender, ChatUtils.LINE);
        } else {
            send(sender, "Do " + ChatFormatting.YELLOW + "/botenvironment help blocks " + ChatFormatting.RESET + "for more information on adding solid blocks.");
            send(sender, "Do " + ChatFormatting.YELLOW + "/botenvironment help mobs " + ChatFormatting.RESET + "for more information on creating a custom mob list.");
        }
    }

    private static String blockName(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block).toString();
    }

    private static String entityName(EntityType<?> type) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
    }

    @Nullable
    private static Block parseBlock(String name) {
        ResourceLocation id = ResourceLocation.tryParse(name.toLowerCase(Locale.ROOT));
        if (id == null) return null;
        Optional<Block> block = BuiltInRegistries.BLOCK.getOptional(id);
        return block.orElse(null);
    }

    @Nullable
    private static EntityType<?> parseEntityType(String name) {
        ResourceLocation id = ResourceLocation.tryParse(name.toLowerCase(Locale.ROOT));
        if (id == null) return null;
        return BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    private static boolean isLocationLoaded(ServerLevel level, BlockPos pos) {
        return level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    @Command(
            name = "getMaterial",
            desc = "Prints out the current material at the specified location.",
            aliases = {"getmat", "getMat", "getmaterial"}
    )
    public void getMaterial(ServerPlayer player, @Arg("x") String x, @Arg("y") String y, @Arg("z") String z) {
        CommandSourceStack sender = player.createCommandSourceStack();
        Location loc = Location.of(player);
        BlockPos pos;
        try {
            pos = BlockPos.containing(
                    CommandUtils.parseDoubleOrRelative(x, loc, 0),
                    CommandUtils.parseDoubleOrRelative(y, loc, 1),
                    CommandUtils.parseDoubleOrRelative(z, loc, 2));
        } catch (NumberFormatException e) {
            send(sender, "A valid location must be provided!");
            return;
        }
        if (!isLocationLoaded(loc.level(), pos)) {
            send(sender, String.format("The location at " + ChatFormatting.BLUE + "(%d, %d, %d)" + ChatFormatting.RESET + " is not loaded.",
                    pos.getX(), pos.getY(), pos.getZ()));
            return;
        }
        Block mat = loc.level().getBlockState(pos).getBlock();
        send(sender, String.format("Material at " + ChatFormatting.BLUE + "(%d, %d, %d)" + ChatFormatting.RESET + ": " + ChatFormatting.GREEN + "%s" + ChatFormatting.RESET,
                pos.getX(), pos.getY(), pos.getZ(), blockName(mat)));
    }

    /**
     * Shared by addSolid/removeSolid: either {@code <block>} or {@code <x> <y> <z>} (block at that position).
     */
    @Nullable
    private Block resolveBlock(CommandSourceStack sender, List<String> args, String commandName) {
        Block mat;
        if (args.size() == 1)
            mat = parseBlock(args.get(0));
        else if (args.size() == 3) {
            ServerPlayer player = sender.getPlayer();
            if (player == null) {
                send(sender, "You must be a player to specify coordinates!");
                return null;
            }
            Location loc = Location.of(player);
            BlockPos pos;
            try {
                pos = BlockPos.containing(
                        CommandUtils.parseDoubleOrRelative(args.get(0), loc, 0),
                        CommandUtils.parseDoubleOrRelative(args.get(1), loc, 1),
                        CommandUtils.parseDoubleOrRelative(args.get(2), loc, 2));
            } catch (NumberFormatException e) {
                send(sender, "A valid location must be provided! " + ChatFormatting.YELLOW + "/botenvironment " + commandName + " <x> <y> <z>" + ChatFormatting.RESET);
                return null;
            }
            if (!isLocationLoaded(loc.level(), pos)) {
                send(sender, String.format("The location at " + ChatFormatting.BLUE + "(%d, %d, %d)" + ChatFormatting.RESET + " is not loaded.",
                        pos.getX(), pos.getY(), pos.getZ()));
                return null;
            }
            mat = loc.level().getBlockState(pos).getBlock();
        } else {
            send(sender, "Invalid syntax!");
            send(sender, "To specify a material: " + ChatFormatting.YELLOW + "/botenvironment " + commandName + " <block>" + ChatFormatting.RESET);
            send(sender, "To specify a location containing a material: " + ChatFormatting.YELLOW + "/botenvironment " + commandName + " <x> <y> <z>" + ChatFormatting.RESET);
            return null;
        }
        if (mat == null) {
            send(sender, "The material you specified does not exist!");
            return null;
        }
        return mat;
    }

    @Command(
            name = "addSolid",
            desc = "Adds a material to the list of solid materials.",
            aliases = {"addsolid"},
            autofill = "autofill"
    )
    public void addSolid(CommandSourceStack sender, List<String> args) {
        Block mat = resolveBlock(sender, args, "addSolid");
        if (mat == null) return;

        if (LegacyMats.SOLID_MATERIALS.add(mat))
            send(sender, "Successfully added " + ChatFormatting.BLUE + blockName(mat) + ChatFormatting.RESET + " to the list.");
        else
            send(sender, ChatFormatting.BLUE + blockName(mat) + ChatFormatting.RESET + " already exists in the list!");
    }

    @Command(
            name = "removeSolid",
            desc = "Removes a material from the list of solid materials.",
            aliases = {"removesolid"},
            autofill = "autofill"
    )
    public void removeSolid(CommandSourceStack sender, List<String> args) {
        Block mat = resolveBlock(sender, args, "removeSolid");
        if (mat == null) return;

        if (LegacyMats.SOLID_MATERIALS.remove(mat))
            send(sender, "Successfully removed " + ChatFormatting.BLUE + blockName(mat) + ChatFormatting.RESET + " from the list.");
        else
            send(sender, ChatFormatting.BLUE + blockName(mat) + ChatFormatting.RESET + " does not exist in the list!");
    }

    @Command(
            name = "listSolids",
            desc = "Displays the list of solid materials manually added.",
            aliases = {"listsolids"}
    )
    public void listSolids(CommandSourceStack sender) {
        send(sender, ChatUtils.LINE);
        for (Block mat : LegacyMats.SOLID_MATERIALS)
            send(sender, ChatFormatting.GREEN + blockName(mat) + ChatFormatting.RESET);
        send(sender, "Total items: " + ChatFormatting.BLUE + LegacyMats.SOLID_MATERIALS.size() + ChatFormatting.RESET);
        send(sender, ChatUtils.LINE);
    }

    @Command(
            name = "clearSolids",
            desc = "Clears the list of solid materials manually added.",
            aliases = {"clearsolids"}
    )
    public void clearSolids(CommandSourceStack sender) {
        int size = LegacyMats.SOLID_MATERIALS.size();
        LegacyMats.SOLID_MATERIALS.clear();
        send(sender, "Removed all " + ChatFormatting.BLUE + size + ChatFormatting.RESET + " item(s) from the list.");
    }

    @Command(
            name = "addCustomMob",
            desc = "Adds a mob to the custom list.",
            aliases = {"addcustommob"},
            autofill = "autofill"
    )
    public void addCustomMob(CommandSourceStack sender, @Arg("mobName") String mobName) {
        EntityType<?> type = parseEntityType(mobName);
        if (type == null) {
            send(sender, "The entity type you specified does not exist!");
            return;
        }
        if (LegacyAgent.CUSTOM_MOB_LIST.add(type))
            send(sender, "Successfully added " + ChatFormatting.BLUE + entityName(type) + ChatFormatting.RESET + " to the list.");
        else
            send(sender, ChatFormatting.BLUE + entityName(type) + ChatFormatting.RESET + " already exists in the list!");
    }

    @Command(
            name = "removeCustomMob",
            desc = "Removes a mob from the custom list.",
            aliases = {"removecustommob"},
            autofill = "autofill"
    )
    public void removeCustomMob(CommandSourceStack sender, @Arg("mobName") String mobName) {
        EntityType<?> type = parseEntityType(mobName);
        if (type == null) {
            send(sender, "The entity type you specified does not exist!");
            return;
        }
        if (LegacyAgent.CUSTOM_MOB_LIST.remove(type))
            send(sender, "Successfully removed " + ChatFormatting.BLUE + entityName(type) + ChatFormatting.RESET + " from the list.");
        else
            send(sender, ChatFormatting.BLUE + entityName(type) + ChatFormatting.RESET + " does not exist in the list!");
    }

    @Command(
            name = "listCustomMobs",
            desc = "Displays the custom list of mobs.",
            aliases = {"listcustommobs"}
    )
    public void listCustomMobs(CommandSourceStack sender) {
        send(sender, ChatUtils.LINE);
        for (EntityType<?> type : LegacyAgent.CUSTOM_MOB_LIST)
            send(sender, ChatFormatting.GREEN + entityName(type) + ChatFormatting.RESET);
        send(sender, "Total items: " + ChatFormatting.BLUE + LegacyAgent.CUSTOM_MOB_LIST.size() + ChatFormatting.RESET);
        send(sender, ChatUtils.LINE);
    }

    @Command(
            name = "clearCustomMobs",
            desc = "Clears the custom list of mobs.",
            aliases = {"clearcustommobs"}
    )
    public void clearCustomMobs(CommandSourceStack sender) {
        int size = LegacyAgent.CUSTOM_MOB_LIST.size();
        LegacyAgent.CUSTOM_MOB_LIST.clear();
        send(sender, "Removed all " + ChatFormatting.BLUE + size + ChatFormatting.RESET + " item(s) from the list.");
    }

    @Command(
            name = "mobListType",
            desc = "Changes the behavior of the custom mob list.",
            aliases = {"moblisttype"},
            autofill = "autofill"
    )
    public void mobListType(CommandSourceStack sender, List<String> args) {
        if (args.isEmpty()) {
            send(sender, "The custom mob list type is " + ChatFormatting.BLUE + LegacyAgent.customListMode + ChatFormatting.RESET + ".");
        } else if (CustomListMode.isValid(args.get(0))) {
            LegacyAgent.customListMode = CustomListMode.from(args.get(0));
            send(sender, "Successfully set the custom mob list type to " + ChatFormatting.BLUE + args.get(0) + ChatFormatting.RESET + ".");
        } else
            send(sender, "Usage: " + ChatFormatting.YELLOW + "/botenvironment mobListType (" + CustomListMode.listModes() + ")" + ChatFormatting.RESET);
    }

    @Autofill
    public List<String> autofill(CommandSourceStack sender, String[] args) {
        List<String> output = new ArrayList<>();
        if (args.length == 2) {
            if (args[0].equals("help")) {
                output.add("blocks");
                output.add("mobs");
            } else if (matches(args[0], "addSolid") || matches(args[0], "removeSolid")) {
                for (ResourceLocation id : BuiltInRegistries.BLOCK.keySet())
                    output.add(id.toString());
            } else if (matches(args[0], "addCustomMob") || matches(args[0], "removeCustomMob")) {
                for (ResourceLocation id : BuiltInRegistries.ENTITY_TYPE.keySet())
                    output.add(id.toString());
            } else if (matches(args[0], "mobListType")) {
                for (CustomListMode mode : CustomListMode.values())
                    output.add(mode.name().toLowerCase(Locale.ENGLISH));
            }
        }
        return output;
    }

    private boolean matches(String input, String check) {
        return input.equals(check) || input.equals(check.toLowerCase(Locale.ENGLISH));
    }
}
