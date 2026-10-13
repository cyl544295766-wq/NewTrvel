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

  public TripMemberService(TripMemberMapper tripMemberMapper, TripMapper tripMapper,
      UserMapper userMapper, NotificationService notificationService) {
    this.tripMemberMapper = tripMemberMapper;
    this.tripMapper = tripMapper;
    this.userMapper = userMapper;
    this.notificationService = notificationService;
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
    requireTripRole(tripId, inviterUserId, TripPermission.EDITABLE_ROLES);
    String normalizedRole = role == null ? TripPermission.MEMBER : role;
    if (TripPermission.OWNER.equals(normalizedRole)) {
      throw ApiException.badRequest("不能邀请所有者");
    }
    User user = userMapper.selectOne(new LambdaQueryWrapper<User>()
        .eq(User::getUsername, username.trim()));
    if (user == null || !"active".equals(user.getStatus())) {
      throw ApiException.notFound("用户不存在");
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
      notificationService.createMemberInvited(user.getId(), tripId, trip.getTitle(), normalizedRole);
    }
    return toMembersResponse(tripId);
  }

  public Map<String, Object> updateRole(String tripId, String memberId, String role,
      String operatorUserId) {
    requireTripRole(tripId, operatorUserId, TripPermission.EDITABLE_ROLES);
    TripMember target = requireTargetMember(tripId, memberId);
    if (TripPermission.OWNER.equals(target.getRole())) {
      throw ApiException.badRequest("不能修改所有者角色");
    }
    if (TripPermission.OWNER.equals(role)) {
      throw ApiException.badRequest("不能将成员改为所有者");
    }
    target.setRole(role);
    target.setUpdatedAt(new Date());
    tripMemberMapper.updateById(target);

    Trip trip = tripMapper.selectById(tripId);
    if (trip != null) {
      notificationService.createRoleChanged(target.getUserId(), tripId, trip.getTitle(), role);
    }
    return toMembersResponse(tripId);
  }

  public Map<String, Object> remove(String tripId, String memberId, String operatorUserId) {
    requireTripRole(tripId, operatorUserId, TripPermission.EDITABLE_ROLES);
    TripMember target = requireTargetMember(tripId, memberId);
    if (TripPermission.OWNER.equals(target.getRole())) {
      throw ApiException.badRequest("不能移除所有者");
    }
    tripMemberMapper.deleteById(memberId);
    return toMembersResponse(tripId);
  }

  public List<TripMember> listMembers(String tripId) {
    return tripMemberMapper.selectList(new LambdaQueryWrapper<TripMember>()
        .eq(TripMember::getTripId, tripId));
  }

  private TripMember requireTargetMember(String tripId, String memberId) {
    TripMember member = tripMemberMapper.selectById(memberId);
    if (member == null || !member.getTripId().equals(tripId)) {
      throw ApiException.notFound("成员不存在");
    }
    return member;
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
