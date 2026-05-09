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
