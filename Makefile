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

.PHONY: log-clean
log-clean:
	rm -rf .context/ai-harness-logs/*
	@echo "Cleaned ai-harness logs"

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
# Pass ARGS for additional options: make run-dumb ARGS="--skip-compile"
# Pass OUTPUT to specify recording path: make run-dumb OUTPUT=/path/to/video.mov
.PHONY: run-dumb
run-dumb:
	uv run --project puppeteer python -m puppeteer --streaming --record$(if $(OUTPUT),=$(OUTPUT)) $(ARGS)

# LLM player mode: pilot AI + CPU opponents (consumes API tokens)
.PHONY: run-llm
run-llm:
	uv run --project puppeteer python -m puppeteer --streaming --record$(if $(OUTPUT),=$(OUTPUT)) --config puppeteer/ai-harness-llm-config.json $(ARGS)
