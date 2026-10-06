package net.nuggetmc.tplus.utils;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.skill.SkillSettings;
import net.nuggetmc.tplus.api.utils.Location;
import net.nuggetmc.tplus.bot.Bot;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Isolated dedicated-server benchmark. Only registered with terminatorplus.benchmark=true. */
public final class PerformanceTest {
    private static final Logger LOG = LogUtils.getLogger();
    private record Stage(String scenario, int bots, int mode, int round) { }
    private final MinecraftServer server;
    private final ServerLevel level;
    private final List<Stage> stages = new ArrayList<>();
    private final List<Bot> actors = new ArrayList<>();
    private final List<Husk> targets = new ArrayList<>();
    private final List<BlockPos> walls = new ArrayList<>();
    private final List<Long> tickTimes = new ArrayList<>();
    private final long[] componentTotal = new long[7], componentCalls = new long[7];
    private final StringBuilder csv = new StringBuilder("scenario,bots,mode,round,samples,mean_ms,p50_ms,p95_ms,p99_ms,max_ms,ticks_over_50ms,ai_ms,bot_entity_ms,target_ms,arrow_ms,pearl_ms,path_ms,chunks_ms,arrow_calls,pearl_calls,path_calls,async_submitted,async_completed,async_reused,async_fallback,async_stale,async_rejected,worker_mean_us,queue_mean_us,result_mean_age_ticks\n");
    private final StringBuilder raw = new StringBuilder("stage,scenario,bots,mode,round,tick,total_ms,ai_ms,entity_ms,target_ms,arrow_ms,pearl_ms,path_ms,chunks_ms\n");
    private final CompletableFuture<String> verification;
    private int index = -1, elapsed;
    private long start;
    private boolean sampling, done;
    private Stage stage;
    private static final int WARMUP = 80, SAMPLES = 240;
    private static int cachedTick = -1;
    private static ServerLevel cachedWorld;
    private static List<LivingEntity> cachedEntities;

    private PerformanceTest(MinecraftServer server) {
        this.server = server; this.level = server.overworld();
        PerfProbe.owner = Thread.currentThread();
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false,server);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false,server);
        level.setDayTime(6000);
        for(int x=-5;x<=5;x++) for(int z=-5;z<=5;z++) level.getChunk(x,z);
        stages.add(new Stage("empty",0,0,1));
        for(String scenario : List.of("bow")) for(int n : new int[]{20,50,100,200})
            for(int mode : new int[]{0,1,3}) stages.add(new Stage(scenario,n,mode,1));
        for(int n : new int[]{100,200}) stages.add(new Stage("bow",n,2,1));
        for(int n : new int[]{50,200}) for(int mode : new int[]{0,1}) stages.add(new Stage("pearl",n,mode,1));
        for(int n : new int[]{20,100,200}) for(int mode : new int[]{0,1}) stages.add(new Stage("path",n,mode,1));
        for(int n : new int[]{200,100}) for(int mode : new int[]{3,1,0}) stages.add(new Stage("bow",n,mode,2));
        verification = BenchAim.verify();
        LOG.info("[AIBench] Prepared {} stages; math correctness is being checked off-thread", stages.size());
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener((ServerStartedEvent event) -> {
            PerformanceTest test = new PerformanceTest(event.getServer());
            NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,false,ServerTickEvent.Pre.class,test::pre);
            NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST,false,ServerTickEvent.Post.class,test::post);
        });
    }

    /** Main-thread shared view. This cache never reaches a worker. */
    public static List<LivingEntity> sharedEntities(ServerLevel world) {
        if (Thread.currentThread() != PerfProbe.owner) throw new IllegalStateException("Live world read on worker");
        int tick = world.getServer().getTickCount();
        if(cachedTick != tick || cachedWorld != world) {
            cachedTick=tick; cachedWorld=world; cachedEntities=new ArrayList<>();
            for(Entity entity:world.getAllEntities()) if(entity instanceof LivingEntity living) cachedEntities.add(living);
        }
        return cachedEntities;
    }

    private void pre(ServerTickEvent.Pre event) {
        if(done) return;
        start=System.nanoTime();
        PerfProbe.tick=server.getTickCount();
        PerfProbe.clearTick();
        sampling=false;
        if(!verification.isDone()) return;
        try {
            if(index<0) LOG.info("[AIBench] {}", verification.join());
            if(stage==null || elapsed>=WARMUP+SAMPLES) {
                if(stage!=null) finishStage();
                if(++index>=stages.size()) { finish(); return; }
                setup(stages.get(index));
                return;
            }
            elapsed++;
            sampling=elapsed>WARMUP;
            if(stage.scenario().equals("bow") && !targets.isEmpty()) {
                targets.getFirst().moveTo(Math.sin(elapsed*0.025)*3,ground(),Math.cos(elapsed*0.025)*3);
                // Hold firing positions so every mode measures sustained ranged work, not eventual melee convergence.
                for(int i=0;i<actors.size();i++) {
                    double angle=2*Math.PI*i/actors.size();
                    actors.get(i).setPos(Math.cos(angle)*36,ground(),Math.sin(angle)*36);
                    actors.get(i).setVelocity(Vec3.ZERO);
                }
            }
            if(stage.scenario().equals("path") && elapsed%60==0) {
                for(int i=0;i<actors.size();i++) {
                    Bot bot=actors.get(i);
                    bot.setPos((i%15-7)*20,ground(),(i/15-7)*20);
                    bot.setVelocity(Vec3.ZERO); bot.fallDistance=0;
                    ((LegacyAgent)TerminatorPlus.getManager().getAgent()).getSkills()
                            .benchmarkForcePathRecovery(bot,targets.get(i).position(),server.getTickCount());
                }
            }
            if(stage.scenario().equals("pearl") && elapsed%60==0) {
                for(int i=0;i<actors.size();i++) {
                    double angle=2*Math.PI*i/actors.size();
                    actors.get(i).teleportTo(Math.cos(angle)*36,ground(),Math.sin(angle)*36);
                    actors.get(i).fallDistance = 0;
                    actors.get(i).setVelocity(Vec3.ZERO);
                }
            }
            // Bound projectile accumulation identically for all modes.
            if(elapsed%20==0) removeProjectiles();
            if(BenchAim.failure!=null) throw new IllegalStateException("Worker failure",BenchAim.failure);
        } catch(Throwable t) {
            LOG.error("[AIBench] FAILED",t);
            done=true; BenchAim.reset(0); server.halt(false);
        }
    }

    private void post(ServerTickEvent.Post event) {
        if(done || !sampling) return;
        long duration=System.nanoTime()-start;
        tickTimes.add(duration);
        for(int c=0;c<7;c++) { componentTotal[c]+=PerfProbe.nanos[c]; componentCalls[c]+=PerfProbe.calls[c]; }
        raw.append(String.format(Locale.ROOT,"%d,%s,%d,%s,%d,%d,%.6f",index,stage.scenario(),stage.bots(),modeName(stage.mode()),stage.round(),elapsed,duration/1e6));
        for(long n:PerfProbe.nanos) raw.append(String.format(Locale.ROOT,",%.6f",n/1e6));
        raw.append('\n');
    }

    private int ground() { return level.getHeight(Heightmap.Types.MOTION_BLOCKING,0,0); }

    private void setup(Stage next) {
        PerfProbe.enabled=false;
        BenchAim.reset(next.mode()==2?2:next.mode()==3?4:0);
        TerminatorPlus.getManager().reset();
        targets.forEach(Entity::discard); targets.clear(); actors.clear(); removeProjectiles();
        for(BlockPos pos:walls) level.setBlockAndUpdate(pos,Blocks.AIR.defaultBlockState());
        walls.clear();
        cachedTick=-1;
        stage=next; elapsed=0; tickTimes.clear(); Arrays.fill(componentTotal,0); Arrays.fill(componentCalls,0);
        SkillSettings settings=((LegacyAgent)TerminatorPlus.getManager().getAgent()).getSkillSettings();
        settings.hardness=10; settings.targetRange=0;
        for(String ability:settings.all().keySet()) settings.set(ability,false);
        if(next.scenario().equals("bow")) settings.set("bow",true);
        if(next.scenario().equals("pearl")) settings.set("pearls",true);
        if(next.scenario().equals("path")) settings.set("pathfinding",true);
        settings.set("criticals",true);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack(),"bot settings setgoal nearesthostile");
        int y=ground();
        if(next.scenario().equals("path")) {
            for(int i=0;i<next.bots();i++) {
                int x=(i%15-7)*20,z=(i/15-7)*20;
                for(int dz=-3;dz<=3;dz++) for(int dy=0;dy<4;dy++) {
                    BlockPos pos=new BlockPos(x+3,y+dy,z+dz);
                    level.getChunkAt(pos);
                    level.setBlockAndUpdate(pos,Blocks.BEDROCK.defaultBlockState()); walls.add(pos);
                }
                target(x+7.5,y,z);
                actor(i,x,y,z,next.scenario());
            }
        } else if(next.scenario().equals("melee")) {
            for(int i=0;i<next.bots();i++) {
                double x=(i%15-7)*8, z=(i/15-7)*8;
                target(x+2.5,y,z);
                actor(i,x,y,z,next.scenario());
            }
        } else if(next.bots()>0) {
            target(0,y,0);
            for(int i=0;i<next.bots();i++) {
                double angle=2*Math.PI*i/next.bots();
                actor(i,Math.cos(angle)*36,y,Math.sin(angle)*36,next.scenario());
            }
        }
        PerfProbe.mode=next.mode(); PerfProbe.enabled=true;
        LOG.info("[AIBench] START {}/{} scenario={} bots={} mode={} round={} warmup={} sample={}",index+1,stages.size(),next.scenario(),next.bots(),modeName(next.mode()),next.round(),WARMUP,SAMPLES);
    }

    private void target(double x,int y,double z) {
        Husk target=new Husk(EntityType.HUSK,level);
        target.moveTo(x,y,z); target.setNoAi(true); target.setInvulnerable(true); target.setPersistenceRequired();
        level.addFreshEntity(target); targets.add(target);
    }

    private void actor(int i,double x,int y,double z,String scenario) {
        Bot bot=(Bot)TerminatorPlus.getManager().createBot(new Location(level,new Vec3(x,y,z)),"Bench"+i,"","");
        bot.setHardnessOverride(10); bot.setInvulnerable(true); actors.add(bot);
        if(scenario.equals("bow")) { bot.giveItem(new ItemStack(Items.BOW)); bot.giveItem(new ItemStack(Items.ARROW,64)); }
        else if(scenario.equals("pearl")) bot.giveItem(new ItemStack(Items.ENDER_PEARL,64));
        else bot.giveItem(new ItemStack(Items.NETHERITE_SWORD));
    }

    private void removeProjectiles() {
        List<Entity> remove=new ArrayList<>();
        for(Entity entity:level.getAllEntities()) if(entity instanceof Projectile) remove.add(entity);
        remove.forEach(Entity::discard);
    }

    private void finishStage() throws Exception {
        if(actors.size()!=stage.bots() || actors.stream().anyMatch(b->!b.isAlive())) throw new IllegalStateException("Actor population changed");
        if(stage.scenario().equals("bow") && componentCalls[PerfProbe.ARROW]<stage.bots()*30L) throw new IllegalStateException("Not a sustained bow workload");
        if(stage.scenario().equals("path") && componentCalls[PerfProbe.PATH]==0) throw new IllegalStateException("Path workload never searched");
        long[] sorted=tickTimes.stream().mapToLong(Long::longValue).sorted().toArray();
        double mean=Arrays.stream(sorted).average().orElse(0)/1e6;
        long slow=Arrays.stream(sorted).filter(n->n>50_000_000).count();
        csv.append(String.format(Locale.ROOT,"%s,%d,%s,%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%d",stage.scenario(),stage.bots(),modeName(stage.mode()),stage.round(),sorted.length,mean,percentile(sorted,.5),percentile(sorted,.95),percentile(sorted,.99),sorted[sorted.length-1]/1e6,slow));
        for(long n:componentTotal) csv.append(String.format(Locale.ROOT,",%.6f",n/(1e6*sorted.length)));
        csv.append(',').append(componentCalls[PerfProbe.ARROW]).append(',').append(componentCalls[PerfProbe.PEARL]).append(',').append(componentCalls[PerfProbe.PATH]);
        csv.append(',').append(BenchAim.submitted.sum()).append(',').append(BenchAim.completed.sum()).append(',').append(BenchAim.reused.sum()).append(',').append(BenchAim.fallback.sum()).append(',').append(BenchAim.stale.sum()).append(',').append(BenchAim.rejected.sum());
        csv.append(String.format(Locale.ROOT,",%.6f,%.6f,%.6f\n",BenchAim.workerNanos.sum()/Math.max(1.,BenchAim.completed.sum())/1e3,BenchAim.waitNanos.sum()/Math.max(1.,BenchAim.completed.sum())/1e3,BenchAim.resultAge.sum()/Math.max(1.,BenchAim.reused.sum())));
        write();
        LOG.info("[AIBench] RESULT {} n={} {} r{} mean={}ms p95={}ms max={}ms slow={}/{} AI={}ms arrow={}ms pearl={}ms reused={} fallback={} rejected={}",stage.scenario(),stage.bots(),modeName(stage.mode()),stage.round(),String.format(Locale.ROOT,"%.3f",mean),String.format(Locale.ROOT,"%.3f",percentile(sorted,.95)),String.format(Locale.ROOT,"%.3f",sorted[sorted.length-1]/1e6),slow,sorted.length,String.format(Locale.ROOT,"%.3f",componentTotal[0]/(1e6*sorted.length)),String.format(Locale.ROOT,"%.3f",componentTotal[3]/(1e6*sorted.length)),String.format(Locale.ROOT,"%.3f",componentTotal[4]/(1e6*sorted.length)),BenchAim.reused.sum(),BenchAim.fallback.sum(),BenchAim.rejected.sum());
    }

    private static double percentile(long[] sorted,double p) { return sorted[(int)Math.ceil(sorted.length*p)-1]/1e6; }
    private static String modeName(int mode) { return switch(mode) { case 1->"cache"; case 2->"async2"; case 3->"async4"; default->"original"; }; }
    private void write() throws Exception {
        Path output=Path.of("benchmark-results"); Files.createDirectories(output);
        Files.writeString(output.resolve("summary.csv"),csv);
        Files.writeString(output.resolve("ticks.csv"),raw);
    }
    private void finish() throws Exception {
        done=true; PerfProbe.enabled=false; BenchAim.reset(0); TerminatorPlus.getManager().reset(); targets.forEach(Entity::discard);
        write(); LOG.info("[AIBench] ALL {} STAGES COMPLETE; verification={}",stages.size(),verification.join());
        server.halt(false);
    }
}
