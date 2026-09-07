package com.nuegochi.app.draw

/** A quick base-fill style the user can drop into a part's silhouette before (or instead of) hand-drawing. */
enum class PatternPreset(val displayName: String) {
    SOLID("단색"),
    STRIPES("줄무늬"),
    DOTS("물방울무늬"),
    GRADIENT("그라데이션")
}
