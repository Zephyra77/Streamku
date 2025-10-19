// use an integer for version numbers
version = 5

cloudstream {
    language = "id"

    // Short description of the extension
    description = "Streaming filmMovie dan serial terbaru dari Pencurimovie"

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

    // Use Pencurimovie's official favicon
    iconUrl = "https://www.google.com/s2/favicons?domain=ww73.pencurimovie.bond&sz=%size%"
}
