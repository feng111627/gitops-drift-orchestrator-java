# GitOps 多环境配置漂移检测与同步编排服务

在现有 Spring Boot 骨架上扩展的一套服务：用**本地文件**模拟各环境的“期望状态（desired）”
与“实际状态（observed）”，把三件事串成闭环——

1. **对账（Reconcile）**：逐项给出 已同步 / 漂移 / 缺失 / 多余 / 无效 结论，结论确定可重复；
2. **同步（Sync）**：按资源依赖划分波次，逐波 apply + 健康门禁，并发互斥，失败中断且保留已应用部分；
3. **渐进发布（Rollout）**：带版本变更按百分比放量，异常可回滚到上一个稳定版本，回滚结论与历史对账快照对得上。

## 1. 目录与运行

```bash
mvn spring-boot:run          # 默认数据根目录 ./data（drift.data-root 可改）
mvn test                     # 全部测试，日志同时落盘 target/test-logs/tests.log
```

存储布局（每个环境一个目录，全部是本地文件）：

```
<data-root>/<env>/
  desired.json                 期望状态：JSON 数组，元素是资源描述
  observed.jsonl               实际状态：JSONL，每行一条观测（允许坏行）
  sync.lock                    同步互斥锁（持有期间存在）
  snapshots/snap-000001.json   历史对账快照
  rollouts/<resourceId>.json   渐进发布记录
  syncs/<executionId>.json     同步执行记录
```

资源描述字段：`id`（必填，环境内唯一）、`type`、`version`、`desiredValue`、`dependsOn`（依赖的 id 列表）。
观测条目字段：`id`（必填字符串）、`type`、`version`、`observedValue`。

## 2. 对账规则

`POST /api/envs/{env}/reconcile[?snapshot=true]`

逐项结论（`ItemStatus`）：

| 结论 | 判定 |
| --- | --- |
| `SYNCED` | 期望与实际都存在，且 `type` / `version` / `desiredValue` 全等 |
| `DRIFTED` | 都存在，但版本、类型或内容至少一项不一致，detail 会逐项列出差异 |
| `MISSING` | 期望声明、实际缺失 |
| `EXTRA` | 实际存在、期望未声明 |
| `INVALID` | 观测条目本身坏掉，**不跳过**，单独标出并注明行号与问题 |

坏数据会被明确诊断，例如：

- 字段缺失：`缺少字段: id`（定位 `observed.jsonl:L5`）
- 类型错误：`字段 version 必须是字符串，实际类型=number`（定位 `L6`）
- 格式损坏：`JSON 格式损坏，无法解析: ...`（定位 `L4`）
- 同一 id 重复出现：`资源 id 重复，至少出现于第 2 行和第 7 行，无法唯一对账`

**确定性（重复执行结论稳定）**：

- 报告只取决于输入内容，不含时间戳/随机数；同一份输入跑任意次，逐项结论与顺序完全一致；
- 结果按资源 id（小写）、状态顺序、来源定位稳定排序；
- 期望状态是权威输入：它本身 JSON 损坏、根不是数组、`id` 缺失/类型错、`dependsOn` 非字符串数组时，
  直接以 `VALIDATION_FAILED` 失败（而不是产出半份报告）。

`snapshot=true` 会额外把这次结论存为 `snapshots/snap-N.json`，供后续回滚对齐。

## 3. 波次与健康检查约定

`POST /api/envs/{env}/sync`

### 3.1 波次划分

对 `dependsOn` 做拓扑分层（Kahn）：

- 第 0 波：没有依赖的资源；第 N 波：其依赖全部落在更早波次；
- 依赖了**不存在**的资源：`DEPENDENCY_NOT_READY`，在**任何变更之前**失败；
- 依赖成环：同样 `DEPENDENCY_NOT_READY`，失败信息列出涉及的资源。

### 3.2 健康门禁

每一波先 apply（把该波资源的期望版本/内容写入实际状态，其余观测行与坏行原样保留），
随后调用健康检查。默认实现 `ReconcileBasedHealthChecker`：**重新对账，本波资源全部 `SYNCED`
才算健康**；在 `drift.health.timeout-ms`（默认 500ms）内按
`drift.health.poll-interval-ms`（默认 50ms）轮询。

- 超时未通过：`HEALTH_TIMEOUT`，立即中断；
- 上一波没过健康检查，**绝不进入下一波**，后续波次在执行记录里标 `SKIPPED`（未应用）。

### 3.3 失败中断与现场保留

- 执行记录 `syncs/<executionId>.json` 逐波写明 `APPLIED / HEALTH_PASSED / FAILED / SKIPPED`；
- 中途失败时，**已 apply 且通过健康检查的波次保留**在实际状态中，不回滚、不假装成功；
- 失败原因用 `FailureReason` 区分：`VALIDATION_FAILED`（校验/输入）、
  `HEALTH_TIMEOUT`（健康超时）、`DEPENDENCY_NOT_READY`（依赖未就绪）、
  `CONCURRENT_SYNC_REJECTED`（并发拒绝）、`INVALID_INPUT`。

### 3.4 并发互斥

同一环境同一时刻只允许一个同步，锁 = 锁文件 `CREATE_NEW` + OS `FileLock`（同机跨进程也互斥）。
第二个触发**立即被明确拒绝**（不排队、不交叉执行），HTTP 返回 `409 CONCURRENT_SYNC_REJECTED`。
同步正常结束或失败都会释放锁，因此失败后可以重新触发。

## 4. 渐进发布与回滚语义

实际状态用加权分布文本模拟多版本并存：`observedValue = "v2@10|v1@90"`
（新版本 10%、稳定版本 90%）。期望状态始终指向“当前稳定版本”。

- `POST /api/envs/{env}/rollouts/{resourceId}/start`，body `{"newVersion":"v2","percentage":10}`
  记录稳定版本与轨迹，只改实际状态权重；在途期间对账为 `DRIFTED`（表示发布在途）。
- `.../promote`，body `{"percentage":50}`：百分比必须**严格递增**；
  到 100% 时 `COMPLETED`，并把期望状态的目标版本切到新版本，此后该资源重新 `SYNCED`。
- `.../rollback`，body `{"snapshotId":"snap-000001"}`：
  - **只能发生在完成之前**（100% 完成意味着新版本已成为稳定事实，不可回滚）；
  - 把实际状态恢复为发布开始时记录的上一个稳定版本（期望在发布期间从未被切换）；
  - 立即重新对账，并要求结论与指定历史快照**逐项一致**（逐资源 `(id, status)` 与各计数相同；
    detail/location 文本不参与），一致才标记 `ROLLED_BACK`；对不上则不确认回滚并报错，保留现场；
  - 幂等/拒绝：已回滚的发布**重复回滚**、对已回滚版本**再放量/重新开始**、
    百分比不递增，一律拒绝且**不产生第二次变更**。

## 5. HTTP 接口速查

| 方法 路径 | 说明 | 失败码 |
| --- | --- | --- |
| POST /api/envs/{env}/reconcile?snapshot=true | 对账（可选落快照） | 400 VALIDATION_FAILED |
| POST /api/envs/{env}/sync | 按波次同步 | 409 并发拒绝；执行内部失败返回 200 + state=FAILED |
| POST /api/envs/{env}/rollouts/{id}/start | 开始放量 | 400 |
| POST /api/envs/{env}/rollouts/{id}/promote | 递增放量 | 400 |
| POST /api/envs/{env}/rollouts/{id}/rollback | 回滚并对齐快照 | 400 |

## 6. 如何验证

```bash
mvn test
# 业务覆盖与每个用例的输入/结论同时打印在控制台，并存入：
cat target/test-logs/tests.log
```

测试按业务分为四组（共 19 个用例）：

- `ReconcileServiceTest`（业务一·对账）
  - 1.1 完全一致全部 SYNCED、clean
  - 1.2 漂移/缺失/多余并存，逐项状态与计数正确
  - 1.3 字段缺失/类型错误/JSON 损坏分别 INVALID 且可定位行号
  - 1.4 重复 id 标记 INVALID
  - 1.5 同一份输入连续对账三次，逐项结论与顺序完全一致
- `SyncOrchestratorTest`（业务二·同步）
  - 2.1 依赖决定波次（db→app），顺序成功后对账 clean
  - 2.2 依赖不存在：变更前 DEPENDENCY_NOT_READY，实际状态不变
  - 2.3 健康超时：停在失败波、后续 SKIPPED、已应用波次保留
  - 2.4 并发触发：第二个被 CONCURRENT_SYNC_REJECTED，不交叉执行
  - 2.5 失败后重跑：锁已释放，第二次成功
- `RolloutServiceTest`（业务三·发布/回滚）
  - 3.1 10→50→100 渐进放量，在途 DRIFTED、完成后 SYNCED
  - 3.2 百分比非严格递增被拒绝且记录无变更
  - 3.3 回滚恢复稳定版本，结论与发布前历史快照逐项对得上
  - 3.4 已回滚版本重复回滚/再放量/重开均被拒绝，实际状态字节级不变
  - 3.5 已 100% 完成的发布不能回滚
- `DriftApiIntegrationTest`（业务四·HTTP 端到端）
  - 4.1 对账→波次同步→再对账 clean 的完整链路
  - 4.2 快照→放量→回滚对齐快照→重复回滚 400
  - 4.3 非法百分比返回 400 VALIDATION_FAILED

手动验证（服务启动后，示例环境 staging 故意带漂移、坏数据、多余资源）：

```bash
curl -s -X POST localhost:8080/api/envs/staging/reconcile | jq
curl -s -X POST localhost:8080/api/envs/staging/sync | jq
curl -s -X POST 'localhost:8080/api/envs/staging/reconcile?snapshot=true' | jq
```

## 7. 关键类

- `model/`：`ItemStatus`、`FailureReason`、`SyncState/WaveState/RolloutPhase` 及各记录类型
- `store/`：`StateStore` 抽象与 `FileStateStore`（原子写、锁、快照）
- `reconcile/ReconcileService`：期望解析、坏数据诊断、确定性对账、快照
- `sync/`：`SyncOrchestrator`（波次/门禁/互斥/中断）、`HealthChecker` 及基于对账的默认实现
- `rollout/RolloutService`：比例放量、版本切换、回滚对齐与拒绝语义
- `web/DriftController`：REST 入口与错误码映射
