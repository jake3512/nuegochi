package com.nuegochi.app.data

/**
 * The body parts a user draws individually when customizing their pet.
 * [fileName] is where the drawn PNG is stored in app-internal storage.
 */
enum class PetPart(val fileName: String, val displayName: String) {
    HEAD("part_head.png", "머리"),
    BODY("part_body.png", "몸통"),
    ARM_LEFT("part_arm_left.png", "왼팔"),
    ARM_RIGHT("part_arm_right.png", "오른팔"),
    LEG_LEFT("part_leg_left.png", "왼다리"),
    LEG_RIGHT("part_leg_right.png", "오른다리"),
    TAIL("part_tail.png", "꼬리");

    companion object {
        /** Drawing order the user goes through when creating a new pet. */
        val creationOrder: List<PetPart> = listOf(HEAD, BODY, ARM_LEFT, ARM_RIGHT, LEG_LEFT, LEG_RIGHT, TAIL)
    }
}
