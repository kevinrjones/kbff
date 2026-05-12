## 2026-05-09
### 06:09

Recap of the work completed to modernize the project's CI/CD pipeline and documentation:

- **CI/CD with GitHub Actions**:
    - Created `.github/workflows/build.yml` to automate building and testing on every push and pull request to the `main` branch.
    - Configured the workflow to use JDK 21 and the latest Gradle setup actions.
- **Enhanced Documentation**:
    - Revitalized `README.md` with a professional layout, including status badges for build, Kotlin/Ktor versions, and JDK requirements.
    - Added a comprehensive "Built-in Endpoints" table and updated usage examples to match the current API.
    - Detailed the prerequisites and installation steps for consuming the library via GitHub Packages.
- **Automated Publishing & Releases**:
    - Configured `maven-publish` in `build.gradle.kts` to distribute the library through GitHub Packages, including sources and Javadoc.
    - Integrated `actions/upload-artifact@v4` to store build artifacts for every run.
    - Configured `softprops/action-gh-release@v2` to automatically create GitHub Releases and upload JAR files when version tags (e.g., `v0.1.0`) are pushed.
- **Build Infrastructure**:
    - Verified Gradle tasks for assembly and publishing.
    - Ensured consistent JDK 21 toolchain configuration across the project.

Git commits included in this update:
- `8459439` — Enable tag builds and add release creation step to CI workflow
- `4817fcb` — Simplify CI workflow by removing debug `ls` steps and renaming artifact to `kbff`
- `2e40a9f` — Update actions and dependencies in CI workflow to latest versions
- `ba1d567` — Add artifact upload step to CI workflow
- `5e6b633` — Remove publish step from CI workflow (subsequently re-enabled and automated)

### 06:28

Recap of the migration to Maven Central (Sonatype):

- **Maven Central Publishing**:
    - Configured `build.gradle.kts` to publish to Sonatype OSSRH (s01 instance).
    - Added full POM metadata (name, description, licenses, developers, SCM) as required by Maven Central.
    - Integrated the `signing` plugin to sign artifacts using GPG before publication.
- **GitHub Actions Update**:
    - Replaced the GitHub Packages publishing step with a dedicated Sonatype publishing step.
    - Configured the workflow to use secrets for Sonatype credentials (`OSSRH_USERNAME`, `OSSRH_PASSWORD`) and GPG signing (`MAVEN_GPG_PRIVATE_KEY`, `MAVEN_GPG_PASSPHRASE`).
- **Documentation**:
    - Updated `README.md` to simplify installation instructions, as the library is now targeted for Maven Central distribution.

### 09:04

Recap of the transition to the `com.vanniktech.maven.publish` plugin:

- **Simplified Publishing Pipeline**:
    - Migrated from the standard `maven-publish` and `signing` plugins to `com.vanniktech.maven.publish`.
    - Integrated the `mavenPublishing` DSL in `build.gradle.kts` for more robust and maintainable Sonatype distribution.
    - Configured automatic POM generation and signing within the new plugin's architecture.
- **CI/CD Alignment**:
    - Updated `.github/workflows/build.yml` to use `publishAllPublicationsToMavenCentralRepository`.
    - Mapped GitHub secrets to the environment variables expected by the Vanniktech plugin (`ORG_GRADLE_PROJECT_mavenCentralUsername`, etc.).
    - Disabled configuration cache for the publishing task to ensure compatibility with release builds.

## 2026-05-12
### 08:13

Recap of the work completed since the last update:

- **Security & Authentication Enhancements**:
    - Improved CSRF protection in the BFF login flow by implementing per-session tokens and header validation.
    - Enhanced return URL validation to ensure only safe, relative URLs are accepted, preventing open redirect vulnerabilities.
    - Refined logging across authentication services to improve clarity while ensuring sensitive data is sanitized.
    - Strengthened OIDC service validation and normalization of return parameters.
- **Publishing & CI/CD Refinement**:
    - Finalized the migration to `com.vanniktech.maven.publish` by removing redundant build configurations.
    - Updated CI secrets and host configurations for reliable Maven Central publishing.
    - Configured the publishing workflow to restrict automatic releases to tagged builds only.
- **Dependency & Build Maintenance**:
    - Performed a major update of project dependencies, including Ktor, Kotlin, and serialization libraries.
    - Integrated Gradle plugins for automated version catalog and dependency updates.
    - Refactored application versioning logic in `build.gradle.kts` to automatically strip the 'v' prefix from git tags for Maven compatibility.

Git commits included in this update:
- `cd4e6d0` — refactor(build): Simplify `getAppVersion` logic and ensure version strings strip `v` prefix
- `7e8750e` — chore(dependencies): Bump `vanniktech-maven-publish` to 0.36.0; update Apache license URL to HTTPS; adjust Maven publishing configuration
- `a287de6` — chore(dependencies): Update library versions (Koin, Kotlin, Ktor, Nimbus, Serialization, etc.); add plugins for version and catalog updates; refine dependency management
- `191af92` — Merge branch 'refactor/security-fixes'
- `208529d` — refactor(auth): Simplify return URL normalization by removing unnecessary parameters; enhance OIDC service with stricter `sslTrustAll` validation and tests
- `16fcd98` — refactor(logging): Improve log clarity in OIDC service, auth routes, and security flow; sanitize sensitive data in logs and refine CSRF mismatch warnings
- `7cb8a17` — feat(auth): Enhance return URL validation and improve CSRF protection in BFF login flow
- `e636fb3` — Restrict Sonatype publishing in CI workflow to tag builds only by updating conditional check
- `2e76fe3` — Remove `java` block with sources and Javadoc JAR configuration
- `d1b5b56` — Update secrets and Sonatype host for Maven Central publishing in CI workflow
