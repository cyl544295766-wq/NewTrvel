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

  // ==================== 好友聊天 ====================

  /** 聊天会话列表（互为好友 + 最近消息 + 未读数）：{ chats: [...] } */
  @GetMapping("/chats")
  public Map<String, Object> chats(@CurrentUser UserContext user) {
    return friendService.chatList(user.id());
  }

  /** 未读私信总数：{ unread } */
  @GetMapping("/unread-count")
  public Map<String, Object> unreadCount(@CurrentUser UserContext user) {
    return friendService.unreadCount(user.id());
  }

  /** 与某好友的聊天记录（?latest=true 取最新 N 条，自动标记已读）。 */
  @GetMapping("/{friendId}/messages")
  public Map<String, Object> messages(@PathVariable String friendId,
      @RequestParam(value = "page", required = false) String page,
      @RequestParam(value = "pageSize", required = false) String pageSize,
      @RequestParam(value = "latest", required = false) String latest,
      @CurrentUser UserContext user) {
    return friendService.chatMessages(user.id(), friendId, page, pageSize, latest);
  }

  /** 发送私信：{ content }。 */
  @PostMapping("/{friendId}/messages")
  public Map<String, Object> send(@PathVariable String friendId,
      @RequestBody Map<String, Object> dto, @CurrentUser UserContext user) {
    return friendService.sendMessage(user.id(), friendId, dto);
  }
}
