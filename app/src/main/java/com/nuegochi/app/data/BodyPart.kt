package com.nuegochi.app.data

/** The parts of the stick figure a user can color and (for limbs) resize. */
enum class BodyPart(val displayName: String) {
    HEAD("머리"),
    BODY("몸통"),
    ARMS("팔"),
    LEGS("다리"),
    TAIL("꼬리")
}
