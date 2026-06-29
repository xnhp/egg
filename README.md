# egg

`egg` is a Kotlin/JVM command-line tool for local Git, GitHub, CI, and worktree
automation.

## Development

Use Java 21 for local development:

```bash
gradle test
gradle installDist
```

The installed binary is written to `build/install/egg/bin/egg`.

`egg` depends on `cn.varsa:cli-core:0.1.0-SNAPSHOT`. Local development uses
Gradle composite build substitution when a `cli-core` checkout is available:

1. `-PcliCorePath=/path/to/cli-core` wins.
2. Otherwise `../cli-core` is used when it exists.
3. If neither exists, Gradle resolves `cn.varsa:cli-core` from GitHub Packages.

Example with an explicit local checkout:

```bash
gradle test -PcliCorePath=/home/ben/repos/cli-core
```

Keep the dependency declared as the Maven coordinate rather than a `project(...)`
dependency. This keeps local source substitution aligned with published-package
resolution.

## GitHub Packages

The GitHub Packages repository for `cli-core` is
`https://maven.pkg.github.com/xnhp/cli-core`.

Local package resolution uses Gradle properties:

```properties
gpr.user=<github-user>
gpr.key=<token-with-read-packages>
```

For private packages in GitHub Actions, grant the consuming repository access to
the package or provide a PAT with `read:packages` as a secret. The Gradle process
must receive `GITHUB_TOKEN` or equivalent credentials in its environment.
