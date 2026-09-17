# Releasing

The first public artifact is the pre-release `v1.0.0-rc.1` (2026-09-17,
GitHub Release with GPG-signed jars; tag `v1.0.0-rc.1`). It is **not** on
Maven Central yet: publication requires maintainer Central Portal
credentials, which do not exist in this environment or repository. The
license provenance decision was closed on 2026-09-17 (split per-file
licensing, `docs/provenance.md` §5); the license-side release gate is
lifted and the flow below may be executed once the remaining preconditions
are met.

The whole flow below is a maintainer-only, manual operation. No credentials
belong in this repository, in CI logs, or in issue tickets.

## Preconditions (all mandatory)

1. License declarations match `docs/provenance.md` §5: two `<license>`
   entries in `pom.xml`, and the built main/sources JARs contain
   `META-INF/LICENSE`, `META-INF/NOTICE`,
   `META-INF/LICENSE_LIBNODAVE.txt`, `META-INF/THIRD_PARTY_NOTICES.md`,
   and `META-INF/licenses/LGPL-2.0.txt` (verify by unpacking before
   publishing). CI automates this on every push to master and every pull
   request: the `release-dry-run` job in `.github/workflows/ci.yml` builds
   with `-Prelease -Dgpg.skip=true` and fails when any of these resources
   is missing — but re-check the actual release artifacts by hand as well.
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
- Never publish with license files missing from the artifacts; the
  META-INF checklist in precondition 1 is part of every release.
