package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.entity.User;
import com.travelos.entity.UserFriend;
import com.travelos.mapper.UserFriendMapper;
import com.travelos.mapper.UserMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 好友系统。
 * 数据模型：user_friends 单向记录（user_id 添加了 friend_id）；「互为好友」= 双向记录同时存在。
 * 添加方式（三选一，服务端统一解析）：用户 ID 精确匹配 / 用户名精确匹配 / 昵称精确匹配。
 * 核心规则：只有互为好友的用户，才能被邀请加入账本（行程）—— 见 TripMemberService.invite。
 */
@Service
public class FriendService {

  private final UserFriendMapper userFriendMapper;
  private final UserMapper userMapper;

  public FriendService(UserFriendMapper userFriendMapper, UserMapper userMapper) {
    this.userFriendMapper = userFriendMapper;
    this.userMapper = userMapper;
  }

  // ==================== 搜索 ====================

  /**
   * 按关键词搜索用户：用户 ID 精确 → 用户名模糊 → 昵称模糊（用户 ID 同时也支持模糊命中）。
   * 不返回自己；统一返回结构 { users: [{id, username, displayName, status, relation}] }，
   * relation: mutual（互为好友）/ added（我已添加，待对方回加）/ incoming（对方已添加我）/ none。
   */
  public Map<String, Object> search(String currentUserId, String keyword) {
    String kw = keyword == null ? "" : keyword.trim();
    if (kw.isEmpty()) {
      throw ApiException.badRequest("请输入昵称、用户名或用户 ID 进行搜索");
    }
    List<User> users = new ArrayList<>();
    // 1) 用户 ID 精确命中优先
    User byId = userMapper.selectById(kw);
    if (byId != null) {
      users.add(byId);
    }
    // 2) 用户名 / 昵称模糊（最多 10 条）
    if (users.isEmpty()) {
      users = userMapper.selectList(new LambdaQueryWrapper<User>()
          .and(q -> q.like(User::getUsername, kw).or().like(User::getDisplayName, kw))
          .ne(User::getId, currentUserId)
          .eq(User::getStatus, "active")
          .last("LIMIT 10"));
    } else if (!byId.getId().equals(currentUserId)) {
      // ID 命中后补齐关系信息即可，不需要额外查询
    } else {
      users = new ArrayList<>(); // 搜到自己：返回空结果
    }
    List<Map<String, Object>> items = new ArrayList<>();
    for (User u : users) {
      if (u.getId().equals(currentUserId) || !"active".equals(u.getStatus())) {
        continue;
      }
      items.add(toUserResponse(currentUserId, u));
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("keyword", kw);
    body.put("users", items);
    return body;
  }

  // ==================== 添加 ====================

  /**
   * 添加好友，支持三种方式（三选一，优先级 userId > username > displayName）：
   *   - userId：按用户 ID 精确添加
   *   - username：按用户名精确添加
   *   - displayName：按昵称精确添加；若昵称对应多个用户，返回 409 并提示改用用户名或 ID
   * 返回统一结构 { friend, relation, message }，relation 同搜索口径。
   */
  public Map<String, Object> add(String currentUserId, Map<String, Object> dto) {
    User target = resolveTarget(currentUserId, dto);
    if (target.getId().equals(currentUserId)) {
      throw ApiException.badRequest("不能添加自己为好友");
    }
    if (hasDirection(currentUserId, target.getId())) {
      boolean mutual = hasDirection(target.getId(), currentUserId);
      throw ApiException.conflict(mutual ? "你们已经是好友了" : "已添加过该好友，等待对方回加");
    }
    UserFriend row = new UserFriend();
    row.setUserId(currentUserId);
    row.setFriendId(target.getId());
    row.setCreatedAt(new Date());
    userFriendMapper.insert(row);

    boolean mutual = hasDirection(target.getId(), currentUserId);
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("friend", toUserResponse(currentUserId, target));
    body.put("relation", mutual ? "mutual" : "added");
    body.put("message", mutual
        ? "已互为好友，现在可以把 TA 邀请进账本啦"
        : "已发送添加请求，对方也添加你后即可一起记账");
    return body;
  }

  /** 按优先级解析目标用户；找不到/歧义时抛出友好异常。 */
  private User resolveTarget(String currentUserId, Map<String, Object> dto) {
    String userId = str(dto.get("userId"));
    String username = str(dto.get("username"));
    String displayName = str(dto.get("displayName"));
    if (!userId.isEmpty()) {
      User u = userMapper.selectById(userId);
      if (u == null) {
        throw ApiException.notFound("未找到该用户 ID 对应的账户");
      }
      return u;
    }
    if (!username.isEmpty()) {
      User u = userMapper.selectOne(new LambdaQueryWrapper<User>()
          .eq(User::getUsername, username).last("LIMIT 1"));
      if (u == null) {
        throw ApiException.notFound("未找到用户名「" + username + "」对应的账户");
      }
      return u;
    }
    if (!displayName.isEmpty()) {
      List<User> matched = userMapper.selectList(new LambdaQueryWrapper<User>()
          .eq(User::getDisplayName, displayName)
          .eq(User::getStatus, "active")
          .ne(User::getId, currentUserId)
          .last("LIMIT 2"));
      if (matched.isEmpty()) {
        throw ApiException.notFound("未找到昵称「" + displayName + "」对应的账户");
      }
      if (matched.size() > 1) {
        throw ApiException.conflict("昵称「" + displayName + "」对应 " + matched.size()
            + " 个用户，请改用用户名或用户 ID 添加");
      }
      return matched.get(0);
    }
    throw ApiException.badRequest("请提供 userId / username / displayName 之一");
  }

  // ==================== 列表 ====================

  /** 我的好友列表 + 待回加列表。统一结构 { friends: [...], incoming: [...] }。 */
  public Map<String, Object> list(String currentUserId) {
    List<UserFriend> mine = userFriendMapper.selectList(new LambdaQueryWrapper<UserFriend>()
        .eq(UserFriend::getUserId, currentUserId)
        .orderByDesc(UserFriend::getCreatedAt));
    List<UserFriend> incoming = userFriendMapper.selectList(new LambdaQueryWrapper<UserFriend>()
        .eq(UserFriend::getFriendId, currentUserId)
        .orderByDesc(UserFriend::getCreatedAt));

    List<Map<String, Object>> friends = new ArrayList<>();
    for (UserFriend row : mine) {
      User u = userMapper.selectById(row.getFriendId());
      if (u == null || !"active".equals(u.getStatus())) {
        continue; // 账户已注销的用户不再展示
      }
      Map<String, Object> item = toUserResponse(currentUserId, u);
      item.put("addedAt", row.getCreatedAt());
      friends.add(item);
    }
    List<Map<String, Object>> pending = new ArrayList<>();
    for (UserFriend row : incoming) {
      if (hasDirection(currentUserId, row.getUserId())) {
        continue; // 我已回加，属于 friends
      }
      User u = userMapper.selectById(row.getUserId());
      if (u == null || !"active".equals(u.getStatus())) {
        continue;
      }
      Map<String, Object> item = toUserResponse(currentUserId, u);
      item.put("addedAt", row.getCreatedAt());
      pending.add(item);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("friends", friends);
    body.put("incoming", pending);
    return body;
  }

  // ==================== 移除 ====================

  /** 移除我添加的某条好友记录（不影响对方对我的记录）。 */
  public Map<String, Object> remove(String currentUserId, String friendId) {
    UserFriend row = userFriendMapper.selectOne(new LambdaQueryWrapper<UserFriend>()
        .eq(UserFriend::getUserId, currentUserId)
        .eq(UserFriend::getFriendId, friendId));
    if (row == null) {
      throw ApiException.notFound("该用户不在你的好友列表中");
    }
    userFriendMapper.deleteById(row.getId());
    return Map.of("success", true, "message",
        hasDirection(friendId, currentUserId) ? "已移除；对方仍添加着你，可搜索后回加" : "已移除好友");
  }

  // ==================== 账本邀请校验 ====================

  /**
   * 账本（行程）邀请的好友校验：双方必须互为好友。
   * 不满足时抛出统一友好提示，由前端原样展示。
   */
  public void requireMutualFriend(String userA, String userB) {
    if (userA.equals(userB)) {
      return;
    }
    if (!hasDirection(userA, userB) || !hasDirection(userB, userA)) {
      throw ApiException.forbidden("请先添加对方为好友，才能邀请 TA 加入账本");
    }
  }

  /** 双向是否互为好友。 */
  public boolean isMutualFriend(String userA, String userB) {
    return hasDirection(userA, userB) && hasDirection(userB, userA);
  }

  // ==================== 内部工具 ====================

  private boolean hasDirection(String userId, String friendId) {
    return userFriendMapper.selectCount(new LambdaQueryWrapper<UserFriend>()
        .eq(UserFriend::getUserId, userId)
        .eq(UserFriend::getFriendId, friendId)) > 0;
  }

  private Map<String, Object> toUserResponse(String currentUserId, User u) {
    boolean iAdded = hasDirection(currentUserId, u.getId());
    boolean addedMe = hasDirection(u.getId(), currentUserId);
    String relation = (iAdded && addedMe) ? "mutual" : (iAdded ? "added" : (addedMe ? "incoming" : "none"));
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", u.getId());
    map.put("username", u.getUsername());
    map.put("displayName", u.getDisplayName());
    map.put("relation", relation);
    return map;
  }

  private String str(Object value) {
    return value == null ? "" : value.toString().trim();
  }
}
