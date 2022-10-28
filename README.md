![worldwind](worldwind-examples-android/src/main/res/drawable/worldwind_logo.png)  
[![GitHub license](https://img.shields.io/badge/license-Apache%20License%202.0-blue.svg?style=flat)](http://www.apache.org/licenses/LICENSE-2.0) 
![badge-android](http://img.shields.io/badge/platform-android-6EDB8D.svg?style=flat)
![badge-jvm](http://img.shields.io/badge/platform-jvm-DB413D.svg?style=flat)
![badge-js](http://img.shields.io/badge/platform-js-F8DB5D.svg?style=flat)

# WorldWind Kotlin

3D virtual globe API for Android, Web and Java developed by WorldWind Community Edition contributors.
Provides a geographic context with high-resolution terrain, for visualizing geographic or geo-located information in 3D and 2D.
Developers can customize the globe's terrain and imagery. Provides a collection of shapes for displaying and interacting with
geographic data and representing a range of geometric objects.

- [worldwind.earth](https://worldwind.earth) has setup instructions, developers guides, API documentation and more

## Download

Grab latest release build via Gradle:
```groovy
repositories {
    mavenCentral()
}

dependencies {
    implementation 'earth.worldwind:worldwind:1.0.6'
}
```

## Snapshots

Get development build snapshots with the latest features and bug fixes:
```groovy
repositories {
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots/")
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