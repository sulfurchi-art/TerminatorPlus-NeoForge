# YSM（是，史蒂夫模型）联动调研：让人机使用指定模型

> - **日期：** 2026-10-05，Claude 在正式服上调研。
> - **用户要求：**"添加和对于YSM的联动，能够让人机使用YSM的指定模型。"
> - **版本：** 服务器和客户端装的都是 `ysm-2.6.5-neoforge+mc1.21.1-release.jar`，mod ID 是 `yes_steve_model`。

## 结论：用 YSM 自带的指令就能做，不需要碰它的代码

**YSM 的代码不能直接调用：**
- 许可证是 **All rights reserved**，代码整个做了混淆，类名都是 `oOo0OO0O0o000…` 这种形式，也没有公开的 API 包。
- 所以**不要**编译期依赖它，也不要用反射调用它的内部类。版本一更新，类名就会全变。

**但 YSM 有一套服务端指令，而且实测对人机有效**（在正式服上测过，人机是 `ServerPlayer`）：

```
/ysm model set <targets> <model_id> <texture_id> [<ignore_auth>]
/ysm model reload
/ysm model disable <players> <value>
/ysm play <targets> <animation>
/ysm molang execute <targets> <exp>
/ysm auth <targets> (add|remove|all|clear)
/ysm export <model_id> [<extra>]
/ysm ping
```

**实测记录（2026-10-05 09:18～09:19）：**
- 选择器 `@e[type=player,name=Squad_7,limit=1,distance=0..]` 能被 `<targets>` 接受。人机不在玩家列表里，所以选择器必须带 `distance` 这类条件。
- `ysm model set ... "wine_fox/01_taisho_maid" skin true` 返回 `[YSM] Set 'wine_fox/01_taisho_maid' models for player 'Squad_7'`。
- `ysm model set ... "wine_fox/16_tactics" tactics true` 设在了 Squad_6 上，同样成功。
- `ysm model set ... default default true` 可以把模型改回默认。
- **客户端看不看得到：** 不行。**用户确认客户端没有显示**：Squad_7 和 Squad_6 在客户端上还是原来的样子。
  - 推测原因：服务端已经记下了模型，但 YSM 只给玩家列表里的玩家同步模型数据。默认情况下，TerminatorPlus 的人机不在玩家列表里，`addplayerlist` 默认是关的。
- **对照实验（09:20）：** 先开 `/bot settings addplayerlist true`，生成测试人机 YsmTest（`/list` 里能看到它），再执行 `ysm model set` 设成酒狐，然后把设置关回去。**用户确认：客户端显示了酒狐模型。**
- **结论：** YSM 只给**玩家列表里的玩家**同步模型。人机要显示 YSM 模型，就必须进玩家列表。只要满足这一条，`/ysm model set` 就能直接用。

## 模型 ID 和贴图 ID 的格式

- **内置模型：** 在 `config/yes_steve_model/builtin/<包>/<模型>/`，模型 ID 是 **`<包文件夹>/<模型文件夹>`**。
  - 因为 ID 里有 `/`，指令里必须加引号，比如 `"wine_fox/01_taisho_maid"`。只写文件夹名 `01_taisho_maid` 会报 "is not exist"。
  - 默认模型的 ID 就是 `default`。
- **贴图 ID：** 就是 `textures/` 下的文件名去掉 `.png`。
  - 酒狐用 `skin` 或 `skin_white`。
  - 战术酒狐用 `tactics`。
  - 默认模型用 `default`。
  - 每个模型自己的 `ysm.json` 里，`files.player.texture` 列出了它所有可用的贴图。
- **服务器现有的模型：**
  - 内置的 `default`。
  - `misc/` 下有 4 个：`1_alex`、`2_steve`、`3_default_boy`、`4_default_controllers`。
  - `wine_fox/` 下有 22 个：`01_taisho_maid` 到 `22_elf`。
  - 自定义模型在 `config/yes_steve_model/custom/`，目前有一个 `DS鲸鱼娘.ysm`。
- **控制台限制：** 中文模型名没法从控制台输入，会变成 U+FFFD。所以 TerminatorPlus 自己调用指令时要直接传字符串，不要经过控制台。
- **`ignore_auth` 设为 true：** 可以跳过 YSM 的模型授权，`/ysm auth` 管理的是哪些玩家能用哪些模型。

## 建议的实现方式（给 Codex）

**1. 软依赖。** 和卓越前线兼容的做法一样：
- 用 `ModList.get().isLoaded("yes_steve_model")` 判断有没有装 YSM。
- 没装时所有相关功能都是空操作，也不在 `mods.toml` 里声明硬依赖。

**2. 调用方式。** 以服务器权限，通过指令派发执行 YSM 自己的指令：
- 生成一个权限 4 的 `CommandSourceStack`，执行 `ysm model set <人机选择器> "<model_id>" <texture_id> true`。
- 选择器用人机的 UUID，不要用名字，因为人机可以重名。
- 执行结果和失败信息写进日志。出错时只记录，不能让服务器崩。

**3. 指令。**
- `/bot ysm <人机|all> <model_id> [texture_id]`
- `/bot ysm <人机|all> reset`：改回 `default default`。
- `/bot ysm list`：列出 builtin 和 custom 里的模型 ID 和贴图 ID，可以直接读 `ysm.json`。

**4. 写进预设。**
- `/bot loadout profile <预设> ysm <model_id> [texture_id]` 存到预设的档案里，`createpreset` 生成人机时自动套用。
- 人机创建是异步的（要等皮肤请求），所以要等人机实体加入世界、已经被客户端追踪之后再执行。建议在加入世界后延迟 20～40 tick。

**4.5 必须进玩家列表（实测得出的前提）。**
- 只把**要用 YSM 模型的那些人机**加进玩家列表，不要靠全局的 `addplayerlist`。
- 顺序：先加进玩家列表，再执行 `ysm model set`。如果人机已经生成，就先补加进列表，再设模型。
- **进列表的副作用，需要处理：**
  - 会出现在 Tab 列表和 `/list` 里，也计入在线人数。建议发 `ClientboundPlayerInfoUpdatePacket` 的 `UPDATE_LISTED` 把 listed 设为 false，这样还在服务端玩家列表里，但不显示在 Tab 列表上。需要验证这样做 YSM 还能不能同步。
  - 会被 `@a`、`@p` 选中。比如管理员执行 `/clear @a`、`/kill @a`、`/tp @a`，也会作用到人机身上。
  - 可能参与睡觉跳过夜晚的人数统计。
  - 原有的 `PlayerListMixin` 要继续保证人机不写 `playerdata/`。
- 人机死亡或被 `bot reset` 时，要从玩家列表里干净地移除，不能留下"幽灵玩家"。

**5. 生命周期。**
- 人机不存盘，重生或者重新生成时，要按预设重新套模型。
- 要测试玩家后进服、或者重连时能不能看到人机的模型。如果看不到，就在玩家进服时，把附近人机的模型再设一次。

**6. 可选的玩法联动（以后再说，先问用户）：**
- `/ysm play <人机> <动画>` 可以接上台词和战术事件，比如击杀后播庆祝动画、撤退时播动画。
- 不同的人设可以绑定不同的模型。

## 待验证

1. ~~客户端能不能看到人机的 YSM 模型？~~ **已经验证：** 不在玩家列表里的人机看不到；进了玩家列表的人机能看到（YsmTest 实测显示了酒狐）。
   - 还要测：玩家后进服或者重连时能不能看到。
   - 还要测：用 `UPDATE_LISTED=false` 把人机从 Tab 列表上隐藏以后，模型还在不在。
2. 人机死亡或者被移除后，YSM 有没有残留数据。人机重名、同一个 UUID 复用时，会不会串模型。
3. 中文的自定义模型 ID（比如 `DS鲸鱼娘`）通过指令派发传进去能不能用。
4. YSM 模型的动画（攻击、举盾、开鞘翅）在人机身上能不能正常触发。人机用的是自定义物理，动画判断依赖的状态可能和真人不一样。
