package ai.devpath.sandbox.run;

import com.github.dockerjava.api.DockerClient;

@FunctionalInterface
interface SandboxDockerClientFactory {
  DockerClient create();
}
