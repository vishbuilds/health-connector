package com.vishaal.healthconnector.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

// Temporary preview-render harness: re-hosts the main-source Home previews so the Compose
// screenshot tooling renders them to PNGs (the same layoutlib engine as the Studio preview pane).

@Preview(name = "home-on-track-light", showBackground = true, heightDp = 1240, widthDp = 400)
@Composable
fun ShotHomeOnTrackLight() = HomeOnTrackPreview()

@Preview(name = "home-stalled-light", showBackground = true, heightDp = 1240, widthDp = 400)
@Composable
fun ShotHomeStalledLight() = HomeStalledPreview()

@Preview(name = "home-dark", showBackground = true, heightDp = 1240, widthDp = 400)
@Composable
fun ShotHomeDark() = HomeDarkPreview()

@Preview(name = "lever-calorie-balance", showBackground = true, heightDp = 760, widthDp = 400)
@Composable
fun ShotCalorieBalanceLever() = CalorieBalanceLeverPagePreview()
