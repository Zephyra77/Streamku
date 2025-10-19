// use an integer for version numbers
version = 8

cloudstream {
    language = "id"

    description = "Melongmovie menampilkan Movie dan TV Series terbaru dengan berbagai genre."
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
        "TvSeries"
    )

    iconUrl = "https://tv11.melongmovies.com/wp-content/uploads/2023/01/cropped-favicon-32x32.png"
}