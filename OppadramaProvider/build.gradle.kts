// OppadramaProvider/build.gradle.kts
version = 1

cloudstream {
    language = "id"
    authors = listOf("Hexated", "Zephyra77")

    /**
     * Status int:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    tvTypes = listOf(
        "Drama",
        "Movie",
        "TvSeries"
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=oppadrama&sz=%size%"
}
