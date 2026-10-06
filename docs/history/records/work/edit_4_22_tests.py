exec((__import__('pathlib').Path(__file__).parent/'edit_4_22.py').read_text(encoding='utf-8').split("edit('gradle.properties'")[0])
p=root/'src/main/java/net/nuggetmc/tplus/utils/SelfTest.java'; s=p.read_text(encoding='utf-8')
s=s.replace('        list.addAll(hardnessScenarios(y));','        list.addAll(battleLogScenarios(y));\n        list.addAll(hardnessScenarios(y));')
s=s.replace('        list.addAll(warfareMissileScenarios(y));','        list.addAll(warfareMissileScenarios(y));\n        list.addAll(warfare422Scenarios(y));')
a=s.index('        for (boolean top : new boolean[]{false, true}) {',s.index('private List<Scenario> warfareMissileScenarios')); b=s.index('        Bot[] scoped =',a)
s=s[:a]+'''        // 4.22 retires the complete-shell/survival contract. Assert actual finite construction and cleanup instead.
        for (boolean top : new boolean[]{false, true}) {
            int x = top ? 17500 : 17200;
            Bot[] protectedBot = {null}, shooter = {null}; Entity[] missile = {null}; int[] peak = {0}, spent = {0}; boolean[] warned = {false}; long[] begun = {-1};
            list.add(new Scenario("warfare 4.22 missile " + (top ? "top" : "direct") + " builds at most four actual blocks and clears after one missile", () -> {
                warfareFlatArena(x - 40, -40, x + 150, 40, y); run("bot settings setgoal none");
                protectedBot[0] = missileBot("SmallShelter" + top, 9, x + 100.5, y, 0.5); protectedBot[0].giveItem(warfareItem("steel_block", 64));
                shooter[0] = infantry("SmallLauncher" + top, 7, x + 10.5, y, 0.5); shooter[0].profileAbilities().put("guns", false);
                track(() -> {
                    if (protectedBot[0].getAliveTicks() == 80) { begun[0] = server.getTickCount(); missile[0] = fireJavelin(shooter[0], protectedBot[0], top); }
                    peak[0] = Math.max(peak[0], defense.placed(protectedBot[0])); spent[0] = Math.max(spent[0], 64 - protectedBot[0].countItem(warfareItem("steel_block", 1).getItem()));
                    warned[0] |= skills.warfare().describe(protectedBot[0]).contains("INBOUND");
                });
            }, () -> begun[0] >= 0 && server.getTickCount() - begun[0] > 100 && missile[0].isRemoved() && warned[0] && peak[0] > 0 && peak[0] <= 4 && spent[0] <= 4 &&
                    java.util.stream.StreamSupport.stream(BlockPos.betweenClosed(x + 95, y, -5, x + 105, y + 4, 5).spliterator(), false).noneMatch(pos -> level.getBlockState(pos).getBlock() == ((net.minecraft.world.item.BlockItem) warfareItem("steel_block", 1).getItem()).getBlock()), 300, true));
        }
''' +s[b:]
s=s.replace('warfare missile scoped launcher is an aim warning and cancellation clears temporary shelter','warfare 4.22 missile scoped aim and lock sounds never build a shelter')
s=s.replace('if (observer[0].getAliveTicks() == 80) skills.warfare().access().gun(scope[0]).zoom(true);','''if (observer[0].getAliveTicks() == 80) skills.warfare().access().gun(scope[0]).zoom(true);
                if (observer[0].getAliveTicks() == 60 || observer[0].getAliveTicks() == 130) defense.warning(level, observer[0].getOnPos().getCenter(), server.getTickCount(), observer[0].getAliveTicks() == 60 ? "locking_warning" : "locked_warning");
                if (observer[0].getAliveTicks() > 50) unscoped[0] &= defense.placed(observer[0]) == 0;''')
s=s.replace('&& skills.warfare().access().gun(scope[0]).ammo() == 1, 220, true));','&& defense.placed(observer[0]) == 0 && observer[0].countItem(warfareItem("steel_block", 1).getItem()) == 128 && skills.warfare().access().gun(scope[0]).ammo() == 1, 220, true));')
idx=s.index('    private List<Scenario> warfareMissileScenarios(')
new=r'''    private List<com.google.gson.JsonObject> battleRows(java.nio.file.Path file) {
        if (file == null || !java.nio.file.Files.isRegularFile(file)) return List.of();
        try { return java.nio.file.Files.readAllLines(file).stream().filter(l -> !l.isBlank()).map(l -> com.google.gson.JsonParser.parseString(l).getAsJsonObject()).toList(); }
        catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    private List<Scenario> battleLogScenarios(int y) {
        List<Scenario> list = new ArrayList<>(); var log = legacy().getSkills().battleLog();
        boolean[] disabled = {false};
        list.add(new Scenario("battle log 4.22 default off and validated category config round trip", () -> {
            disabled[0] = !log.enabled() && log.path() == null;
            var config = net.nuggetmc.tplus.api.agent.legacyagent.skill.BattleLog.Config.read(new net.minecraft.nbt.CompoundTag());
            disabled[0] &= !config.enabled() && config.snapshotTicks() == 40 && config.categories().contains("damage");
            disabled[0] &= net.nuggetmc.tplus.api.agent.legacyagent.skill.BattleLog.Config.read(config.save()).equals(config);
            net.minecraft.nbt.CompoundTag bad = config.save(); bad.putInt("SnapshotTicks", 0);
            try { net.nuggetmc.tplus.api.agent.legacyagent.skill.BattleLog.Config.read(bad); disabled[0] = false; } catch (IllegalArgumentException expected) {}
        }, () -> disabled[0], 10, true));
        Bot[] victim = {null}, killer = {null}; java.nio.file.Path[] file = {null}; boolean[] done = {false};
        list.add(new Scenario("battle log 4.22 real damage totem death target match summary and escaped marks", () -> {
            run("bot settings setgoal none"); run("bot log on"); file[0] = log.path();
            scenarioHooks.add(() -> log.toggle(false));
            forceArea(29100, -5, 29120, 5); victim[0] = spawnBot("LogVictim", 29104.5, y, 0.5); killer[0] = spawnBot("LogKiller", 29112.5, y, 0.5);
            victim[0].profileAbilities().put("teamwork", false); killer[0].profileAbilities().put("teamwork", false);
            victim[0].setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
            victim[0].profileAbilities().put("totem", false);
            log.mark("quote\" newline\n中文"); run("bot settings setgoal nearestbot");
            track(() -> {
                int age = victim[0].getAliveTicks();
                if (age == 80) victim[0].hurt(level.damageSources().playerAttack(killer[0]), 100);
                if (age == 120) { victim[0].invulnerableTime = 0; victim[0].hurt(level.damageSources().playerAttack(killer[0]), 100); run("bot settings setgoal none"); run("bot log off"); done[0] = true; }
            });
        }, () -> {
            var rows = battleRows(file[0]); var types = rows.stream().map(o -> o.get("type").getAsString()).collect(java.util.stream.Collectors.toSet());
            return done[0] && !victim[0].isAlive() && types.containsAll(List.of("spawn", "target", "damage", "totem", "death", "mark", "match_start", "match_end", "summary", "snap")) &&
                    rows.stream().filter(o -> o.get("type").getAsString().equals("mark")).anyMatch(o -> o.get("text").getAsString().equals("quote\" newline\n中文"));
        }, 200, true));
        net.nuggetmc.tplus.utils.BattleLogWriter[] sink = {null}; java.nio.file.Path[] pressureFile = {null}; long[] accepted = {0}, offerNanos = {0};
        list.add(new Scenario("battle log 4.22 bounded queue pressure preserves accepted lines and explicitly reports loss", () -> {
            pressureFile[0] = java.nio.file.Path.of("logs/terminatorplus/pressure-" + java.util.UUID.randomUUID() + ".jsonl").toAbsolutePath();
            sink[0] = new net.nuggetmc.tplus.utils.BattleLogWriter(pressureFile[0], 8); scenarioHooks.add(() -> sink[0].close());
            long begin = System.nanoTime();
            for (int i = 0; i < 50000; i++) if (sink[0].offer("{\"type\":\"pressure\",\"n\":" + i + "}")) accepted[0]++;
            offerNanos[0] = System.nanoTime() - begin; sink[0].close();
            LOGGER.info("[SelfTest] BattleLog pressure offered=50000 accepted={} dropped={} producerMillis={}", accepted[0], sink[0].dropped(), offerNanos[0] / 1000000.0);
        }, () -> sink[0].closed() && sink[0].error() == null && sink[0].written() == accepted[0] && accepted[0] + sink[0].dropped() == 50000 && sink[0].dropped() > 0 &&
                battleRows(pressureFile[0]).stream().filter(o -> o.get("type").getAsString().equals("log_gap")).mapToLong(o -> o.get("dropped").getAsLong()).sum() == sink[0].dropped(), 100, true));
        java.nio.file.Path[] filtered = {null}; Bot[] filteredBot = {null}; boolean[] filteredDone = {false};
        list.add(new Scenario("battle log 4.22 damage and snap filters retain death history and summary counters", () -> {
            run("bot settings setgoal none"); var config = log.config().save(); var cats = config.getCompound("Categories"); cats.putBoolean("damage", false); cats.putBoolean("snap", false);
            config.putBoolean("Enabled", true); log.configure(net.nuggetmc.tplus.api.agent.legacyagent.skill.BattleLog.Config.read(config)); filtered[0] = log.path();
            scenarioHooks.add(() -> { log.toggle(false); log.configure(net.nuggetmc.tplus.api.agent.legacyagent.skill.BattleLog.Config.read(new net.minecraft.nbt.CompoundTag())); });
            forceArea(29140, -5, 29160, 5); filteredBot[0] = spawnBot("FilteredVictim", 29144.5, y, 0.5); filteredBot[0].profileAbilities().put("totem", false); run("bot settings setgoal nearestbot");
            track(() -> {
                if (filteredBot[0].getAliveTicks() == 80) filteredBot[0].hurt(level.damageSources().generic(), 4);
                if (filteredBot[0].getAliveTicks() == 100) { filteredBot[0].invulnerableTime = 0; filteredBot[0].hurt(level.damageSources().generic(), 100); run("bot settings setgoal none"); log.toggle(false); filteredDone[0] = true; }
            });
        }, () -> {
            var rows = battleRows(filtered[0]);
            return filteredDone[0] && rows.stream().noneMatch(o -> List.of("damage", "snap").contains(o.get("type").getAsString())) &&
                    rows.stream().anyMatch(o -> o.get("type").getAsString().equals("summary")) && rows.stream().anyMatch(o -> o.get("type").getAsString().equals("death") && o.getAsJsonArray("recentDamage").size() > 0);
        }, 180, true));
        return list;
    }
    private List<Scenario> warfare422Scenarios(int y) {
        List<Scenario> list = new ArrayList<>(); var skills = legacy().getSkills(); var war = skills.warfare(); var tactics = skills.ordnance(); var log = skills.battleLog();
        Bot[] operator = {null}; Entity[] aircraft = {null}; Vec3[] hoverGoal = {null}; double[] min = {Double.POSITIVE_INFINITY}, max = {Double.NEGATIVE_INFINITY}; int[] samples = {0};
        list.add(new Scenario("warfare 4.22 drone native hover five seconds stays within half a block", () -> {
            warfareFlatArena(29200, -15, 29300, 15, y); run("bot settings setgoal none");
            operator[0] = ordnanceBot("StableDrone", 10, 29210.5, y, 0.5); giveDrone(operator[0], "grenade_40mm", 4); Husk target = spawnHusk(29254.5, y, 0.5, 1000, false);
            legacy().setEnabled(false); scenarioHooks.add(() -> legacy().setEnabled(true));
            track(() -> {
                int age = operator[0].getAliveTicks();
                if (age == 80) {
                    tactics.tick(operator[0], target, server.getTickCount()); aircraft[0] = tactics.drone(operator[0]);
                    if (aircraft[0] != null) { spawned.add(aircraft[0]); hoverGoal[0] = aircraft[0].position().add(0, 8, 0); }
                }
                if (aircraft[0] != null) {
                    try {
                        var states = tactics.getClass().getDeclaredField("states"); states.setAccessible(true); Object state = ((java.util.Map<?, ?>) states.get(tactics)).get(operator[0]);
                        var pilot = tactics.getClass().getDeclaredMethod("pilotDrone", Bot.class, state.getClass(), Vec3.class); pilot.setAccessible(true); pilot.invoke(tactics, operator[0], state, hoverGoal[0]);
                    } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
                    if (age >= 200 && age < 300) { min[0] = Math.min(min[0], aircraft[0].getY()); max[0] = Math.max(max[0], aircraft[0].getY()); samples[0]++; }
                    if (age % 20 == 0) LOGGER.info("[SelfTest] Drone hover age={} y={} vy={} range={} samples={}", age, aircraft[0].getY(), aircraft[0].getDeltaMovement().y, max[0] - min[0], samples[0]);
                }
            });
        }, () -> samples[0] >= 100 && aircraft[0].isAlive() && max[0] - min[0] <= 0.5 && Math.abs(aircraft[0].getY() - hoverGoal[0].y) <= 0.5, 340, true));
        Bot[] bomber = {null}; java.nio.file.Path[] droneFile = {null}; java.util.Set<java.util.UUID> drops = new java.util.HashSet<>(); boolean[] finished = {false};
        list.add(new Scenario("warfare 4.22 drone suppression attacks within five seconds and produces multiple native rounds in thirty seconds", () -> {
            warfareFlatArena(29400, -20, 29530, 20, y); run("bot settings setgoal nearesthostile"); run("bot log on"); droneFile[0] = log.path(); scenarioHooks.add(() -> log.toggle(false));
            bomber[0] = ordnanceBot("FastSuppressor", 10, 29410.5, y, 0.5); giveDrone(bomber[0], "grenade_40mm", 16); spawnHusk(29454.5, y, 0.5, 5000, false);
            java.util.function.Consumer<net.neoforged.neoforge.event.entity.EntityJoinLevelEvent> listener = event -> { if (event.getEntity() instanceof net.minecraft.world.entity.projectile.Projectile p && p.getOwner() == bomber[0]) drops.add(p.getUUID()); };
            NeoForge.EVENT_BUS.addListener(listener); scenarioHooks.add(() -> NeoForge.EVENT_BUS.unregister(listener));
            track(() -> {
                Entity d = tactics.drone(bomber[0]); if (d != null && !spawned.contains(d)) spawned.add(d);
                if (bomber[0].getAliveTicks() == 620) { log.toggle(false); finished[0] = true; }
            });
        }, () -> {
            var attacks = battleRows(droneFile[0]).stream().filter(o -> o.get("type").getAsString().equals("drone_attack")).toList();
            return finished[0] && bomber[0].isAlive() && drops.size() >= 4 && attacks.size() >= 4 && attacks.stream().allMatch(o -> o.get("firstAttackLatencyTicks").getAsLong() <= 100);
        }, 700, true));
        Bot[] driver = {null}, gunner = {null}; Entity[] vehicle = {null}; java.nio.file.Path[] mountedFile = {null}; boolean[] shotDone = {false};
        list.add(new Scenario("warfare 4.22 battle log records actual native seat firing and occupied vehicle snapshots", () -> {
            warfareFlatArena(29600, -20, 29760, 20, y); run("bot settings setgoal nearesthostile"); run("bot log on"); mountedFile[0] = log.path(); scenarioHooks.add(() -> log.toggle(false));
            driver[0] = vehicleBot("LoggedDriver", 10, 29610.5, y, 2.5); vehicle[0] = spawnVehicle("m_1a_2", 29612.5, y, 0.5, -90); war.crew().board(driver[0], vehicle[0], 0);
            spawnHusk(29690.5, y, 0.5, 5000, false);
            track(() -> { if (driver[0].getAliveTicks() == 200) { log.toggle(false); shotDone[0] = true; } });
        }, () -> {
            var rows = battleRows(mountedFile[0]); var types = rows.stream().map(o -> o.get("type").getAsString()).collect(java.util.stream.Collectors.toSet());
            return shotDone[0] && types.containsAll(List.of("board_request", "board_ok", "fire", "vehicle_snap", "weapon_select", "nav")) && rows.stream().anyMatch(o -> o.get("type").getAsString().equals("fire") && o.has("vehicle"));
        }, 300, true));
        return list;
    }

'''
s=s[:idx]+new+s[idx:];p.write_text(s,encoding='utf-8',newline='\n')
print('4.22 functional scenarios added, obsolete large-shell expectations retired')
