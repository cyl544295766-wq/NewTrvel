package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

/** 用户偏好设置（账户页「偏好与显示」），每用户一行，首次读取时懒创建。 */
@TableName("user_preferences")
public class UserPreference {

  @TableId(value = "id", type = IdType.ASSIGN_ID)
  private String id;

  @TableField("user_id")
  private String userId;

  /** system / light / dark */
  private String theme;

  /** Asia/Shanghai / Asia/Tokyo / UTC */
  private String timezone;

  /** km / mi */
  @TableField("distance_unit")
  private String distanceUnit;

  /** CNY / USD / EUR */
  private String currency;

  /** cn（2026年7月24日）/ slash（2026/07/24）/ us（07/24/2026） */
  @TableField("date_format")
  private String dateFormat;

  /** mon / sun */
  @TableField("week_start")
  private String weekStart;

  @TableField("notify_trip")
  private Boolean notifyTrip;

  @TableField("notify_document")
  private Boolean notifyDocument;

  @TableField("notify_member")
  private Boolean notifyMember;

  @TableField("notify_summary")
  private Boolean notifySummary;

  @TableField(value = "created_at", fill = FieldFill.INSERT)
  private Date createdAt;

  @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
  private Date updatedAt;

  public String getId() { return id; }
  public void setId(String id) { this.id = id; }

  public String getUserId() { return userId; }
  public void setUserId(String userId) { this.userId = userId; }

  public String getTheme() { return theme; }
  public void setTheme(String theme) { this.theme = theme; }

  public String getTimezone() { return timezone; }
  public void setTimezone(String timezone) { this.timezone = timezone; }

  public String getDistanceUnit() { return distanceUnit; }
  public void setDistanceUnit(String distanceUnit) { this.distanceUnit = distanceUnit; }

  public String getCurrency() { return currency; }
  public void setCurrency(String currency) { this.currency = currency; }

  public String getDateFormat() { return dateFormat; }
  public void setDateFormat(String dateFormat) { this.dateFormat = dateFormat; }

  public String getWeekStart() { return weekStart; }
  public void setWeekStart(String weekStart) { this.weekStart = weekStart; }

  public Boolean getNotifyTrip() { return notifyTrip; }
  public void setNotifyTrip(Boolean notifyTrip) { this.notifyTrip = notifyTrip; }

  public Boolean getNotifyDocument() { return notifyDocument; }
  public void setNotifyDocument(Boolean notifyDocument) { this.notifyDocument = notifyDocument; }

  public Boolean getNotifyMember() { return notifyMember; }
  public void setNotifyMember(Boolean notifyMember) { this.notifyMember = notifyMember; }

  public Boolean getNotifySummary() { return notifySummary; }
  public void setNotifySummary(Boolean notifySummary) { this.notifySummary = notifySummary; }

  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }

  public Date getUpdatedAt() { return updatedAt; }
  public void setUpdatedAt(Date updatedAt) { this.updatedAt = updatedAt; }
}
