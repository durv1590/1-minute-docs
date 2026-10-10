package com.oneminutedocs.document

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

enum class ContentLanguage(val tag: String) { ENGLISH("en"), HINDI("hi") }

data class TemplateField(val key: String, val labelEn: String, val labelHi: String, val multiline: Boolean = false, val required: Boolean = false) {
    fun label(lang: ContentLanguage) = if (lang == ContentLanguage.HINDI) labelHi else labelEn
}

/**
 * Fill-in templates rendered to [MarkdownLite] text, which the user can edit before export through the normal
 * PDF/DOCX pipeline. Templates contain no AI; the AI creator is a separate path.
 */
enum class DocumentTemplate(val titleEn: String, val titleHi: String, val fields: List<TemplateField>) {
    LETTER(
        "Formal letter", "औपचारिक पत्र",
        listOf(
            TemplateField("senderName", "Your name", "आपका नाम", required = true),
            TemplateField("senderAddress", "Your address", "आपका पता", multiline = true),
            TemplateField("date", "Date", "दिनांक"),
            TemplateField("recipient", "Recipient", "प्राप्तकर्ता", required = true),
            TemplateField("recipientAddress", "Recipient address", "प्राप्तकर्ता का पता", multiline = true),
            TemplateField("subject", "Subject", "विषय", required = true),
            TemplateField("body", "Letter body", "पत्र का मुख्य भाग", multiline = true, required = true),
        ),
    ),
    LEAVE_APPLICATION(
        "Leave application", "छुट्टी के लिए आवेदन",
        listOf(
            TemplateField("to", "Addressed to (e.g. The Principal)", "किसे (जैसे प्रधानाचार्य)", required = true),
            TemplateField("organisation", "School / organisation", "विद्यालय / संस्था"),
            TemplateField("name", "Your name", "आपका नाम", required = true),
            TemplateField("role", "Class / designation", "कक्षा / पद"),
            TemplateField("fromDate", "From date", "आरंभ तिथि", required = true),
            TemplateField("toDate", "To date", "अंतिम तिथि", required = true),
            TemplateField("reason", "Reason", "कारण", multiline = true, required = true),
            TemplateField("date", "Date of application", "आवेदन की तिथि"),
        ),
    ),
    RESUME(
        "Resume", "बायोडाटा",
        listOf(
            TemplateField("name", "Full name", "पूरा नाम", required = true),
            TemplateField("contact", "Phone / email / city", "फ़ोन / ईमेल / शहर"),
            TemplateField("summary", "Professional summary", "संक्षिप्त परिचय", multiline = true),
            TemplateField("experience", "Experience (one per line)", "अनुभव (हर पंक्ति में एक)", multiline = true),
            TemplateField("education", "Education (one per line)", "शिक्षा (हर पंक्ति में एक)", multiline = true),
            TemplateField("skills", "Skills (comma separated)", "कौशल (अल्पविराम से अलग)"),
        ),
    ),
    MEETING_NOTES(
        "Meeting notes", "बैठक का विवरण",
        listOf(
            TemplateField("title", "Meeting title", "बैठक का शीर्षक", required = true),
            TemplateField("date", "Date", "दिनांक"),
            TemplateField("attendees", "Attendees", "उपस्थित सदस्य"),
            TemplateField("agenda", "Agenda (one per line)", "कार्यसूची (हर पंक्ति में एक)", multiline = true),
            TemplateField("decisions", "Decisions (one per line)", "निर्णय (हर पंक्ति में एक)", multiline = true),
            TemplateField("actions", "Action items (one per line)", "कार्य बिंदु (हर पंक्ति में एक)", multiline = true),
        ),
    ),
    STUDY_NOTES(
        "Study notes", "अध्ययन नोट्स",
        listOf(
            TemplateField("subject", "Subject", "विषय", required = true),
            TemplateField("topic", "Topic", "प्रकरण", required = true),
            TemplateField("points", "Key points (one per line)", "मुख्य बिंदु (हर पंक्ति में एक)", multiline = true),
            TemplateField("summary", "Summary", "सारांश", multiline = true),
        ),
    ),
    REPORT(
        "Report", "रिपोर्ट",
        listOf(
            TemplateField("title", "Report title", "रिपोर्ट का शीर्षक", required = true),
            TemplateField("author", "Prepared by", "तैयारकर्ता"),
            TemplateField("date", "Date", "दिनांक"),
            TemplateField("introduction", "Introduction", "परिचय", multiline = true),
            TemplateField("findings", "Findings", "निष्कर्ष बिंदु", multiline = true),
            TemplateField("conclusion", "Conclusion", "निष्कर्ष", multiline = true),
        ),
    ),
    INVOICE(
        "Simple invoice", "साधारण बिल",
        listOf(
            TemplateField("business", "Your business name", "आपके व्यवसाय का नाम", required = true),
            TemplateField("customer", "Bill to", "ग्राहक", required = true),
            TemplateField("invoiceNo", "Invoice number", "बिल संख्या"),
            TemplateField("date", "Date", "दिनांक"),
            TemplateField("items", "Items: description | qty | unit price (one per line)", "वस्तुएँ: विवरण | मात्रा | दर (हर पंक्ति में एक)", multiline = true, required = true),
            TemplateField("notes", "Notes / payment terms", "टिप्पणी / भुगतान शर्तें", multiline = true),
        ),
    ),
    NOTICE(
        "Notice", "सूचना",
        listOf(
            TemplateField("organisation", "Organisation", "संस्था", required = true),
            TemplateField("title", "Notice title", "सूचना का शीर्षक", required = true),
            TemplateField("date", "Date", "दिनांक"),
            TemplateField("body", "Notice text", "सूचना का विवरण", multiline = true, required = true),
            TemplateField("issuedBy", "Issued by", "जारीकर्ता"),
        ),
    ),
    ;

    fun title(lang: ContentLanguage) = if (lang == ContentLanguage.HINDI) titleHi else titleEn

    /** Keys of required fields that are blank in [values]. */
    fun missingRequired(values: Map<String, String>): List<String> =
        fields.filter { it.required && values[it.key].isNullOrBlank() }.map { it.key }

    fun render(lang: ContentLanguage, values: Map<String, String>): String {
        val v = { key: String -> values[key]?.trim().orEmpty() }
        val hi = lang == ContentLanguage.HINDI
        val t = { en: String, hiText: String -> if (hi) hiText else en }
        val out = StringBuilder()
        fun line(s: String = "") { out.append(s).append('\n') }
        fun optional(s: String) { if (s.isNotBlank()) line(s) }
        fun lines(s: String) = s.lines().map { it.trim() }.filter { it.isNotEmpty() }

        when (this) {
            LETTER -> {
                optional("**${v("senderName")}**")
                lines(v("senderAddress")).forEach { line(it) }
                if (v("date").isNotBlank()) { line(); line(t("Date: ", "दिनांक: ") + v("date")) }
                line(); line(t("To,", "सेवा में,")); line(v("recipient")); lines(v("recipientAddress")).forEach { line(it) }
                line(); line("**${t("Subject", "विषय")}: ${v("subject")}**")
                line(); line(t("Dear Sir/Madam,", "महोदय/महोदया,"))
                line(); lines(v("body")).forEach { line(it); line() }
                line(t("Yours sincerely,", "भवदीय,")); line(v("senderName"))
            }
            LEAVE_APPLICATION -> {
                line(t("To,", "सेवा में,")); line(v("to")); optional(v("organisation"))
                line(); line("**${t("Subject: Application for leave", "विषय: अवकाश हेतु प्रार्थना पत्र")}**")
                line(); line(t("Respected Sir/Madam,", "आदरणीय महोदय/महोदया,"))
                line()
                val role = v("role").let { if (it.isBlank()) "" else if (hi) " ($it)" else ", $it," }
                line(
                    if (hi) "सविनय निवेदन है कि मैं, ${v("name")}$role, ${v("fromDate")} से ${v("toDate")} तक अवकाश चाहता/चाहती हूँ। कारण: ${v("reason")}"
                    else "I, ${v("name")}$role request leave from ${v("fromDate")} to ${v("toDate")}. Reason: ${v("reason")}",
                )
                line()
                line(t("I request you to kindly grant me leave for the above period.", "अतः आपसे निवेदन है कि उपरोक्त अवधि के लिए अवकाश स्वीकृत करने की कृपा करें।"))
                line(); line(t("Yours obediently,", "आपका/आपकी आज्ञाकारी,")); line(v("name"))
                optional(if (v("date").isBlank()) "" else t("Date: ", "दिनांक: ") + v("date"))
            }
            RESUME -> {
                line("# ${v("name")}"); optional(v("contact"))
                if (v("summary").isNotBlank()) { line(); line("## ${t("Summary", "परिचय")}"); line(v("summary")) }
                if (v("experience").isNotBlank()) { line(); line("## ${t("Experience", "अनुभव")}"); lines(v("experience")).forEach { line("- $it") } }
                if (v("education").isNotBlank()) { line(); line("## ${t("Education", "शिक्षा")}"); lines(v("education")).forEach { line("- $it") } }
                if (v("skills").isNotBlank()) { line(); line("## ${t("Skills", "कौशल")}"); v("skills").split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { line("- $it") } }
            }
            MEETING_NOTES -> {
                line("# ${v("title")}")
                optional(if (v("date").isBlank()) "" else "**${t("Date", "दिनांक")}:** ${v("date")}")
                optional(if (v("attendees").isBlank()) "" else "**${t("Attendees", "उपस्थित")}:** ${v("attendees")}")
                section(out, t("Agenda", "कार्यसूची"), lines(v("agenda")), ordered = true)
                section(out, t("Decisions", "निर्णय"), lines(v("decisions")), ordered = false)
                section(out, t("Action items", "कार्य बिंदु"), lines(v("actions")), ordered = false)
            }
            STUDY_NOTES -> {
                line("# ${v("subject")}: ${v("topic")}")
                section(out, t("Key points", "मुख्य बिंदु"), lines(v("points")), ordered = false)
                if (v("summary").isNotBlank()) { line(); line("## ${t("Summary", "सारांश")}"); line(v("summary")) }
            }
            REPORT -> {
                line("# ${v("title")}")
                val meta = listOf(v("author"), v("date")).filter { it.isNotBlank() }.joinToString(" · ")
                optional(meta)
                for ((head, key) in listOf(t("Introduction", "परिचय") to "introduction", t("Findings", "मुख्य निष्कर्ष") to "findings", t("Conclusion", "निष्कर्ष") to "conclusion")) {
                    if (v(key).isNotBlank()) { line(); line("## $head"); line(v(key)) }
                }
            }
            INVOICE -> {
                val invoice = InvoiceCalculator.parse(v("items"))
                line("# ${t("Invoice", "बिल")}")
                line("**${v("business")}**")
                line()
                line("${t("Bill to", "ग्राहक")}: ${v("customer")}")
                optional(if (v("invoiceNo").isBlank()) "" else "${t("Invoice no.", "बिल संख्या")}: ${v("invoiceNo")}")
                optional(if (v("date").isBlank()) "" else "${t("Date", "दिनांक")}: ${v("date")}")
                line()
                line("| ${t("Description", "विवरण")} | ${t("Qty", "मात्रा")} | ${t("Unit price", "दर")} | ${t("Amount", "राशि")} |")
                line("| --- | --- | --- | --- |")
                invoice.lines.forEach { line("| ${it.description} | ${it.quantity.toPlainString()} | ${money(it.unitPrice)} | ${money(it.amount)} |") }
                line()
                line("**${t("Total", "कुल")}: ₹${money(invoice.total)}**")
                if (invoice.rejectedLines.isNotEmpty()) {
                    line(); line(t("Lines not understood (please check):", "समझ में न आने वाली पंक्तियाँ (कृपया जाँचें):"))
                    invoice.rejectedLines.forEach { line("- $it") }
                }
                if (v("notes").isNotBlank()) { line(); line(v("notes")) }
            }
            NOTICE -> {
                line("# ${v("organisation")}")
                line("## ${t("NOTICE", "सूचना")}: ${v("title")}")
                optional(if (v("date").isBlank()) "" else "${t("Date", "दिनांक")}: ${v("date")}")
                line(); lines(v("body")).forEach { line(it); line() }
                optional(if (v("issuedBy").isBlank()) "" else "— ${v("issuedBy")}")
            }
        }
        return out.toString().replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun section(out: StringBuilder, heading: String, items: List<String>, ordered: Boolean) {
        if (items.isEmpty()) return
        out.append("\n## ").append(heading).append('\n')
        items.forEachIndexed { i, s -> out.append(if (ordered) "${i + 1}. " else "- ").append(s).append('\n') }
    }

    private fun money(v: BigDecimal): String {
        val f = NumberFormat.getNumberInstance(Locale("en", "IN"))
        f.minimumFractionDigits = 2; f.maximumFractionDigits = 2
        return f.format(v)
    }
}

/** Parses invoice lines "description | qty | unit price" and computes exact decimal totals. */
object InvoiceCalculator {
    data class Line(val description: String, val quantity: BigDecimal, val unitPrice: BigDecimal) {
        val amount: BigDecimal get() = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP)
    }
    data class Invoice(val lines: List<Line>, val rejectedLines: List<String>) {
        val total: BigDecimal get() = lines.fold(BigDecimal.ZERO) { acc, l -> acc + l.amount }.setScale(2, RoundingMode.HALF_UP)
    }

    fun parse(text: String): Invoice {
        val lines = mutableListOf<Line>()
        val rejected = mutableListOf<String>()
        for (raw in text.lines().map { it.trim() }.filter { it.isNotEmpty() }) {
            val parts = raw.split('|').map { it.trim() }
            val qty = parts.getOrNull(1)?.let(::number)
            val price = parts.getOrNull(2)?.let(::number)
            if (parts.size == 3 && parts[0].isNotEmpty() && qty != null && price != null && qty.signum() >= 0 && price.signum() >= 0) {
                lines += Line(parts[0], qty, price)
            } else rejected += raw
        }
        return Invoice(lines, rejected)
    }

    private fun number(s: String): BigDecimal? {
        val ascii = buildString { for (c in s) append(if (c in '०'..'९') '0' + (c - '०') else c) }
        return ascii.replace(",", "").replace("₹", "").trim().toBigDecimalOrNull()
    }
}
