# 一个 Pro哥 的全套装备（以机器人自身身份执行）
# 附魔全部是原版的最高等级，并遵守原版的互斥规则；诅咒类附魔不加
# 快捷栏第 0 格是 AI 用来换物品的"手"，所以东西都放在第 1~8 格

item replace entity @s armor.head with netherite_helmet[enchantments={levels:{protection:4,unbreaking:3,mending:1,respiration:3,aqua_affinity:1,thorns:3}}]
item replace entity @s armor.chest with netherite_chestplate[enchantments={levels:{protection:4,unbreaking:3,mending:1,thorns:3}}]
item replace entity @s armor.legs with netherite_leggings[enchantments={levels:{protection:4,unbreaking:3,mending:1,swift_sneak:3,thorns:3}}]
item replace entity @s armor.feet with netherite_boots[enchantments={levels:{protection:4,feather_falling:4,depth_strider:3,soul_speed:3,unbreaking:3,mending:1,thorns:3}}]
item replace entity @s weapon.offhand with totem_of_undying

item replace entity @s hotbar.1 with netherite_sword[enchantments={levels:{sharpness:5,fire_aspect:2,knockback:2,sweeping_edge:3,looting:3,unbreaking:3,mending:1}}]
item replace entity @s hotbar.2 with mace[enchantments={levels:{density:5,wind_burst:3,fire_aspect:2,unbreaking:3,mending:1}}]
item replace entity @s hotbar.3 with elytra[enchantments={levels:{unbreaking:3,mending:1}}]
item replace entity @s hotbar.4 with firework_rocket 64
item replace entity @s hotbar.5 with firework_rocket 64
item replace entity @s hotbar.6 with ender_pearl 16
item replace entity @s hotbar.7 with ender_pearl 16
item replace entity @s hotbar.8 with wind_charge 64
item replace entity @s inventory.0 with ender_pearl 16
item replace entity @s inventory.1 with ender_pearl 16

# 弓：无限和经验修补互斥，选无限，一支箭就能一直射
item replace entity @s inventory.2 with bow[enchantments={levels:{power:5,punch:2,flame:1,infinity:1,unbreaking:3}}]
item replace entity @s inventory.3 with arrow 64

# 备用图腾：副手的爆了会立刻补上，一共 9 条命
item replace entity @s inventory.4 with totem_of_undying
item replace entity @s inventory.5 with totem_of_undying
item replace entity @s inventory.6 with totem_of_undying
item replace entity @s inventory.7 with totem_of_undying
item replace entity @s inventory.8 with totem_of_undying
item replace entity @s inventory.9 with totem_of_undying
item replace entity @s inventory.10 with totem_of_undying
item replace entity @s inventory.11 with totem_of_undying

# 状态：相当于刚吃了附魔金苹果、喝了力量 II；之后 pro:buff_loop 每 60 秒补一次
effect give @s absorption 120 3
effect give @s regeneration 20 1
effect give @s resistance 300 0
effect give @s fire_resistance 300 0
effect give @s strength 90 1

tag @s add pro_ok
