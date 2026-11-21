package com.travelos.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.util.Date;

/**
 * 受管文件的最小归属元数据：name = 32位hex.ext，owner = 上传时的登录用户。
 *
 * <p>该表只用于授权时的防御性校验，不替代业务表本身的可见性规则。
 * 存量文件由迁移脚本从 photos/travel_documents/users 等可靠属主列回填；
 * 无法回填的文件保持“无元数据”，由 FileAccessService 按业务引用 + 行程成员关系
 * 做保守授权，不会因此对所有登录用户放行。</p>
 */
@TableName("file_metadata")
public class FileMetadata {

  @TableId(value = "name", type = IdType.INPUT)
  private String name;

  @TableField("owner_user_id")
  private String ownerUserId;

  @TableField("created_at")
  private Date createdAt;

  public String getName() { return name; }
  public void setName(String name) { this.name = name; }
  public String getOwnerUserId() { return ownerUserId; }
  public void setOwnerUserId(String ownerUserId) { this.ownerUserId = ownerUserId; }
  public Date getCreatedAt() { return createdAt; }
  public void setCreatedAt(Date createdAt) { this.createdAt = createdAt; }
}
