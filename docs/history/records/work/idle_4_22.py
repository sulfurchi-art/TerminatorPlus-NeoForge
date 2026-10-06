from pathlib import Path
repo=Path(__file__).parent.parent/'outputs/TerminatorPlus-NeoForge'
p=repo/'src/main/java/net/nuggetmc/tplus/utils/SelfTest.java'
s=p.read_text(encoding='utf-8')
s=s.replace('        list.addAll(battleLogScenarios(y));','        list.addAll(idle422Scenarios(y));\n        list.addAll(battleLogScenarios(y));',1)
anchor='    private List<Scenario> battleLogScenarios(int y) {'
code='''    private static float[] orientation(Entity e) {
        return e instanceof LivingEntity l ? new float[]{e.getYRot(), e.getXRot(), l.yHeadRot, l.yBodyRot} : new float[]{e.getYRot(), e.getXRot()};
    }
    private static boolean sameOrientation(Entity e, float[] expected) {
        float[] actual = orientation(e);
        for (int i = 0; i < actual.length; i++) if (Math.abs(net.minecraft.util.Mth.wrapDegrees(actual[i] - expected[i])) > 0.01) return false;
        return true;
    }
    private List<Scenario> idle422Scenarios(int y) {
        List<Scenario> list = new ArrayList<>();
        for (boolean none : new boolean[]{true, false}) {
            List<Bot> units = new ArrayList<>(); java.util.Map<Entity, float[]> facing = new java.util.HashMap<>(); boolean[] stable = {true};
            list.add(new Scenario("idle 4.22 infantry levels one through ten preserve yaw pitch head and body with " + (none ? "goal none" : "no visible hostile"), () -> {
                forceArea(31000, -10, 31100, 10); run("bot settings setgoal " + (none ? "none" : "nearesthostile"));
                for (int level = 1; level <= 10; level++) {
                    Bot b = spawnBot("IdleLevel" + level, 31005.5 + level * 6, y, 0.5); b.setHardnessOverride(level); b.profileAbilities().put("teamwork", false);
                    b.setLook(23 + level * 7, -12 + level); units.add(b);
                }
                track(() -> { for (Bot b : units) { if (b.getAliveTicks() == 40) facing.put(b, orientation(b));
                    if (b.getAliveTicks() > 40) stable[0] &= sameOrientation(b, facing.get(b)) && legacy().getSkills().currentTarget(b) == null; } });
            }, () -> units.getFirst().getAliveTicks() >= 160 && stable[0] && units.stream().allMatch(Bot::isAlive), 180, true));
        }
        Bot[] escort = {null}; ServerPlayer[] ally = {null}; float[][] facing = {null}; boolean[] stable = {true};
        list.add(new Scenario("idle 4.22 nearby healthy escort does not track a moving teammate with its head", () -> {
            cooperationArena(31200, -8, 31220, 8, y); run("bot settings setgoal nearestenemy");
            escort[0] = cooperationBot("StillEscort", 31205.5, y, 0.5); escort[0].setLook(0, 0);
            ally[0] = spawnHuman("NearbyAlly", 31205.5, y, 3.5); PlayerTeam team = testTeam("still_escort", escort[0]); server.getScoreboard().addPlayerToTeam(ally[0].getScoreboardName(), team);
            track(() -> { int age = escort[0].getAliveTicks(); if (age == 40) facing[0] = orientation(escort[0]);
                if (age > 40) { double angle = age * 0.08; ally[0].setPos(31205.5 + Math.sin(angle), y, 2.5 + Math.cos(angle) * 0.5);
                    stable[0] &= sameOrientation(escort[0], facing[0]) && escort[0].position().distanceTo(new Vec3(31205.5, y, 0.5)) < 0.1; } });
        }, () -> escort[0].getAliveTicks() >= 160 && stable[0] && legacy().getSkills().memory(escort[0]).getGuardedAlly() == ally[0].getId(), 180, true));
        return list;
    }
'''
assert anchor in s;s=s.replace(anchor,code+'\n'+anchor,1)
s=s.replace('        list.addAll(warfare422Scenarios(y));','        list.addAll(warfareIdle422Scenarios(y));\n        list.addAll(warfare422Scenarios(y));',1)
anchor='    private List<Scenario> warfare422Scenarios(int y) {'
code='''    private List<Scenario> warfareIdle422Scenarios(int y) {
        List<Scenario> list = new ArrayList<>(); var crew = legacy().getSkills().warfare().crew();
        for (String type : new String[]{"m_1a_2", "ah_6", "mk_19"}) {
            int x = type.equals("m_1a_2") ? 31400 : type.equals("ah_6") ? 31600 : 31800;
            List<Bot> units = new ArrayList<>(); Entity[] body = {null}; java.util.Map<Entity, float[]> facing = new java.util.HashMap<>(); boolean[] stable = {true};
            list.add(new Scenario("warfare idle 4.22 " + type + " crew and native body stop scanning while stationary", () -> {
                warfareFlatArena(x, -20, x + 100, 20, y); run("bot settings setgoal none"); body[0] = spawnVehicle(type, x + 40.5, y, 0.5, -90);
                int seats = Math.min(3, legacy().getSkills().warfare().vessel(body[0]).seats().size());
                for (int i = 0; i < seats; i++) { Bot b = vehicleBot("IdleCrew" + i, 10, x + 40.5, y, 2.5 + i); units.add(b); }
                testTeam("idle_crew", units.toArray(Bot[]::new));
                for (int i = 0; i < units.size(); i++) crew.board(units.get(i), body[0], i);
                track(() -> { int age = units.getFirst().getAliveTicks();
                    if (age == 100) { facing.put(body[0], orientation(body[0])); units.forEach(b -> facing.put(b, orientation(b))); }
                    if (age > 100) { stable[0] &= sameOrientation(body[0], facing.get(body[0]));
                        for (Bot b : units) stable[0] &= b.getVehicle() == body[0] && sameOrientation(b, facing.get(b)); }
                });
            }, () -> units.getFirst().getAliveTicks() >= 220 && stable[0] && units.stream().allMatch(Bot::isAlive), 260, true));
        }
        return list;
    }
'''
assert anchor in s;s=s.replace(anchor,code+'\n'+anchor,1)
s=s.replace('boolean[] changed = {false}, landed = {false}; double[] traveled = {0}; Vec3[] launchPosition = {null};','boolean[] changed = {false}, landed = {false}, bounded = {true}; double[] traveled = {0}; Vec3[] launchPosition = {null}; long[] firedAt = {-1};',1)
s=s.replace('"early warning descends lands and survives a real " + mode + " Javelin"','"early warning descends and lands with limited temporary cover against a real " + mode + " Javelin"',1)
s=s.replace('incoming[0] = fireJavelin(launcher[0], flyer[0], top); }','incoming[0] = fireJavelin(launcher[0], flyer[0], top); firedAt[0] = server.getTickCount(); }',1)
s=s.replace('landed[0] |= flyer[0].isBotOnGround(); traveled[0]','landed[0] |= flyer[0].isAlive() && flyer[0].isBotOnGround(); bounded[0] &= defense.placed(flyer[0]) <= 4; traveled[0]',1)
s=s.replace('incoming[0].isRemoved() && flyer[0].getAliveTicks() > 220 && flyer[0].isAlive()\n                    && changed[0] && traveled[0] > 10 && (near || landed[0])','incoming[0].isRemoved() && server.getTickCount() - firedAt[0] > 220 && (near ? flyer[0].isAlive() : landed[0])\n                    && changed[0] && traveled[0] > 10 && bounded[0] && defense.placed(flyer[0]) == 0',1)
p.write_text(s,encoding='utf-8',newline='\n')
print('Added 3 base + 3 native idle orientation scenarios; early-warning test now validates response, limited cover and cleanup instead of superseded guaranteed survival')
