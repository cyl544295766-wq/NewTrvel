package com.travelos.controller;

import com.travelos.auth.CurrentUser;
import com.travelos.auth.UserContext;
import com.travelos.service.FriendService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** 好友系统：搜索（昵称/用户名/用户 ID）、添加、列表、移除。所有接口要求登录。 */
@RestController
@RequestMapping("/api/friends")
public class FriendController {

  private final FriendService friendService;

  public FriendController(FriendService friendService) {
    this.friendService = friendService;
  }

  /** 我的好友列表 + 待回加列表：{ friends: [...], incoming: [...] } */
  @GetMapping
  public Map<String, Object> list(@CurrentUser UserContext user) {
    return friendService.list(user.id());
  }

  /** 搜索用户（昵称/用户名/用户 ID 均可命中）：{ users: [...] } */
  @GetMapping("/search")
  public Map<String, Object> search(@RequestParam("keyword") String keyword,
      @CurrentUser UserContext user) {
    return friendService.search(user.id(), keyword);
  }

  /** 添加好友：body 三选一 { userId } / { username } / { displayName }，统一返回 { friend, relation, message } */
  @PostMapping
  public Map<String, Object> add(@RequestBody Map<String, Object> dto,
      @CurrentUser UserContext user) {
    return friendService.add(user.id(), dto);
  }

  /** 移除我添加的好友：{ success, message } */
  @DeleteMapping("/{friendId}")
  public Map<String, Object> remove(@PathVariable String friendId,
      @CurrentUser UserContext user) {
    return friendService.remove(user.id(), friendId);
  }
}
