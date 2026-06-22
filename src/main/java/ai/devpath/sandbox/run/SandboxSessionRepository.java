package ai.devpath.sandbox.run;

import org.springframework.data.jpa.repository.JpaRepository;

public interface SandboxSessionRepository extends JpaRepository<SandboxSession, Long> {
}
