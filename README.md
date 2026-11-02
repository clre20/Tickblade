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
  - 箱子介面列出實體、方塊實體、漏斗最多的區塊，顯示最多的生物種類（以玩家的遊戲語言顯示）
  - 左鍵點一下就傳送過去；開介面時才掃描，掃描分散到多個 tick
- **自動降載**：
  - 依最近幾秒的平均 MSPT 分級，降低每位玩家的模擬距離與視距
  - 負載恢復後一級一級還原成原本的距離，避免來回跳動
- **擁擠／被關住的生物暫停 AI**：
  - 1.5 格內同類生物 ≥ 5 隻，或前後左右都走不過去時暫停；降到 3 隻以下且周圍有空間時恢復（都可設定）
  - 需要 AI 時暫時喚醒，計算交給原版：村民在白天工作時段補貨、交易後升級；被剪過毛的羊吃草長回羊毛；進入繁殖狀態的動物；被玩家攻擊時
  - 暫停期間村民照常可以交易；沒有職業的村民不暫停（才能找到新的工作站）
  - 自動辨識鐵傀儡農場（被關住的村民附近有殭屍類生物就不暫停）
  - 生物名字含有「保留」（可設定）就不會被暫停
  - 暫停狀態存在生物身上，關閉功能、關服或執行 `/tickb ai restore` 都會完整恢復
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

- 左鍵點擊下方按鈕切換排序：實體 → 方塊實體 → 漏斗
- 左鍵點擊區塊即可傳送到該區塊數量最多的生物所在位置

### 3. 讓某隻生物不要被暫停 AI

1. 用命名牌把生物取名為含有「保留」的名字，例如「保留-鐵傀儡農場」
2. 下一次掃描時就會恢復並不再暫停

### 4. 移除插件前

```bash
/tickb ai restore
```

- 關服時也會自動恢復已載入的生物；之後若關閉 `ai-pause.enabled`，未載入區塊裡的生物會在區塊載入時恢復

## 設定重點

| 設定 | 說明 |
|---|---|
| `hotspots.min-op-level` | 開啟熱點介面需要的 OP 等級（0 = 不檢查） |
| `protection.name-keyword` | 名字含有這段文字的生物，消失判斷與暫停 AI 都不處理 |
| `despawn-fix.block-enderman-pickup` | 阻止終界使者撿方塊 |
| `despawn-fix.worlds` / `ai-pause.worlds` | 套用的世界，空清單 = 全部世界 |
| `spike-detector.spike-threshold-ms` | 單一 tick 超過幾毫秒算一次卡頓 |
| `spike-detector.reports.max-files` / `max-total-mb` | 報告數量與總容量上限 |
| `load-shedding.levels` | 降載等級：MSPT 門檻與要降到的模擬距離／視距 |
| `ai-pause.crowd.pause-at` / `resume-at` | 暫停與恢復的同類生物數量 |
| `ai-pause.wake.villager-work-windows` | 村民補貨的喚醒時段（遊戲時間） |
| `ai-pause.excluded-types` | 永遠不暫停的生物種類 |

## 可自訂的訊息鍵

| 鍵 | 說明 |
|---|---|
| `common.*` | 權限不足、重載結果等共用訊息 |
| `help.*` | 指令說明 |
| `version.*` | 版本檢查結果與登入提醒 |
| `status.*` | `/tickb status` 的每一行 |
| `spike.*` | 自動錄製、報告存檔、錄製失敗 |
| `reports.*` | `/tickb reports` 列表 |
| `shedding.*` | 降載等級變化通知 |
| `hotspots.*` | 熱點區塊介面的標題、物品名稱與說明 |
| `console.*` | 後台紀錄 |
