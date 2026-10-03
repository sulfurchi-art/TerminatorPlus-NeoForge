# 给还没拿到装备的 Pro哥 发装备，每人只发一次（发完会打上 pro_ok 标签）。
# spawn 会自动调用它；新生成的机器人只要在 pro 队伍里，也可以手动 /function pro:equip 补发
execute as @e[team=pro,tag=!pro_ok] run function pro:equip_one
scoreboard players remove #wait pro_timer 1
execute if score #wait pro_timer matches 1.. run schedule function pro:equip 1s replace
