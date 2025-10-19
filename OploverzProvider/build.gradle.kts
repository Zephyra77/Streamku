// use an integer for version numbers
version = 31

cloudstream {
    language = "id"

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
        "AnimeMovie",
        "Anime",
        "OVA",
    )

    iconUrl = "https://oploverz.ltd/favicon.ico"
}