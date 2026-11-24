package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.ApiException;
import com.travelos.entity.TripMember;
import com.travelos.entity.Trip;
import com.travelos.entity.User;
import com.travelos.mapper.TripMemberMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.UserMapper;
import com.travelos.trip.TripPermission;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class TripMemberService {

  private final TripMemberMapper tripMemberMapper;
  private final TripMapper tripMapper;
  private final UserMapper userMapper;
  private final NotificationService notificationService;
  private final AuditService auditService;
  private final FriendService friendService;

  /** 共同行程扫描上限（sharesTrip 防御性有界查询）。 */
  private static final int MAX_SHARED_TRIP_SCAN = 200;

  public TripMemberService(TripMemberMapper tripMemberMapper, TripMapper tripMapper,
      UserMapper userMapper, NotificationService notificationService, AuditService auditService,
      FriendService friendService) {
    this.tripMemberMapper = tripMemberMapper;
    this.tripMapper = tripMapper;
    this.userMapper = userMapper;
    this.notificationService = notificationService;
    this.auditService = auditService;
    this.friendService = friendService;
  }

  public TripMember createOwner(String tripId, String userId) {
    TripMember member = new TripMember();
    member.setTripId(tripId);
    member.setUserId(userId);
    member.setRole(TripPermission.OWNER);
    member.setCreatedAt(new Date());
    member.setUpdatedAt(new Date());
    tripMemberMapper.insert(member);
    return member;
  }

  public TripMember requireTripMember(String tripId, String userId) {
    TripMember membership = tripMemberMapper.selectOne(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getTripId, tripId)
        .eq(TripMember::getUserId, userId));
    if (membership == null) {
      throw ApiException.notFound("旅行不存在");
    }
    return membership;
  }

  /**
   * 是否为行程成员（owner/admin/member/viewer 一律 true），不抛异常。
   * 供文件对象级授权等跨入口判定复用；移出行成员后立即返回 false（即时失效）。
   */
  public boolean isTripMember(String tripId, String userId) {
    if (tripId == null || userId == null) {
      return false;
    }
    Long count = tripMemberMapper.selectCount(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getTripId, tripId)
        .eq(TripMember::getUserId, userId));
    return count != null && count > 0;
  }

  /**
   * 两人是否共同持有未删除行程（头像等非行程文件的授权矩阵用）。
   * 查询有界：单人最多扫描 200 个成员关系、200 个存活行程，超出按「无法确认 → 不放行」处理。
   */
  public boolean sharesTrip(String userA, String userB) {
    if (userA == null || userB == null) {
      return false;
    }
    if (userA.equals(userB)) {
      return true;
    }
    List<String> tripIds = tripMemberMapper.selectList(new LambdaQueryWrapper<TripMember>()
            .eq(TripMember::getUserId, userA)
            .last("LIMIT " + MAX_SHARED_TRIP_SCAN))
        .stream().map(TripMember::getTripId).distinct().toList();
    if (tripIds.isEmpty()) {
      return false;
    }
    List<String> liveTrips = tripMapper.selectList(new LambdaQueryWrapper<Trip>()
            .in(Trip::getId, tripIds)
            .isNull(Trip::getDeletedAt)
            .last("LIMIT " + MAX_SHARED_TRIP_SCAN))
        .stream().map(Trip::getId).distinct().toList();
    if (liveTrips.isEmpty()) {
      return false;
    }
    Long shared = tripMemberMapper.selectCount(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getUserId, userB)
        .in(TripMember::getTripId, liveTrips));
    return shared != null && shared > 0;
  }

  public TripMember requireTripRole(String tripId, String userId, List<String> roles) {
    TripMember membership = requireTripMember(tripId, userId);
    if (!roles.contains(membership.getRole())) {
      throw ApiException.forbidden("没有足够的旅行权限");
    }
    return membership;
  }

  public Map<String, Object> findMembers(String tripId, String userId) {
    requireTripMember(tripId, userId);
    return toMembersResponse(tripId);
  }

  public Map<String, Object> invite(String tripId, String inviterUserId,
      String username, String role) {
    // 第二道防线：操作者必须是 owner/admin；身份来自登录上下文，不信任请求体
    requireTripRole(tripId, inviterUserId, TripPermission.MANAGER_ROLES);
    String normalizedRole = role == null ? TripPermission.MEMBER : role.trim();
    if (TripPermission.OWNER.equals(normalizedRole)) {
      throw ApiException.badRequest("不能邀请所有者");
    }
    if (!TripPermission.ASSIGNABLE_ROLES.contains(normalizedRole)) {
      throw ApiException.badRequest("角色不合法：仅支持 admin / member / viewer");
    }
    User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
        .eq(User::getUsername, username.trim()));
    if (user == null || !"active".equals(user.getStatus())) {
      throw ApiException.notFound("用户不存在");
    }
    // 账本好友规则：只有互为好友的用户才能被邀请加入账本（行程）
    if (!inviterUserId.equals(user.getId())) {
      friendService.requireMutualFriend(inviterUserId, user.getId());
    }
    TripMember existing = tripMemberMapper.selectOne(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getTripId, tripId)
        .eq(TripMember::getUserId, user.getId()));
    if (existing != null) {
      throw ApiException.badRequest("该用户已加入旅行");
    }
    TripMember member = new TripMember();
    member.setTripId(tripId);
    member.setUserId(user.getId());
    member.setRole(normalizedRole);
    member.setCreatedAt(new Date());
    member.setUpdatedAt(new Date());
    tripMemberMapper.insert(member);

    Trip trip = tripMapper.selectById(tripId);
    if (trip != null) {
      notificationService.createMemberInvited(user.getId(), tripId, trip.getTitle(), normalizedRole, member.getId());
    }
    auditService.record(inviterUserId, tripId, "member_invited", "user", user.getId(),
        Map.of("username", user.getUsername(), "role", normalizedRole));
    return toMembersResponse(tripId);
  }

  public Map<String, Object> updateRole(String tripId, String memberId, String role,
      String operatorUserId) {
    // 第二道防线：操作者必须是 owner/admin；身份来自登录上下文，不信任请求体
    TripMember operator = requireTripRole(tripId, operatorUserId, TripPermission.MANAGER_ROLES);
    TripMember target = requireTargetMember(tripId, memberId);
    if (TripPermission.OWNER.equals(target.getRole())) {
      throw ApiException.badRequest("不能修改所有者角色");
    }
    requireNotPeerAdmin(operator, target);
    String normalizedRole = role == null ? "" : role.trim();
    if (TripPermission.OWNER.equals(normalizedRole)) {
      throw ApiException.badRequest("不能将成员改为所有者");
    }
    if (!TripPermission.ASSIGNABLE_ROLES.contains(normalizedRole)) {
      throw ApiException.badRequest("角色不合法：仅支持 admin / member / viewer");
    }
    target.setRole(normalizedRole);
    target.setUpdatedAt(new Date());
    tripMemberMapper.updateById(target);

    Trip trip = tripMapper.selectById(tripId);
    if (trip != null) {
      notificationService.createRoleChanged(target.getUserId(), tripId, trip.getTitle(),
          normalizedRole);
    }
    auditService.record(operatorUserId, tripId, "role_changed", "trip_member", memberId,
        Map.of("username", usernameOf(target.getUserId()), "role", normalizedRole));
    return toMembersResponse(tripId);
  }

  public Map<String, Object> remove(String tripId, String memberId, String operatorUserId) {
    // 第二道防线：操作者必须是 owner/admin；身份来自登录上下文，不信任请求体
    TripMember operator = requireTripRole(tripId, operatorUserId, TripPermission.MANAGER_ROLES);
    TripMember target = requireTargetMember(tripId, memberId);
    if (TripPermission.OWNER.equals(target.getRole())) {
      throw ApiException.badRequest("不能移除所有者");
    }
    requireNotPeerAdmin(operator, target);
    tripMemberMapper.deleteById(memberId);
    auditService.record(operatorUserId, tripId, "member_removed", "trip_member", memberId,
        Map.of("username", usernameOf(target.getUserId()), "role", target.getRole()));
    return toMembersResponse(tripId);
  }

  /**
   * owner 角色转移：仅当前 owner 可调用；目标必须是行程成员且不能是自己。
   * 事务内完成角色互换（目标→owner，原 owner→admin），带并发保护与幂等保护。
   */
  @org.springframework.transaction.annotation.Transactional
  public Map<String, Object> transferOwner(String tripId, String currentUserId,
      String targetUserId) {
    if (targetUserId == null || targetUserId.isBlank()) {
      throw ApiException.badRequest("targetUserId 不能为空");
    }
    if (targetUserId.equals(currentUserId)) {
      throw ApiException.badRequest("不能将所有者转移给自己");
    }
    // 仅当前 owner 可调用（非成员 404 → 403 语义由切面/此处统一）
    TripMember currentMember = requireTripRole(tripId, currentUserId, List.of(TripPermission.OWNER));
    TripMember targetMember = tripMemberMapper.selectOne(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getTripId, tripId)
        .eq(TripMember::getUserId, targetUserId));
    if (targetMember == null) {
      throw ApiException.badRequest("目标用户不是该行程成员");
    }
    // 幂等：目标已是 owner（说明此前已转移成功）直接返回，不产生脏数据
    if (TripPermission.OWNER.equals(targetMember.getRole())) {
      return toMembersResponse(tripId);
    }
    targetMember.setRole(TripPermission.OWNER);
    targetMember.setUpdatedAt(new Date());
    int updatedTarget = tripMemberMapper.updateById(targetMember);
    // 并发保护：原 owner 行必须是 owner 角色才允许降级，失败则整体回滚
    currentMember.setRole(TripPermission.ADMIN);
    currentMember.setUpdatedAt(new Date());
    int updatedCurrent = tripMemberMapper.update(currentMember,
        new LambdaQueryWrapper<TripMember>()
            .eq(TripMember::getId, currentMember.getId())
            .eq(TripMember::getRole, TripPermission.OWNER));
    if (updatedTarget == 0 || updatedCurrent == 0) {
      throw ApiException.conflict("所有者状态已变化，请刷新后重试");
    }
    Trip trip = tripMapper.selectById(tripId);
    if (trip != null) {
      notificationService.createOwnerTransferred(targetUserId, tripId, trip.getTitle());
    }
    auditService.record(currentUserId, tripId, "owner_transferred", "trip", tripId,
        Map.of("from", usernameOf(currentUserId), "to", usernameOf(targetUserId)));
    return toMembersResponse(tripId);
  }

  private String usernameOf(String userId) {
    User user = userMapper.selectById(userId);
    return user == null ? userId : user.getUsername();
  }

  public List<TripMember> listMembers(String tripId) {
    return tripMemberMapper.selectList(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getTripId, tripId));
  }

  /**
   * 危险操作行级属主校验：属主本人或行程 owner/admin 放行。
   * 存量数据属主为 NULL 时按行程成员兼容处理（任何成员可改），
   * 该兼容逻辑在属主列回填后仅对极老的遗留数据生效。
   */
  public void requireOwnerOrManager(String tripId, String ownerId, String userId,
      String ownerLabel) {
    if (ownerId == null) {
      return;
    }
    if (ownerId.equals(userId)) {
      return;
    }
    TripMember membership = requireTripMember(tripId, userId);
    if (!TripPermission.OWNER.equals(membership.getRole())
        && !TripPermission.ADMIN.equals(membership.getRole())) {
      throw ApiException.forbidden("仅" + ownerLabel + "本人或行程管理员可执行该操作");
    }
  }

  private TripMember requireTargetMember(String tripId, String memberId) {
    TripMember member = tripMemberMapper.selectById(memberId);
    if (member == null || !member.getTripId().equals(tripId)) {
      throw ApiException.notFound("成员不存在");
    }
    return member;
  }

  /** admin 不能修改或移除同级 admin（包括自己）；owner 不受此限制。 */
  private void requireNotPeerAdmin(TripMember operator, TripMember target) {
    if (TripPermission.ADMIN.equals(operator.getRole())
        && TripPermission.ADMIN.equals(target.getRole())) {
      throw ApiException.forbidden("管理员不能管理同级成员");
    }
  }

  private Map<String, Object> toMembersResponse(String tripId) {
    List<Map<String, Object>> members = listMembers(tripId).stream()
        .map(member -> {
          User user = userMapper.selectById(member.getUserId());
          Map<String, Object> map = new LinkedHashMap<>();
          map.put("id", member.getId());
          map.put("userId", member.getUserId());
          map.put("username", user == null ? null : user.getUsername());
          map.put("displayName", user == null ? null : user.getDisplayName());
          map.put("role", member.getRole());
          return map;
        })
        .toList();
    return Map.of("members", members);
  }
}
