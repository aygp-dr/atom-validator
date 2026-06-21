<?xml version="1.0" encoding="UTF-8"?>
<!--
  Schematron Rules for Atom Feed Validation (RFC 4287)

  These rules express constraints that RelaxNG cannot enforce:
  - Cross-element dependencies (author inheritance)
  - Conditional requirements (content OR alternate link)
  - Business logic (feed freshness)
  - Uniqueness constraints (link combinations)

  Usage:
    java -jar saxon-he.jar -xsl:iso_svrl.xsl -s:feed.xml -o:report.xml schema=atom.sch

  Reference: RFC 4287 https://tools.ietf.org/html/rfc4287
-->
<sch:schema xmlns:sch="http://purl.oclc.org/dml/schematron"
            xmlns:atom="http://www.w3.org/2005/Atom"
            queryBinding="xslt2">

  <sch:title>Atom Feed Validation Rules (RFC 4287 Compliance)</sch:title>

  <sch:ns prefix="atom" uri="http://www.w3.org/2005/Atom"/>

  <!-- ================================================================== -->
  <!-- Pattern: Entry Content Requirement (RFC 4287 Section 4.1.2)        -->
  <!-- Entry MUST have either atom:content OR atom:link[@rel='alternate'] -->
  <!-- ================================================================== -->

  <sch:pattern id="entry-content-requirement">
    <sch:title>Entry Content or Alternate Link Requirement</sch:title>

    <sch:rule context="atom:entry">
      <sch:assert test="atom:content or atom:link[@rel='alternate']"
                  id="entry-content-or-alternate"
                  role="error">
        [RFC 4287 4.1.2] Entry MUST contain either an atom:content element
        or at least one atom:link element with rel="alternate".
        Entry ID: <sch:value-of select="atom:id"/>
      </sch:assert>

      <sch:report test="not(atom:content) and atom:link[@rel='alternate']"
                  id="entry-no-content-has-alternate"
                  role="info">
        Entry has no atom:content but provides alternate link(s).
        Entry ID: <sch:value-of select="atom:id"/>
      </sch:report>
    </sch:rule>
  </sch:pattern>

  <!-- ================================================================== -->
  <!-- Pattern: Author Inheritance (RFC 4287 Section 4.1.2)               -->
  <!-- If feed has no author, each entry MUST have its own author         -->
  <!-- ================================================================== -->

  <sch:pattern id="author-inheritance">
    <sch:title>Author Inheritance Rules</sch:title>

    <!-- Check entries when feed has no author -->
    <sch:rule context="atom:feed[not(atom:author)]/atom:entry">
      <sch:assert test="atom:author or atom:source/atom:author"
                  id="entry-author-required"
                  role="error">
        [RFC 4287 4.1.2] When feed has no atom:author, each entry MUST
        contain at least one atom:author element (or inherit from atom:source).
        Entry ID: <sch:value-of select="atom:id"/>
      </sch:assert>
    </sch:rule>

    <!-- Report when feed author is inherited -->
    <sch:rule context="atom:feed[atom:author]/atom:entry[not(atom:author)]">
      <sch:report test="true()"
                  id="entry-inherits-feed-author"
                  role="info">
        Entry inherits author from feed.
        Entry ID: <sch:value-of select="atom:id"/>
        Feed Author: <sch:value-of select="ancestor::atom:feed/atom:author/atom:name"/>
      </sch:report>
    </sch:rule>
  </sch:pattern>

  <!-- ================================================================== -->
  <!-- Pattern: Feed Freshness (atom-validator semantic check)            -->
  <!-- Feed updated SHOULD be >= max(entry updated)                       -->
  <!-- ================================================================== -->

  <sch:pattern id="feed-freshness">
    <sch:title>Feed Updated Freshness Check</sch:title>

    <sch:rule context="atom:feed">
      <!-- Warning if feed updated is older than any entry updated -->
      <sch:report test="some $entry in atom:entry satisfies
                        xs:dateTime($entry/atom:updated) > xs:dateTime(atom:updated)"
                  id="stale-feed-updated"
                  role="warning">
        [atom-validator :stale-feed-updated] Feed updated timestamp
        (<sch:value-of select="atom:updated"/>) is older than one or more
        entry updated timestamps. The feed updated element should reflect
        the most recent modification to the feed or its entries.
      </sch:report>

      <!-- Report the freshest entry for diagnostic purposes -->
      <sch:report test="atom:entry"
                  id="feed-freshness-info"
                  role="info">
        Feed updated: <sch:value-of select="atom:updated"/>.
        Entry count: <sch:value-of select="count(atom:entry)"/>.
      </sch:report>
    </sch:rule>
  </sch:pattern>

  <!-- ================================================================== -->
  <!-- Pattern: Link Uniqueness (RFC 4287 Section 4.2.7)                  -->
  <!-- No duplicate rel + type + hreflang combinations per container      -->
  <!-- ================================================================== -->

  <sch:pattern id="link-uniqueness">
    <sch:title>Link Attribute Combination Uniqueness</sch:title>

    <sch:rule context="atom:feed | atom:entry | atom:source">
      <!-- Check for duplicate link combinations within this container -->
      <sch:assert test="not(
                    some $link1 in atom:link,
                         $link2 in atom:link[. >> $link1]
                    satisfies (
                      ($link1/@rel = $link2/@rel or (not($link1/@rel) and not($link2/@rel)))
                      and ($link1/@type = $link2/@type or (not($link1/@type) and not($link2/@type)))
                      and ($link1/@hreflang = $link2/@hreflang or (not($link1/@hreflang) and not($link2/@hreflang)))
                    )
                  )"
                  id="duplicate-link-combination"
                  role="error">
        [RFC 4287 4.2.7] Duplicate link found with same rel, type, and hreflang
        combination. Each link MUST have a unique combination of these attributes
        within its parent element.
        Context: <sch:value-of select="local-name()"/>
        <sch:value-of select="if (atom:id) then concat(' ID: ', atom:id) else ''"/>
      </sch:assert>
    </sch:rule>

    <!-- Specific check for alternate links -->
    <sch:rule context="atom:link[@rel='alternate' or not(@rel)]">
      <sch:let name="parent" value="parent::*"/>
      <sch:let name="my-type" value="@type"/>
      <sch:let name="my-hreflang" value="@hreflang"/>

      <sch:report test="preceding-sibling::atom:link
                        [(@rel='alternate' or not(@rel))]
                        [(@type = $my-type) or (not(@type) and not($my-type))]
                        [(@hreflang = $my-hreflang) or (not(@hreflang) and not($my-hreflang))]"
                  id="duplicate-alternate-link"
                  role="error">
        [RFC 4287 4.2.7] Duplicate alternate link. An atom:feed/entry MUST NOT
        contain more than one atom:link element with rel="alternate" that has
        the same combination of type and hreflang attribute values.
        href: <sch:value-of select="@href"/>
      </sch:report>
    </sch:rule>
  </sch:pattern>

  <!-- ================================================================== -->
  <!-- Pattern: ID Format (RFC 4287 Section 4.2.6)                        -->
  <!-- IDs SHOULD be valid IRIs (we check for basic URI structure)        -->
  <!-- ================================================================== -->

  <sch:pattern id="id-format">
    <sch:title>ID Format Validation</sch:title>

    <sch:rule context="atom:id">
      <!-- Check for valid IRI-like structure -->
      <!-- RFC 4287: atom:id MUST be a valid IRI (RFC 3987) -->

      <!-- Basic structure check: should contain a colon for scheme -->
      <sch:assert test="contains(normalize-space(.), ':')"
                  id="id-no-scheme"
                  role="error">
        [RFC 4287 4.2.6] atom:id MUST be a valid IRI. The ID appears to lack
        a scheme (no colon found).
        ID value: <sch:value-of select="."/>
      </sch:assert>

      <!-- Warn about empty or whitespace-only IDs -->
      <sch:assert test="normalize-space(.) != ''"
                  id="id-empty"
                  role="error">
        [RFC 4287 4.2.6] atom:id MUST NOT be empty.
      </sch:assert>

      <!-- Check for common IRI schemes -->
      <sch:let name="scheme" value="substring-before(normalize-space(.), ':')"/>
      <sch:report test="not($scheme = ('http', 'https', 'urn', 'tag', 'uuid', 'mailto', 'ftp'))"
                  id="id-unusual-scheme"
                  role="info">
        ID uses non-standard scheme '<sch:value-of select="$scheme"/>'.
        Common schemes: http, https, urn, tag, uuid.
        ID value: <sch:value-of select="."/>
      </sch:report>

      <!-- Tag URI format check (RFC 4151) -->
      <sch:report test="starts-with(normalize-space(.), 'tag:') and
                        not(matches(normalize-space(.), '^tag:[a-zA-Z0-9._-]+@?[a-zA-Z0-9._-]*,\d{4}(-\d{2}(-\d{2})?)?:'))"
                  id="id-malformed-tag-uri"
                  role="warning">
        Tag URI may be malformed. Expected format: tag:authority,date:specific
        ID value: <sch:value-of select="."/>
      </sch:report>

      <!-- URN format check -->
      <sch:report test="starts-with(normalize-space(.), 'urn:') and
                        not(matches(normalize-space(.), '^urn:[a-zA-Z0-9][a-zA-Z0-9-]*:[^\s]+$'))"
                  id="id-malformed-urn"
                  role="warning">
        URN may be malformed. Expected format: urn:namespace:specific-string
        ID value: <sch:value-of select="."/>
      </sch:report>
    </sch:rule>
  </sch:pattern>

  <!-- ================================================================== -->
  <!-- Pattern: Summary Requirement (RFC 4287 Section 4.1.2)              -->
  <!-- Summary SHOULD be provided if content is not inline                -->
  <!-- ================================================================== -->

  <sch:pattern id="summary-recommendation">
    <sch:title>Summary Recommendation for Non-Inline Content</sch:title>

    <sch:rule context="atom:entry[atom:content[@src]]">
      <sch:report test="not(atom:summary)"
                  id="missing-summary-for-remote-content"
                  role="warning">
        [RFC 4287 4.1.2] Entry with out-of-line content (src attribute)
        SHOULD provide an atom:summary element.
        Entry ID: <sch:value-of select="atom:id"/>
        Content src: <sch:value-of select="atom:content/@src"/>
      </sch:report>
    </sch:rule>

    <sch:rule context="atom:entry[atom:content[@type and
                        not(@type = ('text', 'html', 'xhtml')) and
                        not(contains(@type, 'xml')) and
                        not(starts-with(@type, 'text/'))]]">
      <sch:report test="not(atom:summary)"
                  id="missing-summary-for-non-text-content"
                  role="warning">
        [RFC 4287 4.1.2] Entry with non-text content type
        '<sch:value-of select="atom:content/@type"/>'
        SHOULD provide an atom:summary element.
        Entry ID: <sch:value-of select="atom:id"/>
      </sch:report>
    </sch:rule>
  </sch:pattern>

  <!-- ================================================================== -->
  <!-- Pattern: Date Format (RFC 4287 Section 3.3)                        -->
  <!-- Dates MUST conform to RFC 3339 (subset of ISO 8601)                -->
  <!-- ================================================================== -->

  <sch:pattern id="date-format">
    <sch:title>RFC 3339 Date Format Validation</sch:title>

    <sch:rule context="atom:updated | atom:published">
      <!-- RFC 3339 date-time pattern -->
      <sch:assert test="matches(normalize-space(.),
                        '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})$')"
                  id="invalid-date-format"
                  role="error">
        [RFC 4287 3.3] Date MUST conform to RFC 3339 format:
        YYYY-MM-DDTHH:MM:SS.sssZ or YYYY-MM-DDTHH:MM:SS+HH:MM
        Found: <sch:value-of select="."/>
      </sch:assert>
    </sch:rule>
  </sch:pattern>

  <!-- ================================================================== -->
  <!-- Pattern: Link Href Validation                                      -->
  <!-- Link href MUST be a valid IRI reference                            -->
  <!-- ================================================================== -->

  <sch:pattern id="link-href-validation">
    <sch:title>Link Href Validation</sch:title>

    <sch:rule context="atom:link">
      <sch:assert test="@href and normalize-space(@href) != ''"
                  id="link-empty-href"
                  role="error">
        [RFC 4287 4.2.7] atom:link MUST have a non-empty href attribute.
      </sch:assert>

      <!-- Check for self link -->
      <sch:report test="@rel = 'self' and not(starts-with(@href, 'http://') or starts-with(@href, 'https://'))"
                  id="self-link-not-absolute"
                  role="warning">
        Self link SHOULD be an absolute URL.
        href: <sch:value-of select="@href"/>
      </sch:report>
    </sch:rule>

    <!-- Feed should have a self link -->
    <sch:rule context="atom:feed">
      <sch:report test="not(atom:link[@rel='self'])"
                  id="feed-missing-self-link"
                  role="warning">
        Feed SHOULD contain an atom:link element with rel="self" pointing
        to the feed's canonical URL.
      </sch:report>
    </sch:rule>
  </sch:pattern>

  <!-- ================================================================== -->
  <!-- Pattern: Rights Inheritance                                        -->
  <!-- Document rights inheritance model                                  -->
  <!-- ================================================================== -->

  <sch:pattern id="rights-inheritance">
    <sch:title>Rights Element Inheritance</sch:title>

    <sch:rule context="atom:feed[atom:rights]/atom:entry[not(atom:rights)]">
      <sch:report test="true()"
                  id="entry-inherits-feed-rights"
                  role="info">
        Entry inherits rights statement from feed.
        Entry ID: <sch:value-of select="atom:id"/>
      </sch:report>
    </sch:rule>
  </sch:pattern>

</sch:schema>
