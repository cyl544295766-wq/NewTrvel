package com.travelos.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.entity.User;
import com.travelos.mapper.UserMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class UserService {

  private final UserMapper userMapper;

  public UserService(UserMapper userMapper) {
    this.userMapper = userMapper;
  }

  public User findByUsername(String username) {
    return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getUsername, username));
  }

  /** 华为账号登录入口：按 openID 精确匹配（users_huawei_open_id_key 唯一索引保证至多一条）。 */
  public User findByHuaweiOpenId(String openId) {
    if (openId == null || openId.isBlank()) {
      return null;
    }
    return userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getHuaweiOpenId, openId));
  }

  /** 用户名唯一性检查（不区分大小写）。 */
  public Long countByUsernameIgnoreCase(String lowerUsername) {
    return userMapper.selectCount(new LambdaQueryWrapper<User>()
        .apply("lower(username) = {0}", lowerUsername));
  }

  @Transactional
  public void createUser(User user) {
    userMapper.insert(user);
  }

  @Transactional
  public void updateUser(User user) {
    userMapper.updateById(user);
  }

  public User findById(String id) {
    return userMapper.selectById(id);
  }

  public void updateLastLoginAt(String id) {
    User user = new User();
    user.setId(id);
    user.setLastLoginAt(new Date());
    userMapper.updateById(user);
  }

  public Map<String, Object> toPublicUser(User user) {
    Map<String, Object> map = new LinkedHashMap<>();
    map.put("id", user.getId());
    map.put("username", user.getUsername());
    map.put("displayName", user.getDisplayName());
    map.put("avatarUrl", user.getAvatarUrl());
    map.put("phoneNumber", user.getPhoneNumber());
    map.put("status", user.getStatus());
    map.put("role", user.getRole() == null ? "user" : user.getRole());
    map.put("createdAt", user.getCreatedAt());
    return map;
  }
}
