exec((__import__('pathlib').Path(__file__).parent/'edit_4_22.py').read_text(encoding='utf-8').split("edit('gradle.properties'")[0])
p=root/'src/main/java/net/nuggetmc/tplus/utils/SelfTest.java';s=p.read_text(encoding='utf-8');a=s.index('        Bot[] shelterGroup = new Bot[6]');b=s.index('        Bot[] heliPilot',a)
s=s[:a]+'''        Bot[] shelterGroup = new Bot[6], launchers = new Bot[6]; Entity[] groupMissiles = new Entity[6]; int[] lastPlaced = new int[6], peakPlacement = {0};
        list.add(new Scenario("warfare missile six real inbound threats share a fair 24-block tick budget with finite native materials", () -> {
            warfareFlatArena(20100, -40, 20300, 40, y); run("bot settings setgoal none");
            for (int i = 0; i < shelterGroup.length; i++) {
                shelterGroup[i] = missileBot("BudgetShelter" + i, 9, 20200.5, y, -30.5 + i * 12); shelterGroup[i].setInvulnerable(true);
                shelterGroup[i].giveItem(warfareItem("steel_block", 64)); shelterGroup[i].giveItem(warfareItem("steel_block", 64));
                launchers[i] = infantry("BudgetLauncher" + i, 7, 20110.5, y, -30.5 + i * 12); launchers[i].profileAbilities().put("guns", false);
            }
            track(() -> {
                int placedThisTick = 0;
                for (int i = 0; i < shelterGroup.length; i++) {
                    Bot b = shelterGroup[i];
                    if (b.getAliveTicks() == 80) groupMissiles[i] = fireJavelin(launchers[i], b, false);
                    int placedNow = defense.placed(b); placedThisTick += placedNow - lastPlaced[i]; lastPlaced[i] = placedNow;
                }
                peakPlacement[0] = Math.max(peakPlacement[0], placedThisTick);
            });
        }, () -> {
            if (shelterGroup[0].getAliveTicks() < 180 || peakPlacement[0] <= 0 || peakPlacement[0] > 24) return false;
            for (int i = 0; i < shelterGroup.length; i++) if (!shelterGroup[i].isAlive() || defense.placed(shelterGroup[i]) != 4 || groupMissiles[i] == null || !groupMissiles[i].isRemoved()
                    || shelterGroup[i].countItem(warfareItem("steel_block", 1).getItem()) != 124) return false;
            LOGGER.info("[SelfTest] Six small barriers peak placements={} counts={}", peakPlacement[0], java.util.Arrays.toString(lastPlaced)); return true;
        }, 300, true));
''' +s[b:];p.write_text(s,encoding='utf-8',newline='\n');print('legacy six full-shell warning test replaced with six actual inbound limited barriers')
