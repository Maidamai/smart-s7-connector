# Releasing

This project has never published an artifact. The first official release is
**blocked until the license provenance decision is closed**
(see `docs/provenance.md`); until then nothing in this page may be executed.

The whole flow below is a maintainer-only, manual operation. No credentials
belong in this repository, in CI logs, or in issue tickets.

## Preconditions (all mandatory)

1. License provenance closed and documented in `docs/provenance.md`, with
   `pom.xml`/README license statements aligned to the decision.
2. `./mvnw -B -ntp clean verify` green on the release commit; CI green on the
   same commit for the full JDK matrix.
3. `CHANGELOG.md` updated with the release version and date.
4. Version in `pom.xml` set to the release version (drop `-SNAPSHOT`), and the
   coordinates confirmed unoccupied: check that
   `io.github.maidamai:smart-s7-connector` cannot already be resolved from
   Maven Central (a 404 from
   `https://repo1.maven.org/maven2/io/github/maidamai/smart-s7-connector/`
   is the expected state).
5. Namespace ownership (`io.github.maidamai`) verified in the Central Portal
   account that will publish.

## Build the release artifacts

```bash
./mvnw -B -ntp -Prelease clean verify
```

The `release` profile attaches sources, javadoc, and GPG signatures (requires
a local signing key; `doclint` is disabled for the Java-8-era javadoc).
Verify the produced files in `target/`: main jar, sources jar, javadoc jar,
and a `.asc` signature for each.

## Publish (Central Portal)

Follow the current official Central Publishing documentation — do not copy
tutorials, the portal flow changes:
<https://central.sonatype.org/publish/publish-portal-maven/>

Typical flow: configure `central-publishing-maven-plugin` (or upload the
bundle via the portal UI), publish, then validate the release in the portal
review queue before it syncs to Maven Central.

## Post-release verification

From a clean consumer project (empty local repository) resolve and use the
artifact:

```bash
mvn -B dependency:get -Dartifact=io.github.maidamai:smart-s7-connector:<version>
```

Run at least one public API example against the resolved artifact, not the
local build. If this fails, the release is not done.

## Tag and GitHub Release

- Tag the release commit as `v<version>` and push the tag.
- Create a GitHub Release for the tag with the changelog section for this
  version as the release notes. Main jar, sources, javadoc, and their
  signatures must correspond to exactly this version and commit.

## After releasing

- Bump `pom.xml` to the next `-SNAPSHOT` version on the development branch.
- Record evidence links (portal validation, consumer verification) in the
  internal application evidence notes; do not paste private data into the
  public repository.

## Never

- Never publish from a fork PR, or with CI secrets available to third-party
  workflows.
- Never override or roll back an already-synced Central version; a broken
  release is fixed by a new patch version.
- Never publish while the license provenance decision is open.
