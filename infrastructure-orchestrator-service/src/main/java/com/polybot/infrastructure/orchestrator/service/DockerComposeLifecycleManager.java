package com.polybot.infrastructure.orchestrator.service;

import com.polybot.infrastructure.orchestrator.config.InfrastructureProperties;
import com.polybot.infrastructure.orchestrator.config.InfrastructureProperties.DockerComposeStack;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DockerComposeLifecycleManager {

    private final InfrastructureProperties properties;
    private volatile boolean stacksRunning = false;

    @PostConstruct
    public void startInfrastructureStacks() {
        log.info("Starting infrastructure stacks lifecycle manager");
        try {
            // Sort stacks by startup order
            List<DockerComposeStack> orderedStacks = properties.getStacks().stream()
                .sorted(Comparator.comparingInt(DockerComposeStack::getStartupOrder))
                .toList();

            for (DockerComposeStack stack : orderedStacks) {
                log.info("═══════════════════════════════════════════");
                log.info("Starting stack: {}", stack.getName());
                log.info("═══════════════════════════════════════════");

                validateDockerComposeFile(stack);
                cleanupExistingContainers(stack);
                startStack(stack);
                waitForStackReadiness(stack);

                log.info("✓ Stack '{}' is UP and READY", stack.getName());
            }

            stacksRunning = true;
            log.info("═══════════════════════════════════════════");
            log.info("✓ All infrastructure stacks are UP and READY");
            log.info("═══════════════════════════════════════════");
        } catch (Exception e) {
            log.error("Failed to start infrastructure stacks", e);
            throw new RuntimeException("Failed to start infrastructure stacks", e);
        }
    }

    @PreDestroy
    public void stopInfrastructureStacks() {
        if (!stacksRunning) {
            log.info("Infrastructure stacks are not running, skipping shutdown");
            return;
        }

        log.info("Stopping infrastructure stacks");

        // Stop in reverse order
        List<DockerComposeStack> reverseOrderStacks = properties.getStacks().stream()
            .sorted(Comparator.comparingInt(DockerComposeStack::getStartupOrder).reversed())
            .toList();

        for (DockerComposeStack stack : reverseOrderStacks) {
            try {
                log.info("Stopping stack: {}", stack.getName());
                stopStack(stack);
                log.info("✓ Stack '{}' stopped", stack.getName());
            } catch (Exception e) {
                log.error("Failed to stop stack '{}' cleanly", stack.getName(), e);
            }
        }

        stacksRunning = false;
        log.info("✓ All infrastructure stacks stopped");
    }

    private void validateDockerComposeFile(DockerComposeStack stack) throws IOException {
        Path composePath = resolveComposeFilePath(stack);
        if (!Files.exists(composePath)) {
            throw new IllegalStateException(
                "Docker Compose file not found: " + composePath +
                " for stack '" + stack.getName() + "'" +
                "\nCurrent working directory: " + Paths.get(".").toAbsolutePath()
            );
        }
        log.info("Found docker-compose file at: {}", composePath);
        // Update stack with resolved path
        stack.setFilePath(composePath.toString());
    }

    private Path resolveComposeFilePath(DockerComposeStack stack) {
        String configuredPath = stack.getFilePath();
        Path path = Paths.get(configuredPath);

        // If absolute path exists, use it
        if (path.isAbsolute() && Files.exists(path)) {
            return path;
        }

        // Try relative to current working directory
        Path cwdPath = Paths.get(".").toAbsolutePath().resolve(configuredPath).normalize();
        if (Files.exists(cwdPath)) {
            return cwdPath;
        }

        // Try relative to project root (go up from module directory)
        Path projectRootPath = Paths.get("..").toAbsolutePath().resolve(configuredPath).normalize();
        if (Files.exists(projectRootPath)) {
            return projectRootPath;
        }

        // Return the configured path as-is (will fail in validation)
        return path.toAbsolutePath();
    }

    private void cleanupExistingContainers(DockerComposeStack stack) {
        log.info("Checking for existing containers...");
        try {
            ProcessBuilder pb = new ProcessBuilder(
                "docker", "compose",
                "-f", stack.getFilePath(),
                "-p", stack.getProjectName(),
                "ps", "-q"
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            List<String> output = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.add(line.trim());
                }
            }
            process.waitFor();

            // If there are existing containers, clean them up
            if (!output.isEmpty() && output.stream().anyMatch(line -> !line.isEmpty())) {
                log.info("Found existing containers, cleaning up...");
                stopStack(stack);
                log.info("Cleanup complete");
            } else {
                log.debug("No existing containers found");
            }
        } catch (Exception e) {
            log.warn("Failed to check for existing containers, continuing anyway", e);
        }
    }

    private void startStack(DockerComposeStack stack) throws IOException, InterruptedException {
        log.info("Executing: docker compose up -d");

        ProcessBuilder pb = new ProcessBuilder(
            "docker", "compose",
            "-f", stack.getFilePath(),
            "-p", stack.getProjectName(),
            "up", "-d"
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();

        // Log output
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.info("docker-compose: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("Docker Compose up failed with exit code: " + exitCode);
        }
    }

    private void waitForStackReadiness(DockerComposeStack stack) throws InterruptedException, IOException {
        log.info("Waiting for services to become healthy...");

        int attempts = properties.getStartupTimeoutSeconds() / properties.getHealthCheckIntervalSeconds();

        for (int i = 0; i < attempts; i++) {
            if (isStackHealthy(stack)) {
                log.info("All services are healthy after {} seconds",
                    (i + 1) * properties.getHealthCheckIntervalSeconds());
                return;
            }

            log.debug("Health check {}/{} - waiting {} seconds...",
                i + 1, attempts, properties.getHealthCheckIntervalSeconds());
            TimeUnit.SECONDS.sleep(properties.getHealthCheckIntervalSeconds());
        }

        log.warn("Some services may not be fully healthy after {} seconds, but continuing...",
            properties.getStartupTimeoutSeconds());
    }

    private boolean isStackHealthy(DockerComposeStack stack) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(
            "docker", "compose",
            "-f", stack.getFilePath(),
            "-p", stack.getProjectName(),
            "ps", "--format", "json"
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();

        List<String> output = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.add(line);
            }
        }

        process.waitFor();

        // Check if all expected services are running
        long runningCount = output.stream()
            .filter(line -> line.contains("\"State\":\"running\"") || line.contains("\"running\""))
            .count();

        return runningCount >= stack.getExpectedServices();
    }

    private void stopStack(DockerComposeStack stack) throws IOException, InterruptedException {
        log.debug("Executing: docker compose down");

        ProcessBuilder pb = new ProcessBuilder(
            "docker", "compose",
            "-f", stack.getFilePath(),
            "-p", stack.getProjectName(),
            "down"
        );
        pb.redirectErrorStream(true);

        Process process = pb.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("docker-compose: {}", line);
            }
        }

        process.waitFor(30, TimeUnit.SECONDS);
    }

    public InfrastructureStatus getInfrastructureStatus() {
        List<StackStatus> stackStatuses = new ArrayList<>();

        for (DockerComposeStack stack : properties.getStacks()) {
            try {
                ProcessBuilder pb = new ProcessBuilder(
                    "docker", "compose",
                    "-f", stack.getFilePath(),
                    "-p", stack.getProjectName(),
                    "ps", "--format", "json"
                );
                pb.redirectErrorStream(true);

                Process process = pb.start();

                List<String> services = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        services.add(line);
                    }
                }

                process.waitFor();

                long runningCount = services.stream()
                    .filter(line -> line.contains("\"State\":\"running\"") || line.contains("\"running\""))
                    .count();

                String healthStatus = runningCount >= stack.getExpectedServices() ? "HEALTHY" : "DEGRADED";

                stackStatuses.add(new StackStatus(
                    stack.getName(),
                    services.size(),
                    (int) runningCount,
                    stack.getExpectedServices(),
                    healthStatus
                ));

            } catch (Exception e) {
                log.error("Failed to get status for stack '{}'", stack.getName(), e);
                stackStatuses.add(new StackStatus(
                    stack.getName(),
                    0,
                    0,
                    stack.getExpectedServices(),
                    "ERROR: " + e.getMessage()
                ));
            }
        }

        boolean allHealthy = stackStatuses.stream()
            .allMatch(s -> s.healthStatus.equals("HEALTHY"));

        return new InfrastructureStatus(
            stacksRunning,
            allHealthy ? "HEALTHY" : "DEGRADED",
            stackStatuses
        );
    }

    public record InfrastructureStatus(
        boolean managed,
        String overallHealth,
        List<StackStatus> stacks
    ) {}

    public record StackStatus(
        String name,
        int totalServices,
        int runningServices,
        int expectedServices,
        String healthStatus
    ) {}
}
