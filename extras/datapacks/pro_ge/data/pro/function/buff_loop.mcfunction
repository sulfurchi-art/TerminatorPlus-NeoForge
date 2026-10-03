# 场上还有 Pro哥 就每 60 秒补一次状态（效果见 pro:buff）
execute if entity @e[team=pro] run function pro:buff
execute if entity @e[team=pro] run schedule function pro:buff_loop 60s replace
