// use an integer for version numbers
version = 3

cloudstream {
    language = "id"

    // Nama pembuat ekstensi
    authors = listOf("Zephyra77")

    /**
     * Status:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1 // Aktif dan berfungsi normal

    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "TvSeries"
    )

    // Gunakan favicon resmi Nimeindo
    iconUrl = "https://www.google.com/s2/favicons?domain=nimeindo.web.id&sz=%size%"
}
