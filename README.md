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

An experiment server for Eignex repositories. It runs [Klause](https://github.com/Eignex/klause) solver experiments,
[Koblas](https://github.com/Eignex/koblas) benchmark captures, and committed profile entry points from any Eignex
repository, with the queue, live output and results served over HTTP. Source repositories are restricted to the
Eignex GitHub organization. Optional required/excluded node tags target CPU architecture, SIMD features, market
type or custom labels; ordinary jobs need no selector. The client uses `--tags` for selection and
`--measurement` for resource, JFR or compilation recording of experiment CLI runs.

Setup and usage: [deploy/SETUP.md](deploy/SETUP.md).

Existing installations: [upgrade from Klause Lab](deploy/SETUP.md#upgrading-from-klause-lab) before updating.
