# AI 任务槽位计数时序断言修复提示词

请修复 `AiTaskManagerTest` 在全量回归中偶发失败的问题，并验证任务槽位、队列和执行记录都能可靠释放。

本次只涉及 **Java 后端及其自动化测试**，不处理鸿蒙端，也不要修改无关业务。请先核对当前实现，再完成修复和测试；不要只提供分析或示例代码。

## 一、已观察到的现象

后端全量回归曾出现：

```text
AiTaskManagerTest.slotsReleasedOnSuccessExceptionAndCancel:202
expected: <0> but was: <1>
```

当时总计 262 项测试，只有这一项失败；该测试单独复测以及第二轮全量回归均通过，属于可复现概率较低的时序波动，不能视为已经彻底修复。

重点文件：

- `apps/server/src/test/java/com/travelos/ai/AiTaskManagerTest.java`
- `apps/server/src/main/java/com/travelos/ai/AiTaskManager.java`

当前测试在成功任务的 `TaskHandle.await(2000)` 返回后，立即执行：

```java
assertEquals(0, m.activeCount());
```

而 `activeCount()` 当前直接返回：

```java
executor.getActiveCount()
```

`Future` 已完成与 `ThreadPoolExecutor` 内部 worker 活跃计数归零不是同一个对外同步语义。任务结果已经可被 `await` 获取时，worker 可能尚未完全退出本次 `runWorker` 执行段，因此立即断言 `getActiveCount() == 0` 存在时序窗口。

`AiTaskManager` 已有有界的 `awaitIdle(long timeoutMillis)`，会等待：

- `executor.getActiveCount() == 0`；
- 执行队列为空；
- `activeTasks` 为空。

生产代码的任务执行体已经在 `finally` 中运行幂等 `cleanup`，用于释放用户槽位并移除 `activeTasks`。本次应首先修正不正确的测试同步假设，不要为了迎合一个时序断言而随意改写正常的资源清理实现。

## 二、修复目标

### 1. 区分“任务结果完成”与“线程池 worker 完全空闲”

明确以下语义：

- `TaskHandle.await` 返回：任务结果或异常已经对调用者可见；
- `stats().tasks` / `activeTasks`：任务执行记录是否已清理；
- `stats().userSlots` / `inFlightUsers`：用户并发槽是否已释放；
- `executor.getActiveCount()`：线程池 worker 的瞬时观测值，不适合作为 Future 完成后的无条件即时断言。

测试应分别验证这些语义，不能把它们混为一个原子事件。

### 2. 修正测试等待方式，但不能放宽资源释放要求

在检查线程池完全空闲前，使用已有的 `awaitIdle` 或等效的有界、语义明确的同步机制。

修复后仍必须严格断言：

- 成功、业务异常、取消三条路径最终都没有遗留活跃 worker；
- 队列为空；
- `activeTasks` 为空；
- 用户槽位归零；
- 被拒绝或已结束的任务不能继续占用后续任务的并发额度；
- 清理逻辑保持幂等，不能出现重复释放或计数下溢。

不得使用以下方式掩盖问题：

- 删除或永久削弱 `activeCount`、队列、`activeTasks`、用户槽位断言；
- 添加随意的 `Thread.sleep`；
- 把断言改成允许残留 1 个任务或 1 个用户槽；
- 重复调用直到偶然成功后忽略前序失败；
- 直接跳过、禁用或缩小该测试；
- 只提高 `await` 超时时间，却仍不等待正确的空闲条件。

### 3. 保留对真实清理缺陷的识别能力

测试必须能够区分：

- worker 计数短暂尚未归零：有界等待后恢复，属于观测时序；
- `activeTasks` 或用户槽未释放：属于真实资源泄漏，必须失败；
- 队列残留或后续任务无法提交：属于真实生命周期缺陷，必须失败。

如果检查发现除测试时序外还存在真实清理缺陷，可以做最小生产修复，但必须说明具体触发路径，并补充能稳定复现该缺陷的测试；不得仅凭一次 `activeCount=1` 就推断资源泄漏。

### 4. 控制改动范围

本任务只处理 AI 任务生命周期测试及其必要的最小实现修正。不要改变：

- AI 消息发送 `clientRequestId` 幂等；
- 草稿应用幂等、租约和 fencing；
- 用户并发、全局限流、队列容量及 UTC 每日预算规则；
- 取消传播、任务超时和 SSE 行为；
- 无关业务模块。

除非确有必要，不建议为了本测试修改公开接口或重命名观测方法。

## 三、必须补充或调整的测试

至少覆盖：

1. **成功路径**：结果可获取后，执行记录和用户槽清理；有界等待后 worker 与队列归零。
2. **业务异常路径**：调用者能观察到原异常包装，且所有资源最终释放。
3. **取消路径**：阻塞执行体被中断或取消后，所有资源最终释放，后续任务可以正常提交。
4. **连续多任务**：连续成功、异常、取消混合执行，不发生计数累积或后续任务误拒绝。
5. **重复稳定性**：对相关场景进行足够次数的重复执行，证明不是一次偶然通过；测试本身应有明确总时限，不能变成压力死循环。
6. **清理幂等**：如已有重复 cleanup/取消竞争路径，保持并通过其计数不下溢断言。

优先使用现有 `awaitIdle`、latch、Future 和明确状态条件，不要引入随意睡眠。测试失败时应输出 `stats()` 等安全诊断信息，便于区分 worker 时序与真实泄漏；不得输出用户正文、Token 或密钥。

## 四、运行与验收

先在 `D:\myWork\NewTrevl\apps\server` 重复运行目标测试：

```bash
JAVA_HOME="C:/Users/15003/.workbuddy/binaries/java/jdk/jdk-17.0.20.1+1" "C:/Users/15003/.workbuddy/binaries/java/maven/apache-maven-3.9.11/bin/mvn.cmd" -B -Dtest=AiTaskManagerTest test
```

然后运行完整后端回归：

```bash
JAVA_HOME="C:/Users/15003/.workbuddy/binaries/java/jdk/jdk-17.0.20.1+1" "C:/Users/15003/.workbuddy/binaries/java/maven/apache-maven-3.9.11/bin/mvn.cmd" -B verify
```

如修改了异步共享逻辑，应适当增加目标测试的独立重复轮数，并至少再进行一次全量回归；报告实际执行轮数和结果，不要把“未再次碰巧失败”表述成已证明绝对无竞态。

完成后请报告：

- 根因是测试同步假设、观测语义，还是真实资源泄漏；
- 修改的文件和具体断言变化；
- 成功、异常、取消及混合场景的验证结果；
- 目标测试重复运行次数、全量回归测试数和失败/错误/跳过数；
- 是否仍保留任何已知的时序或资源生命周期风险。

测试结束后清理临时账号、会话、任务记录和临时脚本，不要遗留 Java/Maven 进程或测试服务。

## 五、完成标准

只有同时满足以下条件才算完成：

- 不再把 Future 完成错误地等同于 worker 计数即时归零；
- 成功、异常、取消路径的执行记录、用户槽、队列和 worker 都有严格最终断言；
- 没有通过 sleep、删除断言、允许残留或跳过测试来掩盖波动；
- 后续任务不会因前一任务的残留计数被误拒绝；
- 目标测试重复运行和完整后端回归均通过；
- 未破坏既有 AI 发送幂等、草稿应用幂等及资源防护逻辑。
