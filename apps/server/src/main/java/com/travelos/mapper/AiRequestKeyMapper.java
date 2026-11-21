package com.travelos.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.travelos.entity.AiRequestKey;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

import java.util.Date;

@Mapper
public interface AiRequestKeyMapper extends BaseMapper<AiRequestKey> {

  /**
   * 原子接管（failed / 过期 running / 结果不可读三类路径共用）：
   * 仅当「仍是读取到的状态」且「updated_at 仍是读取到的值」时才更新——
   * 数据库行锁保证并发下只有一个请求影响行数 = 1，其余返回 0 必须重读再决策。
   * 接管同时写入新 execution_token（执行归属）并清空旧 result。
   */
  @Update("UPDATE ai_request_keys SET status = 'running', execution_token = #{token}, "
      + "result = NULL, updated_at = #{now} "
      + "WHERE id = #{id} AND status = #{expectedStatus} AND updated_at = #{expectedUpdatedAt}")
  int takeover(@Param("id") String id,
      @Param("expectedStatus") String expectedStatus,
      @Param("expectedUpdatedAt") Date expectedUpdatedAt,
      @Param("token") String token,
      @Param("now") Date now);

  /** 仅当前执行归属可写 succeeded（防被接管后的旧执行者覆盖新执行者状态）。 */
  @Update("UPDATE ai_request_keys SET status = 'succeeded', result = #{resultJson}, "
      + "updated_at = #{now} "
      + "WHERE id = #{id} AND status = 'running' AND execution_token = #{token}")
  int succeedIfOwned(@Param("id") String id, @Param("token") String token,
      @Param("resultJson") String resultJson, @Param("now") Date now);

  /** 仅当前执行归属可写 failed / cancelled（token 为 NULL 的历史行不匹配任何条件）。 */
  @Update("UPDATE ai_request_keys SET status = #{newStatus}, updated_at = #{now} "
      + "WHERE id = #{id} AND status = 'running' AND execution_token = #{token}")
  int finishIfOwned(@Param("id") String id, @Param("token") String token,
      @Param("newStatus") String newStatus, @Param("now") Date now);

  /** 仅当前执行归属可删除占位（abandon 幂等：重复调用影响 0 行）。 */
  @Delete("DELETE FROM ai_request_keys WHERE id = #{id} "
      + "AND status = 'running' AND execution_token = #{token}")
  int deleteIfOwned(@Param("id") String id, @Param("token") String token);

  /**
   * 接管者放弃执行时的<b>条件恢复</b>（替代物理删除）：仅当记录仍为 running
   * 且仍由本次接管令牌持有时，把状态/执行归属/时间戳（及结果快照）恢复为接管前的值——
   * 旧执行者晚完成仍可按旧令牌保存 succeeded；恢复失败（状态已被他人改变）返回 0，
   * 调用方保留记录并只记日志，绝不删除。
   */
  @Update("UPDATE ai_request_keys SET status = #{status}, execution_token = #{token}, "
      + "result = #{result,jdbcType=VARCHAR}, updated_at = #{updatedAt} "
      + "WHERE id = #{id} AND status = 'running' AND execution_token = #{currentToken}")
  int restoreIfOwned(@Param("id") String id, @Param("currentToken") String currentToken,
      @Param("status") String status, @Param("token") String token,
      @Param("updatedAt") Date updatedAt, @Param("result") String result);
}
