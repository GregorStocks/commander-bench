-include .env

# The target directory is used for setting where the output zip files will end up
# You can override this with an environment variable, ex
# TARGET_DIR=my_custom_directory make deploy
# Alternatively, you can set this variable in the .env file
TARGET_DIR ?= deploy/

.PHONY: clean
clean:
	mvn clean

.PHONY: lint
lint:
	uv run python scripts/lint-issues.py
	uv run --project puppeteer ruff check puppeteer/ scripts/

.PHONY: lint-fix
lint-fix:
	uv run --project puppeteer ruff check --fix puppeteer/ scripts/

.PHONY: format
format:
	uv run --project puppeteer ruff format puppeteer/ scripts/

.PHONY: format-check
format-check:
	uv run --project puppeteer ruff format --check puppeteer/ scripts/

.PHONY: typecheck
typecheck:
	uv run --project puppeteer mypy --config-file puppeteer/pyproject.toml puppeteer/src/puppeteer/

.PHONY: test
test:
	uv run --project puppeteer pytest puppeteer/

.PHONY: check
check: lint format-check typecheck test


.PHONY: build
build:
	mvn install package -DskipTests

.PHONY: package
package:
	# Packaging Mage.Client to zip
	cd Mage.Client && mvn package assembly:single
	# Packaging Mage.Server to zip
	cd Mage.Server && mvn package assembly:single
	# Copying the files to the target directory
	mkdir -p $(TARGET_DIR)
	cp ./Mage.Server/target/mage-server.zip $(TARGET_DIR)
	cp ./Mage.Client/target/mage-client.zip $(TARGET_DIR)

# Note that the proper install script is located under ./Utils/build-and-package.pl
# and that should be used instead. This script is purely for convenience.
# The perl script bundles the artifacts into a single zip
.PHONY: install
install: clean build package

# Default: streaming with recording enabled
# Pass OUTPUT to specify recording path: make run-dumb OUTPUT=/path/to/video.mov
# Overlay controls: make run-dumb ARGS="--overlay-port 18080"
# Disable overlay: make run-dumb ARGS="--no-overlay"
.PHONY: run-dumb
run-dumb:
	uv run --project puppeteer python -m puppeteer --streaming --record$(if $(OUTPUT),=$(OUTPUT)) $(ARGS)

# LLM player mode: pilot AI + CPU opponents (consumes API tokens)
.PHONY: run-llm
run-llm:
	uv run --project puppeteer python -m puppeteer --streaming --record$(if $(OUTPUT),=$(OUTPUT)) --config puppeteer/ai-harness-llm-config.json $(ARGS)

# 4-LLM mode: 4 different LLM pilots battle each other (consumes API tokens)
.PHONY: run-llm4
run-llm4:
	uv run --project puppeteer python -m puppeteer --streaming --record$(if $(OUTPUT),=$(OUTPUT)) --config puppeteer/ai-harness-llm4-config.json $(ARGS)

# Generate mcp-tools.json with MCP tool definitions
.PHONY: mcp-tools
mcp-tools:
	cd Mage.Client.Headless && mvn -q exec:exec -Dexec.executable=java '-Dexec.args=-cp %classpath mage.client.headless.McpServer' > ../website/src/data/mcp-tools.json

# 1v1 Legacy: CPU players, no API keys needed
.PHONY: run-legacy-dumb
run-legacy-dumb:
	uv run --project puppeteer python -m puppeteer --streaming --record$(if $(OUTPUT),=$(OUTPUT)) --config puppeteer/ai-harness-legacy-dumb-config.json $(ARGS)

# 1v1 Legacy: Gemini vs Claude (consumes API tokens)
.PHONY: run-legacy-llm
run-legacy-llm:
	uv run --project puppeteer python -m puppeteer --streaming --record$(if $(OUTPUT),=$(OUTPUT)) --config puppeteer/ai-harness-legacy-llm-config.json $(ARGS)

# Launch the desktop client (for image downloads, deck building, etc.)
.PHONY: run-client
run-client:
	cd Mage.Client && mvn -q exec:java

# Run the website dev server
.PHONY: website
website:
	cd website && npm install && npx astro dev

# Export a game log for the website visualizer
# Usage: make export-game GAME=game_20260208_220934
.PHONY: export-game
export-game:
	python3 scripts/export_game.py $(GAME)

# Standalone test server (stays running until Ctrl-C)
# Optional: make run-staller PORT=18080
.PHONY: run-staller
run-staller:
	@PORT_VALUE=$${PORT:-17171}; \
	CONFIG_PATH="$(PWD)/.context/ai-harness-logs/server_config_$${PORT_VALUE}.xml"; \
	mkdir -p "$(PWD)/.context/ai-harness-logs"; \
	PORT=$$PORT_VALUE CONFIG_PATH="$$CONFIG_PATH" uv run --project puppeteer python -c "import os; from pathlib import Path; from puppeteer.xml_config import modify_server_config; modify_server_config(Path('Mage.Server/config/config.xml'), Path(os.environ['CONFIG_PATH']), int(os.environ['PORT']))"; \
	echo "Starting staller server on localhost:$$PORT_VALUE"; \
	echo "Config: $$CONFIG_PATH"; \
	cd Mage.Server && MAVEN_OPTS="-Dxmage.testMode=true -Dxmage.config.path=$$CONFIG_PATH" mvn -q exec:java
