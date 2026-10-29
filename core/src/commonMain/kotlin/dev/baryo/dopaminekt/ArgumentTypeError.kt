package dev.baryo.dopaminekt

/** Thrown when an argument has the wrong type or shape, as a require check would. */
internal class ArgumentTypeError(message: String) : IllegalArgumentException(message)
