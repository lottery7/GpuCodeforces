# Agent guidance

These instructions apply to the whole repository. Read any module-specific
`AGENTS.md` before changing that module.

## Keep changes focused

- Inspect the relevant code and build configuration before editing. Follow existing
  conventions and avoid unrelated cleanup, renaming, or dependency upgrades.
- Implement the requested behavior with clear solution. Add abstractions,
  interfaces, configuration options, and dependencies when they serve a current need.
- Prefer direct `apply_patch` edits for ordinary changes. Use scripted rewrites only
  for repetitive or structured transformations, and review the resulting diff.
- Preserve unrelated local changes. Do not commit, push, or discard changes unless asked.

## Kotlin

- Use imports rather than unnecessary fully qualified names in code. Resolve name
  collisions with explicit import aliases when that improves readability.
- Prefer idiomatic Kotlin: `val`, expressions, null-safe operations, and standard
  collection functions.
- Model type-safety accurately. Avoid unchecked casts unless the invariant or limitation 
  is understood and documented where needed. Never use warning suppression. 
- Keep exceptions meaningful. Do not swallow failures, catch `Throwable` broadly,
  or turn errors into success-shaped default values. Preserve coroutine cancellation.
  Use !! or meaningful error where the null can't happen, instead of silently propagating or swallowing. 
- Comment on surprising behavior; avoid narrating obvious code or comments just for separating code.
- Use the configured Kotlin language features. Keep shared compiler settings in the
  root build rather than duplicating them in individual modules.
- Use explicitly named parameters only where they are really required: when there are a lot of parameters 
  that can't be distinguished by type only 
- When string literals carry code in other languages, like json, sql or jsonpath, use @Language annotation. 
  Sometimes it might be meaningful to add a proxy to library method just to add that annotation to a parameter.

## Backend and persistence

- Use constructor injection for application components. Keep transport, business,
  and persistence responsibilities clear without adding empty architectural layers.
- Validate input at application boundaries. Do not expose internal exceptions,
  credentials, or persistence entities directly as API responses.
- Account for JPA proxying, lazy loading, and entity identity. Do not use data classes
  for entities by default or include lazy associations in generated equality methods.
- Keep credentials out of source and logs. Local development defaults are not
  production configuration.

## Gradle and verification

- Use `./gradlew`. Keep plugin versions and shared compiler options centralized.
  Let Spring Boot's BOM manage supported backend dependency versions unless an
  explicit override is necessary.
- Do not edit generated files, build outputs, or Gradle caches. Keep the wrapper
  scripts and wrapper JAR in version control.
- Run checks appropriate to the change. For Kotlin changes, compile the affected
  sources and tests; add behavior-focused tests for new behavior or bug fixes.
  Avoid tests that merely repeat implementation details.
- PostgreSQL integration tests use Testcontainers and require a running Docker
  daemon. Do not replace PostgreSQL with H2 or silently skip integration tests
  to make a build pass.
- `./gradlew test` runs tests without the integration tag.
  `./gradlew :backend:integrationTest` runs PostgreSQL integration tests.
  `./gradlew build` includes both.
- Report what was verified and any checks that could not run. Do not describe
  compilation, an empty test task, or a running command as passing integration tests.

## Shell approvals

Before requesting approval for a shell command, show the exact full command in a
fenced code block, wrapped into several lines if longer than 80 characters.
Do not truncate arguments or use placeholders. If the command
runs a prepared script, show the script contents or link to the readable file too.
