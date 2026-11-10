package com.pethospital.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.fasterxml.jackson.databind.JsonNode;
import com.pethospital.persistence.JsonNodeTypeHandler;
import java.time.Instant;
import org.apache.ibatis.type.JdbcType;

@TableName(value = "ai_session", autoResultMap = true)
public class AiSessionEntity {
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;
    private Long userId;
    private Long petId;
    private String title;
    private String status;
    @TableField(typeHandler = JsonNodeTypeHandler.class, jdbcType = JdbcType.VARCHAR)
    private JsonNode petSnapshotJson;
    private String summaryText;
    private Integer summaryThroughSeq;
    private Integer summaryVersion;
    private Instant lastMessageAt;
    @Version
    private Integer version;
    private Instant createdAt;
    private Instant updatedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public Long getPetId() { return petId; }
    public void setPetId(Long petId) { this.petId = petId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public JsonNode getPetSnapshotJson() { return petSnapshotJson; }
    public void setPetSnapshotJson(JsonNode petSnapshotJson) { this.petSnapshotJson = petSnapshotJson; }
    public String getSummaryText() { return summaryText; }
    public void setSummaryText(String summaryText) { this.summaryText = summaryText; }
    public Integer getSummaryThroughSeq() { return summaryThroughSeq; }
    public void setSummaryThroughSeq(Integer summaryThroughSeq) { this.summaryThroughSeq = summaryThroughSeq; }
    public Integer getSummaryVersion() { return summaryVersion; }
    public void setSummaryVersion(Integer summaryVersion) { this.summaryVersion = summaryVersion; }
    public Instant getLastMessageAt() { return lastMessageAt; }
    public void setLastMessageAt(Instant lastMessageAt) { this.lastMessageAt = lastMessageAt; }
    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
