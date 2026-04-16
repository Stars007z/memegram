package com.example.memegram.translation


object NllbLanguageCodes {

    fun toFlores(bcp47: String): String? = BCP47_TO_FLORES[bcp47.lowercase()]

    fun toBcp47(flores: String): String? = FLORES_TO_BCP47[flores]

    val supportedBcp47: Set<String> get() = BCP47_TO_FLORES.keys

    // ── Comprehensive mapping ────────────────────────────────────

    private val BCP47_TO_FLORES: Map<String, String> = mapOf(
        "en" to "eng_Latn",
        "ru" to "rus_Cyrl",
        "de" to "deu_Latn",
        "fr" to "fra_Latn",
        "es" to "spa_Latn",
        "it" to "ita_Latn",
        "pt" to "por_Latn",
        "nl" to "nld_Latn",
        "tr" to "tur_Latn",
        "ar" to "arb_Arab",
        "zh" to "zho_Hans",
        "ja" to "jpn_Jpan",
        "ko" to "kor_Hang",
        "hi" to "hin_Deva",
        "th" to "tha_Thai",
        "vi" to "vie_Latn",
        "id" to "ind_Latn",

        // European
        "uk" to "ukr_Cyrl",
        "pl" to "pol_Latn",
        "sv" to "swe_Latn",
        "da" to "dan_Latn",
        "fi" to "fin_Latn",
        "no" to "nob_Latn",
        "nb" to "nob_Latn",
        "nn" to "nno_Latn",
        "cs" to "ces_Latn",
        "ro" to "ron_Latn",
        "hu" to "hun_Latn",
        "el" to "ell_Grek",
        "bg" to "bul_Cyrl",
        "sr" to "srp_Cyrl",
        "hr" to "hrv_Latn",
        "sk" to "slk_Latn",
        "sl" to "slv_Latn",
        "et" to "est_Latn",
        "lv" to "lvs_Latn",
        "lt" to "lit_Latn",
        "sq" to "als_Latn",
        "mk" to "mkd_Cyrl",
        "bs" to "bos_Latn",
        "is" to "isl_Latn",
        "ga" to "gle_Latn",
        "cy" to "cym_Latn",
        "mt" to "mlt_Latn",
        "lb" to "ltz_Latn",
        "ca" to "cat_Latn",
        "gl" to "glg_Latn",
        "eu" to "eus_Latn",

        // CIS / Central Asia
        "be" to "bel_Cyrl",
        "kk" to "kaz_Cyrl",
        "ky" to "kir_Cyrl",
        "uz" to "uzn_Latn",
        "tg" to "tgk_Cyrl",
        "mn" to "khk_Cyrl",
        "ka" to "kat_Geor",
        "hy" to "hye_Armn",
        "az" to "azj_Latn",

        // Middle East / South Asia
        "he" to "heb_Hebr",
        "fa" to "pes_Arab",
        "ur" to "urd_Arab",
        "ps" to "pbt_Arab",
        "bn" to "ben_Beng",
        "ta" to "tam_Taml",
        "te" to "tel_Telu",
        "ml" to "mal_Mlym",
        "kn" to "kan_Knda",
        "gu" to "guj_Gujr",
        "mr" to "mar_Deva",
        "pa" to "pan_Guru",
        "ne" to "npi_Deva",
        "si" to "sin_Sinh",

        // Southeast Asia
        "ms" to "zsm_Latn",
        "tl" to "tgl_Latn",
        "my" to "mya_Mymr",
        "km" to "khm_Khmr",
        "lo" to "lao_Laoo",
        "jv" to "jav_Latn",
        "su" to "sun_Latn",

        // Africa
        "sw" to "swh_Latn",
        "af" to "afr_Latn",
        "am" to "amh_Ethi",
        "ha" to "hau_Latn",
        "yo" to "yor_Latn",
        "zu" to "zul_Latn",
        "so" to "som_Latn",
        "mg" to "plt_Latn",
    )

    private val FLORES_TO_BCP47: Map<String, String> =
        BCP47_TO_FLORES.entries.associate { (k, v) -> v to k }
}
