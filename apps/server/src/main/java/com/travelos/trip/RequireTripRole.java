package com.travelos.trip;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 行程角色校验注解：AOP 切面从方法参数中解析 tripId（路径变量优先）与当前用户，
 * 复用 TripMemberService.requireTripRole 语义；资源不存在（404）统一转为
 * 403，避免向非成员暴露资源存在性。
 *
 * 使用示例：@RequireTripRole({"owner", "admin", "member"})
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireTripRole {

  String[] value();
}
