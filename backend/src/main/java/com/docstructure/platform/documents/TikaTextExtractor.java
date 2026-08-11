package com.docstructure.platform.documents;

import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

/**
 * Wraps Apache Tika's facade, which auto-detects file type and dispatches to the right
 * parser for PDF/DOCX/HTML/TXT uniformly, including OCR for image content and scanned PDFs
 * IF a Tesseract binary is on PATH (bundled in the Docker image; install separately for
 * local dev — see README). Without Tesseract, Tika degrades gracefully: image-only content
 * just yields little/no text rather than throwing.
 */
@Component
public class TikaTextExtractor {

    private static final int MAX_CHARS = 5_000_000;

    private final Tika tika;

    public TikaTextExtractor() {
        this.tika = new Tika();
        this.tika.setMaxStringLength(MAX_CHARS);
    }

    public String extract(InputStream content) throws IOException, TikaException {
        return tika.parseToString(content);
    }
}
