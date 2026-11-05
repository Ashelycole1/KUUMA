# Included by the small Makefile in each owner folder, so `make <target>` works there too.
# `make test` here runs only this folder's tests; everything else defers to the root.
FOLDER_ROOT := $(patsubst %/tools/,%,$(dir $(abspath $(lastword $(MAKEFILE_LIST)))))

.DEFAULT_GOAL := help
%:
	@$(MAKE) --no-print-directory -f $(FOLDER_ROOT)/Makefile $@ TEST_PATH="$(CURDIR)"
