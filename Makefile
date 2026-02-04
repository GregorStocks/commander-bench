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

.PHONY: ai-harness
ai-harness:
	uv run --project puppeteer python -m puppeteer --streaming

.PHONY: ai-harness-record
ai-harness-record:
	uv run --project puppeteer python -m puppeteer --streaming --record

.PHONY: ai-harness-record-to
ai-harness-record-to:
	@test -n "$(OUTPUT)" || (echo "Usage: make ai-harness-record-to OUTPUT=/path/to/video.mov" && exit 1)
	uv run --project puppeteer python -m puppeteer --streaming --record=$(OUTPUT)

.PHONY: ai-harness-skip-compile
ai-harness-skip-compile:
	uv run --project puppeteer python -m puppeteer --streaming --skip-compile

.PHONY: ai-harness-record-skip-compile
ai-harness-record-skip-compile:
	uv run --project puppeteer python -m puppeteer --streaming --record --skip-compile
