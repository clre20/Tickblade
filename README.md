# TickBlade

找出伺服器卡頓的來源、修正越積越多的生物、MSPT 過高時自動降載，並暫停擠在一起或被關住的生物 AI，同時保留交易、補貨與剪羊毛等原版機制。

支援 Paper 1.21.11、26.1.x～26.3（Purpur 等 Paper 分支理論上相容；不支援 Spigot、Folia）。1.21.11 需要 Java 21 以上，26.x 伺服器需要 Java 25。

## 功能

- **生物消失判斷**：
  - 原版中，終界使者拿起方塊、殭屍或骷髏撿起物品穿戴後會變成「永遠不會消失」，因此越積越多
  - 阻止終界使者撿起方塊（放下不受影響，可設定套用的世界）
  - 區塊載入時與定時檢查：可以自由走動的這類怪物，會把方塊或物品原地掉落，恢復成可以正常消失；掉落的東西其他怪物無法再撿回去
  - 被關住、坐在船／礦車上、取了名字、拴著、突襲中、殭屍村民，以及其他插件的自訂生物一律不處理
- **卡頓偵測與 spark 報告存檔**：
  - 記錄每個 tick 花費的時間，短時間內多次超過門檻（預設 500 ms）就自動錄製 spark（只記錄長 tick）
  - 報告直接存成檔案放在 `plugins/TickBlade/reports/`，不會有網址過期的問題；有檔案數與總容量上限
  - 每次卡頓記錄到 `spikes.log`（有大小上限，自動輪替）
  - **卡頓當下擷取主執行緒堆疊**：tick 跑超過 1 秒時，背景執行緒直接讀主執行緒正在做什麼，寫到 `stacks.log`，後台顯示一行「可能來源」（含插件的 jar 檔名）。卡在寫硬碟等系統呼叫時 spark 取樣抓不到，這個抓得到
  - **程序凍結診斷**：如果卡頓時連背景執行緒也停住，代表整個 Java 程序被凍住（不是某段程式太慢）。這時會對照 JVM 安全點（用 Java 內建的 JFR，不需要改啟動參數）、容器 CPU 限流、系統壓力（PSI）與 steal，判斷是 JVM 內部、CPU 限流、磁碟、記憶體還是主機造成，寫到 `freezes.log`
- **熱點區塊介面**：
  - 箱子介面列出區塊的實體、方塊實體、漏斗數量，顯示數量最多的 5 種生物（以玩家的遊戲語言顯示）
  - 分類（實體／方塊實體／漏斗）與排序（多到少／少到多／離我最近）分開切換
  - 左鍵點一下就傳送過去；開介面時才掃描，掃描分散到多個 tick
- **自動降載**：
  - 依最近幾秒的平均 MSPT 分級，降低每位玩家的模擬距離與視距
  - 負載恢復後一級一級還原成原本的距離，避免來回跳動
- **擁擠／被關住的生物暫停 AI**：
  - 1.5 格內同類生物 ≥ 5 隻，或前後左右都走不過去時暫停；降到 3 隻以下且周圍有空間時恢復（都可設定）
  - 「走不過去」會考慮生物身高，例如床上方只剩 1.5 格，身高將近 2 格的村民就過不去（這種床原版村民也睡不了）
  - 敵對生物（殭屍、骷髏、史萊姆等）被困住時，16 格內有可攻擊的目標（生存／冒險模式玩家、村民、鐵傀儡）就保持清醒、照常攻擊，沒有目標才暫停；擠在一起時一律暫停
  - 需要 AI 時暫時喚醒，計算交給原版：村民在白天工作時段補貨、晚上有床的會被喚醒走到床上（每晚最多 60 秒）、交易後升級；被剪過毛的羊吃草長回羊毛；進入繁殖狀態的動物；被任何生物攻擊時
  - 沒被關住的村民，附近 12 格內有殭屍、掠奪者等威脅時會醒來逃跑
  - 暫停期間村民照常可以交易；右鍵點擊被暫停的村民會喚醒 10 秒
  - 沒有職業的村民：能走動的不暫停（才會去找工作站）；被困住的會暫停，玩家在附近放下工作站方塊時喚醒 60 秒去認領職業
  - 自動辨識鐵傀儡農場（被關住的村民附近有殭屍、掠奪者等村民會害怕的生物就不暫停）
  - 生物名字含有「保留」（可設定）就不會被暫停
  - 暫停狀態存在生物身上，關閉功能、關服或執行 `/tickb ai restore` 都會完整恢復
- **查看單隻生物的判斷**：
  - 看著一隻生物執行 `/tickb inspect`（可隔著玻璃、圍欄），顯示 AI 是否被暫停、原因、1.5 格內同類數量、是否被困住
  - 列出保持清醒或不會暫停的原因（例如工作時段、附近有威脅、鐵傀儡農場、名字含「保留」、其他插件的生物）
  - 顯示消失判斷：會不會被處理，不會的話卡在哪一條規則
  - 只讀取不改變任何狀態；判斷規則與實際掃描完全相同
- **其他**：
  - 所有文字都在 `messages.yml`，預設繁體中文
  - `config.yml`、`messages.yml` 缺少的項目會在啟動與重載時自動補回，保留原本的值
  - 啟動時在背景檢查 GitHub 新版本，有新版本時管理員登入 3 秒後會收到提醒

## 指令與權限

所有指令都以 `/tickblade` 開頭，別名 `/tickb`。

| 指令 | 說明 | 執行者 | 權限節點 | 預設 |
|---|---|---|---|---|
| `/tickb help` | 列出自己有權限的指令 | 玩家、主控台 | `tickblade.help` | OP |
| `/tickb status` | 平均 MSPT、降載等級、AI 暫停數量、消失判斷與卡頓統計 | 玩家、主控台 | `tickblade.status` | OP |
| `/tickb hotspots` | 開啟熱點區塊介面（另需 OP 等級 2 以上） | 玩家 | `tickblade.hotspots` | OP |
| `/tickb inspect` | 看著一隻生物，查看 TickBlade 對它的判斷（另需 OP 等級 2 以上） | 玩家 | `tickblade.inspect` | OP |
| `/tickb reports` | 列出已存檔的 spark 報告 | 玩家、主控台 | `tickblade.reports` | OP |
| `/tickb profile` | 立即錄製一份只含長 tick 的 spark 報告 | 玩家、主控台 | `tickblade.profile` | OP |
| `/tickb cleanup` | 立即檢查所有已載入的怪物 | 玩家、主控台 | `tickblade.cleanup` | OP |
| `/tickb ai restore` | 恢復已載入區塊中所有被暫停的生物 AI | 玩家、主控台 | `tickblade.ai` | OP |
| `/tickb reload` | 重新載入 `config.yml` 與 `messages.yml` | 玩家、主控台 | `tickblade.reload` | OP |
| `/tickb version` | 顯示版本並檢查更新 | 玩家、主控台 | `tickblade.version` | OP |
| （通知） | 收到自動錄製、報告存檔、降載等級變化的通知 | 玩家 | `tickblade.notify` | OP |

> `tickblade.admin` 包含以上全部權限。
>
> 熱點介面可以傳送到任何區塊、inspect 可以看到任何生物的判斷，所以除了權限，還需要 `ops.json` 中的 OP 等級達到 `hotspots.min-op-level`／`inspect.min-op-level`（預設 2；設 0 = 只看權限）。
>
> 有 `tickblade.version` 權限的玩家，在有新版本時登入 3 秒後會收到提醒。

## 使用範例

### 1. 抓出卡頓的原因

1. 保持預設設定，伺服器卡頓次數達到門檻時會自動錄製
2. 或在卡的時候手動錄一份：

```bash
/tickb profile
```

3. 錄完後用 `/tickb reports` 查看檔名，到 `plugins/TickBlade/reports/` 下載檔案
4. 把 `.sparkprofile` 檔案拖進 https://spark.lucko.me 即可查看
5. 如果 spark 報告看不出原因，看 `plugins/TickBlade/reports/stacks.log`：每次卡頓超過 1 秒時主執行緒正在執行的程式
6. 如果 `stacks.log` 的位置很分散、每次都不一樣，看 `plugins/TickBlade/reports/freezes.log`：每一次整個程序被凍住的判斷結果；「主機暫停」「CPU 限流」這類結果需要聯絡主機商處理

- 每次錄製長度、只記錄超過幾毫秒的 tick、冷卻時間都可以在 `spike-detector.auto-profile` 設定
- 需要 spark（Paper 內建）；`paper-global.yml` 的 `spark.enabled` 必須是 `true`

### 2. 找到生物太多的地方

```bash
/tickb hotspots
```

- 左鍵點擊「分類」按鈕切換要看的數量：實體 → 方塊實體 → 漏斗
- 左鍵點擊「排序」按鈕切換順序：數量多到少 → 數量少到多 → 離我最近（同世界依水平距離，其他世界排在後面）
- 分類和排序可以自由搭配，重新掃描後會保留目前的選擇
- 左鍵點擊區塊即可傳送到該區塊數量最多的生物所在位置

### 3. 讓某隻生物不要被暫停 AI

1. 用命名牌把生物取名為含有「保留」的名字，例如「保留-鐵傀儡農場」
2. 下一次掃描時就會恢復並不再暫停

### 4. 查不出某隻生物為什麼被暫停／沒被暫停

1. 看著那隻生物（16 格內，可以隔著玻璃）
2. 執行：

```bash
/tickb inspect
```

- 判斷和目前狀態不一樣時，等下一次掃描（預設 5 秒）就會更新
- 回報問題時可以直接貼上這段結果

### 5. 移除插件前

```bash
/tickb ai restore
```

- 關服時也會自動恢復已載入的生物；之後若關閉 `ai-pause.enabled`，未載入區塊裡的生物會在區塊載入時恢復

## 設定重點

| 設定 | 說明 |
|---|---|
| `inspect.min-op-level` / `max-distance` | 使用 inspect 需要的 OP 等級、最遠查看距離 |
| `hotspots.min-op-level` | 開啟熱點介面需要的 OP 等級（0 = 不檢查） |
| `protection.name-keyword` | 名字含有這段文字的生物，消失判斷與暫停 AI 都不處理 |
| `despawn-fix.block-enderman-pickup` | 阻止終界使者撿方塊 |
| `despawn-fix.worlds` / `ai-pause.worlds` | 套用的世界，空清單 = 全部世界 |
| `spike-detector.spike-threshold-ms` | 單一 tick 超過幾毫秒算一次卡頓 |
| `spike-detector.reports.max-files` / `max-total-mb` | 報告數量與總容量上限 |
| `spike-detector.stack-capture.threshold-ms` | tick 跑超過幾毫秒就擷取主執行緒堆疊 |
| `spike-detector.freeze-diagnosis.enabled` | 程序凍結診斷開關（`jfr: false` 可以只用系統數據） |
| `load-shedding.levels` | 降載等級：MSPT 門檻與要降到的模擬距離／視距 |
| `ai-pause.crowd.pause-at` / `resume-at` | 暫停與恢復的同類生物數量 |
| `ai-pause.wake.villager-work-windows` | 村民補貨的喚醒時段（遊戲時間） |
| `ai-pause.wake.villager-bedtime-windows` / `villager-bedtime-max-seconds` | 有床的村民晚上被喚醒走到床上的時段，以及每晚最多喚醒幾秒 |
| `ai-pause.wake.villager-threat-radius` | 附近幾格內有威脅時喚醒村民（0 = 關閉） |
| `ai-pause.hostile-target-radius` | 被困住的敵對生物，幾格內有可攻擊目標就不暫停（0 = 被困住的敵對生物一律不暫停） |
| `ai-pause.excluded-types` | 永遠不暫停的生物種類 |

---

# TickBlade (English)

Identify sources of server lag, fix mobs that pile up because they never despawn, automatically shed load when MSPT gets too high, and pause the AI of crowded or trapped mobs, all while keeping vanilla mechanics such as villager trading, restocking, and sheep wool regrowth.

Compatible with Paper 1.21.11 and 26.1.x–26.3 (Paper forks such as Purpur should work; Spigot and Folia are not supported). Paper 1.21.11 requires Java 21 or newer; 26.x servers require Java 25.

## Features

- **Despawn Fix**:
  - In vanilla Minecraft, Endermen carrying blocks and Zombies or Skeletons that pick up and wear items become persistent and never despawn, so they pile up over time.
  - Prevents Endermen from picking up blocks (placing blocks is unaffected; configurable per world).
  - On chunk load and during periodic scans, freely roaming mobs of this kind drop their carried block or picked-up items on the spot and can despawn normally again. Other mobs cannot pick those drops back up.
  - Trapped mobs, mobs riding boats/minecarts, named mobs, leashed mobs, raid participants, Zombie Villagers, and custom mobs from other plugins are never touched.

- **Lag Spike Detection & spark Reports Saved to File**:
  - Measures every tick. When ticks exceed the threshold (default: 500 ms) several times within a short window, a spark profiler is started automatically that records long ticks only.
  - Reports are saved as `.sparkprofile` files in `plugins/TickBlade/reports/`, so there are no expiring links. File count and total size are capped.
  - Every spike is logged to `spikes.log`, which is size-limited and rotated automatically.
  - **Stack capture during spikes**: when a tick runs longer than 1 second, a background thread reads what the main thread is doing and writes it to `stacks.log`, with a one-line "likely source" (including the plugin's jar name) in the console. This works even when the main thread is stuck in a system call such as a disk write, which spark's sampling cannot see.
  - **Process freeze diagnosis**: if the background thread also stops during a spike, the whole Java process was frozen (not just slow code). TickBlade then checks JVM safepoints (using Java's built-in JFR, no startup flags needed), container CPU throttling, system pressure (PSI), and steal time, and decides whether the JVM, CPU throttling, disk, memory, or the host caused it. Results go to `freezes.log`.

- **Hotspot Chunk GUI**:
  - A chest GUI lists entity, block entity, and hopper counts per chunk, plus the 5 most common mob types (shown in each player's client language).
  - Category (Entities / Block Entities / Hoppers) and sort order (Most / Least / Nearest) are switched independently.
  - Left-click a chunk to teleport there. Scanning only happens when the GUI is opened and is spread over several ticks to avoid lag.

- **Dynamic Load Shedding**:
  - Uses the average MSPT of the last few seconds to lower each player's simulation distance and view distance in tiers.
  - When performance recovers, distances are restored one tier at a time to avoid flip-flopping.

- **AI Pausing for Crowded & Trapped Mobs**:
  - Pauses a mob's AI when 5 or more mobs of the same type are within 1.5 blocks, or when it cannot step into any of the four neighbouring blocks. Resumes when the count drops to 3 or fewer and the mob has room to move (all thresholds configurable).
  - "Cannot step into" takes the mob's height into account, e.g. a villager (almost 2 blocks tall) cannot walk onto a bed that has only 1.5 blocks of space above it (vanilla villagers cannot sleep in such a bed either).
  - **Hostile mobs**: trapped hostiles (Zombies, Skeletons, Slimes, etc.) stay active while an attackable target (Survival/Adventure player, Villager, Iron Golem) is within 16 blocks, and pause only when no target is in range. Crowded hostiles always pause.
  - **Temporary wake-ups for vanilla mechanics**: whenever AI is needed, the mob is woken up and vanilla does the work:
    - Villagers during work hours (restocking), at night if they own a bed (walking to it, up to 60 seconds per night), and after trading (levelling up).
    - Sheared sheep, so they can eat grass and regrow wool.
    - Animals in love mode (breeding).
    - Any mob that is attacked by another entity.
  - Villagers that are not trapped wake up and flee when a threat (Zombie, Pillager, etc.) comes within 12 blocks.
  - Paused villagers can still be traded with; right-clicking a paused villager wakes it for 10 seconds.
  - **Unemployed villagers**: those that can move around are never paused, so they can find a job site. Trapped ones are paused, but wake up for 60 seconds when a player places a workstation nearby.
  - **Iron golem farm detection**: trapped villagers are not paused when a mob they fear (Zombie, Pillager, etc.) is nearby.
  - Mobs whose name contains the keyword `保留` (configurable, e.g. `Keep`) are never paused.
  - The paused state is stored on the mob itself and fully restored when the feature is turned off, the server shuts down, or `/tickb ai restore` is run.

- **Mob Inspector**:
  - Look at any mob (works through glass and fences) and run `/tickb inspect` to see its current evaluation: whether its AI is paused and why, the nearby same-type count, and whether it counts as trapped.
  - Lists why a mob stays awake or will not be paused (e.g. work hours, nearby threat, iron golem farm, name keyword, mob from another plugin).
  - Shows the despawn-fix evaluation: whether the mob will be handled, and if not, which rule prevents it.
  - Read-only and changes nothing; uses exactly the same rules as the background scan.

- **Other**:
  - All text lives in `messages.yml` (Traditional Chinese by default).
  - Missing keys in `config.yml` and `messages.yml` are added automatically on startup and reload, keeping your existing values.
  - Checks GitHub for a new release in the background on startup; admins are notified 3 seconds after joining if one is available.

## Commands & Permissions

Base command: `/tickblade` (alias: `/tickb`).

| Command | Description | Executor | Permission Node | Default |
|---|---|---|---|---|
| `/tickb help` | Lists the commands you have permission to use | Player, Console | `tickblade.help` | OP |
| `/tickb status` | Shows average MSPT, load-shedding level, paused AI counts, despawn-fix and spike stats | Player, Console | `tickblade.status` | OP |
| `/tickb hotspots` | Opens the Hotspot Chunk GUI (also requires OP level 2+) | Player | `tickblade.hotspots` | OP |
| `/tickb inspect` | Shows TickBlade's evaluation of the mob you are looking at (also requires OP level 2+) | Player | `tickblade.inspect` | OP |
| `/tickb reports` | Lists saved spark reports | Player, Console | `tickblade.reports` | OP |
| `/tickb profile` | Immediately records a spark report of long ticks only | Player, Console | `tickblade.profile` | OP |
| `/tickb cleanup` | Immediately checks all loaded monsters for the despawn fix | Player, Console | `tickblade.cleanup` | OP |
| `/tickb ai restore` | Restores the AI of all paused mobs in loaded chunks | Player, Console | `tickblade.ai` | OP |
| `/tickb reload` | Reloads `config.yml` and `messages.yml` | Player, Console | `tickblade.reload` | OP |
| `/tickb version` | Shows the plugin version and checks for updates | Player, Console | `tickblade.version` | OP |
| *(Notification)* | Receives alerts for auto-profiling, saved reports, and load-shedding level changes | Player | `tickblade.notify` | OP |

> `tickblade.admin` grants all permissions listed above.
>
> Because the hotspot GUI can teleport to any chunk and inspect reveals any mob's evaluation, both also require an OP level in `ops.json` of at least `hotspots.min-op-level` / `inspect.min-op-level` (default: 2; set to `0` to check the permission only).
>
> Players with `tickblade.version` are notified 3 seconds after joining when a new version is available.

## Usage Examples

### 1. Finding the cause of lag spikes

1. With the default settings, recording starts automatically when spikes reach the threshold.
2. Or record one manually while the server is lagging:

```bash
/tickb profile
```

3. Run `/tickb reports` to see the file name, then download it from `plugins/TickBlade/reports/`.
4. Drag the `.sparkprofile` file into https://spark.lucko.me to view it.
5. If the spark report does not show the cause, check `plugins/TickBlade/reports/stacks.log`: what the main thread was running each time a tick took longer than 1 second.
6. If the locations in `stacks.log` are scattered and different every time, check `plugins/TickBlade/reports/freezes.log`: the verdict for each whole-process freeze. Verdicts such as "paused by the host" or "CPU throttling" need to be raised with your host.

- Recording length, the minimum tick duration to record, and the cooldown are configured under `spike-detector.auto-profile`.
- Requires spark (bundled with Paper); `spark.enabled` in `paper-global.yml` must be `true`.

### 2. Finding places with too many mobs

```bash
/tickb hotspots
```

- Left-click the **Category** button to switch what is counted: Entities → Block Entities → Hoppers.
- Left-click the **Sort** button to switch the order: Most → Least → Nearest (horizontal distance within your current world; chunks in other worlds are listed after).
- Category and sort can be combined freely and are kept when you rescan.
- Left-click a chunk to teleport to where the most common mob in that chunk is.

### 3. Keeping a specific mob from being paused

1. Use a name tag to give the mob a name containing `保留` (or your keyword from `protection.name-keyword`), e.g. `保留-IronFarm`.
2. On the next scan it wakes up and will not be paused again.

### 4. Finding out why a mob is (or is not) paused

1. Look at the mob (within 16 blocks; works through glass).
2. Run:

```bash
/tickb inspect
```

- If the evaluation differs from the current state, it is applied on the next scan (default: every 5 seconds).
- You can paste this output directly when reporting an issue.

### 5. Before removing the plugin

```bash
/tickb ai restore
```

- Loaded mobs are also restored automatically when the server shuts down. If `ai-pause.enabled` is turned off later, mobs in unloaded chunks are restored when their chunk loads.

## Configuration Highlights

| Key | Description |
|---|---|
| `inspect.min-op-level` / `max-distance` | OP level required for `/tickb inspect`, and the maximum look distance |
| `hotspots.min-op-level` | OP level required to open the hotspot GUI (`0` = no OP check) |
| `protection.name-keyword` | Mobs whose name contains this text are skipped by both the despawn fix and AI pausing |
| `despawn-fix.block-enderman-pickup` | Prevents Endermen from picking up blocks |
| `despawn-fix.worlds` / `ai-pause.worlds` | Worlds the feature applies to (empty list = all worlds) |
| `spike-detector.spike-threshold-ms` | A single tick longer than this (ms) counts as a spike |
| `spike-detector.reports.max-files` / `max-total-mb` | Maximum number and total size of saved reports |
| `spike-detector.stack-capture.threshold-ms` | Capture the main thread's stack once a tick runs longer than this (ms) |
| `spike-detector.freeze-diagnosis.enabled` | Turn process freeze diagnosis on or off (`jfr: false` uses system statistics only) |
| `load-shedding.levels` | Load-shedding tiers: MSPT threshold and the simulation/view distance to drop to |
| `ai-pause.crowd.pause-at` / `resume-at` | Same-type mob count to pause at and to resume at |
| `ai-pause.wake.villager-work-windows` | In-game time windows when villagers wake up to restock |
| `ai-pause.wake.villager-bedtime-windows` / `villager-bedtime-max-seconds` | Night-time windows when villagers that own a bed wake up to walk to it, and the maximum seconds per night |
| `ai-pause.wake.villager-threat-radius` | Range in blocks within which a threat wakes villagers so they can flee (`0` = off) |
| `ai-pause.hostile-target-radius` | Trapped hostiles stay active if a target is within this range (`0` = trapped hostiles are never paused) |
| `ai-pause.excluded-types` | Mob types that are never paused |
