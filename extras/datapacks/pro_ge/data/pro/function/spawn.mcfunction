# /function pro:spawn
# 以执行者为圆心、半径 40 格围成一圈，生成 5 个 Pro（Zard_Yan、Cyanobacteria_Z、Dream、Technoblade、Styfunny），出现后自动发装备。
# 必须以玩家身份执行（坐标相对于这名玩家）。控制台用：execute as <玩家> at @s run function pro:spawn

# 队伍：名字显示为金色，队友之间不会互相伤害
team add pro
team modify pro color gold
team modify pro friendlyFire false
team empty pro
team join pro Zard_Yan
team join pro Cyanobacteria_Z
team join pro Dream
team join pro Technoblade
team join pro Styfunny

# 所有能力全开，搭方块用黑曜石（这些是全局设置，服务器重启后会恢复默认，所以每次召唤都设一遍）
bot settings buildblock minecraft:obsidian
bot settings ability pathfinding true
bot settings ability climbing true
bot settings ability elytra true
bot settings ability mace true
bot settings ability pearls true
bot settings ability windcharges true
bot settings ability bow true
bot settings ability totems true
bot settings ability retaliate true

# 前四个用各自正版账号的皮肤，要从 Mojang 拉取，所以会晚几秒出现。
# Styfunny 没有正版账号，查不到皮肤，就显示经典史蒂夫。
bot create Zard_Yan Zard_Yan ~40 ~ ~
bot create Cyanobacteria_Z Cyanobacteria_Z ~12 ~ ~38
bot create Dream Dream ~-32 ~ ~24
bot create Technoblade Technoblade ~-32 ~ ~-24
bot create Styfunny Styfunny ~12 ~ ~-38

# 接下来 30 秒，每秒给新出现的 Pro 发装备
scoreboard objectives add pro_timer dummy
scoreboard players set #wait pro_timer 30
schedule function pro:equip 1s replace
# 场上有 Pro 时每 60 秒补一次状态
schedule function pro:buff_loop 60s replace

tellraw @a {"text":"[Pro] Zard_Yan、Cyanobacteria_Z、Dream、Technoblade、Styfunny 正在赶来（重锤 / 弓箭 / 珍珠 / 黑曜石 / 9 个图腾）……","color":"gold"}
