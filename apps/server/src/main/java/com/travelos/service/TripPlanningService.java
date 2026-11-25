package com.travelos.service;

import com.travelos.ai.AiChatService;
import com.travelos.common.ApiException;
import com.travelos.common.Payloads;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.regex.Pattern;

@Service
public class TripPlanningService {
  private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9_-]{8,80}");

  private final JdbcTemplate jdbc;
  private final TripService trips;
  private final AiChatService chat;

  public TripPlanningService(JdbcTemplate jdbc, TripService trips, AiChatService chat) {
    this.jdbc = jdbc;
    this.trips = trips;
    this.chat = chat;
  }

  @Transactional
  public Map<String, Object> start(String userId, Map<String, Object> dto) {
    String requestId = Payloads.trimmedOrNull(dto.get("requestId"));
    if (requestId == null || !REQUEST_ID.matcher(requestId).matches()) {
      throw ApiException.badRequest("规划请求标识无效");
    }
    String destination = Payloads.trimmedOrNull(dto.get("destination"));
    String startDate = Payloads.trimmedOrNull(dto.get("startDate"));
    String endDate = Payloads.trimmedOrNull(dto.get("endDate"));
    if (destination == null || startDate == null || endDate == null) {
      throw ApiException.badRequest("请填写规划目的地和出行日期");
    }

    // 占位、创建行程和创建会话在同一事务内。相同请求并发进入时复用已提交的结果。
    int inserted = jdbc.update("""
        INSERT INTO trip_planning_requests(user_id, request_id)
        VALUES (?, ?) ON CONFLICT (user_id, request_id) DO NOTHING
        """, userId, requestId);
    if (inserted == 0) {
      return jdbc.queryForObject("""
          SELECT trip_id, conversation_id FROM trip_planning_requests
          WHERE user_id = ? AND request_id = ?
          """, (rs, row) -> {
        String tripId = rs.getString("trip_id");
        String conversationId = rs.getString("conversation_id");
        if (tripId == null || conversationId == null || trips.findLive(tripId) == null) {
          throw ApiException.conflict("这次规划的行程或会话已删除，请重新发起规划");
        }
        return Map.of("tripId", tripId, "conversationId", conversationId);
      }, userId, requestId);
    }

    String tripId = idOf(trips.create(Map.of("title", destination + "之旅",
        "destination", destination, "startDate", startDate, "endDate", endDate), userId), "trip");
    String conversationId = idOf(chat.createConversation(userId,
        Map.of("tripId", tripId, "title", "一键行程规划")), "conversation");
    jdbc.update("""
        UPDATE trip_planning_requests SET trip_id = ?, conversation_id = ?
        WHERE user_id = ? AND request_id = ?
        """, tripId, conversationId, userId, requestId);
    return Map.of("tripId", tripId, "conversationId", conversationId);
  }

  private static String idOf(Map<String, Object> response, String key) {
    return ((Map<?, ?>) response.get(key)).get("id").toString();
  }
}
