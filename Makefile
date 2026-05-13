COMPOSE_INFRA = docker compose -f docker/docker-compose.yml
COMPOSE_ALL   = docker compose -f docker/docker-compose.yml -f docker/docker-compose.backend.yml

.PHONY: infra-up infra-down up down logs ps build-local

## 인프라만 실행 (PostgreSQL, Valkey, Kafka, LocalStack 등)
infra-up:
	$(COMPOSE_INFRA) up -d

infra-down:
	$(COMPOSE_INFRA) down

## ghcr.io에서 최신 이미지 pull 후 전체 실행 (프론트엔드 개발자용 — JDK 불필요)
up:
	$(COMPOSE_ALL) pull
	$(COMPOSE_ALL) up -d

## 로컬에서 직접 빌드 후 실행 (백엔드 개발자용 — 빠른 이터레이션)
build-local:
	cd backend && ./gradlew bootJar -x test
	$(COMPOSE_ALL) up -d --build

down:
	$(COMPOSE_ALL) down

logs:
	$(COMPOSE_ALL) logs -f

ps:
	$(COMPOSE_ALL) ps
