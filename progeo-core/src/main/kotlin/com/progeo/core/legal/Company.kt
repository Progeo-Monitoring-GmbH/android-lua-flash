package com.progeo.core.legal

/** Contact data of the company behind the apps (footer of the portal and its mails). */
data class Company(
    val name: String,
    val street: String,
    val postalCity: String,
    val phone: String,
    val fax: String?,
    val email: String,
    val website: String,
) {
    companion object {
        /** Mirrors frontend/src/main/company.ts and helper/emailhelper.py of the portal. */
        val ProGeo = Company(
            name = "ProGeo Monitoring Systeme und Services GmbH & Co. KG",
            street = "Hauptstraße 2",
            postalCity = "DE-14979 Großbeeren",
            phone = "+49 33701 22-0",
            fax = "+49 33701 22-119",
            email = "kontakt@progeo.com",
            website = "https://www.progeo.com",
        )
    }
}

/**
 * The legal notice (Impressum). Every app shows the same text; [sourceUrl] is the published
 * original that wins if the two ever differ.
 */
data class Imprint(
    val sections: List<Pair<String, List<String>>>,
    val sourceUrl: String,
) {
    companion object {
        /** As published on www.progeo.com/impressum (checked 2026-11-12). */
        val ProGeo = Imprint(
            sections = listOf(
                "Anbieter" to listOf(
                    "ProGeo Monitoring Systeme u. Services GmbH & Co. KG",
                    "Hauptstraße 2",
                    "14979 Großbeeren",
                    "Handelsregister: HRB 30512",
                    "Registergericht: Amtsgericht Potsdam",
                ),
                "Vertreten durch" to listOf(
                    "ProGeo Management GmbH",
                    "Hauptstraße 2",
                    "14979 Großbeeren",
                    "Handelsregister: HRA 6840 P",
                    "Registergericht: Amtsgericht Potsdam",
                    "Diese vertreten durch: Andreas Rödel",
                ),
                "Kontakt" to listOf(
                    "Telefon: +49 33701 22-0",
                    "Telefax: +49 33701 22-119",
                    "E-Mail: progeo@progeo.com",
                ),
                "Redaktionell verantwortlich" to listOf("Andreas Rödel"),
                "Verbraucherstreitbeilegung/Universalschlichtungsstelle" to listOf(
                    "Wir sind nicht bereit oder verpflichtet, an Streitbeilegungsverfahren vor einer " +
                        "Verbraucherschlichtungsstelle teilzunehmen.",
                ),
            ),
            sourceUrl = "https://www.progeo.com/impressum",
        )
    }
}
