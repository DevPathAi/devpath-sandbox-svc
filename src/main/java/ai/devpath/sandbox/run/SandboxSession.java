package ai.devpath.sandbox.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "sandbox_sessions")
public class SandboxSession {

  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;
  @Column(name = "user_id", nullable = false) private Long userId;
  @Column(name = "content_id") private Long contentId;
  @Column(name = "code_block_id") private Long codeBlockId;
  @Column(nullable = false) private String language;
  @Column(name = "container_id") private String containerId;
  @Column(nullable = false) private String status;
  @Column(name = "submitted_code", nullable = false) private String submittedCode;
  @Column(name = "stdout") private String stdout;
  @Column(name = "stderr") private String stderr;
  @Column(name = "exit_code") private Integer exitCode;
  @Column(name = "cpu_ms_used") private Long cpuMsUsed;
  @Column(name = "memory_mb_peak") private Integer memoryMbPeak;
  @Column(name = "started_at", nullable = false) private Instant startedAt;
  @Column(name = "finished_at") private Instant finishedAt;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  @PrePersist
  void prePersist() {
    Instant now = Instant.now();
    if (startedAt == null) startedAt = now;
    if (createdAt == null) createdAt = now;
    if (updatedAt == null) updatedAt = now;
  }

  @PreUpdate
  void preUpdate() {
    updatedAt = Instant.now();
  }

  public Long getId() { return id; }
  public Long getUserId() { return userId; }
  public void setUserId(Long userId) { this.userId = userId; }
  public Long getContentId() { return contentId; }
  public void setContentId(Long contentId) { this.contentId = contentId; }
  public Long getCodeBlockId() { return codeBlockId; }
  public void setCodeBlockId(Long codeBlockId) { this.codeBlockId = codeBlockId; }
  public String getLanguage() { return language; }
  public void setLanguage(String language) { this.language = language; }
  public String getContainerId() { return containerId; }
  public void setContainerId(String containerId) { this.containerId = containerId; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public String getSubmittedCode() { return submittedCode; }
  public void setSubmittedCode(String submittedCode) { this.submittedCode = submittedCode; }
  public String getStdout() { return stdout; }
  public void setStdout(String stdout) { this.stdout = stdout; }
  public String getStderr() { return stderr; }
  public void setStderr(String stderr) { this.stderr = stderr; }
  public Integer getExitCode() { return exitCode; }
  public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
  public Long getCpuMsUsed() { return cpuMsUsed; }
  public void setCpuMsUsed(Long cpuMsUsed) { this.cpuMsUsed = cpuMsUsed; }
  public Integer getMemoryMbPeak() { return memoryMbPeak; }
  public void setMemoryMbPeak(Integer memoryMbPeak) { this.memoryMbPeak = memoryMbPeak; }
  public Instant getStartedAt() { return startedAt; }
  public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
  public Instant getFinishedAt() { return finishedAt; }
  public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
