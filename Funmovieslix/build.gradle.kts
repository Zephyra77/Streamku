// use an integer for version numbers
version = 4

cloudstream {
    language = "id"

    // Short description of the extension
    description = "Streaming filmmovie dan serial terbaru dari FunMovieFlix"

    // Extension authors
    authors = listOf("phisher98")

    /**
     * Status:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1 // Active and functioning normally

    tvTypes = listOf(
        "Movie",
        "TVSeries"
    )

    // Use FunMovieFlix's official favicon
    iconUrl = "https://www.google.com/s2/favicons?domain=funmovieslix.com&sz=%size%"
}
