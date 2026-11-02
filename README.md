# TickBlade

找出伺服器卡頓的來源、修正越積越多的生物、MSPT 過高時自動降載，並暫停擠在一起或被關住的生物 AI，同時保留交易、補貨與剪羊毛等原版機制。

支援 Paper 1.21.11、26.1.x～26.3（Purpur 等 Paper 分支理論上相容；不支援 Spigot、Folia）。1.21.11 使用 Java 21，26.x 伺服器需要 Java 25。

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
- **熱點區塊介面**：
  - 箱子介面列出區塊的實體、方塊實體、漏斗數量，顯示最多的生物種類（以玩家的遊戲語言顯示）
  - 分類（實體／方塊實體／漏斗）與排序（多到少／少到多／離我最近）分開切換
  - 左鍵點一下就傳送過去；開介面時才掃描，掃描分散到多個 tick
- **自動降載**：
  - 依最近幾秒的平均 MSPT 分級，降低每位玩家的模擬距離與視距
  - 負載恢復後一級一級還原成原本的距離，避免來回跳動
- **擁擠／被關住的生物暫停 AI**：
  - 1.5 格內同類生物 ≥ 5 隻，或前後左右都走不過去時暫停；降到 3 隻以下且周圍有空間時恢復（都可設定）
  - 敵對生物（殭屍、骷髏、史萊姆等）被困住時，16 格內有可攻擊的目標（生存／冒險模式玩家、村民、鐵傀儡）就保持清醒、照常攻擊，沒有目標才暫停；擠在一起時一律暫停
  - 需要 AI 時暫時喚醒，計算交給原版：村民在白天工作時段補貨、交易後升級；被剪過毛的羊吃草長回羊毛；進入繁殖狀態的動物；被任何生物攻擊時
  - 沒被關住的村民，附近 12 格內有殭屍、掠奪者等威脅時會醒來逃跑
  - 暫停期間村民照常可以交易；右鍵點擊被暫停的村民會喚醒 10 秒
  - 沒有職業的村民：能走動的不暫停（才會去找工作站）；被困住的會暫停，附近放下工作站方塊時喚醒 60 秒去認領職業
  - 自動辨識鐵傀儡農場（被關住的村民附近有殭屍類生物就不暫停）
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
| `/tickb ai restore` | 恢復所有被暫停的生物 AI | 玩家、主控台 | `tickblade.ai` | OP |
| `/tickb reload` | 重新載入 `config.yml` 與 `messages.yml` | 玩家、主控台 | `tickblade.reload` | OP |
| `/tickb version` | 顯示版本並檢查更新 | 玩家、主控台 | `tickblade.version` | OP |
| （通知） | 收到自動錄製、報告存檔、降載等級變化的通知 | 玩家 | `tickblade.notify` | OP |

> `tickblade.admin` 包含以上全部權限。
>
> 熱點介面可以傳送到任何區塊，所以除了權限，還需要 `ops.json` 中的 OP 等級達到 `hotspots.min-op-level`（預設 2；設 0 = 只看權限）。
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

- 每次錄製長度、只記錄超過幾毫秒的 tick、冷卻時間都可以在 `spike-detector.auto-profile` 設定
- 需要 spark（Paper 內建）；`paper-global.yml` 的 `spark.enabled` 必須是 `true`

### 2. 找到生物太多的地方

```bash
/tickb hotspots
```

- 左鍵點擊「分類」按鈕切換要看的數量：實體 → 方塊實體 → 漏斗
- 左鍵點擊「排序」按鈕切換順序：數量多到少 → 數量少到多 → 離我最近（同世界依距離，其他世界排在後面）
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
| `load-shedding.levels` | 降載等級：MSPT 門檻與要降到的模擬距離／視距 |
| `ai-pause.crowd.pause-at` / `resume-at` | 暫停與恢復的同類生物數量 |
| `ai-pause.wake.villager-work-windows` | 村民補貨的喚醒時段（遊戲時間） |
| `ai-pause.wake.villager-threat-radius` | 附近幾格內有威脅時喚醒村民（0 = 關閉） |
| `ai-pause.hostile-target-radius` | 被困住的敵對生物，幾格內有可攻擊目標就不暫停（0 = 被困住的敵對生物一律不暫停） |
| `ai-pause.excluded-types` | 永遠不暫停的生物種類 |


Identify sources of server lag, fix accumulating non-despawning mobs, automatically shed load when MSPT spikes, and pause the AI of crowded or trapped entities—all while preserving vanilla mechanics such as villager trading, restocking, and sheep shearing.

Compatible with Paper 1.21.11 and 26.1.x–26.3 (Paper forks such as Purpur are theoretically compatible; Spigot and Folia are not supported). Paper 1.21.11 requires Java 21; 26.x servers require Java 25.

---

## Features

- **Despawn Issue Resolution**:
  - In vanilla Minecraft, Endermen carrying blocks and Zombies or Skeletons that pick up items/armor become persistent and will never despawn, leading to unbounded accumulation over time.
  - Prevents Endermen from picking up blocks (placing blocks is unaffected; configurable per world).
  - During chunk loading and periodic scans, freely roaming mobs of this type drop their held items/blocks on the spot and regain normal despawn eligibility. Dropped items cannot be picked up again by other monsters.
  - Trapped mobs, entities riding boats/minecarts, named mobs, leashed mobs, raid participants, Zombie Villagers, and custom mobs created by other plugins are never modified.

- **Lag Spike Detection & Offline spark Profiling**:
  - Monitors individual tick durations. When the tick time repeatedly exceeds a threshold (default: 500 ms) within a short window, an automated spark profiler captures long ticks exclusively.
  - Profiler dumps are stored directly as `.sparkprofile` files in `plugins/TickBlade/reports/`, eliminating expired web link issues. Enforces strict file count and disk usage limits.
  - Every detected spike is logged to `spikes.log` with automatic file size rotation.

- **Hotspot Chunk GUI**:
  - Chest GUI displays chunk statistics: entity counts, block entity counts, hopper counts, and the most prevalent mob type (localized to each player's client language).
  - Independent toggles for Metric (Entities / Block Entities / Hoppers) and Sorting (Highest / Lowest / Nearest).
  - Left-click to teleport directly to the hotspot location. Scans occur strictly on-demand when opening the GUI and are distributed across multiple ticks to avoid frame drops.

- **Dynamic Load Shedding**:
  - Tracks running average MSPT over recent seconds and adjusts per-player simulation distance and view distance across progressive tiers.
  - Once server performance stabilizes, distances are restored gradually step-by-step to prevent oscillation.

- **Smart AI Pausing for Crowded & Trapped Mobs**:
  - Automatically pauses mob AI when $\ge 5$ mobs of the same type congregate within a $1.5$-block radius, or when a mob is completely boxed in and unable to pathfind in any direction. Resumes when the crowd drops to $\le 3$ and space becomes available (thresholds configurable).
  - **Hostile Mob Handling**: Trapped hostiles (Zombies, Skeletons, Slimes, etc.) stay active if a viable target (Survival/Adventure player, Villager, Iron Golem) is within 16 blocks; they only pause if no target is in range. Crowded hostiles always pause.
  - **Dynamic Awakening for Vanilla Mechanics**: Wakes up entities temporarily when computational AI is required, returning logic to the vanilla engine:
    - Villagers during daytime work/restock hours and during trade-level progression.
    - Sheared sheep grazing to regrow wool.
    - Animals entering breeding/love mode.
    - Any entity taking damage from attacks.
  - Unconfined villagers wake up and flee if a threat (Zombie, Pillager, etc.) enters a 12-block radius.
  - Villagers remain interactive and tradable while paused; right-clicking a paused villager temporarily wakes it for 10 seconds.
  - **Unemployed Villagers**: Free-roaming unemployed villagers remain unpaused to pathfind toward job site blocks. Confined ones pause, but wake up for 60 seconds whenever a workstation is placed nearby.
  - **Iron Golem Farm Detection**: Automatically recognizes iron golem farm setups (confined villagers near hostile mobs are not paused).
  - Mobs with custom names containing the keyword `保留` (configurable, e.g., "Keep") are excluded from AI pausing.
  - Paused states are saved persistently on the entity and cleanly restored upon plugin disable, server shutdown, or running `/tickb ai restore`.

- **Targeted Entity Inspector**:
  - Target any mob (works through glass and fences) and run `/tickb inspect` to view its real-time evaluation: AI pause status, reasons, local crowd count, and containment status.
  - Displays wake triggers and bypass reasons (e.g., active work window, nearby threat, golem farm setup, name exemption, third-party plugin entity).
  - Shows despawn eligibility evaluation and highlights which exact rule bypassed or flagged the entity.
  - Non-destructive and read-only; logic mirrors background tick scans identically.

- **Additional System Features**:
  - Fully customizable text in `messages.yml` (Traditional Chinese by default).
  - Missing keys in `config.yml` and `messages.yml` are automatically appended on startup and reload while preserving existing user values.
  - Asynchronous GitHub update checker on startup; alerts administrators 3 seconds after joining if a newer release is found.

---

## Commands & Permissions

Base command: `/tickblade` (Alias: `/tickb`).

| Command | Description | Sender | Permission Node | Default |
|---|---|---|---|---|
| `/tickb help` | Lists all commands available to the sender | Player, Console | `tickblade.help` | OP |
| `/tickb status` | Displays average MSPT, shedding level, paused AI counts, and spike metrics | Player, Console | `tickblade.status` | OP |
| `/tickb hotspots` | Opens the Hotspot Chunk GUI (requires OP level 2+) | Player | `tickblade.hotspots` | OP |
| `/tickb inspect` | Inspects the target entity under crosshairs (requires OP level 2+) | Player | `tickblade.inspect` | OP |
| `/tickb reports` | Lists saved spark profile reports | Player, Console | `tickblade.reports` | OP |
| `/tickb profile` | Manually captures a spark report filtering for long ticks | Player, Console | `tickblade.profile` | OP |
| `/tickb cleanup` | Forces an immediate scan on all currently loaded monsters | Player, Console | `tickblade.cleanup` | OP |
| `/tickb ai restore` | Restores AI to all currently paused entities | Player, Console | `tickblade.ai` | OP |
| `/tickb reload` | Reloads `config.yml` and `messages.yml` | Player, Console | `tickblade.reload` | OP |
| `/tickb version` | Displays plugin version and checks for updates | Player, Console | `tickblade.version` | OP |
| *(Notification)* | Receives alerts for auto-profiling, report generation, and load-shedding shifts | Player | `tickblade.notify` | OP |

> **Notes**:
> - `tickblade.admin` grants all permissions listed above.
> - Because `/tickb hotspots` allows teleportation across any chunk, it requires both the permission node and an OP permission level meeting `hotspots.min-op-level` (default: 2; set to `0` to check permissions only).
> - Players with `tickblade.version` receive an update notification 3 seconds after logging in if an update is available.

---

## Usage Guide

### 1. Diagnosing Lag Spikes

1. Under default settings, TickBlade automatically starts profiling whenever tick times hit the spike threshold repeatedly.
2. Alternatively, capture a profile manually during a lag event:

```bash
/tickb profile
```

3. Run `/tickb reports` to locate the generated filename, then retrieve it from `plugins/TickBlade/reports/`.
4. Drag and drop the `.sparkprofile` file into [spark.lucko.me](https://spark.lucko.me) to analyze thread activity.

- Capture duration, minimum tick duration filter, and cooldown intervals are configurable under `spike-detector.auto-profile`.
- Requires spark (bundled with Paper); ensure `spark.enabled: true` is set in `paper-global.yml`.

### 2. Locating Entity & Tile Congestion

```bash
/tickb hotspots
```

- **Metric Toggle**: Left-click to switch between `Entities` $\rightarrow$ `Block Entities` $\rightarrow$ `Hoppers`.
- **Sorting Toggle**: Left-click to switch between `Highest Count` $\rightarrow$ `Lowest Count` $\rightarrow$ `Nearest` (sorts by Euclidean distance in the current world, followed by other dimensions).
- Filters can be combined arbitrarily and persist across manual re-scans.
- Left-click any chunk entry to teleport directly to the densest entity cluster within that chunk.

### 3. Exempting Specific Entities from AI Pausing

1. Use a Name Tag to name the entity with a title containing the keyword `保留` (or your custom keyword configured in `protection.name-keyword`, e.g., `保留-IronFarm`).
2. The entity will resume full AI activity on the subsequent scan cycle and will be ignored by future pause sweeps.

### 4. Troubleshooting Entity States

1. Target the mob with your crosshairs (within 16 blocks; works through transparent blocks like glass).
2. Execute:

```bash
/tickb inspect
```

- If the diagnostic verdict differs from the current live state, it will reconcile on the next scheduled scan cycle (default: every 5 seconds).
- This inspection block can be copied and pasted directly when submitting issue reports.

### 5. Safe Plugin Uninstallation

```bash
/tickb ai restore
```

- Loaded entities are automatically restored upon graceful server shutdown. If `ai-pause.enabled` is toggled off later, entities residing in unloaded chunks will safely restore their AI as their chunks load back into memory.

---

## Configuration Reference

| Key | Description |
|---|---|
| `inspect.min-op-level` / `max-distance` | Minimum OP level required for `/tickb inspect` and maximum raycast distance |
| `hotspots.min-op-level` | Minimum OP level required to open the Hotspot GUI (`0` to disable OP check) |
| `protection.name-keyword` | Substring keyword in custom names that exempts mobs from despawn fixes and AI pausing |
| `despawn-fix.block-enderman-pickup` | Disables Endermen from picking up world blocks |
| `despawn-fix.worlds` / `ai-pause.worlds` | Target worlds where features apply (empty list `[]` applies to all worlds) |
| `spike-detector.spike-threshold-ms` | Minimum tick duration (in milliseconds) to register as a lag spike |
| `spike-detector.reports.max-files` / `max-total-mb` | File count and cumulative disk space limit for local report archives |
| `load-shedding.levels` | Dynamic tiers defining MSPT triggers and target simulation/view distances |
| `ai-pause.crowd.pause-at` / `resume-at` | Same-type mob count threshold required to pause and subsequently resume AI |
| `ai-pause.wake.villager-work-windows` | In-game time windows (ticks) during which villagers wake up to restock trades |
| `ai-pause.wake.villager-threat-radius` | Radial distance in blocks to detect hostiles and wake villagers to flee (`0` to disable) |
| `ai-pause.hostile-target-radius` | Trapped hostiles remain active if a target is within this range (`0` to keep trapped hostiles always active) |
| `ai-pause.excluded-types` | Explicit list of entity types permanently exempt from AI pausing |
