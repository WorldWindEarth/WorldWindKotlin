![worldwind](worldwind-examples-android/src/main/res/drawable/worldwind_logo.png)  
[![GitHub license](https://img.shields.io/badge/license-Apache%20License%202.0-blue.svg?style=flat)](http://www.apache.org/licenses/LICENSE-2.0) 
![badge-android](http://img.shields.io/badge/platform-android-6EDB8D.svg?style=flat)
![badge-ios](http://img.shields.io/badge/platform-ios-CDCDCD.svg?style=flat)
![badge-jvm](http://img.shields.io/badge/platform-jvm-DB413D.svg?style=flat)
![badge-js](http://img.shields.io/badge/platform-js-F8DB5D.svg?style=flat)
![badge-wasm](http://img.shields.io/badge/platform-wasm-654FF0.svg?style=flat)

# WorldWind Kotlin

3D virtual globe API for Android, iOS, Web and Java developed by WorldWind Community Edition contributors.
Provides a geographic context with high-resolution terrain, for visualizing geographic or geo-located information in 3D and 2D.
Developers can customize the globe's terrain and imagery. Provides a collection of shapes for displaying and interacting with
geographic data and representing a range of geometric objects.

- [WorldWind Examples](https://play.google.com/store/apps/details?id=earth.worldwind.examples) a set of Android benchmarks
- [WorldWind Tutorials](https://play.google.com/store/apps/details?id=earth.worldwind.tutorials) demonstration of Android version capabilities
- [tutorials.worldwind.earth](https://tutorials.worldwind.earth) demonstration of Web version capabilities
- [worldwind.earth](https://worldwind.earth) has setup instructions, developers guides, API documentation and more

## Download

Grab latest release build via Gradle:
```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'earth.worldwind:worldwind:2.1.0'
}
```

`earth.worldwind:worldwind-assets` comes in automatically as a transitive dependency — see below.

## Bundled assets

The default background imagery, night-time city lights, star catalogue and EGM96 geoid grid live in a separate
`worldwind-assets` artifact rather than inside the engine. They are about 6.7 MB of binary data, and because a Kotlin
Multiplatform library embeds its resources into every published target, carrying them in the engine added roughly
55 MB to every release — for files that essentially never change. Splitting them out means an engine release no longer
re-uploads them; the assets artifact is published only when an asset actually changes.

Nothing changes for applications. The engine depends on it, so it resolves automatically and every default works as
before — `BackgroundLayer()`, `StarFieldLayer()`, `AtmosphereLayer()` and the EGM96 geoid all pick up their bundled
resources with no setup code.

The one thing worth knowing is that this artifact carries its own version, which deliberately does not track the
engine version. Pin it explicitly only if you need a specific asset revision:

```groovy
implementation 'earth.worldwind:worldwind-assets:1.0.0'
```

To use your own imagery instead, pass it to the layer that consumes it, as always:

```kotlin
BackgroundLayer(ImageSource.fromResource(MyMR.images.my_background))
```

## Snapshots

Get development build snapshots with the latest features and bug fixes:
```groovy
repositories {
    maven("https://central.sonatype.com/repository/maven-snapshots/")
}

dependencies {
    implementation 'earth.worldwind:worldwind:+'
}
```

## Releases and Roadmap

Official WorldWind Android releases have the latest stable features, enhancements and bug fixes ready for production use.

- [GitHub Releases](https://github.com/WorldWindEarth/WorldWindKotlin/releases/) documents official releases
- [GitHub Milestones](https://github.com/WorldWindEarth/WorldWindKotlin/milestones) documents upcoming releases and the development roadmap
- [GitHub Actions](https://github.com/WorldWindEarth/WorldWindKotlin/actions) provides continuous integration and release automation

## License

Copyright 2022 WorldWind Community Edition contributors. All rights reserved.

The WorldWindKotlin platform is licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the License. You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the specific language governing permissions and limitations under the License.