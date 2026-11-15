package com.dan.timelapse.data

enum class CropRatio(val displayName: String) {
    SOURCE("Crop (original)"),
    RATIO_1_1("Crop (square 1:1)"),
    RATIO_4_3("Crop (4:3)"),
    RATIO_3_4("Crop (3:4)"),
    RATIO_16_9("Crop (16:9)"),
    RATIO_9_16("Crop (vertical 9:16)"),
    RATIO_4_5("Crop (4:5)"),
    RATIO_5_4("Crop (5:4)"),
    RATIO_21_9("Crop (21:9)"),
    RATIO_9_21("Crop (9:21)")
}
