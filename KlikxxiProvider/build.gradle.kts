// use an integer for version numbers
version = 10

cloudstream {
    language = "id"
    
    description = "Klikxxi menampilkan Movie dan series"
    authors = listOf("Zephyra77")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1
    tvTypes = listOf(
        "Movie",
        "TvSeries",
        "AsianDrama",
    )

    iconUrl = "https://www.klikxxi.com/favicon.ico"
}