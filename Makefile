# Nilinde: one set of commands for every language. Run from the repo root, or from your
# own folder (modules/<build>/, ai/, packages/<pkg>/, apps/<app>/, fakes/) for `make test`.
#
# Windows: run from Git Bash. If `make` is missing: winget install ezwinports.make

ROOT := $(patsubst %/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))
PY := $(shell python3 -c "import sys" >/dev/null 2>&1 && echo python3 || echo python)

# Toolchain paths written by `make bootstrap` (Flutter, Android SDK, JDK, uv, pnpm).
-include $(ROOT)/.kuuma/env.mk

UV := uv
SCENARIO ?= luganda-insults
TEST_PATH ?= $(CURDIR)

.DEFAULT_GOAL := help
.PHONY: help bootstrap dev dev-down dev-logs gen gen-check test check format fake apk clean

help: ## Show the commands
	@grep -hE '^[a-z-]+:.*## ' $(ROOT)/Makefile | awk 'BEGIN {FS = ":.*## "}; {printf "  make %-12s %s\n", $$1, $$2}'

bootstrap: ## Install toolchains and dependencies for every language
	@$(PY) $(ROOT)/tools/bootstrap.py

dev: ## Start the local stack: PostgreSQL, Redis, MinIO, every service, the gateway
	@$(PY) $(ROOT)/tools/dev.py up

dev-down: ## Stop the local stack (keeps data volumes)
	@$(PY) $(ROOT)/tools/dev.py down

dev-logs: ## Follow the local stack's logs
	@$(PY) $(ROOT)/tools/dev.py logs

gen: ## Regenerate contract types (and lang/scenario bundles) from the sources
	@cd $(ROOT) && $(PY) packages/contracts/codegen/gen.py
	@cd $(ROOT) && $(UV) run python packages/lang/tools/build.py
	@cd $(ROOT) && $(UV) run python -m kuuma_fakes.build_scenarios

gen-check: ## Fail if generated code is out of date (CI codegen drift check)
	@cd $(ROOT) && $(PY) packages/contracts/codegen/gen.py --check
	@cd $(ROOT) && $(UV) run python packages/lang/tools/build.py --check
	@cd $(ROOT) && $(UV) run python -m kuuma_fakes.build_scenarios --check

test: ## Unit and contract tests for the current folder, or everything from the root
	@$(UV) run --project $(ROOT) python $(ROOT)/tools/run_tests.py "$(TEST_PATH)"

check: ## Lint, format, boundary check, privacy scan, version pins, codegen drift
	@$(UV) run --project $(ROOT) python $(ROOT)/tools/check.py

format: ## Apply every formatter
	@$(UV) run --project $(ROOT) python $(ROOT)/tools/check.py --fix

fake: ## Play a scripted chat through the fakes: make fake SCENARIO=<name>
	@cd $(ROOT) && $(UV) run kuuma-fake $(SCENARIO)

apk: ## Build the debug child and parent APKs into out/apk/
	@$(UV) run --project $(ROOT) python $(ROOT)/tools/apk.py

clean: ## Remove build outputs
	@cd $(ROOT) && ./gradlew clean -q || true
	@rm -rf $(ROOT)/out
