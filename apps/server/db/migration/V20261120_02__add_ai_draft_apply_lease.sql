-- V20261120_02: AI 草稿应用租约（并发互斥 + 崩溃恢复 + 执行 fencing）
-- 状态机：pending → applying → applied；pending → discarded（均为数据库条件更新）。
--   draft_apply_token        执行归属令牌：apply 成功/失败释放/放弃都必须携带本令牌（fencing，
--                            旧执行者晚完成不能覆盖新执行者状态）；
--   draft_apply_started_at   租约起点：applying 超过 ai.draft.apply-stale-ms 才允许条件接管
--                            （未过期的执行记录不可被抢走）；
--   draft_apply_result       成功应用结果 JSON：同稿重试回放一致结果，不再执行业务。
-- 旧数据兼容：历史 applied/pending/discarded 行三列均为 NULL——applied 回退到通用成功响应，
-- pending 正常抢占；不存在历史 applying 行（旧实现从不写该状态）。
-- 幂等：ADD COLUMN IF NOT EXISTS 可重复重放。

ALTER TABLE ai_messages ADD COLUMN IF NOT EXISTS draft_apply_token TEXT;
ALTER TABLE ai_messages ADD COLUMN IF NOT EXISTS draft_apply_started_at TIMESTAMP;
ALTER TABLE ai_messages ADD COLUMN IF NOT EXISTS draft_apply_result TEXT;
