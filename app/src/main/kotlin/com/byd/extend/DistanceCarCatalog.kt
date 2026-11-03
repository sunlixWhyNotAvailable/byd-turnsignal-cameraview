package com.byd.extend

data class DistanceCarArt(
    val model: String, val color: String, val colorLabel: Int, val drawable: Int,
    val left: Int, val top: Int, val width: Int, val height: Int,
) {
    val resourceName get() = "distance_car_${model}_${color}.png"
}

object DistanceCarCatalog {
    // Original OEM PNGs; source bounds exclude transparent padding only.
    val variants = listOf(
        DistanceCarArt("uke", "white", R.string.distance_car_color_white, R.drawable.distance_car_uke_white, 187, 2, 313, 683),
        DistanceCarArt("uke", "beige", R.string.distance_car_color_beige, R.drawable.distance_car_uke_beige, 187, 2, 313, 683),
        DistanceCarArt("uke", "blue_grey", R.string.distance_car_color_blue_grey, R.drawable.distance_car_uke_blue_grey, 187, 2, 313, 683),
        DistanceCarArt("uke", "black", R.string.distance_car_color_black, R.drawable.distance_car_uke_black, 187, 2, 313, 683),
        DistanceCarArt("uke", "purple", R.string.distance_car_color_purple, R.drawable.distance_car_uke_purple, 187, 2, 313, 683),
        DistanceCarArt("sk2", "white", R.string.distance_car_color_white, R.drawable.distance_car_sk2_white, 72, 3, 226, 509),
        DistanceCarArt("sk2", "black", R.string.distance_car_color_black, R.drawable.distance_car_sk2_black, 72, 3, 226, 509),
        DistanceCarArt("sk2", "blue", R.string.distance_car_color_blue, R.drawable.distance_car_sk2_blue, 72, 3, 226, 509),
        DistanceCarArt("sk2", "stone_grey", R.string.distance_car_color_stone_grey, R.drawable.distance_car_sk2_stone_grey, 72, 3, 226, 509),
        DistanceCarArt("sk2", "grey", R.string.distance_car_color_grey, R.drawable.distance_car_sk2_grey, 72, 3, 226, 509),
        DistanceCarArt("sk2", "red", R.string.distance_car_color_red, R.drawable.distance_car_sk2_red, 72, 3, 226, 509),
        DistanceCarArt("sk2", "pale_blue", R.string.distance_car_color_pale_blue, R.drawable.distance_car_sk2_pale_blue, 72, 3, 226, 509),
        DistanceCarArt("sk24", "white", R.string.distance_car_color_white, R.drawable.distance_car_sk24_white, 187, 0, 312, 687),
        DistanceCarArt("sk24", "stone_grey", R.string.distance_car_color_stone_grey, R.drawable.distance_car_sk24_stone_grey, 187, 0, 312, 687),
        DistanceCarArt("sk24", "blue_grey", R.string.distance_car_color_blue_grey, R.drawable.distance_car_sk24_blue_grey, 187, 0, 312, 687),
        DistanceCarArt("sk24", "black", R.string.distance_car_color_black, R.drawable.distance_car_sk24_black, 187, 0, 312, 687),
    )
    val models = variants.map { it.model }.distinct()

    fun resolve(model: String, color: String): DistanceCarArt {
        val retainedModel = if (model in listOf("ukea", "ukeb", "uked")) "uke" else model
        return variants.firstOrNull { it.model == retainedModel && it.color == color }
            ?: variants.firstOrNull { it.model == retainedModel && it.color == "white" }
            ?: variants.first()
    }
}
