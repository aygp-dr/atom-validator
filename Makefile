.PHONY: help clean test repl jar install deploy outdated lint lint-fix storm check

CLOJARS_USER := apace

help:
	@echo "atom-validator - RFC 4287 Atom feed validation"
	@echo ""
	@echo "Development:"
	@echo "  make repl       - Start REPL with CIDER middleware"
	@echo "  make test       - Run test suite"
	@echo "  make lint       - Check for issues (clj-kondo)"
	@echo "  make storm      - Start FlowStorm time-travel debugger"
	@echo "  make check      - Run lint + test"
	@echo "  make outdated   - Check for outdated dependencies"
	@echo ""
	@echo "Build:"
	@echo "  make clean      - Remove build artifacts"
	@echo "  make jar        - Build JAR file"
	@echo "  make install    - Install to local Maven repo"
	@echo ""
	@echo "Publish:"
	@echo "  make deploy     - Deploy to Clojars (requires credentials)"

clean:
	clj -T:build clean

test:
	clj -X:test

repl:
	clj -M:dev:test -m nrepl.cmdline \
		--middleware '[cider.nrepl/cider-middleware]' \
		--bind 127.0.0.1 --port 7888

lint:
	clj -M:lint

# FlowStorm time-travel debugger
# After starting, run: (flow-storm.api/local-connect)
storm:
	clj -A:storm:test

check: lint test

jar:
	clj -T:build jar

install:
	clj -T:build install

# Deploy to Clojars using pass for credentials
# Username is account name, password is deploy token
deploy:
	@echo "Deploying to Clojars as $(CLOJARS_USER)..."
	CLOJARS_USERNAME=$(CLOJARS_USER) \
	CLOJARS_PASSWORD=$$(pass show clojars/$(CLOJARS_USER)/deploy-token-hydra) \
	clj -T:build deploy

outdated:
	clj -M:outdated
