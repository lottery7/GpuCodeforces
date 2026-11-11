package dev.gpucodeforces.judge.executor.util

import java.security.SecureRandom
import java.util.HexFormat

/** A fresh prefix for the status lines of one run: the solution cannot know it before it starts. */
fun SecureRandom.nextNonce(): String = HexFormat.of().formatHex(ByteArray(12).also(::nextBytes))
