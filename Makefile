.PHONY: help clean test repl jar install deploy outdated lint

CLOJARS_USER := apace

help:
	@echo "atom-validator - RFC 4287 Atom feed validation"
	@echo ""
	@echo "Development:"
	@echo "  make repl       - Start REPL with dev dependencies"
	@echo "  make test       - Run test suite"
	@echo "  make lint       - Check for issues"
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
	clj -A:dev

jar:
	clj -T:build jar

install:
	clj -T:build install

# Deploy to Clojars using pass for credentials
# Both CLOJARS_USERNAME and CLOJARS_PASSWORD should be the deploy token
deploy:
	@echo "Deploying to Clojars as $(CLOJARS_USER) (hydra-local token)..."
	CLOJARS_USERNAME=$$(pass clojars/$(CLOJARS_USER)/deploy-token-hydra) \
	CLOJARS_PASSWORD=$$(pass clojars/$(CLOJARS_USER)/deploy-token-hydra) \
	clj -T:build deploy

outdated:
	clj -M:outdated

lint:
	@echo "No linter configured yet"
