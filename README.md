# Appointments API

Patients book medical appointments; the service assigns a doctor and a room without
overbooking, then updates external systems and emails the patient.

Design: [docs/HLD.md](docs/HLD.md) · Decisions: [DECISIONS.md](DECISIONS.md) ·
Implementation plan: [docs/PLAN.md](docs/PLAN.md)

> Work in progress — this README is completed in Step 8 of the plan.

## Prerequisites

- Java 21 (`JAVA_HOME` pointing to a JDK 21)
- Docker Desktop (running) — the tests use Testcontainers

Maven is not required: the project ships with the Maven Wrapper.

## Run the tests

macOS / Linux:

```bash
./mvnw verify
```

Windows (PowerShell or cmd):

```powershell
.\mvnw.cmd verify
```

`verify` also checks formatting (Spotless + google-java-format). To fix formatting:
`./mvnw spotless:apply` (or `.\mvnw.cmd spotless:apply`).

## Run locally

```bash
docker compose up -d postgres
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Health check: `GET http://localhost:8080/actuator/health`.
