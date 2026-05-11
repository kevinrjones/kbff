# CricketArchive StatsApp Project Guidelines

This document outlines the coding standards, architecture, and deployment procedures for the CricketArchive StatsApp project.

## Coding Standards & Style
*   **Kotlin First**: Use idiomatic Kotlin (data classes, extension functions, sealed classes, null safety).
*   **Functional Style**: Prefer a functional style over imperative code.
*   **Naming**:
      - Classes/Interfaces: PascalCase (e.g., `JooqPartnershipsRepository`)
      - Functions/Properties: camelCase (e.g., `getOverallPartnership`)
      - Constants: UPPER_SNAKE_CASE (e.g., `OVERALL_ROUTE`)
*   **Immutability**: Prefer `val` over `var` and `List` over `MutableList`.
*   **Logging**: Use `LoggerDelegate` for consistent logging across the project. Log errors during extraction to help identify CricketArchive structural changes.
*   **Error Handling**: Use `Result<T>` for operations that can fail, particularly network calls and parsing.
*   **Naming**: 
    *   Modules should follow the `sa-` prefix (e.g., `sa-getcards`, `sa-parsecard`, `sa-database`).
    *   Entity naming should match CricketArchive terminology (e.g., "cards" for match details, "seasons", "grounds").


### Build & Dependencies
- Use Gradle (`build.gradle.kts`) for dependency management and builds in JVM based projects (eg Kotlin or Java).
- Keep dependencies updated and follow the project's versioning in `gradle.properties` if applicable.
- Use a toml file for all dependencies and refernce that TOML file in the gradle build files
- Make sure to create the appropriate .gitignore file. This should make sure it ignores any files that are built as well as files that are created by the ide that should not be shared.
- Make sure that .gitignore files also includes any files that contains secrets that should not be pushed to a public repository
- when referencing plugins add the plugin reference to the toml file and then use that reference in the gradle file, i.e.
  prefer this
``` kotlin
    alias(libs.plugins.ktor.plugin)
```
to this
```
    id("io.ktor.plugin") version "3.4.1"
```
## Project Architecture

The project is a multi-module Gradle build:
*   `sa-shared`: Common logic, including HTTP connection utilities (`HttpConnection.kt`) and base page types.
*   `sa-entities`: Shared data models, often with Kotlinx Serialization support.
*   `sa-get*`: Modules responsible for fetching raw HTML from CricketArchive.
*   `sa-parse*`: Modules that use JSoup to extract structured data from fetched HTML.
*   `sa-database`: Handles database migrations and data persistence (supports MariaDB, Postgres, and SQLite).

**Data Flow**: Fetch (`sa-get*`) -> Parse (`sa-parse*`) -> Persist (`sa-database`).

## Testing Strategies

*   **Frameworks**: Use JUnit 5 with Kluent or Strikt for assertions.
*   **Mocking**: When testing parsers, use local HTML files instead of making real network calls.
*   **Database**: Integration tests for `sa-database` should ideally run against a temporary or test database.

## Maintenance & Deployment (Nightly Runner)

The application is designed to run nightly on macOS using `launchctl`.
*   **Scripts**: Main scripts are located in `cricketarchive-scripts`.
*   **Installation**: 
    1. Build distributions using `gradle installDist`.
    2. Copy `bin` and `lib` to the script directories.
    3. Update the `com.knowledgespike.database.plist` with the correct `Program` and `WorkingDirectory` paths.
    4. Load the plist into `/Library/LaunchDaemons`.
*   **Verification**: Check logs in `sa-get*` folders to ensure the nightly run is progressing.

## Data Extraction & Parsing Rules

*   **HTML Parsing**: Use JSoup for CSS selector-based extraction.
*   **CricketArchive URLs**: URLs often follow a pattern based on IDs (e.g., `.../Archive/Teams/$root/$id/$id.html` where `$root = id / 1000`).
*   **Edge Cases**: 
    *   Handle "unknown" team entries explicitly.
    *   Check for "error in your requested page" messages in HTML bodies, as these may not return a 404 status.
    *   Handle duplicate team entries by re-verifying from their specific team pages.
*   **Authentication**: Some pages require specific cookies (e.g., `ea8ff5eedb059380fe4f6edfea62f912_id`, `ea8ff5eedb059380fe4f6edfea62f912_hash`) which are managed in `HttpConnection.kt`.


### Git Workflow
#### Guidelines

- Only commit when explicitly asked to
- Use clear, descriptive commit messages.
- DO NOT add any ads such as "Co-authored-by: Junie <junie@jetbrains.com>`."
- Only generate the message for staged files/changes
- Don't add any files using `git add`. The user will decide what to add. 
- Follow the rules below for the commit message.
- Use standard labels and scoping
- Make sure I'm not committing to `main`. If I'm trying to do that, suggest a branch name, ask me to confirm, then create the branch.
- Ask me to confirm the message, then add all files and run the commit.


#### Format

```
<type>:<space><message title>

<bullet points summarizing what was updated>
```

#### Example Titles

```
feat(auth): add JWT login flow
fix(ui): handle null pointer in sidebar
refactor(api): split user controller logic
docs(readme): add usage section
```

#### Example with Title and Body

```
feat(auth): add JWT login flow

- Implemented JWT token validation logic
- Added documentation for the validation component
```

#### Rules

* title is lowercase, no period at the end.
* Title should be a clear summary, max 50 characters.
* Use the body (optional) to explain *why*, not just *what*.
* Bullet points should be concise and high-level.

Avoid

* Vague titles like: "update", "fix stuff"
* Overly long or unfocused titles
* Excessive detail in bullet points

#### Allowed Types

| Type     | Description                           |
| -------- | ------------------------------------- |
| feat     | New feature                           |
| fix      | Bug fix                               |
| chore    | Maintenance (e.g., tooling, deps)     |
| docs     | Documentation changes                 |
| refactor | Code restructure (no behavior change) |
| test     | Adding or refactoring tests           |
| style    | Code formatting (no logic change)     |
| perf     | Performance improvements              |



### Coding Standards
- **Kotlin First**: Use idiomatic Kotlin (data classes, extension functions, sealed classes, null safety).
- **Provide Tests**: Prefer JUnit for the tests and always provide tests
- **Functional Style**: Prefer a functional style for all code.
- **Naming**:
    - Classes/Interfaces: PascalCase (e.g., `JooqPartnershipsRepository`)
    - Functions/Properties: camelCase (e.g., `getOverallPartnership`)
    - Constants: UPPER_SNAKE_CASE (e.g., `OVERALL_ROUTE`)
- **Immutability**: Prefer `val` over `var` and `List` over `MutableList`.
- Prefer immutable data classes, for example, prefer
```kotlin
data class OidcConfiguration(
    val authority: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val scopes: List<String> = listOf("openid", "profile")
)
``` 
over
```kotlin
data class OidcConfiguration(
    var authority: String = "",
    var clientId: String = "",
    var clientSecret: String = "",
    var scopes: List<String> = listOf("openid", "profile")
)

``` 
- When building DSLs prefer the use of context receivers to make the DSLs easier to construct and read

### Task Status Tracking

- Tasks are marked with checkboxes:
    - `[ ]` indicates a task that has not been started or is in progress
    - `[x]` indicates a task that has been completed
- A parent task should only be marked as completed when all its subtasks are completed
- The tasks file should be updated as you progress through the tasks
