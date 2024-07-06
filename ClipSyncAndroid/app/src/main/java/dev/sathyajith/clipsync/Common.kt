package dev.sathyajith.clipsync

const val CST = "CLIP-SYNC"

enum class ServiceActions {
    START,
    STOP
}

const val COPY_FROM_CB = "cfc"
var laptopIp: String? = null