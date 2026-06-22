package ai.devpath.sandbox.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox")
public class OutboxEntry {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "aggregate_type", nullable = false) private String aggregateType;
  @Column(name = "aggregate_id", nullable = false) private String aggregateId;
  @Column(name = "event_type", nullable = false) private String eventType;
  @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false) private String payload;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "published_at") private Instant publishedAt;

  public Long getId() { return id; }
  public String getAggregateType() { return aggregateType; }
  public void setAggregateType(String v) { this.aggregateType = v; }
  public String getAggregateId() { return aggregateId; }
  public void setAggregateId(String v) { this.aggregateId = v; }
  public String getEventType() { return eventType; }
  public void setEventType(String v) { this.eventType = v; }
  public String getPayload() { return payload; }
  public void setPayload(String v) { this.payload = v; }
  public Instant getCreatedAt() { return createdAt; }
  public void setCreatedAt(Instant v) { this.createdAt = v; }
  public Instant getPublishedAt() { return publishedAt; }
  public void setPublishedAt(Instant v) { this.publishedAt = v; }
}
