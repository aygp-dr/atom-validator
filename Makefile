.PHONY: help clean test repl jar install deploy release outdated lint storm check ci coverage nvd security tools changelog verify-publish

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
	@echo "  make nvd        - Scan dependencies for CVEs (needs API key)"
	@echo "  make security   - Run lint + nvd (advisory)"
	@echo "  make outdated   - Check for outdated dependencies"
	@echo "  make tools      - Download jing/trang for RelaxNG validation"
	@echo "  make changelog  - Generate CHANGELOG.md from git-cliff"
	@echo "  make verify-publish - E2E verify JAR on Clojars (requires release)"
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
# Install: clojure -Ttools install nvd-clojure/nvd-clojure '{:mvn/version "RELEASE"}' :as nvd
# API key: https://nvd.nist.gov/developers/request-an-api-key
# Config:  Add to nvd-clojure.edn: {:nvd {:nvd-api {:key "YOUR-KEY"}}}
# Note:    First run downloads ~360K CVE records - VERY slow without API key
nvd:
	@echo "Scanning dependencies for known CVEs..."
	clojure -Tnvd nvd.task/check :classpath '"'"$$(clojure -Spath)"'"' || echo "NVD scan failed (API key recommended)"

# Full security check: static analysis + CVE scan (nvd advisory only)
security: lint
	@$(MAKE) nvd || echo "Continuing without NVD (get API key for faster scans)"

# Download XML validation tools (jing, trang)
tools:
	@mkdir -p tools
	@echo "Downloading jing (RelaxNG validator)..."
	@curl -sL "https://repo1.maven.org/maven2/org/relaxng/jing/20220510/jing-20220510.jar" -o tools/jing.jar
	@echo "Downloading trang (schema converter)..."
	@curl -sL "https://repo1.maven.org/maven2/org/relaxng/trang/20220510/trang-20220510.jar" -o tools/trang.jar
	@echo "Converting atom.rnc to atom.rng..."
	@java -jar tools/trang.jar schemas/atom.rnc schemas/atom.rng
	@echo "Tools installed. Usage:"
	@echo "  java -jar tools/jing.jar schemas/atom.rng feed.xml"
	@echo "  xmllint --relaxng schemas/atom.rng feed.xml --noout"

# Generate CHANGELOG.md from git-cliff (conventional commits)
changelog:
	@command -v git-cliff >/dev/null || (echo "Install: pkg install git-cliff" && exit 1)
	git-cliff --output CHANGELOG.md
	@echo "CHANGELOG.md generated"

# E2E verify the latest version is actually available on Clojars
verify-publish:
	@VERSION="0.1.$$(git rev-list --count HEAD)" && \
	URL="https://repo.clojars.org/org/clojars/apace/atom-validator/$$VERSION/atom-validator-$$VERSION.pom" && \
	echo "Checking: $$URL" && \
	if curl -sf -o /dev/null "$$URL"; then \
	  echo "✓ v$$VERSION is published on Clojars"; \
	else \
	  echo "✗ v$$VERSION not found on Clojars"; \
	  exit 1; \
	fi

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
