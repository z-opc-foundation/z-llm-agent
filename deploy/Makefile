# z-llm-agent-proxy 部署快捷键
.PHONY: help package image up down logs clean

IMAGE ?= ghcr.io/yuku123/z-llm-agent-proxy
VERSION ?= 1.0.0

help:
	@echo "z-llm-agent-proxy targets:"
	@echo "  make package  编译 fat jar（跳过测试）"
	@echo "  make image    构建镜像 $(IMAGE):$(VERSION)"
	@echo "  make up       启动"
	@echo "  make down     停止"
	@echo "  make logs     看日志"

package:
	cd .. && mvn -B -DskipTests package

image: package
	docker build -f Dockerfile -t $(IMAGE):$(VERSION) ..

up:
	IMAGE_VERSION=$(VERSION) docker compose up -d

down:
	docker compose down

logs:
	docker compose logs -f --tail=100

clean:
	cd .. && mvn -B -q clean
