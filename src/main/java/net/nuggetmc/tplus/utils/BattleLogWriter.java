package net.nuggetmc.tplus.utils;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** The writer accepts immutable JSON strings only. No Minecraft object crosses this boundary. */
public final class BattleLogWriter implements AutoCloseable {
    private final ArrayBlockingQueue<String> queue;
    private final Thread worker;
    private final Path path;
    private final AtomicLong dropped = new AtomicLong();
    private volatile long written;
    private volatile boolean closing;
    private volatile String error;

    public BattleLogWriter(Path path) { this(path, 16384); }
    public BattleLogWriter(Path path, int capacity) {
        this.path = path;
        queue = new ArrayBlockingQueue<>(capacity);
        worker = new Thread(this::write, "terminatorplus-battle-log");
        worker.setDaemon(true);
        worker.start();
    }
    public boolean offer(String json) {
        if (closing || error != null) return false;
        if (queue.offer(json)) return true;
        dropped.incrementAndGet();
        return false;
    }
    private void write() {
        try {
            Files.createDirectories(path.getParent());
            try (BufferedWriter out = Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW)) {
                long reported = 0, lastFlush = System.nanoTime();
                while (!closing || !queue.isEmpty()) {
                    String line = queue.poll(100, TimeUnit.MILLISECONDS);
                    if (line != null) { out.write(line); out.newLine(); written++; }
                    long loss = dropped.get();
                    if (loss != reported) {
                        out.write("{\"type\":\"log_gap\",\"dropped\":" + (loss - reported) + ",\"totalDropped\":" + loss + "}");
                        out.newLine(); reported = loss;
                    }
                    if (System.nanoTime() - lastFlush >= TimeUnit.SECONDS.toNanos(1)) { out.flush(); lastFlush = System.nanoTime(); }
                }
            }
        } catch (IOException | InterruptedException failure) {
            error = failure.toString(); queue.clear();
            com.mojang.logging.LogUtils.getLogger().error("Battle log disabled after file I/O failure: {}", path, failure);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
    public Path path() { return path; }
    public long written() { return written; }
    public long dropped() { return dropped.get(); }
    public int queued() { return queue.size(); }
    public String error() { return error; }
    public boolean closed() { return !worker.isAlive(); }
    @Override public void close() { closing = true; }
    /** Shutdown/test use only; never call from an AI tick. */
    public boolean awaitClosed(long millis) throws InterruptedException { worker.join(millis); return closed(); }
}
