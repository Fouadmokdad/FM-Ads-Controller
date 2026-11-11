# FM Ads Controller 0.1

An Android, no-root prototype for inspecting APKs and installed apps. Select an APK or APK bundle archive from the device, or browse installed user apps and inspect their base and split APKs. Scanning is local.

Detection is heuristic: DEX content and paths may indicate Google Mobile Ads (AdMob) or Unity Ads. Absence of evidence is not evidence that an app has no ads. Findings do not establish that an ad can be sped up or that rewards can be granted. Nested APKs in XAPK/APKS are inspected one level deep. Reward-related class and callback markers are heuristic only; native code and server validation are not analyzed. No modifications, signing, installing, reward bypassing, or ad acceleration are implemented.

Installed app visibility uses QUERY_ALL_PACKAGES; distribution on Google Play is subject to its policy. Some package files or archives may be inaccessible. APK signatures, server validation, and installed-app data are not analyzed.

GitHub Actions builds a debug APK; download the artifact from the successful workflow run. Debug builds are for testing.
