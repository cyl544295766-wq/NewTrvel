package com.travelos.trip;

import com.travelos.auth.UserContext;
import com.travelos.common.ApiException;
import com.travelos.common.TripScopeContext;
import com.travelos.service.TripMemberService;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * {@link RequireTripRole} 切面：从方法参数解析 tripId（路径变量优先）与当前用户，
 * 复用 TripMemberService.requireTripRole；资源不存在（404）统一转为 403，
 * 保留「不向非成员暴露资源存在性」的既有语义。
 */
@Aspect
@Component
public class TripRoleAspect {

  private final TripMemberService tripMemberService;
  private final ParameterNameDiscoverer parameterNameDiscoverer = new DefaultParameterNameDiscoverer();

  public TripRoleAspect(TripMemberService tripMemberService) {
    this.tripMemberService = tripMemberService;
  }

  @Around("@annotation(com.travelos.trip.RequireTripRole)")
  public Object requireTripRole(ProceedingJoinPoint joinPoint) throws Throwable {
    MethodSignature signature = (MethodSignature) joinPoint.getSignature();
    Method method = signature.getMethod();
    RequireTripRole annotation = method.getAnnotation(RequireTripRole.class);
    String tripId = resolveTripId(method, joinPoint.getArgs());
    if (tripId == null || tripId.isBlank()) {
      throw ApiException.badRequest("缺少行程标识");
    }
    String userId = TripScopeContext.getUserId();
    if (userId == null) {
      throw ApiException.unauthorized("Unauthorized");
    }
    try {
      tripMemberService.requireTripRole(tripId, userId, Arrays.asList(annotation.value()));
    } catch (ApiException ex) {
      if (ex.getStatusCode() == 404) {
        // 保留 404→403 转换语义：资源不存在时不向非成员暴露存在性
        throw ApiException.forbidden("没有权限访问该旅行资源");
      }
      throw ex;
    }
    return joinPoint.proceed();
  }

  private String resolveTripId(Method method, Object[] args) {
    java.lang.reflect.Parameter[] parameters = method.getParameters();
    String[] names = parameterNameDiscoverer.getParameterNames(method);
    for (int index = 0; index < parameters.length; index++) {
      String name = names == null ? parameters[index].getName() : names[index];
      if ("tripId".equals(name) && args[index] != null) {
        return args[index].toString();
      }
    }
    return null;
  }
}
