package net.nuggetmc.tplus.utils;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.SnbtPrinterTagVisitor;
import net.minecraft.nbt.TagParser;
import net.neoforged.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Human-editable NBT with atomic replacement; item components retain their native codecs. */
public final class SnbtConfig {
    private SnbtConfig() {}

    public static Path path(String name) {
        return FMLPaths.CONFIGDIR.get().resolve("terminatorplus").resolve(name);
    }

    public static CompoundTag read(Path path) throws IOException {
        if (Files.size(path) > 16 * 1024 * 1024) throw new IOException("Config exceeds 16 MiB: " + path);
        try { return TagParser.parseTag(Files.readString(path, StandardCharsets.UTF_8)); }
        catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new IOException("Invalid SNBT: " + path, e); }
    }

    public static void write(Path path, CompoundTag tag) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.writeString(temp, new SnbtPrinterTagVisitor().visit(tag) + "\n", StandardCharsets.UTF_8);
        try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
    }
}
