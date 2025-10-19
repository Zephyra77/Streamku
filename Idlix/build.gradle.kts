// use an integer for version numbers
version = 21

cloudstream {
    language = "id"

    authors = listOf("Hexated", "Zephyra77")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "TvSeries",
        "Movie",
        "Anime",
        "AsianDrama",
    )

    iconUrl = "https://www.google.com/s2/favicons?domain=https://idlixplus.pro&sz=%size%"
}
