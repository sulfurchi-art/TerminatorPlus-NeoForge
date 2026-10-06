from pathlib import Path
import re

root = Path('work/ai-thread-benchmark')
java = root / 'src/main/java/net/nuggetmc/tplus'

def mask(source):
    pattern = r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|\x27(?:\\.|[^\x27\\])*\x27'
    return re.sub(pattern, lambda m: ''.join('\n' if c == '\n' else ' ' for c in m.group()), source)

def wrap(file, signature, category, prefix=''):
    path = java / file
    text = path.read_text(encoding='utf-8')
    start = text.index(signature)
    code = mask(text)
    opening = code.index('{', start)
    depth = 1
    end = opening + 1
    while depth:
        if code[end] == '{': depth += 1
        if code[end] == '}': depth -= 1
        end += 1
    body = text[opening+1:end-1]
    begin = ('\n        long perfStart = net.nuggetmc.tplus.utils.PerfProbe.begin();\n'
             '        try {\n' + prefix)
    finish = ('\n        } finally { net.nuggetmc.tplus.utils.PerfProbe.end('
              f'net.nuggetmc.tplus.utils.PerfProbe.{category}, perfStart); }}\n    ')
    path.write_text(text[:opening+1] + begin + body + finish + text[end-1:], encoding='utf-8')

wrap('api/agent/legacyagent/LegacyAgent.java', 'protected void tick()', 'AI')
wrap('api/agent/legacyagent/LegacyAgent.java', 'private LivingEntity locateTarget(', 'TARGET')
wrap('bot/Bot.java', 'public void tick()', 'ENTITY')
wrap('bot/Bot.java', 'private void loadChunks()', 'CHUNKS')
wrap('api/agent/legacyagent/skill/PearlAim.java', 'public static Solution solve(', 'PEARL')
wrap('api/agent/legacyagent/skill/BotPathfinder.java', 'public List<BlockPos> find(', 'PATH')
prefix = '''
        if (net.nuggetmc.tplus.utils.PerfProbe.enabled && net.nuggetmc.tplus.utils.PerfProbe.mode >= 2) {
            Vec3 start = new Vec3(shooter.getX(), shooter.getEyeY() - 0.1, shooter.getZ());
            Vec3 center = target.position().add(0, target.getBbHeight() * 0.5, 0);
            Vec3 velocity = target.onGround() ? new Vec3(targetVelocity.x, 0, targetVelocity.z) : targetVelocity;
            var plan = net.nuggetmc.tplus.utils.BenchAim.choose(new net.nuggetmc.tplus.utils.BenchAim.Request(
                    shooter.getId(), target.getId(), net.nuggetmc.tplus.utils.PerfProbe.tick,
                    start.x, start.y, start.z, center.x, center.y, center.z, velocity.x, velocity.y, velocity.z));
            Solution result = plan.solution();
            return result != null && isClear(level, shooter, start, result, new Vec3(plan.gx(), plan.gy(), plan.gz())) ? result : null;
        }
'''
wrap('api/agent/legacyagent/skill/ArrowAim.java', 'public static Solution solve(', 'ARROW', prefix)

p = java / 'api/agent/legacyagent/LegacyAgent.java'
s = p.read_text(encoding='utf-8')
key = 'private Iterable<LivingEntity> livingEntities(ServerLevel world, LivingEntity self) {'
assert s.count(key) == 1
s = s.replace(key, key + '''
        if (net.nuggetmc.tplus.utils.PerfProbe.enabled && net.nuggetmc.tplus.utils.PerfProbe.mode > 0) {
            return net.nuggetmc.tplus.utils.PerformanceTest.sharedEntities(world);
        }
''')
p.write_text(s, encoding='utf-8')

p = java / 'TerminatorPlus.java'
s = p.read_text(encoding='utf-8')
key = 'SelfTest.register();'
assert s.count(key) == 1
s = s.replace(key, key + '\n        }\n        if (Boolean.getBoolean("terminatorplus.benchmark")) {\n            net.nuggetmc.tplus.utils.PerformanceTest.register();')
p.write_text(s, encoding='utf-8')

p = java / 'utils/PerformanceTest.java'
s = p.read_text(encoding='utf-8').replace('actors.get(i).onTeleported();', 'actors.get(i).fallDistance = 0;')
p.write_text(s, encoding='utf-8')

p = root / 'build.gradle'
s = p.read_text(encoding='utf-8')
key = '        configureEach {'
assert s.count(key) == 1
s = s.replace(key, '''        benchmark {
            server()
            programArgument '--nogui'
            gameDirectory = project.file('run-benchmark')
            systemProperty 'terminatorplus.benchmark', 'true'
            systemProperty 'terminatorplus.fetchSkins', 'false'
            jvmArgument '-Xms2G'
            jvmArgument '-Xmx4G'
        }

''' + key)
p.write_text(s, encoding='utf-8')

run = root / 'run-benchmark'
run.mkdir(exist_ok=True)
(run / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
(run / 'server.properties').write_text('server-port=25571\nonline-mode=false\nlevel-type=minecraft\\:flat\nspawn-protection=0\nview-distance=6\nsimulation-distance=6\nmax-tick-time=180000\nenable-rcon=false\n', encoding='utf-8')
print('Temporary benchmark instrumented; modes are original/cache/async2/async4.')
