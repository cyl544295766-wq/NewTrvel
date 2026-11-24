package com.travelos.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.travelos.common.DateUtil;
import com.travelos.common.Fmt;
import com.travelos.common.TripScopeContext;
import com.travelos.entity.TravelDocument;
import com.travelos.entity.Trip;
import com.travelos.entity.TripMember;
import com.travelos.mapper.TravelDocumentMapper;
import com.travelos.mapper.TripMapper;
import com.travelos.mapper.TripMemberMapper;
import com.travelos.service.NotificationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

@Component
@ConditionalOnProperty(value = "app.document-reminder.enabled", havingValue = "true", matchIfMissing = true)
public class DocumentReminderScheduler {
  private final TravelDocumentMapper documents;
  private final TripMapper trips;
  private final TripMemberMapper members;
  private final NotificationService notifications;

  public DocumentReminderScheduler(TravelDocumentMapper documents, TripMapper trips,
      TripMemberMapper members, NotificationService notifications) {
    this.documents = documents;
    this.trips = trips;
    this.members = members;
    this.notifications = notifications;
  }

  @Scheduled(cron = "0 15 9 * * *", zone = "Asia/Shanghai")
  public void scan() {
    TripScopeContext.runBypassed(() -> sendDue(LocalDate.now(java.time.ZoneId.of("Asia/Shanghai"))));
  }

  void sendDue(LocalDate date) {
    for (TravelDocument doc : documents.selectList(new LambdaQueryWrapper<TravelDocument>()
        .eq(TravelDocument::getIsReminder, true)
        .ge(TravelDocument::getExpiredAt, DateUtil.parse(date.toString()))
        .lt(TravelDocument::getExpiredAt, DateUtil.parse(date.plusDays(8).toString())))) {
      Trip trip = trips.selectById(doc.getTripId());
      if (trip == null || trip.getDeletedAt() != null || trip.getArchivedAt() != null
          || "archived".equals(trip.getStatus())) continue;
      for (TripMember member : members.selectList(new LambdaQueryWrapper<TripMember>()
          .eq(TripMember::getTripId, trip.getId()))) {
        notifications.createDocumentExpiry(member.getUserId(), trip.getId(), doc.getId(),
            doc.getTitle(), Fmt.dateKey(doc.getExpiredAt()));
      }
    }
  }
}
