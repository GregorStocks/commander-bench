-include .env

# The target directory is used for setting where the output zip files will end up
# You can override this with an environment variable, ex
# TARGET_DIR=my_custom_directory make deploy
# Alternatively, you can set this variable in the .env file
TARGET_DIR ?= deploy/

.PHONY: clean
clean:
	mvn clean

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
# Pass ARGS for additional options: make ai-harness ARGS="--skip-compile"
# Pass OUTPUT to specify recording path: make ai-harness OUTPUT=/path/to/video.mov
.PHONY: ai-harness
ai-harness:
	uv run --project puppeteer python -m puppeteer --streaming --record$(if $(OUTPUT),=$(OUTPUT)) $(ARGS)

# Convenience: skip compilation (common case)
.PHONY: ai-harness-quick
ai-harness-quick:
	uv run --project puppeteer python -m puppeteer --streaming --record$(if $(OUTPUT),=$(OUTPUT)) --skip-compile $(ARGS)

# LLM player mode: pilot AI + CPU opponents (consumes API tokens)
.PHONY: ai-harness-llm
ai-harness-llm:
	uv run --project puppeteer python -m puppeteer --streaming --record$(if $(OUTPUT),=$(OUTPUT)) --config puppeteer/ai-harness-llm-config.json $(ARGS)
