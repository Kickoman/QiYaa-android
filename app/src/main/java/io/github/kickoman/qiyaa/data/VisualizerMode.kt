package io.github.kickoman.qiyaa.data

enum class VisualizerMode(val storedValue: Int) {
    SPECTRUM(0),
    SCOPE(1),
    OFF(2),
    ;

    fun next(): VisualizerMode = entries[(ordinal + 1) % entries.size]

    companion object {
        fun fromStored(value: Int): VisualizerMode =
            entries.firstOrNull { it.storedValue == value } ?: SPECTRUM
    }
}
