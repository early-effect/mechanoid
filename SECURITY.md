# Security

## Reporting Vulnerabilities

If you discover a security vulnerability, please report it by opening a private security advisory at:
https://github.com/russwyte/mechanoid/security/advisories/new

Do not open a public issue for security vulnerabilities.

## Branch Protection (Recommended)

Configure branch protection for `main` in **Settings > Branches > Branch protection rules**:

- **Require a pull request before merging** (1 approval required)
- **Require status checks to pass before merging**
  - Required checks: `build` (Scala CI)
- **Require branches to be up to date before merging**
- **Require signed commits** (PGP signing is already configured for this project)
- **Do not allow force pushes**
- **Do not allow deletions**

## Repository Security Settings

Enable these in **Settings > Code security and analysis**:

- **Dependency graph** - already enabled (via sbt-dependency-submission in CI)
- **Dependabot alerts** - enable to receive notifications for vulnerable dependencies
- **Dependabot security updates** - enable for automatic security fix PRs
- **Secret scanning** - enable to detect accidentally committed secrets
- **Push protection** - enable to block pushes containing secrets
