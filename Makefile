.PHONY: help clean test repl jar install deploy release outdated lint storm check ci coverage nvd security

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
	@echo "  make nvd        - Scan dependencies for CVEs"
	@echo "  make security   - Run lint + nvd scan"
	@echo "  make outdated   - Check for outdated dependencies"
	@echo ""
	@echo "Build:"
	@echo "  make clean      - Remove build artifacts"
	@echo "  make jar        - Build JAR file"
	@echo "  make install    - Install to local Maven repo"
	@echo ""
	@echo "Publish:"
	@echo "  make release    - Full release: check, tag, deploy, docs"
	@echo "  make deploy     - Deploy to Clojars (requires credentials)"
	@echo ""
	@echo "Credentials:"
	@echo "  pass otp clojars/apace/totp   - Get Clojars 2FA code"

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

# CVE dependency scanning (nvd-clojure)
# Install once: clojure -Ttools install nvd-clojure/nvd-clojure '{:mvn/version "RELEASE"}' :as nvd
nvd:
	@echo "Scanning dependencies for known CVEs..."
	clojure -Tnvd nvd.task/check :classpath '"'"$$(clojure -Spath)"'"'

# Full security check: static analysis + CVE scan
security: lint nvd

coverage:
	clojure -M:coverage

# Run everything CI runs
ci: lint test coverage jar
	@echo "CI checks passed"

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

# Full release workflow
# Version = 0.1.N where N = git commit count
release: check
	@VERSION=0.1.$$(git rev-list --count HEAD) && \
	echo "Releasing v$$VERSION..." && \
	bin/validate-deploy && \
	git tag v$$VERSION && \
	git push origin v$$VERSION && \
	$(MAKE) deploy && \
	curl -sX POST "https://cljdoc.org/api/request-build2" \
		-d "project=org.clojars.apace/atom-validator&version=$$VERSION" && \
	echo "Released v$$VERSION to Clojars"
