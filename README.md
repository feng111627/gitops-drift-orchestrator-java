# GitOps 漂移检测与同步编排服务（多环境）

在现有 Spring Boot 工程上实现的一套多环境配置漂移检测与同步编排服务。
用**本地文件**模拟每个环境的“期望状态”（desired，Git 中应有的声明）和
“实际/观测状态”（observed，运行环境里真正存在的东西），把三件事串起来：

1. **对账（Reconcile）**：逐项比较期望与实际，给出稳定结论；
2. **按波次同步（Sync）**：按依赖关系分波次推进，上一波不健康不进下一波，
   并发触发被拒绝，中途失败保留现场；
3. **渐进发布与回滚（Rollout/Rollback）**：带版本变更按比例放量，
   异常时回滚到上一稳定版本，回滚结论可与历史对账对齐。

所有状态落在本地目录（默认 `./state`，由 `app.state-dir` 配置），无需外部依赖。

---

## 1. 数据模型

- `ResourceId = type/name`：环境内资源唯一标识，如 `service/api`。
- 期望条目 `DesiredResource`：`id`、`version`、`spec`（任意 JSON 对象）、
  `dependsOn`（依赖资源列表）。
- 观测条目：直接读写原始 JSON，结构是否合法由对账器校验，存储层不做假设。

文件布局（以环境名 `prod` 为例）：

```
state/
  prod/
    desired.json                 期望状态
    observed.json                观测（实际）状态
    reports/rpt-<hash>.json      对账报告归档（内容寻址、只追加、不可变）
    sync-records/sync-<uuid>.json 每次同步执行记录
    releases/rel-<uuid>.json     每次发布记录
  snapshots/
    rel-<uuid>-observed.json     发布前观测快照（回滚目标）
    rel-<uuid>-desired.json      发布前期望快照
```

## 2. 对账规则

对账接口读取期望与观测后逐项产出 `ReconcileItem(status, detail)`：

| 状态 | 含义 |
|---|---|
| `SYNCED` | 期望与实际都存在，`version` 与 `spec` 完全一致 |
| `DRIFTED` | 都存在但有差异，`detail` 指出具体差异（版本或点路径字段） |
| `MISSING` | 期望有、实际没有 |
| `EXTRA` | 实际多出、期望未声明 |
| `INVALID` | 观测条目本身坏掉 |

**坏条目不跳过**。下列情况都会标 `INVALID` 并在 `detail` 里写清“第几条、哪个字段、什么问题”：

- 缺 `type`/`name`，或它们不是字符串、为空白；
- 缺 `spec`，或 `spec` 不是 JSON 对象（例如是字符串/数字）；
- `version` 存在但不是字符串；
- 同一 `type/name` 在观测中重复出现。

`INVALID` 条目不影响其他条目的正常对账（可以与 SYNCED/DRIFTED 同时出现在一份报告里）。

### 稳定性（重复执行结论一致）

- 条目按 `ResourceId` 排序；嵌套 `spec` 的字段比较按键排序，首个差异路径稳定。
- `reportId = rpt-` + SHA-256(`环境 + 全部条目状态与说明`) 的前 16 位，
  **不含时间戳**。
- 报告归档幂等：同内容报告只存一份，沿用首次生成时间。
- 因此同一份输入重复对账任意次，`reportId`、条目、时间戳完全一致。

## 3. 同步：波次与健康检查约定

`DependencyPlanner` 用 Kahn 分层把资源切成波次：

- 第 0 波：无依赖资源；资源只依赖已经落入更早波次的资源时才进入当前波。
- 同一波内按 id 排序，结论对声明顺序不敏感。

`SyncOrchestrator` 执行约定：

1. **先校验后动手**：依赖指向未声明资源、资源依赖自己、依赖成环、
   期望中 id 重复，都判 `VALIDATION_FAILED`，不应用任何资源、不改观测状态。
2. **逐波推进**：对波内每个资源先“应用”（把期望条目写入观测文件，模拟下发），
   再做健康检查；**本波全部健康才允许进入下一波**。
3. **失败即停、保留现场**：任一资源失败，未开始的波绝不动；
   已应用的资源和已落盘的观测状态原样保留。`SyncRecord` 记录
   `totalWaves / completedWaves / appliedResources`，事后再跑一次对账即可说清现场。
4. **失败原因明确区分**：
   - `VALIDATION_FAILED`：期望状态校验没过（应用前）；
   - `HEALTH_CHECK_TIMEOUT`：在超时时间内健康检查未通过（默认超时 `app.health-timeout-millis=2000ms`）；
   - `DEPENDENCY_NOT_READY`：资源报告其上游依赖尚未就绪；
   - `CONCURRENT_SYNC`：并发触发被拒绝。
5. **并发策略——拒绝（不排队、不交叉）**：每个环境一把 `ReentrantLock`，
   `tryLock` 失败立即抛 `ConcurrentSyncException`（HTTP 409），并留下一条
   `REJECTED` 记录。不同环境互不阻塞。

同步结束后自动再跑一次对账并打印报告计数，保证“现在现场是什么样”随时有结论可查。

## 4. 渐进发布与回滚语义

`RolloutService`：

- `startRollout(env, resource, version, desired, percentage)`
  - 先把当前**观测与期望分别整体快照**，再写入新版本期望；
  - 首次比例必须 1–99，发布状态为 `ROLLING_OUT`，记录上一稳定版本。
- `advance(env, releaseId, percentage)`：比例只能增加；到 100 变为 `STABLE`。
- `rollback(env, releaseId)`：
  - **仅允许对 `ROLLING_OUT` 的发布执行**（`STABLE`/`ROLLED_BACK` 都拒绝）；
  - 恢复发布前的观测快照**和期望快照**，状态置 `ROLLED_BACK`，再跑一次对账；
  - 若期望未再被改动，回滚后对账的 `reportId` 与发布前某次历史对账**完全相同**，
    该基线 id 记录在 `ReleaseRecord.rollbackBaselineReportId` 上。

**拒绝重复变更：**

- 已 `ROLLED_BACK` 的发布再次回滚 → 拒绝（“已经回滚过”），不产生第二次变更；
- 已回滚的发布再 `advance` → 拒绝；
- 对**已经回滚过的版本号**（同环境同资源）重新 `startRollout` → 拒绝；
- 比例回退、超过 100、目标资源不在期望中 → 拒绝。

## 5. HTTP 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/envs/{env}/reconcile` | 触发对账，返回报告 |
| GET | `/api/envs/{env}/reports` | 历史对账报告列表 |
| PUT | `/api/envs/{env}/desired` | 写入期望状态（JSON 数组） |
| GET | `/api/envs/{env}/desired`、`/observed` | 查看状态原文 |
| POST | `/api/envs/{env}/sync` | 触发按波次同步；并发触发返回 `409 CONCURRENT_SYNC` |
| GET | `/api/envs/{env}/sync-records` | 同步执行记录 |
| POST | `/api/envs/{env}/releases` | 开始放量（body: resource/version/desired/percentage） |
| POST | `/api/envs/{env}/releases/{id}/advance` | 推进放量（body: `{"percentage":50}`） |
| POST | `/api/envs/{env}/releases/{id}/rollback` | 回滚，返回基线对账报告 |

## 6. 如何验证

```bash
mvn test
```

测试共 31 个用例，按业务分四个测试类；运行日志通过 `BusinessTestLogger`
（`TestExecutionListener` SPI）以统一格式输出，可直接看出覆盖了哪些业务、
每个用例名称与通过/失败结果：

```
INFO BUSINESS-TEST -- 【业务用例 ReconcilerTests.1】...
INFO BUSINESS-TEST -- 【用例结果】... => 通过 ✓
```

覆盖的业务场景：

- `ReconcilerTests`（11）：全量 SYNCED、字段/版本漂移、MISSING、EXTRA、
  坏条目（缺字段、spec 类型错、version 类型错）不跳过、好坏混合、
  重复执行报告 id/时间戳稳定、排序稳定。
- `SyncOrchestratorTests`（8）：两波依赖顺序、未知依赖与成环校验失败、
  健康检查超时阻断后续波次且保留现场、依赖未就绪分类、失败后重试收敛、
  同环境并发恰好一份成功一份 REJECTED、不同环境互不阻塞。
- `RolloutServiceTests`（8）：10%→50%→100% 放量、比例不可回退、
  回滚恢复现场且报告 id 与发布前历史对齐、重复回滚被拒且无重复变更、
  已回滚版本不可再发、STABLE 不可回滚、环境间隔离。
- `ApiIntegrationTests`（3）：端到端写入期望→同步→对账（id 稳定）、
  同步中再次触发返回 409、坏观测经接口标 INVALID。

也可以启动服务后手动验证：

```bash
mvn spring-boot:run
curl -X PUT localhost:$PORT/api/envs/demo/desired \
  -H 'Content-Type: application/json' \
  -d '[{"id":{"type":"stateful","name":"db"},"version":"v1","spec":{"disk":"10Gi"},"dependsOn":[]},
       {"id":{"type":"service","name":"api"},"version":"v1","spec":{"replicas":2},
        "dependsOn":[{"type":"stateful","name":"db"}]}]'
curl -X POST localhost:$PORT/api/envs/demo/sync
curl localhost:$PORT/api/envs/demo/reconcile
```
