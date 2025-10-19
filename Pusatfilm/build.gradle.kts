// use an integer for version numbers
version = 6

cloudstream {
    language = "id"
    
    // All of these properties are optional, you can safely remove them
    description = "Pusatfilm – Streaming Film dan Series Indonesia"
    authors = listOf("Hexated", "Zephyra77")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1 // will be 3 if unspecified

    tvTypes = listOf(
        "AsianDrama",
        "TvSeries",
        "Movie",
        "Anime"
    )

    iconUrl = "https://v1.pusatfilm21info.net/favicon.ico"
}