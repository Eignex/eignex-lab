<p align="center">
  <a href="https://eignex.com/">
    <picture>
      <source media="(prefers-color-scheme: dark)" srcset="https://raw.githubusercontent.com/Eignex/.github/refs/heads/main/profile/banner-white.svg">
      <source media="(prefers-color-scheme: light)" srcset="https://raw.githubusercontent.com/Eignex/.github/refs/heads/main/profile/banner.svg">
      <img alt="Eignex" src="https://raw.githubusercontent.com/Eignex/.github/refs/heads/main/profile/banner.svg" style="max-width: 100%; width: 22em;">
    </picture>
  </a>
</p>

# Eignex Lab

Run [Klause](https://github.com/Eignex/klause) solver experiments, [Koblas](https://github.com/Eignex/koblas) benchmark
captures, and committed profile entry points from Eignex repositories.
The lab builds trusted commits, queues local or cloud work, and serves live results and retained artifacts over HTTP.
Source execution is restricted to the Eignex GitHub organization.

Start with [installation](deploy/SETUP.md) or [submitting a run](docs/runs.md). Most jobs need no hardware selector
or recording option.

| Guide | Contents |
| --- | --- |
| [Runs](docs/runs.md) | Experiments, profiles, Koblas, node selectors, and daily commands |
| [Measurements](docs/measurements.md) | Resource/JFR modes, compilation reports, and retained evidence |
| [Validation](docs/validation.md) | Witness checkers, reference solvers, and result interpretation |
| [Cloud nodes](docs/cloud.md) | AWS setup, labels, architectures, and capacity |
| [Operations](docs/operations.md) | Access, storage, Docker, and recovery |
| [HTTP API](docs/api.md) | Integration endpoints |
