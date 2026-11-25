package com.travelos.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelos.entity.AiMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface AiMessageMapper extends BaseMapper<AiMessage> {

  @Update("UPDATE ai_messages SET tool_calls = CAST(#{draft} AS jsonb), content = #{summary} "
      + "WHERE id = #{id} AND conversation_id = #{conversationId} AND user_id = #{userId} "
      + "AND draft_status = 'pending' AND tool_calls ->> 'type' = 'receipt_review'")
  int confirmReceipt(@Param("id") String id, @Param("conversationId") String conversationId,
      @Param("userId") String userId, @Param("draft") String draft, @Param("summary") String summary);

  /**
   * 草稿应用原子抢占：pending → applying（写入执行令牌与租约起点）。
   * 仅当记录仍为 pending 时影响行数 = 1——并发 apply/discard/接管中只有一个赢家。
   * 必须在调用任何行程/费用/预订业务 Service 之前执行。
   */
  @Update("UPDATE ai_messages SET draft_status = 'applying', "
      + "draft_apply_token = #{token}, draft_apply_started_at = #{now} "
      + "WHERE id = #{id} AND draft_status = 'pending'")
  int claimDraftApply(@Param("id") String id, @Param("token") String token,
      @Param("now") Date now);

  /**
   * 过期 applying 的条件接管：仅当状态、旧令牌与旧租约起点都与读取值一致时
   * （数据库行锁下并发恢复者只有一个影响行数 = 1），换入新令牌与新租约。
   * 不允许“超时直接改回 pending”——接管者直接成为唯一执行者。
   */
  @Update("UPDATE ai_messages SET draft_apply_token = #{token}, "
      + "draft_apply_started_at = #{now} "
      + "WHERE id = #{id} AND draft_status = 'applying' "
      + "AND draft_apply_token = #{oldToken} AND draft_apply_started_at = #{oldStartedAt}")
  int takeoverDraftApply(@Param("id") String id, @Param("oldToken") String oldToken,
      @Param("oldStartedAt") Date oldStartedAt, @Param("token") String token,
      @Param("now") Date now);

  /**
   * 应用成功落账（fencing）：仅当记录仍为 applying 且仍由本令牌持有时 → applied + 结果 JSON。
   * 被接管后的旧执行者影响行数 = 0 → 其业务事务必须回滚（同一事务内），不得留下业务副作用。
   */
  @Update("UPDATE ai_messages SET draft_status = 'applied', "
      + "draft_apply_result = #{result}, draft_apply_started_at = #{now} "
      + "WHERE id = #{id} AND draft_status = 'applying' AND draft_apply_token = #{token}")
  int finishDraftApply(@Param("id") String id, @Param("token") String token,
      @Param("result") String result, @Param("now") Date now);

  /**
   * 业务失败后释放自己的占位：仅当仍为 applying 且令牌匹配 → 回到 pending
   * （立即可重试）；若提交实际已成功（applied）或已被接管则影响行数 = 0，不覆盖他人状态。
   */
  @Update("UPDATE ai_messages SET draft_status = 'pending', draft_apply_token = NULL, "
      + "draft_apply_started_at = NULL, draft_apply_result = NULL "
      + "WHERE id = #{id} AND draft_status = 'applying' AND draft_apply_token = #{token}")
  int releaseDraftApply(@Param("id") String id, @Param("token") String token);

  /** 放弃草稿（原子互斥）：仅 pending → discarded；与 apply 的抢占竞争只有一个赢家。 */
  @Update("UPDATE ai_messages SET draft_status = 'discarded' "
      + "WHERE id = #{id} AND draft_status = 'pending'")
  int discardDraftIfPending(@Param("id") String id);
}
