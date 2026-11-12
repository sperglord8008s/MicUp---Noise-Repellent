# Third-party notices

MicUp NR is based on [MicUp](https://github.com/papergray/MicUp), licensed under
the MIT License. The original license is in `LICENSE`.

The built-in learned-noise processor uses
[libspecbleach](https://github.com/lucianodato/libspecbleach) v0.4.1 at commit
`ab4ae8b4be74f5c52c5a3386a6d9bbea29fbd129`, licensed under the GNU Lesser
General Public License v2.1 or later. Its corresponding source, bundled
third-party notices, and license are included in the accompanying source
archive. A copy of its license is also packaged in the APK assets.

The Android audio path uses [Oboe](https://github.com/google/oboe) 1.8.1,
licensed under the Apache License 2.0. A copy of that license is packaged in the
APK assets and its corresponding source is included in the source archive.

The build also obtains the CLAP headers 1.2.1 and LV2 headers 1.18.10 at build
time. Their original copyright and license files remain with their source in
the accompanying source archive. MicUp's existing locally bundled VST3 headers
retain their original notices.

The complete custom application source and reproducible build scripts are
provided so recipients can inspect, modify, rebuild, and relink the application
with a modified libspecbleach as permitted by its license.
